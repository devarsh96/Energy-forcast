package ca.cleanhour.pricing;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TariffTest {

    static final RateSet NOV_2025 = new RateSet(LocalDate.of(2025, 11, 1), 9.8, 15.7, 20.3, 3.9, 9.8, 15.7, 39.1, 12.0, 14.2, 23.5);
    static final RateSet NOV_2026 = new RateSet(LocalDate.of(2026, 11, 1), 9.1, 14.6, 18.9, 3.7, 9.1, 14.6, 37.7, 11.2, 13.2, 15.7);
    static final Tariff T = new Tariff(List.of(NOV_2026, NOV_2025), 13);

    private static Tariff.Price at(RatePlan p, String localDateTime) {
        return T.price(p, LocalDateTime.parse(localDateTime), false);
    }

    @Test
    void ultraLowOvernightPeriods() {
        // Monday 2026-11-16
        assertEquals("ultra-low", at(RatePlan.ULO, "2026-11-16T23:00").period());
        assertEquals(3.7, at(RatePlan.ULO, "2026-11-16T06:59").cents());
        assertEquals("mid-peak", at(RatePlan.ULO, "2026-11-16T07:00").period());
        assertEquals("mid-peak", at(RatePlan.ULO, "2026-11-16T15:59").period());
        assertEquals("on-peak", at(RatePlan.ULO, "2026-11-16T16:00").period());
        assertEquals(37.7, at(RatePlan.ULO, "2026-11-16T20:59").cents());
        assertEquals("mid-peak", at(RatePlan.ULO, "2026-11-16T21:00").period());
        assertEquals("mid-peak", at(RatePlan.ULO, "2026-11-16T22:59").period());
        // Saturday: weekend off-peak in the day, ultra-low overnight
        assertEquals("weekend-off-peak", at(RatePlan.ULO, "2026-11-14T17:00").period());
        assertEquals("ultra-low", at(RatePlan.ULO, "2026-11-14T02:00").period());
    }

    @Test
    void timeOfUseWinterAndSummer() {
        assertEquals("on-peak", at(RatePlan.TOU, "2026-11-16T07:00").period());
        assertEquals("mid-peak", at(RatePlan.TOU, "2026-11-16T11:00").period());
        assertEquals("on-peak", at(RatePlan.TOU, "2026-11-16T17:30").period());
        assertEquals("off-peak", at(RatePlan.TOU, "2026-11-16T19:00").period());
        assertEquals("off-peak", at(RatePlan.TOU, "2026-11-15T12:00").period()); // Sunday
        // Summer weekday (2026-07-15, Nov 2025 rates): on-peak midday, mid-peak at the shoulders
        assertEquals("on-peak", at(RatePlan.TOU, "2026-07-15T12:00").period());
        assertEquals(20.3, at(RatePlan.TOU, "2026-07-15T12:00").cents());
        assertEquals("mid-peak", at(RatePlan.TOU, "2026-07-15T08:00").period());
    }

    @Test
    void rateSetChangesOnNovemberFirst() {
        assertEquals(3.9, at(RatePlan.ULO, "2026-10-31T23:30").cents());
        assertEquals(3.7, at(RatePlan.ULO, "2026-11-01T00:30").cents());
    }

    @Test
    void holidaysAreOffPeakAllDay() {
        // Christmas 2026 is a Friday
        assertEquals("off-peak", at(RatePlan.TOU, "2026-12-25T08:00").period());
        assertEquals("weekend-off-peak", at(RatePlan.ULO, "2026-12-25T17:00").period());
        // Boxing Day 2026 is a Saturday, so Monday Dec 28 is priced as the holiday
        assertTrue(OntarioHolidays.isHoliday(LocalDate.of(2026, 12, 28)));
        assertEquals("off-peak", at(RatePlan.TOU, "2026-12-28T08:00").period());
        assertFalse(OntarioHolidays.isHoliday(LocalDate.of(2026, 12, 29)));
    }

    @Test
    void movingHolidays() {
        assertEquals(LocalDate.of(2026, 4, 5), OntarioHolidays.easter(2026));
        assertTrue(OntarioHolidays.isHoliday(LocalDate.of(2026, 4, 3)));   // Good Friday
        assertTrue(OntarioHolidays.isHoliday(LocalDate.of(2026, 2, 16)));  // Family Day
        assertTrue(OntarioHolidays.isHoliday(LocalDate.of(2026, 5, 18)));  // Victoria Day
        assertTrue(OntarioHolidays.isHoliday(LocalDate.of(2026, 8, 3)));   // Civic Holiday
        assertTrue(OntarioHolidays.isHoliday(LocalDate.of(2026, 10, 12))); // Thanksgiving
    }

    @Test
    void tieredUsesTier1UnlessOverThreshold() {
        assertEquals(11.2, at(RatePlan.TIERED, "2026-11-16T18:00").cents());
        assertEquals(13.2, T.price(RatePlan.TIERED, LocalDateTime.parse("2026-11-16T18:00"), true).cents());
    }

    @Test
    void daylightSavingUsesLocalClock() {
        // 2026-11-01 01:30 happens twice; both are overnight. 2026-03-09 (Monday after spring forward) 16:30 local is on-peak.
        Instant afterSpring = ZonedDateTime.of(2026, 3, 9, 16, 30, 0, 0, Tariff.LOCAL).toInstant();
        assertEquals("on-peak", T.price(RatePlan.ULO, afterSpring).period());
        Instant ambiguous = Instant.parse("2026-11-01T06:30:00Z"); // 01:30 EST, second pass
        assertEquals("ultra-low", T.price(RatePlan.ULO, ambiguous).period());
    }

    @Test
    void nextChangeFindsTheNextPeriod() {
        Instant monday3pm = ZonedDateTime.of(2026, 11, 16, 15, 20, 0, 0, Tariff.LOCAL).toInstant();
        Instant next = T.nextChange(RatePlan.ULO, monday3pm);
        assertEquals(ZonedDateTime.of(2026, 11, 16, 16, 0, 0, 0, Tariff.LOCAL).toInstant(), next);
    }
}
