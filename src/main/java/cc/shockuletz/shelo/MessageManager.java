package cc.shockuletz.shelo;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.OfflinePlayer;

import java.io.File;

public final class MessageManager {
    private final ShEloPlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private YamlConfiguration messages;

    public MessageManager(ShEloPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        messages = YamlConfiguration.loadConfiguration(file);
    }

    public void send(CommandSender receiver, String key, TagResolver... placeholders) {
        receiver.sendMessage(component(receiver, key, placeholders));
    }

    public Component component(CommandSender receiver, String key, TagResolver... placeholders) {
        String prefix = messages.getString("prefix", "");
        boolean usePrefix = true;
        String message;
        if (messages.isConfigurationSection(key)) {
            usePrefix = messages.getBoolean(key + ".prefix", true);
            message = messages.getString(
                    key + ".message", "<red>Mesaj lipsa: " + key + "</red>");
        } else {
            message = messages.getString(key, "<red>Mesaj lipsa: " + key + "</red>");
        }
        return renderRaw(receiver, (usePrefix ? prefix : "") + message, placeholders);
    }

    public Component renderRaw(CommandSender receiver, String text, TagResolver... placeholders) {
        String serialized = text;
        OfflinePlayer context = receiver instanceof OfflinePlayer player ? player : null;
        if (context != null) {
            int elo = plugin.database().getCachedElo(
                    context.getUniqueId(), plugin.settings().startingElo());
            serialized = serialized.replace("%shelo_elo_formatted%", plugin.formatElo(context.getUniqueId(), elo));
            serialized = serialized.replace("%shelo_elo%", Integer.toString(elo));
        }
        serialized = plugin.applyPlaceholders(context, serialized);
        return miniMessage.deserialize(serialized, TagResolver.resolver(placeholders));
    }

    public static TagResolver text(String key, String value) {
        return Placeholder.component(key, Component.text(value));
    }

    public static TagResolver parsed(String key, String value) {
        return Placeholder.parsed(key, value);
    }}
