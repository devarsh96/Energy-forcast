package ca.cleanhour.ieso;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * MW by fuel for each IESO delivery hour of one day.
 * IESO hours are "hour ending" 1..24 in Eastern Standard Time (no daylight saving).
 */
public record DayReport(LocalDate date, Map<Integer, EnumMap<Fuel, Double>> hours) {

    public DayReport(LocalDate date) {
        this(date, new TreeMap<>());
    }

    void add(int hourEnding, Fuel fuel, double mw) {
        hours.computeIfAbsent(hourEnding, h -> new EnumMap<>(Fuel.class)).merge(fuel, mw, Double::sum);
    }
}
