package cc.shockuletz.shelo;

import java.util.Locale;

public enum AntiFarmMode {
    BLOCK,
    REDUCE;

    public static AntiFarmMode parse(String value) {
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException exception) {
            return BLOCK;
        }
    }
}
