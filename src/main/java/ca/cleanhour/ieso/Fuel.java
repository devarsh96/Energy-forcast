package ca.cleanhour.ieso;

/** Fuel categories used by IESO public reports. */
public enum Fuel {
    NUCLEAR, GAS, HYDRO, WIND, SOLAR, BIOFUEL,
    /** Storage and miscellaneous units. Excluded from intensity (storage only shifts energy that was already counted). */
    OTHER;

    public static Fuel parse(String raw) {
        if (raw == null) return OTHER;
        return switch (raw.trim().toUpperCase()) {
            case "NUCLEAR" -> NUCLEAR;
            case "GAS" -> GAS;
            case "HYDRO" -> HYDRO;
            case "WIND" -> WIND;
            case "SOLAR" -> SOLAR;
            case "BIOFUEL", "BIOMASS" -> BIOFUEL;
            default -> OTHER;
        };
    }
}
