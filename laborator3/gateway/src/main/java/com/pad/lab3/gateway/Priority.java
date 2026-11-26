package com.pad.lab3.gateway;

/** Request priority, taken from the X-Priority header (high | normal | low). Default: normal. */
public enum Priority {
    LOW(0), NORMAL(1), HIGH(2);

    public final int weight;

    Priority(int weight) {
        this.weight = weight;
    }

    public static Priority parse(String v) {
        if (v == null) return NORMAL;
        return switch (v.trim().toLowerCase()) {
            case "high", "2" -> HIGH;
            case "low", "0" -> LOW;
            default -> NORMAL;
        };
    }
}
