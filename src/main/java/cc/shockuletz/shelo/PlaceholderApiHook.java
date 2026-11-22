package cc.shockuletz.shelo;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.OfflinePlayer;

public final class PlaceholderApiHook implements PlaceholderHook {
    private final ShEloExpansion expansion;

    public PlaceholderApiHook(ShEloPlugin plugin) {
        expansion = new ShEloExpansion(plugin);
        if (!expansion.register()) {
            plugin.getLogger().warning("The shelo PlaceholderAPI expansion could not be registered.");
        }
    }

    @Override
    public String apply(OfflinePlayer player, String text) {
        return PlaceholderAPI.setPlaceholders(player, text);
    }

    @Override
    public void close() {
        expansion.unregister();
    }
}
