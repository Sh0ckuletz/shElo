package cc.shockuletz.shelo;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;

public final class ConfigUpdater {
    private static final String VERSION_KEY = "config-version";

    private ConfigUpdater() {
    }

    public static UpdateResult update(JavaPlugin plugin, String resourceName)
            throws IOException, InvalidConfigurationException {
        File target = new File(plugin.getDataFolder(), resourceName);
        if (!target.exists()) {
            plugin.saveResource(resourceName, false);
            return new UpdateResult(false, 0, loadDefaults(plugin, resourceName).getInt(VERSION_KEY, 1), 0);
        }

        YamlConfiguration defaults = loadDefaults(plugin, resourceName);
        YamlConfiguration current = new YamlConfiguration();
        current.options().parseComments(true);
        try {
            current.load(target);
        } catch (InvalidConfigurationException exception) {
            File backup = new File(plugin.getDataFolder(), resourceName + ".invalid.bak");
            Files.copy(target.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Files.delete(target.toPath());
            plugin.saveResource(resourceName, false);
            int latestVersion = defaults.getInt(VERSION_KEY, 1);
            plugin.getLogger().warning(resourceName + " era invalid. A fost salvat ca "
                    + backup.getName() + " si regenerat automat.");
            return new UpdateResult(true, 0, latestVersion, 0);
        }

        int latestVersion = defaults.getInt(VERSION_KEY, 1);
        int currentVersion = current.getInt(VERSION_KEY, 0);
        if (currentVersion > latestVersion) {
            plugin.getLogger().warning(resourceName + " provine dintr-o versiune mai noua ("
                    + currentVersion + "). Updaterul nu il va modifica.");
            return new UpdateResult(false, currentVersion, latestVersion, 0);
        }

        if (resourceName.equals("messages.yml") && currentVersion < 12) {
            migrateKillBroadcast(defaults, current);
        }
        if (resourceName.equals("menus.yml") && currentVersion < 2) {
            migratePremierMenu(defaults, current);
        }

        int addedKeys = 0;
        for (String path : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(path) || current.contains(path)) {
                continue;
            }
            current.set(path, defaults.get(path));
            copyComments(defaults, current, path);
            addedKeys++;
        }

        boolean versionChanged = currentVersion < latestVersion;
        if (!versionChanged && addedKeys == 0) {
            return new UpdateResult(false, currentVersion, latestVersion, 0);
        }

        if (versionChanged) {
            File backup = new File(plugin.getDataFolder(), resourceName + ".bak-v" + currentVersion);
            Files.copy(target.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        current.set(VERSION_KEY, latestVersion);
        copyComments(defaults, current, VERSION_KEY);
        current.save(target);
        plugin.getLogger().info(resourceName + " actualizat la versiunea " + latestVersion
                + " (" + addedKeys + " chei noi).");
        return new UpdateResult(true, currentVersion, latestVersion, addedKeys);
    }

    private static YamlConfiguration loadDefaults(JavaPlugin plugin, String resourceName) throws IOException {
        try (InputStream stream = plugin.getResource(resourceName)) {
            if (stream == null) {
                throw new IOException("Resursa " + resourceName + " lipseste din JAR");
            }
            YamlConfiguration defaults = new YamlConfiguration();
            defaults.options().parseComments(true);
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                defaults.load(reader);
            } catch (InvalidConfigurationException exception) {
                throw new IOException("Resursa implicita " + resourceName + " este invalida", exception);
            }
            return defaults;
        }
    }

    private static void copyComments(YamlConfiguration source, YamlConfiguration target, String path) {
        List<String> comments = source.getComments(path);
        if (!comments.isEmpty()) {
            target.setComments(path, comments);
        }
        List<String> inlineComments = source.getInlineComments(path);
        if (!inlineComments.isEmpty()) {
            target.setInlineComments(path, inlineComments);
        }
    }

    private static void migrateKillBroadcast(YamlConfiguration defaults, YamlConfiguration current) {
        if (!current.isString("kill-broadcast")) {
            return;
        }
        String previousMessage = current.getString("kill-broadcast", "");
        current.set("kill-broadcast", null);
        current.set("kill-broadcast.prefix", defaults.getBoolean("kill-broadcast.prefix", true));
        current.set("kill-broadcast.message", previousMessage);
        copyComments(defaults, current, "kill-broadcast.prefix");
    }

    private static void migratePremierMenu(YamlConfiguration defaults, YamlConfiguration current) {
        for (String path : List.of(
                "title", "size", "default-format",
                "filler.enabled", "filler.material", "filler.name",
                "status.selected", "status.unlocked", "status.locked"
        )) {
            current.set(path, defaults.get(path));
            copyComments(defaults, current, path);
        }

        current.set("colors", null);
        for (String path : defaults.getKeys(true)) {
            if (!path.startsWith("colors.") || defaults.isConfigurationSection(path)) {
                continue;
            }
            current.set(path, defaults.get(path));
            copyComments(defaults, current, path);
        }
    }

    public record UpdateResult(boolean updated, int previousVersion, int currentVersion, int addedKeys) {
    }
}
