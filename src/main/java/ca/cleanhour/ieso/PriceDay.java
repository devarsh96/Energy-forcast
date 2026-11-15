package ca.cleanhour.ieso;

import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;

/** Ontario Zonal Price ($/MWh) by IESO delivery hour (hour ending 1..24, Eastern Standard Time). */
public record PriceDay(LocalDate date, Map<Integer, Double> hours) {
    public PriceDay(LocalDate date) { this(date, new TreeMap<>()); }
}
