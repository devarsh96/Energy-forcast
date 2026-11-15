package ca.cleanhour.pricing;

import ca.cleanhour.pricing.ChargePlanner.Slot;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChargePlannerTest {

    static final Instant T0 = Instant.parse("2026-11-16T20:00:00Z"); // 3 pm Toronto

    /** prices[i] cents and intensity[i] g/kWh for consecutive hours from T0. */
    static List<Slot> slots(double[] prices, double[] intensity) {
        List<Slot> out = new ArrayList<>();
        for (int i = 0; i < prices.length; i++) out.add(new Slot(T0.plus(Duration.ofHours(i)), prices[i], intensity[i]));
        return out;
    }

    @Test
    void cheapestAndCleanestCanDiffer() {
        double[] price = {14.6, 37.7, 37.7, 14.6, 3.7, 3.7, 3.7, 14.6};
        double[] grams = {40, 120, 110, 30, 60, 70, 65, 90};
        var r = ChargePlanner.plan(slots(price, grams), 20, 10, null); // 2 hours of charging
        assertEquals(T0.plus(Duration.ofHours(4)), r.cheapest().start());
        assertEquals(0.74, r.cheapest().cost(), 1e-9);                 // 20 kWh x 3.7 c
        assertEquals(T0.plus(Duration.ofHours(3)), r.cleanest().start()); // 30 then 60 g
        assertEquals(T0, r.now().start());
        assertNotNull(r.balanced());
    }

    @Test
    void partialLastHourIsPricedPartly() {
        double[] price = {10, 20, 30};
        double[] grams = {10, 10, 10};
        var w = ChargePlanner.window(slots(price, grams), 0, 15, 10); // 1.5 hours
        assertEquals(1.5, w.hours(), 1e-9);
        assertEquals(10 * 0.10 + 5 * 0.20, w.cost(), 1e-9);
        assertEquals(T0.plusSeconds(5400), w.end());
    }

    @Test
    void readyByLimitsTheWindows() {
        double[] price = {20, 20, 20, 5, 5};
        double[] grams = {50, 50, 50, 50, 50};
        var r = ChargePlanner.plan(slots(price, grams), 10, 10, T0.plus(Duration.ofHours(3)));
        assertTrue(r.cheapest().end().compareTo(T0.plus(Duration.ofHours(3))) <= 0);
        assertEquals(3, r.candidates());
    }

    @Test
    void tooLongForTheForecastGivesANote() {
        double[] price = {10, 10};
        double[] grams = {10, 10};
        var r = ChargePlanner.plan(slots(price, grams), 60, 7.2, null);
        assertNull(r.cheapest());
        assertNotNull(r.note());
    }

    @Test
    void noCarbonForecastMeansNoCleanestOrBalanced() {
        List<Slot> slots = new ArrayList<>();
        for (int i = 0; i < 12; i++) slots.add(new Slot(T0.plus(Duration.ofHours(i)), i == 5 ? 2 : 10, i < 6 ? 40.0 : null));
        var r = ChargePlanner.plan(slots, 7, 7, null);
        assertEquals(T0.plus(Duration.ofHours(5)), r.cheapest().start());
        assertNull(r.cleanest());
        assertNull(r.balanced());
        assertNotNull(r.note());
        assertEquals(0.28, r.cheapest().kgCo2(), 1e-9); // its own hour does have a forecast
        assertNull(ChargePlanner.window(slots, 6, 7, 7).kgCo2()); // hour 6 has no forecast
    }
}
