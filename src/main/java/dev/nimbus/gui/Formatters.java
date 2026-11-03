package dev.nimbus.gui;

import java.util.Locale;

public final class Formatters {
    private static final String[] COMPASS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
    private Formatters() { }
    public static String compass(Double degrees) {
        if (degrees == null || !Double.isFinite(degrees)) return "–";
        return COMPASS[(int) Math.floor(((degrees % 360 + 360) % 360 + 22.5) / 45) % 8];
    }
    public static String number(Number value) { return value == null ? "–" : String.format(Locale.ROOT, "%.1f", value.doubleValue()); }
    public static String value(Number value, String unit) { return value == null ? "–" : number(value) + (unit == null || unit.isBlank() ? "" : " " + unit); }
    public static String text(String value) { return value == null || value.isBlank() ? "–" : value; }
}
