package ca.cleanhour.store;

import ca.cleanhour.ieso.Fuel;

import java.time.Instant;
import java.util.EnumMap;

/** Everything known about one IESO hour. {@code start} is the start of the hour. */
public class HourRow {
    public Instant start;
    /** Actual output by fuel, MW (hourly average = MWh). Null until IESO reports it. */
    public EnumMap<Fuel, Double> actual;
    /** Ontario demand, MW. */
    public Double demand;
    /** IESO day-ahead scheduled output by fuel: the last Adequacy3 version published before the delivery day. */
    public EnumMap<Fuel, Double> dayAhead;

    public HourRow() { }

    public HourRow(Instant start) { this.start = start; }
}
