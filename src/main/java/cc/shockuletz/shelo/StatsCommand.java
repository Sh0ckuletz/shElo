package cc.shockuletz.shelo;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletionException;

public final class StatsCommand implements CommandExecutor, TabCompleter {
    private final ShEloPlugin plugin;

    public StatsCommand(ShEloPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                plugin.messages().send(sender, "stats-usage");
                return true;
            }
            loadKnownPlayer(sender, player, "stats-self");
            return true;
        }

        if (args.length != 1) {
            plugin.messages().send(sender, "stats-usage");
            return true;
        }
        if (!sender.hasPermission("shelo.stats.others")) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }

        OfflinePlayer target = findPlayer(args[0]);
        if (target != null) {
            loadKnownPlayer(sender, target, "stats-other");
            return true;
        }

        plugin.database().findStatsByName(args[0])
                .whenComplete((result, error) -> plugin.runSync(() -> {
                    if (error != null) {
                        databaseFailure(sender, error);
                        return;
                    }
                    Optional<EloStorage.PlayerStats> stats = result;
                    if (stats.isEmpty()) {
                        plugin.messages().send(sender, "player-not-found",
                                MessageManager.text("player", args[0]));
                        return;
                    }
                    sendStats(sender, "stats-other", stats.get());
                }));
        return true;
    }

    private void loadKnownPlayer(CommandSender sender, OfflinePlayer target, String messageKey) {
        String name = target.getName() == null ? target.getUniqueId().toString() : target.getName();
        plugin.database().getOrCreateStats(
                target.getUniqueId(), name, plugin.settings().startingElo()
        ).whenComplete((stats, error) -> plugin.runSync(() -> {
            if (error != null) {
                databaseFailure(sender, error);
                return;
            }
            sendStats(sender, messageKey, stats);
        }));
    }

    private void sendStats(CommandSender sender, String messageKey, EloStorage.PlayerStats stats) {
        plugin.messages().send(sender, messageKey,
                MessageManager.text("player", stats.name()),
                MessageManager.text("peak_elo", Integer.toString(stats.highestElo())),
                MessageManager.parsed("peak_elo_formatted",
                        plugin.formatElo(stats.uuid(), stats.highestElo())),
                MessageManager.text("kills", Long.toString(stats.kills())),
                MessageManager.text("deaths", Long.toString(stats.deaths())),
                MessageManager.text("kd", String.format(Locale.US, "%.2f", stats.kd())),
                MessageManager.text("streak", Long.toString(stats.currentStreak())),
                MessageManager.text("best_streak", Long.toString(stats.bestStreak())));
    }

    private void databaseFailure(CommandSender sender, Throwable error) {
        plugin.messages().send(sender, "database-error");
        Throwable cause = error instanceof CompletionException && error.getCause() != null
                ? error.getCause() : error;
        plugin.getLogger().severe("Eroare baza de date la comanda /stats: " + cause.getMessage());
    }

    @Nullable
    private OfflinePlayer findPlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        return cached != null && (cached.hasPlayedBefore() || cached.isOnline()) ? cached : null;
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull CommandSender sender,
                                                @NotNull Command command,
                                                @NotNull String alias,
                                                @NotNull String[] args) {
        if (args.length != 1 || !sender.hasPermission("shelo.stats.others")) {
            return List.of();
        }
        String input = args[0].toLowerCase(Locale.ROOT);
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(input))
                .toList();
    }
}
