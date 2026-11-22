package cc.shockuletz.shelo;

import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;

public final class CombatListener implements Listener {
    private final ShEloPlugin plugin;
    private final Map<KillPair, Long> recentKills = new HashMap<>();
    private int antiFarmChecks;

    public CombatListener(ShEloPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.database().ensurePlayer(player.getUniqueId(), player.getName(), plugin.settings().startingElo())
                .exceptionally(error -> {
                    logDatabaseError("initializarea lui " + player.getName(), error);
                    return null;
                });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getPlayer();
        Player killer = victim.getKiller();
        PluginSettings settings = plugin.settings();
        if (settings.isWorldDisabled(victim.getWorld().getName())) {
            return;
        }

        boolean suicide = killer != null && killer.getUniqueId().equals(victim.getUniqueId());
        int previousVictimElo = plugin.database().getCachedElo(
                victim.getUniqueId(), settings.startingElo());
        double availableBalance = plugin.economy().getBalance(victim);
        double deathCharge = Double.isNaN(availableBalance)
                ? 0.0
                : Math.min(settings.deathMoney(), Math.max(0.0, availableBalance));
        boolean deathEconomySuccess = true;
        if (deathCharge > 0.0) {
            EconomyResponse deathTransaction = plugin.economy().withdrawPlayer(victim, deathCharge);
            if (!deathTransaction.transactionSuccess()) {
                deathEconomySuccess = false;
                notifyEconomyFailure(victim, "penalizarea la moarte", deathTransaction);
            }
        }
        if (deathEconomySuccess) {
            plugin.database().addElo(
                    victim.getUniqueId(), victim.getName(), -settings.deathElo(),
                    settings.startingElo(), settings.minimumElo()
            ).whenComplete((newElo, error) -> plugin.runSync(() -> {
                if (error != null) {
                    compensateDeathPenalty(victim, deathCharge);
                    notifyDatabaseFailure(victim, "penalizarea lui " + victim.getName(), error);
                    return;
                }
                applyDeathPenalty(victim, killer, previousVictimElo, newElo, deathCharge, settings);
            }));
        }

        if (killer == null || suicide) {
            return;
        }

        plugin.database().recordPvpResult(
                killer.getUniqueId(), killer.getName(),
                victim.getUniqueId(), victim.getName(),
                settings.startingElo()
        ).exceptionally(error -> {
            logDatabaseError("salvarea statisticilor PvP pentru " + killer.getName()
                    + " si " + victim.getName(), error);
            return null;
        });

        AntiFarmDecision antiFarm = checkAntiFarm(killer, victim, settings);
        if (antiFarm.blocked()) {
            plugin.messages().send(killer, "anti-farm-blocked",
                    MessageManager.text("player", victim.getName()),
                    MessageManager.text("remaining_seconds", Long.toString(antiFarm.remainingSeconds())));
            int currentElo = plugin.database().getCachedElo(
                    killer.getUniqueId(), settings.startingElo());
            broadcastKill(killer, victim, currentElo, settings);
            return;
        }

        double rewardMultiplier = antiFarm.percentage() / 100.0;
        double rewardMoney = settings.killMoney() * rewardMultiplier;
        int rewardElo = (int) Math.round(settings.killElo() * rewardMultiplier);
        EconomyResponse killTransaction = plugin.economy().depositPlayer(killer, rewardMoney);
        if (!killTransaction.transactionSuccess()) {
            notifyEconomyFailure(killer, "recompensa pentru kill", killTransaction);
            return;
        }

        plugin.database().addElo(
                killer.getUniqueId(), killer.getName(), rewardElo,
                settings.startingElo(), settings.minimumElo()
        ).whenComplete((newElo, error) -> plugin.runSync(() -> {
            if (error != null) {
                compensateKillReward(killer, rewardMoney);
                notifyDatabaseFailure(killer, "recompensa lui " + killer.getName(), error);
                return;
            }
            applyKillReward(killer, victim, newElo, rewardMoney, rewardElo, antiFarm, settings);
        }));
    }

    private AntiFarmDecision checkAntiFarm(Player killer, Player victim, PluginSettings settings) {
        if (!settings.antiFarmEnabled()) {
            recentKills.clear();
            return AntiFarmDecision.normal();
        }

        long now = System.currentTimeMillis();
        long cooldown = settings.antiFarmCooldownMillis();
        KillPair pair = new KillPair(killer.getUniqueId(), victim.getUniqueId());
        Long previousKill = recentKills.put(pair, now);

        antiFarmChecks = (antiFarmChecks + 1) % 100;
        if (antiFarmChecks == 0) {
            recentKills.entrySet().removeIf(entry -> now - entry.getValue() >= cooldown);
        }

        if (previousKill == null || now - previousKill >= cooldown) {
            return AntiFarmDecision.normal();
        }

        long elapsed = Math.max(0L, now - previousKill);
        long remainingSeconds = Math.max(1L, (cooldown - elapsed + 999L) / 1000L);
        if (settings.antiFarmMode() == AntiFarmMode.BLOCK) {
            return new AntiFarmDecision(true, 0, remainingSeconds);
        }
        return new AntiFarmDecision(false, settings.antiFarmReductionPercent(), remainingSeconds);
    }

    private void applyDeathPenalty(Player victim, Player killer, int previousElo,
                                   int newElo, double chargedMoney, PluginSettings settings) {
        TagResolver[] placeholders = {
                MessageManager.text("money", plugin.formatMoney(chargedMoney)),
                MessageManager.text("elo", Integer.toString(settings.deathElo())),
                MessageManager.text("new_elo", Integer.toString(newElo)),
                MessageManager.text("killer", killer == null ? "Mediu" : killer.getName())
        };
        plugin.messages().send(victim, killer == null ? "death-other" : "death-player", placeholders);
        plugin.colorMenu().notifyIfLostAccess(victim, previousElo, newElo);
    }

    private void applyKillReward(Player killer, Player victim, int newElo,
                                 double rewardMoney, int rewardElo,
                                 AntiFarmDecision antiFarm, PluginSettings settings) {
        plugin.messages().send(killer, "kill",
                MessageManager.text("player", victim.getName()),
                MessageManager.text("money", plugin.formatMoney(rewardMoney)),
                MessageManager.text("elo", Integer.toString(rewardElo)),
                MessageManager.text("new_elo", Integer.toString(newElo))
        );
        if (antiFarm.reduced()) {
            plugin.messages().send(killer, "anti-farm-reduced",
                    MessageManager.text("player", victim.getName()),
                    MessageManager.text("percentage", Integer.toString(antiFarm.percentage())),
                    MessageManager.text("remaining_seconds", Long.toString(antiFarm.remainingSeconds())));
        }
        broadcastKill(killer, victim, newElo, settings);
    }

    private void broadcastKill(Player killer, Player victim, int killerElo, PluginSettings settings) {
        if (!settings.broadcastKills()) {
            return;
        }
        for (Player recipient : Bukkit.getOnlinePlayers()) {
            plugin.messages().send(recipient, "kill-broadcast",
                    MessageManager.text("killer", killer.getName()),
                    MessageManager.text("victim", victim.getName()),
                    MessageManager.text("killer_elo", Integer.toString(killerElo))
            );
        }
    }

    private void compensateDeathPenalty(Player player, double amount) {
        if (amount <= 0.0) {
            return;
        }
        EconomyResponse response = plugin.economy().depositPlayer(player, amount);
        notifyCompensationFailure(player, "returnarea penalizarii", response);
    }

    private void compensateKillReward(Player player, double amount) {
        EconomyResponse response = plugin.economy().withdrawPlayer(player, amount);
        notifyCompensationFailure(player, "retragerea recompensei", response);
    }

    private void notifyCompensationFailure(Player player, String operation, EconomyResponse response) {
        if (response.transactionSuccess()) {
            return;
        }
        plugin.messages().send(player, "economy-error");
        plugin.getLogger().severe("Compensare Vault esuata la " + operation + " pentru "
                + player.getName() + ": " + response.errorMessage);
    }

    private void notifyEconomyFailure(Player player, String operation, EconomyResponse response) {
        plugin.messages().send(player, "economy-error");
        plugin.getLogger().warning("Tranzactie Vault esuata la " + operation + " pentru "
                + player.getName() + ": " + response.errorMessage);
    }

    private void notifyDatabaseFailure(Player player, String operation, Throwable error) {
        plugin.messages().send(player, "database-error");
        logDatabaseError(operation, error);
    }

    private void logDatabaseError(String operation, Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null
                ? error.getCause() : error;
        plugin.getLogger().severe("Eroare MySQL la " + operation + ": " + cause.getMessage());
    }

    private record KillPair(UUID killer, UUID victim) {
    }

    private record AntiFarmDecision(boolean blocked, int percentage, long remainingSeconds) {
        private static AntiFarmDecision normal() {
            return new AntiFarmDecision(false, 100, 0L);
        }

        private boolean reduced() {
            return !blocked && percentage < 100;
        }
    }
}
