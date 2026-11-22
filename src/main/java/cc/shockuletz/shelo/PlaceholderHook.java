package cc.shockuletz.shelo;

import org.bukkit.OfflinePlayer;

@FunctionalInterface
public interface PlaceholderHook extends AutoCloseable {
    String apply(OfflinePlayer player, String text);

    @Override
    default void close() {
    }

    static PlaceholderHook disabled() {
        return (player, text) -> text;
    }
}
