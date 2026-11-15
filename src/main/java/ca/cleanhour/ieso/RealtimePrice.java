package ca.cleanhour.ieso;

import java.time.LocalDate;

/**
 * One hour of the real-time 5-minute Ontario Zonal Price.
 * {@code average} is the average of the intervals published so far ($/MWh); {@code intervals} is how many (max 12).
 */
public record RealtimePrice(LocalDate date, int hourEnding, double average, int intervals) {}
