package cc.shockuletz.shelo;

import org.bukkit.configuration.file.FileConfiguration;

public record DatabaseSettings(
        StorageType storageType,
        String sqliteFile,
        String host,
        int port,
        String database,
        String username,
        String password,
        String table,
        boolean useSsl,
        boolean allowPublicKeyRetrieval,
        int maximumPoolSize,
        int minimumIdle,
        long connectionTimeout,
        long maxLifetime,
        long keepaliveTime
) {
    public static DatabaseSettings from(FileConfiguration config) {
        int maximumPoolSize = Math.max(1, config.getInt("database.pool.maximum-size", 10));
        int minimumIdle = Math.max(0, Math.min(maximumPoolSize,
                config.getInt("database.pool.minimum-idle", 2)));

        return new DatabaseSettings(
                StorageType.parse(config.getString("database.storage-type", "MYSQL")),
                validSqliteFile(config.getString("database.sqlite.file", "shelo.db")),
                config.getString("database.host", "127.0.0.1"),
                Math.max(1, Math.min(65535, config.getInt("database.port", 3306))),
                config.getString("database.name", "minecraft"),
                config.getString("database.username", "root"),
                config.getString("database.password", "schimba-ma"),
                validTable(config.getString("database.table", "shelo_players")),
                config.getBoolean("database.use-ssl", false),
                config.getBoolean("database.allow-public-key-retrieval", true),
                maximumPoolSize,
                minimumIdle,
                Math.max(250, config.getLong("database.pool.connection-timeout-ms", 10_000)),
                Math.max(30_000, config.getLong("database.pool.max-lifetime-ms", 1_800_000)),
                Math.max(0, config.getLong("database.pool.keepalive-time-ms", 0))
        );
    }

    private static String validSqliteFile(String file) {
        if (file == null || file.isBlank()) {
            return "shelo.db";
        }
        String trimmed = file.trim();
        if (trimmed.contains("..") || trimmed.contains("/") || trimmed.contains("\\")) {
            return "shelo.db";
        }
        return trimmed;
    }

    private static String validTable(String table) {
        if (table != null && table.matches("[A-Za-z0-9_]+")) {
            return table;
        }
        return "shelo_players";
    }
}
