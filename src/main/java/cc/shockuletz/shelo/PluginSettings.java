package cc.shockuletz.shelo;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public record PluginSettings(
        double killMoney,
        int killElo,
        boolean broadcastKills,
        Set<String> disabledWorlds,
        int leaderboardPageSize,
        boolean antiFarmEnabled,
        long antiFarmCooldownMillis,
        AntiFarmMode antiFarmMode,
        int antiFarmReductionPercent,
        ColorMode colorMode,
        boolean notifyColorLoss,
        double deathMoney,
        int deathElo,
        int startingElo,
        int minimumElo,
        String moneyFormat
) {
    public static PluginSettings from(FileConfiguration config) {
        int minimumElo = config.getInt("elo.minimum-value", 0);
        int startingElo = Math.max(minimumElo, config.getInt("elo.starting-value", 1000));
        long cooldownSeconds = Math.max(1L,
                Math.min(86_400L, config.getLong("anti-farm.cooldown-seconds", 300L)));

        return new PluginSettings(
                Math.max(0.0, config.getDouble("kill.money", 300.0)),
                Math.max(0, config.getInt("kill.elo", 30)),
                config.getBoolean("kill.broadcast", true),
                config.getStringList("combat.disabled-worlds").stream()
                        .map(String::trim)
                        .filter(value -> !value.isEmpty())
                        .map(value -> value.toLowerCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet()),
                Math.max(1, Math.min(50, config.getInt("leaderboard.entries-per-page", 10))),
                config.getBoolean("anti-farm.enabled", true),
                cooldownSeconds * 1000L,
                AntiFarmMode.parse(config.getString("anti-farm.mode", "BLOCK")),
                Math.max(0, Math.min(100, config.getInt("anti-farm.reduction-percent", 50))),
                ColorMode.parse(config.getString("color-system.mode", "AUTOMATIC")),
                config.getBoolean("color-system.notify-on-threshold-loss", true),
                Math.max(0.0, config.getDouble("death.money", 50.0)),
                Math.max(0, config.getInt("death.elo", 10)),
                startingElo,
                minimumElo,
                config.getString("money-format", "#,##0.##")
        );
    }

    public boolean isWorldDisabled(String worldName) {
        return disabledWorlds.contains(worldName.toLowerCase(Locale.ROOT));
    }
}
