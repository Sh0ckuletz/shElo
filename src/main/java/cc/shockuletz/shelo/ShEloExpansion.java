package cc.shockuletz.shelo;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class ShEloExpansion extends PlaceholderExpansion {
    private final ShEloPlugin plugin;

    public ShEloExpansion(ShEloPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "shelo";
    }

    @Override
    public @NotNull String getAuthor() {
        return String.join(", ", plugin.getPluginMeta().getAuthors());
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String parameters) {
        if (player == null) {
            return "";
        }

        Integer elo = plugin.database().getCachedEloIfPresent(player.getUniqueId());
        if (elo == null) {
            plugin.database().ensureEloCached(player.getUniqueId(), plugin.settings().startingElo());
            return "";
        }

        return switch (parameters.toLowerCase(java.util.Locale.ROOT)) {
            case "elo" -> Integer.toString(elo);
            case "elo_formatted" -> plugin.formatElo(player.getUniqueId(), elo);
            default -> null;
        };
    }
}
