package cc.shockuletz.shelo;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * SQLite keeps a single connection in the pool (an SQLite file has a single writer),
 * which serializes all operations through this manager and removes the need for
 * explicit locking such as the "SELECT ... FOR UPDATE" used by the MySQL variant.
 */
public final class SqliteEloStorage implements EloStorage {
    private final HikariDataSource dataSource;
    private final ExecutorService executor;
    private final ConcurrentHashMap<UUID, Integer> eloCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> highestEloCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> selectedColorCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CompletableFuture<Integer>> pendingEloLoads = new ConcurrentHashMap<>();
    private final String tableName;
    private final String table;

    public SqliteEloStorage(ShEloPlugin plugin, DatabaseSettings settings) throws SQLException {
        this.tableName = settings.table();
        this.table = "\"" + tableName + "\"";
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "shElo-SQLite");
            thread.setDaemon(true);
            return thread;
        });

        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            throw new SQLException("Plugin directory could not be created: " + dataFolder);
        }
        File databaseFile = new File(dataFolder, settings.sqliteFile());

        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("shElo-Hikari-SQLite");
        hikari.setDriverClassName("org.sqlite.JDBC");
        hikari.setJdbcUrl("jdbc:sqlite:" + databaseFile.getAbsolutePath());
        hikari.setMaximumPoolSize(1);
        hikari.setMinimumIdle(1);
        hikari.setConnectionTimeout(settings.connectionTimeout());
        hikari.setMaxLifetime(settings.maxLifetime());
        hikari.setInitializationFailTimeout(settings.connectionTimeout());

        HikariDataSource newDataSource = new HikariDataSource(hikari);
        try {
            configureConnection(newDataSource);
            createTable(newDataSource);
        } catch (SQLException exception) {
            newDataSource.close();
            executor.shutdownNow();
            throw exception;
        }
        this.dataSource = newDataSource;
    }

    private void configureConnection(HikariDataSource source) throws SQLException {
        try (Connection connection = source.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA foreign_keys=ON");
        }
    }

    private void createTable(HikariDataSource source) throws SQLException {
        String sql = "CREATE TABLE IF NOT EXISTS " + table + " ("
                + "uuid TEXT NOT NULL PRIMARY KEY,"
                + "last_name TEXT NOT NULL,"
                + "elo INTEGER NOT NULL,"
                + "highest_elo INTEGER NOT NULL DEFAULT 0,"
                + "selected_color TEXT,"
                + "kills INTEGER NOT NULL DEFAULT 0,"
                + "deaths INTEGER NOT NULL DEFAULT 0,"
                + "current_streak INTEGER NOT NULL DEFAULT 0,"
                + "best_streak INTEGER NOT NULL DEFAULT 0,"
                + "updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP"
                + ")";
        try (Connection connection = source.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS \"idx_" + tableName + "_last_name\" ON "
                    + table + " (last_name)");
            migrateColumns(connection);
        }
    }

    private void migrateColumns(Connection connection) throws SQLException {
        ensureColumn(connection, "highest_elo", "INTEGER NOT NULL DEFAULT 0");
        ensureColumn(connection, "selected_color", "TEXT");
        ensureColumn(connection, "kills", "INTEGER NOT NULL DEFAULT 0");
        ensureColumn(connection, "deaths", "INTEGER NOT NULL DEFAULT 0");
        ensureColumn(connection, "current_streak", "INTEGER NOT NULL DEFAULT 0");
        ensureColumn(connection, "best_streak", "INTEGER NOT NULL DEFAULT 0");
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE " + table + " SET highest_elo = MAX(highest_elo, elo)");
        }
    }

    private void ensureColumn(Connection connection, String column, String definition) throws SQLException {
        try (Statement pragma = connection.createStatement();
             ResultSet result = pragma.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (result.next()) {
                if (column.equalsIgnoreCase(result.getString("name"))) {
                    return;
                }
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE " + table + " ADD COLUMN \"" + column + "\" " + definition);
        }
    }

    @Override
    public CompletableFuture<Void> ensurePlayer(UUID playerId, String playerName, int startingElo) {
        return runAsync(() -> {
            String sql = "INSERT INTO " + table + " (uuid, last_name, elo, highest_elo, updated_at) "
                    + "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP) "
                    + "ON CONFLICT(uuid) DO UPDATE SET last_name = excluded.last_name, "
                    + "updated_at = CURRENT_TIMESTAMP";
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, playerId.toString());
                statement.setString(2, playerName);
                statement.setInt(3, startingElo);
                statement.setInt(4, startingElo);
                statement.executeUpdate();
                try (PreparedStatement select = connection.prepareStatement(
                        "SELECT elo, highest_elo, selected_color FROM " + table + " WHERE uuid = ?")) {
                    select.setString(1, playerId.toString());
                    try (ResultSet result = select.executeQuery()) {
                        if (result.next()) {
                            cachePlayer(playerId, result);
                        }
                    }
                }
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Integer> getElo(UUID playerId, int startingElo) {
        return runAsync(() -> {
            String sql = "SELECT elo, highest_elo, selected_color FROM " + table + " WHERE uuid = ?";
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, playerId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        eloCache.put(playerId, startingElo);
                        highestEloCache.put(playerId, startingElo);
                        selectedColorCache.remove(playerId);
                        return startingElo;
                    }
                    cachePlayer(playerId, result);
                    return result.getInt("elo");
                }
            }
        });
    }

    @Override
    public CompletableFuture<Integer> preloadAllPlayers() {
        return runAsync(() -> {
            int loaded = 0;
            String sql = "SELECT uuid, elo, highest_elo, selected_color FROM " + table;
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    UUID playerId = UUID.fromString(result.getString("uuid"));
                    cachePlayerIfAbsent(playerId, result);
                    loaded++;
                }
            }
            return loaded;
        });
    }

    @Override
    public Integer getCachedEloIfPresent(UUID playerId) {
        return eloCache.get(playerId);
    }

    @Override
    public void ensureEloCached(UUID playerId, int startingElo) {
        pendingEloLoads.computeIfAbsent(playerId, ignored -> getElo(playerId, startingElo))
                .whenComplete((elo, error) -> pendingEloLoads.remove(playerId));
    }

    @Override
    public CompletableFuture<Integer> addElo(UUID playerId, String playerName, int amount,
                                              int startingElo, int minimumElo) {
        return runAsync(() -> {
            int elo = addEloTransaction(playerId, playerName, amount, startingElo, minimumElo);
            eloCache.put(playerId, elo);
            return elo;
        });
    }

    @Override
    public CompletableFuture<Integer> setElo(UUID playerId, String playerName, int value, int minimumElo) {
        return runAsync(() -> {
            int updatedElo = Math.max(minimumElo, value);
            String upsertSql = "INSERT INTO " + table
                    + " (uuid, last_name, elo, highest_elo, updated_at) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP) "
                    + "ON CONFLICT(uuid) DO UPDATE SET last_name = excluded.last_name, elo = excluded.elo, "
                    + "highest_elo = MAX(highest_elo, excluded.elo), updated_at = CURRENT_TIMESTAMP";
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement upsert = connection.prepareStatement(upsertSql)) {
                upsert.setString(1, playerId.toString());
                upsert.setString(2, playerName);
                upsert.setInt(3, updatedElo);
                upsert.setInt(4, updatedElo);
                upsert.executeUpdate();

                try (PreparedStatement select = connection.prepareStatement(
                        "SELECT elo, highest_elo, selected_color FROM " + table + " WHERE uuid = ?")) {
                    select.setString(1, playerId.toString());
                    try (ResultSet result = select.executeQuery()) {
                        if (!result.next()) {
                            throw new SQLException("Player Elo could not be updated");
                        }
                        cachePlayer(playerId, result);
                        return result.getInt("elo");
                    }
                }
            }
        });
    }

    private int addEloTransaction(UUID playerId, String playerName, int amount,
                                  int startingElo, int minimumElo) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                String ensureSql = "INSERT INTO " + table
                        + " (uuid, last_name, elo, highest_elo, updated_at) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP) "
                        + "ON CONFLICT(uuid) DO UPDATE SET last_name = excluded.last_name, "
                        + "updated_at = CURRENT_TIMESTAMP";
                try (PreparedStatement ensure = connection.prepareStatement(ensureSql)) {
                    ensure.setString(1, playerId.toString());
                    ensure.setString(2, playerName);
                    ensure.setInt(3, startingElo);
                    ensure.setInt(4, startingElo);
                    ensure.executeUpdate();
                }

                int currentElo;
                int currentHighestElo;
                String selectSql = "SELECT elo, highest_elo FROM " + table + " WHERE uuid = ?";
                try (PreparedStatement select = connection.prepareStatement(selectSql)) {
                    select.setString(1, playerId.toString());
                    try (ResultSet result = select.executeQuery()) {
                        if (!result.next()) {
                            throw new SQLException("Player could not be initialized");
                        }
                        currentElo = result.getInt("elo");
                        currentHighestElo = result.getInt("highest_elo");
                    }
                }

                int updatedElo = (int) Math.min(Integer.MAX_VALUE,
                        Math.max(minimumElo, (long) currentElo + amount));
                String updateSql = "UPDATE " + table
                        + " SET elo = ?, highest_elo = ?, last_name = ?, updated_at = CURRENT_TIMESTAMP WHERE uuid = ?";
                int updatedHighestElo = Math.max(currentHighestElo, updatedElo);
                try (PreparedStatement update = connection.prepareStatement(updateSql)) {
                    update.setInt(1, updatedElo);
                    update.setInt(2, updatedHighestElo);
                    update.setString(3, playerName);
                    update.setString(4, playerId.toString());
                    update.executeUpdate();
                }
                connection.commit();
                eloCache.put(playerId, updatedElo);
                highestEloCache.put(playerId, updatedHighestElo);
                return updatedElo;
            } catch (SQLException exception) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackException) {
                    exception.addSuppressed(rollbackException);
                }
                throw exception;
            }
        }
    }

    @Override
    public CompletableFuture<Optional<PlayerElo>> findByName(String playerName) {
        return runAsync(() -> {
            String sql = "SELECT uuid, last_name, elo, highest_elo, selected_color FROM " + table
                    + " WHERE last_name = ? ORDER BY updated_at DESC LIMIT 1";
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, playerName);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        return Optional.empty();
                    }
                    UUID playerId = UUID.fromString(result.getString("uuid"));
                    int elo = result.getInt("elo");
                    cachePlayer(playerId, result);
                    return Optional.of(new PlayerElo(
                            playerId,
                            result.getString("last_name"),
                            elo
                    ));
                }
            }
        });
    }

    @Override
    public CompletableFuture<Void> recordPvpResult(UUID killerId, String killerName,
                                                   UUID victimId, String victimName,
                                                   int startingElo) {
        return runAsync(() -> {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    String ensureSql = "INSERT INTO " + table
                            + " (uuid, last_name, elo, highest_elo, updated_at) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP) "
                            + "ON CONFLICT(uuid) DO UPDATE SET last_name = excluded.last_name, "
                            + "updated_at = CURRENT_TIMESTAMP";
                    try (PreparedStatement ensure = connection.prepareStatement(ensureSql)) {
                        ensure.setString(1, killerId.toString());
                        ensure.setString(2, killerName);
                        ensure.setInt(3, startingElo);
                        ensure.setInt(4, startingElo);
                        ensure.executeUpdate();

                        ensure.setString(1, victimId.toString());
                        ensure.setString(2, victimName);
                        ensure.setInt(3, startingElo);
                        ensure.setInt(4, startingElo);
                        ensure.executeUpdate();
                    }

                    String killerSql = "UPDATE " + table
                            + " SET best_streak = MAX(best_streak, current_streak + 1), "
                            + "current_streak = current_streak + 1, kills = kills + 1, "
                            + "updated_at = CURRENT_TIMESTAMP WHERE uuid = ?";
                    try (PreparedStatement updateKiller = connection.prepareStatement(killerSql)) {
                        updateKiller.setString(1, killerId.toString());
                        updateKiller.executeUpdate();
                    }

                    String victimSql = "UPDATE " + table
                            + " SET deaths = deaths + 1, current_streak = 0, "
                            + "updated_at = CURRENT_TIMESTAMP WHERE uuid = ?";
                    try (PreparedStatement updateVictim = connection.prepareStatement(victimSql)) {
                        updateVictim.setString(1, victimId.toString());
                        updateVictim.executeUpdate();
                    }
                    connection.commit();
                } catch (SQLException exception) {
                    try {
                        connection.rollback();
                    } catch (SQLException rollbackException) {
                        exception.addSuppressed(rollbackException);
                    }
                    throw exception;
                }
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<PlayerStats> getOrCreateStats(UUID playerId, String playerName,
                                                            int startingElo) {
        return runAsync(() -> {
            String ensureSql = "INSERT INTO " + table
                    + " (uuid, last_name, elo, highest_elo, updated_at) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP) "
                    + "ON CONFLICT(uuid) DO UPDATE SET last_name = excluded.last_name, "
                    + "updated_at = CURRENT_TIMESTAMP";
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement ensure = connection.prepareStatement(ensureSql)) {
                ensure.setString(1, playerId.toString());
                ensure.setString(2, playerName);
                ensure.setInt(3, startingElo);
                ensure.setInt(4, startingElo);
                ensure.executeUpdate();

                try (PreparedStatement select = connection.prepareStatement(
                        "SELECT uuid, last_name, highest_elo, kills, deaths, current_streak, best_streak "
                                + "FROM " + table + " WHERE uuid = ?")) {
                    select.setString(1, playerId.toString());
                    try (ResultSet result = select.executeQuery()) {
                        if (!result.next()) {
                            throw new SQLException("Player stats could not be initialized");
                        }
                        return readStats(result);
                    }
                }
            }
        });
    }

    @Override
    public CompletableFuture<Optional<PlayerStats>> findStatsByName(String playerName) {
        return runAsync(() -> {
            String sql = "SELECT uuid, last_name, highest_elo, kills, deaths, current_streak, best_streak FROM "
                    + table + " WHERE last_name = ? ORDER BY updated_at DESC LIMIT 1";
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, playerName);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(readStats(result)) : Optional.empty();
                }
            }
        });
    }

    private PlayerStats readStats(ResultSet result) throws SQLException {
        return new PlayerStats(
                UUID.fromString(result.getString("uuid")),
                result.getString("last_name"),
                result.getInt("highest_elo"),
                result.getLong("kills"),
                result.getLong("deaths"),
                result.getLong("current_streak"),
                result.getLong("best_streak")
        );
    }

    @Override
    public CompletableFuture<LeaderboardPage> getLeaderboard(int page, int pageSize) {
        return runAsync(() -> {
            try (Connection connection = dataSource.getConnection()) {
                long totalPlayers;
                try (PreparedStatement count = connection.prepareStatement("SELECT COUNT(*) FROM " + table);
                     ResultSet result = count.executeQuery()) {
                    result.next();
                    totalPlayers = result.getLong(1);
                }

                long totalPages = totalPlayers == 0
                        ? 0
                        : (totalPlayers + pageSize - 1L) / pageSize;
                if (totalPlayers == 0 || page > totalPages) {
                    return new LeaderboardPage(List.of(), page, totalPages, totalPlayers);
                }

                long offset = (page - 1L) * pageSize;
                List<LeaderboardEntry> entries = new ArrayList<>();
                String sql = "SELECT uuid, last_name, elo FROM " + table
                        + " ORDER BY elo DESC, last_name ASC, uuid ASC LIMIT ? OFFSET ?";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setInt(1, pageSize);
                    statement.setLong(2, offset);
                    try (ResultSet result = statement.executeQuery()) {
                        while (result.next()) {
                            entries.add(new LeaderboardEntry(
                                    UUID.fromString(result.getString("uuid")),
                                    result.getString("last_name"),
                                    result.getInt("elo")
                            ));
                        }
                    }
                }
                return new LeaderboardPage(List.copyOf(entries), page, totalPages, totalPlayers);
            }
        });
    }

    @Override
    public int getCachedElo(UUID playerId, int startingElo) {
        return eloCache.getOrDefault(playerId, startingElo);
    }

    @Override
    public int getCachedHighestElo(UUID playerId, int fallback) {
        return highestEloCache.getOrDefault(playerId, fallback);
    }

    @Override
    public String getCachedSelectedColor(UUID playerId) {
        return selectedColorCache.get(playerId);
    }

    @Override
    public CompletableFuture<Void> setSelectedColor(UUID playerId, String colorId) {
        return runAsync(() -> {
            String sql = "UPDATE " + table + " SET selected_color = ?, updated_at = CURRENT_TIMESTAMP WHERE uuid = ?";
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, colorId);
                statement.setString(2, playerId.toString());
                if (statement.executeUpdate() == 0) {
                    throw new SQLException("Player does not exist in the database");
                }
            }
            selectedColorCache.put(playerId, colorId);
            return null;
        });
    }

    private void cachePlayer(UUID playerId, ResultSet result) throws SQLException {
        int elo = result.getInt("elo");
        int highestElo = Math.max(elo, result.getInt("highest_elo"));
        String selectedColor = result.getString("selected_color");
        eloCache.put(playerId, elo);
        highestEloCache.put(playerId, highestElo);
        if (selectedColor == null) {
            selectedColorCache.remove(playerId);
        } else {
            selectedColorCache.put(playerId, selectedColor);
        }
    }

    private void cachePlayerIfAbsent(UUID playerId, ResultSet result) throws SQLException {
        int elo = result.getInt("elo");
        int highestElo = Math.max(elo, result.getInt("highest_elo"));
        String selectedColor = result.getString("selected_color");
        if (eloCache.putIfAbsent(playerId, elo) != null) {
            return;
        }
        highestEloCache.putIfAbsent(playerId, highestElo);
        if (selectedColor != null) {
            selectedColorCache.putIfAbsent(playerId, selectedColor);
        }
    }

    private <T> CompletableFuture<T> runAsync(SqlSupplier<T> operation) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return operation.get();
            } catch (SQLException exception) {
                throw new CompletionException(exception);
            }
        }, executor);
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
        dataSource.close();
    }

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws SQLException;
    }
}
