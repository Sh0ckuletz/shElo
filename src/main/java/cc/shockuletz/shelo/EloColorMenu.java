package cc.shockuletz.shelo;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class EloColorMenu implements Listener {
    private final ShEloPlugin plugin;
    private Map<String, MenuColor> colors = Map.of();
    private String title = "<b><gradient:#4498DB:#82C7FF>shElo Colors</gradient></b>";
    private String defaultFormat = "<gray>{elo}</gray>";
    private int size = 27;
    private boolean fillerEnabled;
    private Material fillerMaterial = Material.GRAY_STAINED_GLASS_PANE;
    private String fillerName = " ";
    private String selectedStatus = "<green>Selected</green>";
    private String unlockedStatus = "<yellow>Click to select</yellow>";
    private String lockedStatus = "<red>Locked</red>";

    public EloColorMenu(ShEloPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(
                new File(plugin.getDataFolder(), "menus.yml"));
        title = config.getString("title", "<b><gradient:#4498DB:#82C7FF>shElo Colors</gradient></b>");
        defaultFormat = config.getString("default-format", "<gray>{elo}</gray>");
        size = normalizeSize(config.getInt("size", 27));
        fillerEnabled = config.getBoolean("filler.enabled", true);
        fillerMaterial = material(config.getString("filler.material", "GRAY_STAINED_GLASS_PANE"));
        fillerName = config.getString("filler.name", " ");
        selectedStatus = config.getString("status.selected", "<green>Selected</green>");
        unlockedStatus = config.getString("status.unlocked", "<yellow>Click to select</yellow>");
        lockedStatus = config.getString("status.locked", "<red>Locked</red>");

        Map<String, MenuColor> loaded = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection("colors");
        if (section != null) {
            for (String id : section.getKeys(false)) {
                if (!id.matches("[A-Za-z0-9_-]{1,64}")) {
                    plugin.getLogger().warning("Invalid color ID in menus.yml: " + id);
                    continue;
                }
                String path = "colors." + id + ".";
                int slot = config.getInt(path + "slot", -1);
                if (slot < 0 || slot >= size) {
                    plugin.getLogger().warning("Invalid slot for color " + id + " in menus.yml");
                    continue;
                }
                loaded.put(id, new MenuColor(
                        id,
                        slot,
                        Math.max(0, config.getInt(path + "required-elo", 0)),
                        material(config.getString(path + "material", "PAPER")),
                        config.getString(path + "name", "<white>" + id + "</white>"),
                        config.getStringList(path + "lore"),
                        config.getString(path + "format", defaultFormat)
                ));
            }
        }
        colors = Map.copyOf(loaded);
    }

    public void open(Player player) {
        if (plugin.settings().colorMode() != ColorMode.MENU) {
            plugin.messages().send(player, "color-menu-disabled");
            return;
        }

        plugin.database().ensurePlayer(
                player.getUniqueId(), player.getName(), plugin.settings().startingElo()
        ).whenComplete((ignored, error) -> plugin.runSync(() -> {
            if (!player.isOnline()) {
                return;
            }
            if (error != null) {
                plugin.messages().send(player, "database-error");
                plugin.getLogger().severe("The color menu could not load the player: "
                        + error.getMessage());
                return;
            }
            openLoaded(player);
        }));
    }

    private void openLoaded(Player player) {
        int elo = plugin.database().getCachedElo(player.getUniqueId(), plugin.settings().startingElo());
        int highestElo = plugin.database().getCachedHighestElo(player.getUniqueId(), elo);
        String selected = plugin.database().getCachedSelectedColor(player.getUniqueId());
        ColorMenuHolder holder = new ColorMenuHolder();
        Inventory inventory = Bukkit.createInventory(holder, size, plugin.messages().renderRaw(player, title));
        holder.inventory = inventory;

        if (fillerEnabled) {
            ItemStack filler = new ItemStack(fillerMaterial);
            ItemMeta meta = filler.getItemMeta();
            meta.displayName(plugin.messages().renderRaw(player, fillerName));
            filler.setItemMeta(meta);
            for (int slot = 0; slot < size; slot++) {
                inventory.setItem(slot, filler);
            }
        }

        for (MenuColor color : colors.values()) {
            boolean unlocked = elo >= color.requiredElo();
            boolean isSelected = unlocked && color.id().equals(selected);
            Component status = plugin.messages().renderRaw(player,
                    isSelected ? selectedStatus : unlocked ? unlockedStatus : lockedStatus);
            TagResolver placeholders = TagResolver.resolver(
                    Placeholder.unparsed("color_id", color.id()),
                    Placeholder.unparsed("required_elo", Integer.toString(color.requiredElo())),
                    Placeholder.unparsed("current_elo", Integer.toString(elo)),
                    Placeholder.unparsed("highest_elo", Integer.toString(highestElo)),
                    Placeholder.component("status", status)
            );

            ItemStack item = new ItemStack(color.material());
            ItemMeta meta = item.getItemMeta();
            meta.displayName(plugin.messages().renderRaw(player, color.name(), placeholders));
            List<Component> lore = new ArrayList<>();
            for (String line : color.lore()) {
                lore.add(plugin.messages().renderRaw(player, line, placeholders));
            }
            meta.lore(lore);
            meta.setEnchantmentGlintOverride(isSelected);
            item.setItemMeta(meta);
            inventory.setItem(color.slot(), item);
            holder.bySlot.put(color.slot(), color);
        }
        player.openInventory(inventory);
    }

    public String formatSelected(java.util.UUID playerId, int elo) {
        String selected = plugin.database().getCachedSelectedColor(playerId);
        MenuColor color = selected == null ? null : colors.get(selected);
        String format = color == null || elo < color.requiredElo() ? defaultFormat : color.format();
        return format.replace("{elo}", Integer.toString(elo));
    }

    public void notifyIfLostAccess(Player player, int previousElo, int currentElo) {
        if (plugin.settings().colorMode() != ColorMode.MENU
                || !plugin.settings().notifyColorLoss()
                || currentElo >= previousElo) {
            return;
        }
        String selected = plugin.database().getCachedSelectedColor(player.getUniqueId());
        MenuColor color = selected == null ? null : colors.get(selected);
        if (color == null || previousElo < color.requiredElo() || currentElo >= color.requiredElo()) {
            return;
        }
        plugin.messages().send(player, "color-threshold-lost",
                MessageManager.text("color", color.id()),
                MessageManager.text("required_elo", Integer.toString(color.requiredElo())),
                MessageManager.text("previous_elo", Integer.toString(previousElo)),
                MessageManager.text("current_elo", Integer.toString(currentElo)));
    }

    @EventHandler
    public void onCommandSuggestions(PlayerCommandSendEvent event) {
        if (plugin.settings().colorMode() != ColorMode.MENU) {
            event.getCommands().removeIf(command -> command.equalsIgnoreCase("elocolors")
                    || command.equalsIgnoreCase("shelo:elocolors"));
        }
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof ColorMenuHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        MenuColor color = holder.bySlot.get(event.getRawSlot());
        if (color == null) {
            return;
        }

        int currentElo = plugin.database().getCachedElo(
                player.getUniqueId(), plugin.settings().startingElo());
        if (currentElo < color.requiredElo()) {
            plugin.messages().send(player, "color-locked",
                    MessageManager.text("required_elo", Integer.toString(color.requiredElo())),
                    MessageManager.text("current_elo", Integer.toString(currentElo)));
            return;
        }

        plugin.database().setSelectedColor(player.getUniqueId(), color.id())
                .whenComplete((ignored, error) -> plugin.runSync(() -> {
                    if (error != null) {
                        plugin.messages().send(player, "database-error");
                        plugin.getLogger().severe("Could not save the Elo color: " + error.getMessage());
                        return;
                    }
                    plugin.messages().send(player, "color-selected",
                            MessageManager.text("color", color.id()));
                    open(player);
                }));
    }

    private Material material(String name) {
        Material material = Material.matchMaterial(name == null ? "" : name);
        return material == null || !material.isItem() ? Material.PAPER : material;
    }

    private int normalizeSize(int requested) {
        int rows = Math.max(1, Math.min(6, (requested + 8) / 9));
        return rows * 9;
    }

    private record MenuColor(String id, int slot, int requiredElo, Material material,
                             String name, List<String> lore, String format) {
    }

    private static final class ColorMenuHolder implements InventoryHolder {
        private final Map<Integer, MenuColor> bySlot = new HashMap<>();
        private Inventory inventory;

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
