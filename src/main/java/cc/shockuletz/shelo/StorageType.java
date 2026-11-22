package cc.shockuletz.shelo;

import java.util.Locale;

public enum StorageType {
    MYSQL,
    SQLITE;

    public static StorageType parse(String value) {
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException exception) {
            return MYSQL;
        }
    }
}
