package ca.cleanhour.pricing;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holidays that the OEB prices as off-peak (all day) for Time-of-Use and Ultra-Low Overnight plans.
 * When a holiday falls on a weekend, the next weekday that is not already a holiday is priced as the holiday.
 */
public final class OntarioHolidays {

    private static final Map<Integer, Set<LocalDate>> CACHE = new ConcurrentHashMap<>();

    private OntarioHolidays() { }

    public static boolean isHoliday(LocalDate d) {
        return CACHE.computeIfAbsent(d.getYear(), OntarioHolidays::forYear).contains(d);
    }

    /** True for Saturdays, Sundays and holidays: the whole day is off-peak (or weekend off-peak on ULO). */
    public static boolean isOffPeakDay(LocalDate d) {
        DayOfWeek w = d.getDayOfWeek();
        return w == DayOfWeek.SATURDAY || w == DayOfWeek.SUNDAY || isHoliday(d);
    }

    static Set<LocalDate> forYear(int y) {
        TreeSet<LocalDate> base = new TreeSet<>();
        base.add(LocalDate.of(y, Month.JANUARY, 1));                                   // New Year's Day
        base.add(nth(y, Month.FEBRUARY, DayOfWeek.MONDAY, 3));                         // Family Day
        base.add(easter(y).minusDays(2));                                              // Good Friday
        base.add(LocalDate.of(y, Month.MAY, 25).with(TemporalAdjusters.previous(DayOfWeek.MONDAY))); // Victoria Day
        base.add(LocalDate.of(y, Month.JULY, 1));                                      // Canada Day
        base.add(nth(y, Month.AUGUST, DayOfWeek.MONDAY, 1));                           // Civic Holiday
        base.add(nth(y, Month.SEPTEMBER, DayOfWeek.MONDAY, 1));                        // Labour Day
        base.add(nth(y, Month.OCTOBER, DayOfWeek.MONDAY, 2));                          // Thanksgiving
        base.add(LocalDate.of(y, Month.DECEMBER, 25));                                 // Christmas Day
        base.add(LocalDate.of(y, Month.DECEMBER, 26));                                 // Boxing Day

        TreeSet<LocalDate> out = new TreeSet<>();
        for (LocalDate d : base) {
            DayOfWeek w = d.getDayOfWeek();
            if (w != DayOfWeek.SATURDAY && w != DayOfWeek.SUNDAY) { out.add(d); continue; }
            LocalDate sub = d.plusDays(1);
            while (sub.getDayOfWeek() == DayOfWeek.SATURDAY || sub.getDayOfWeek() == DayOfWeek.SUNDAY
                    || base.contains(sub) || out.contains(sub)) sub = sub.plusDays(1);
            out.add(sub);
        }
        return out;
    }

    private static LocalDate nth(int y, Month m, DayOfWeek dow, int n) {
        return LocalDate.of(y, m, 1).with(TemporalAdjusters.dayOfWeekInMonth(n, dow));
    }

    /** Western Easter Sunday (anonymous Gregorian algorithm). */
    static LocalDate easter(int y) {
        int a = y % 19, b = y / 100, c = y % 100, d = b / 4, e = b % 4, f = (b + 8) / 25, g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30, i = c / 4, k = c % 4, l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31, day = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(y, month, day);
    }
}
