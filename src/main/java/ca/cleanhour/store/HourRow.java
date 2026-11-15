package ca.cleanhour.store;

import ca.cleanhour.ieso.Fuel;
import com.fasterxml.jackson.annotation.JsonIgnore;

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
    /** Ontario Zonal Price, $/MWh: day-ahead market result for this hour. */
    public Double priceDayAhead;
    /** Ontario Zonal Price, $/MWh: latest pre-dispatch forecast (only kept for today). */
    public Double pricePredispatch;
    /** Ontario Zonal Price, $/MWh: average of the real-time 5-minute prices. */
    public Double priceRealtime;
    /** How many of the 12 real-time intervals {@link #priceRealtime} is built from. */
    public Integer priceRealtimeIntervals;

    /** Best known grid price for a past hour: real-time where we have it, otherwise day-ahead. */
    @JsonIgnore
    public Double gridPrice() {
        return priceRealtime != null ? priceRealtime : priceDayAhead;
    }

    public HourRow() { }

    public HourRow(Instant start) { this.start = start; }
}
