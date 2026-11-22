package cc.shockuletz.shelo;

import java.util.Locale;

public enum ColorMode {
    AUTOMATIC,
    MENU;

    public static ColorMode parse(String value) {
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException exception) {
            return AUTOMATIC;
        }
    }
}
