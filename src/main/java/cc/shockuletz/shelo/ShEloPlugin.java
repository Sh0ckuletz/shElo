package cc.shockuletz.shelo;

import net.milkbowl.vault.economy.Economy;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ShEloPlugin extends JavaPlugin {
    // Inregistreaza pluginul pe https://bstats.org/plugin/bukkit si inlocuieste cu ID-ul primit.
    // Atentie: nu pune zero-uri in fata (ex. 01234), Java il citeste ca octal.
    private static final int BSTATS_PLUGIN_ID = 33167;

    private final AtomicBoolean reloadInProgress = new AtomicBoolean();
    private Economy economy;
    private EloStorage database;
    private volatile EloStorage pendingReloadDatabase;
    private MessageManager messages;
    private EloColorManager colors;
    private EloColorMenu colorMenu;
    private PluginSettings settings;
    private DecimalFormat moneyFormatter;
    private PlaceholderHook placeholderHook = PlaceholderHook.disabled();
    private ExecutorService reloadExecutor;
    private volatile CompletableFuture<ReloadResult> activeReload;
    private volatile boolean shuttingDown;

    @Override
    public void onEnable() {
        shuttingDown = false;
        reloadInProgress.set(false);
        reloadExecutor = createReloadExecutor();

        if (!updateConfigurationFiles()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        settings = PluginSettings.from(getConfig());
        updateMoneyFormatter();
        messages = new MessageManager(this);
        colors = new EloColorManager(this);
        colorMenu = new EloColorMenu(this);

        if (!setupEconomy()) {
            getLogger().severe("Nu a fost gasit un provider de economie Vault. Pluginul va fi dezactivat.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        DatabaseSettings databaseSettings = DatabaseSettings.from(getConfig());
        try {
            database = createStorage(databaseSettings);
        } catch (Exception exception) {
            getLogger().severe("Conexiunea la baza de date nu a putut fi initializata: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        warmOnlinePlayerCache();

        initializePlaceholderApi();

        getServer().getPluginManager().registerEvents(new CombatListener(this), this);
        getServer().getPluginManager().registerEvents(colorMenu, this);

        PluginCommand command = getCommand("elo");
        if (command == null) {
            throw new IllegalStateException("Comanda elo lipseste din plugin.yml");
        }
        EloCommand eloCommand = new EloCommand(this);
        command.setExecutor(eloCommand);
        command.setTabCompleter(eloCommand);

        PluginCommand statsCommand = getCommand("stats");
        if (statsCommand == null) {
            throw new IllegalStateException("Comanda stats lipseste din plugin.yml");
        }
        StatsCommand statsExecutor = new StatsCommand(this);
        statsCommand.setExecutor(statsExecutor);
        statsCommand.setTabCompleter(statsExecutor);

        PluginCommand colorCommand = getCommand("elocolors");
        if (colorCommand == null) {
            throw new IllegalStateException("Comanda elocolors lipseste din plugin.yml");
        }
        colorCommand.setExecutor(new ColorMenuCommand(this));

        setupMetrics(databaseSettings);

        getLogger().info("shElo a fost activat cu succes si conectat la baza de date ("
                + databaseSettings.storageType() + ").");
    }

    @Override
    public void onDisable() {
        shuttingDown = true;
        CompletableFuture<ReloadResult> reload = activeReload;
        if (reload != null) {
            finishReload(reload, ReloadResult.DISABLED);
        }

        placeholderHook.close();
        placeholderHook = PlaceholderHook.disabled();

        ExecutorService executor = reloadExecutor;
        if (executor != null) {
            executor.shutdownNow();
        }

        EloStorage pending = pendingReloadDatabase;
        pendingReloadDatabase = null;
        if (pending != null && pending != database) {
            pending.close();
        }
        if (database != null) {
            database.close();
        }
    }

    public CompletableFuture<ReloadResult> reloadPluginAsync() {
        if (!reloadInProgress.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(ReloadResult.ALREADY_IN_PROGRESS);
        }

        CompletableFuture<ReloadResult> result = new CompletableFuture<>();
        activeReload = result;
        if (shuttingDown || reloadExecutor == null || reloadExecutor.isShutdown()) {
            finishReload(result, ReloadResult.DISABLED);
            return result;
        }

        if (!updateConfigurationFiles()) {
            finishReload(result, ReloadResult.CONFIG_ERROR);
            return result;
        }

        PluginSettings replacementSettings;
        DatabaseSettings replacementDatabaseSettings;
        try {
            YamlConfiguration candidate = new YamlConfiguration();
            candidate.load(new File(getDataFolder(), "config.yml"));
            replacementSettings = PluginSettings.from(candidate);
            replacementDatabaseSettings = DatabaseSettings.from(candidate);
        } catch (Exception exception) {
            getLogger().severe("Configuratia noua nu a putut fi citita: " + exception.getMessage());
            finishReload(result, ReloadResult.CONFIG_ERROR);
            return result;
        }

        try {
            reloadExecutor.execute(() -> openReplacementDatabase(
                    replacementSettings, replacementDatabaseSettings, result));
        } catch (RejectedExecutionException exception) {
            finishReload(result, ReloadResult.DISABLED);
        }
        return result;
    }

    public boolean isReloadInProgress() {
        return reloadInProgress.get();
    }

    private void openReplacementDatabase(PluginSettings replacementSettings,
                                         DatabaseSettings replacementDatabaseSettings,
                                         CompletableFuture<ReloadResult> result) {
        EloStorage replacement;
        try {
            replacement = createStorage(replacementDatabaseSettings);
        } catch (Exception exception) {
            getLogger().severe("Noua conexiune la baza de date nu a putut fi initializata: " + exception.getMessage());
            finishReload(result, ReloadResult.DATABASE_ERROR);
            return;
        }

        pendingReloadDatabase = replacement;
        try {
            int loadedPlayers = replacement.preloadAllPlayers().join();
            getLogger().info("Cache-ul PlaceholderAPI a preincarcat " + loadedPlayers + " jucatori.");
        } catch (CompletionException exception) {
            pendingReloadDatabase = null;
            replacement.close();
            if (shuttingDown || result.isDone()) {
                finishReload(result, ReloadResult.DISABLED);
            } else {
                Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                getLogger().severe("Cache-ul Elo nu a putut fi preincarcat: " + cause.getMessage());
                finishReload(result, ReloadResult.DATABASE_ERROR);
            }
            return;
        }

        if (shuttingDown || !isEnabled()) {
            pendingReloadDatabase = null;
            replacement.close();
            finishReload(result, ReloadResult.DISABLED);
            return;
        }

        runSync(() -> applyReload(replacement, replacementSettings, result));
    }

    private void applyReload(EloStorage replacement, PluginSettings replacementSettings,
                             CompletableFuture<ReloadResult> result) {
        if (shuttingDown || result.isDone()) {
            if (pendingReloadDatabase == replacement) {
                pendingReloadDatabase = null;
                closeDatabaseAsync(replacement);
            }
            finishReload(result, ReloadResult.DISABLED);
            return;
        }

        EloStorage oldDatabase = database;
        PluginSettings oldSettings = settings;
        DecimalFormat oldMoneyFormatter = moneyFormatter;
        try {
            reloadConfig();
            settings = replacementSettings;
            updateMoneyFormatter();
            messages.reload();
            colors.reload();
            colorMenu.reload();
            for (Player player : getServer().getOnlinePlayers()) {
                player.updateCommands();
            }

            database = replacement;
            pendingReloadDatabase = null;
            registerPlaceholderApiIfNeeded();
            warmOnlinePlayerCache();
            finishReload(result, ReloadResult.SUCCESS);
            if (oldDatabase != null) {
                closeDatabaseAsync(oldDatabase);
            }
        } catch (RuntimeException exception) {
            settings = oldSettings;
            moneyFormatter = oldMoneyFormatter;
            pendingReloadDatabase = null;
            closeDatabaseAsync(replacement);
            getLogger().severe("Configuratia nu a putut fi aplicata: " + exception.getMessage());
            finishReload(result, ReloadResult.CONFIG_ERROR);
        }
    }

    private void finishReload(CompletableFuture<ReloadResult> result, ReloadResult status) {
        reloadInProgress.set(false);
        if (activeReload == result) {
            activeReload = null;
        }
        result.complete(status);
    }

    private void closeDatabaseAsync(EloStorage databaseToClose) {
        ExecutorService executor = reloadExecutor;
        if (executor == null || executor.isShutdown()) {
            databaseToClose.close();
            return;
        }
        try {
            executor.execute(databaseToClose::close);
        } catch (RejectedExecutionException exception) {
            databaseToClose.close();
        }
    }

    private ExecutorService createReloadExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "shElo-Reload");
            thread.setDaemon(true);
            return thread;
        });
    }

    private void setupMetrics(DatabaseSettings databaseSettings) {
        if (BSTATS_PLUGIN_ID <= 0) {
            return;
        }
        Metrics metrics = new Metrics(this, BSTATS_PLUGIN_ID);
        metrics.addCustomChart(new SimplePie("storage_type", () -> databaseSettings.storageType().name()));
    }

    private boolean setupEconomy() {
        RegisteredServiceProvider<Economy> registration = getServer().getServicesManager().getRegistration(Economy.class);
        if (registration == null) {
            return false;
        }
        economy = registration.getProvider();
        return economy != null;
    }

    private boolean updateConfigurationFiles() {
        try {
            ConfigUpdater.update(this, "config.yml");
            ConfigUpdater.update(this, "messages.yml");
            ConfigUpdater.update(this, "colors.yml");
            ConfigUpdater.update(this, "menus.yml");
            return true;
        } catch (Exception exception) {
            getLogger().severe("Configuratiile nu au putut fi actualizate: " + exception.getMessage());
            return false;
        }
    }

    private void updateMoneyFormatter() {
        try {
            moneyFormatter = new DecimalFormat(settings.moneyFormat(), DecimalFormatSymbols.getInstance(Locale.US));
        } catch (IllegalArgumentException exception) {
            getLogger().warning("money-format invalid; se foloseste formatul implicit #,##0.##");
            moneyFormatter = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.US));
        }
    }

    public String formatMoney(double amount) {
        return moneyFormatter.format(amount);
    }

    public Economy economy() {
        return economy;
    }

    public EloStorage database() {
        return database;
    }

    private EloStorage createStorage(DatabaseSettings settings) throws Exception {
        return switch (settings.storageType()) {
            case SQLITE -> new SqliteEloStorage(this, settings);
            case MYSQL -> new MySqlEloStorage(this, settings);
        };
    }

    public MessageManager messages() {
        return messages;
    }

    public EloColorMenu colorMenu() {
        return colorMenu;
    }

    public String formatElo(java.util.UUID playerId, int elo) {
        if (settings.colorMode() == ColorMode.MENU) {
            return colorMenu.formatSelected(playerId, elo);
        }
        return colors.format(elo);
    }

    public PluginSettings settings() {
        return settings;
    }

    public String applyPlaceholders(OfflinePlayer player, String text) {
        return placeholderHook.apply(player, text);
    }

    private void initializePlaceholderApi() {
        if (!getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            return;
        }

        EloStorage source = database;
        source.preloadAllPlayers().whenComplete((loadedPlayers, error) -> {
            if (error != null) {
                Throwable cause = error instanceof CompletionException && error.getCause() != null
                        ? error.getCause() : error;
                getLogger().severe("Cache-ul PlaceholderAPI nu a putut fi preincarcat: "
                        + cause.getMessage());
            }
            runSync(() -> {
                if (database != source || shuttingDown) {
                    return;
                }
                registerPlaceholderApiIfNeeded();
                if (error == null) {
                    getLogger().info("Cache-ul PlaceholderAPI a preincarcat "
                            + loadedPlayers + " jucatori.");
                }
            });
        });
    }

    private void registerPlaceholderApiIfNeeded() {
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")
                && !(placeholderHook instanceof PlaceholderApiHook)) {
            placeholderHook = new PlaceholderApiHook(this);
        }
    }

    private void warmOnlinePlayerCache() {
        for (Player player : getServer().getOnlinePlayers()) {
            database.ensurePlayer(player.getUniqueId(), player.getName(), settings.startingElo())
                    .exceptionally(error -> {
                        getLogger().warning("Cache-ul Elo nu a putut fi incarcat pentru "
                                + player.getName() + ": " + error.getMessage());
                        return null;
                    });
        }
    }

    public void runSync(Runnable task) {
        if (!isEnabled()) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            task.run();
            return;
        }
        Bukkit.getScheduler().runTask(this, () -> {
            if (isEnabled()) {
                task.run();
            }
        });
    }

    public enum ReloadResult {
        SUCCESS,
        ALREADY_IN_PROGRESS,
        CONFIG_ERROR,
        DATABASE_ERROR,
        DISABLED
    }
}
