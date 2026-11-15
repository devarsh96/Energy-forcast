package ca.cleanhour.pricing;

import ca.cleanhour.CleanHourProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.*;

/**
 * Answers "what does plan P charge at time T?" using OEB Regulated Price Plan rules.
 * Periods follow the local (Toronto) clock, including daylight saving time.
 * <ul>
 *   <li>Ultra-Low Overnight: 11 pm to 7 am every day is ultra-low. Weekdays: mid-peak 7 am to 4 pm and
 *       9 pm to 11 pm, on-peak 4 pm to 9 pm. Weekends and holidays: weekend off-peak 7 am to 11 pm.</li>
 *   <li>Time-of-Use: off-peak 7 pm to 7 am weekdays and all day on weekends and holidays.
 *       Winter (Nov to Apr): on-peak 7 to 11 am and 5 to 7 pm, mid-peak 11 am to 5 pm.
 *       Summer (May to Oct): mid-peak 7 to 11 am and 5 to 7 pm, on-peak 11 am to 5 pm.</li>
 *   <li>Tiered: one price up to the monthly threshold, a higher one above it. The marginal kWh of an
 *       EV charge is priced at tier 1 unless the caller says the household is already over the threshold.</li>
 * </ul>
 */
@Component
public class Tariff {

    public static final ZoneId LOCAL = ZoneId.of("America/Toronto");

    /** The price of one hour on one plan. {@code period} is a stable code, {@code label} is for people. */
    public record Price(RatePlan plan, String period, String label, double cents) {}

    private final List<RateSet> sets;
    private final double hstPct;

    @Autowired
    public Tariff(CleanHourProperties props) {
        this(props.getPricing().getRateSets(), props.getPricing().getHstPct());
    }

    public Tariff(List<RateSet> sets, double hstPct) {
        List<RateSet> s = new ArrayList<>(sets);
        s.removeIf(r -> r.getEffective() == null);
        s.sort(Comparator.comparing(RateSet::getEffective));
        if (s.isEmpty()) throw new IllegalStateException("cleanhour.pricing.rate-sets is empty");
        this.sets = List.copyOf(s);
        this.hstPct = hstPct;
    }

    public double hstPct() { return hstPct; }

    public List<RateSet> rateSets() { return sets; }

    /** Rate set in effect on a local date (the earliest set is used for dates before it). */
    public RateSet ratesOn(LocalDate d) {
        RateSet out = sets.get(0);
        for (RateSet r : sets) if (!r.getEffective().isAfter(d)) out = r;
        return out;
    }

    public Price price(RatePlan plan, Instant t) {
        return price(plan, t.atZone(LOCAL).toLocalDateTime(), false);
    }

    public Price price(RatePlan plan, Instant t, boolean overTierThreshold) {
        return price(plan, t.atZone(LOCAL).toLocalDateTime(), overTierThreshold);
    }

    public Price price(RatePlan plan, LocalDateTime local, boolean overTierThreshold) {
        RateSet r = ratesOn(local.toLocalDate());
        int h = local.getHour();
        boolean offDay = OntarioHolidays.isOffPeakDay(local.toLocalDate());
        return switch (plan) {
            case ULO -> {
                if (h >= 23 || h < 7) yield new Price(plan, "ultra-low", "Ultra-low overnight", r.getUloOvernight());
                if (offDay) yield new Price(plan, "weekend-off-peak", "Weekend off-peak", r.getUloWeekendOff());
                if (h >= 16 && h < 21) yield new Price(plan, "on-peak", "On-peak", r.getUloOn());
                yield new Price(plan, "mid-peak", "Mid-peak", r.getUloMid());
            }
            case TOU -> {
                if (offDay || h >= 19 || h < 7) yield new Price(plan, "off-peak", "Off-peak", r.getTouOff());
                boolean shoulder = h < 11 || h >= 17; // 7-11 am and 5-7 pm
                boolean winter = isWinter(local.toLocalDate());
                boolean on = winter == shoulder;
                yield on ? new Price(plan, "on-peak", "On-peak", r.getTouOn())
                         : new Price(plan, "mid-peak", "Mid-peak", r.getTouMid());
            }
            case TIERED -> overTierThreshold
                    ? new Price(plan, "tier-2", "Tier 2", r.getTier2())
                    : new Price(plan, "tier-1", "Tier 1", r.getTier1());
        };
    }

    /** Nov 1 to Apr 30. */
    public static boolean isWinter(LocalDate d) {
        int m = d.getMonthValue();
        return m >= 11 || m <= 4;
    }

    /** Next time the plan's price changes after {@code t}, within two days, or null. */
    public Instant nextChange(RatePlan plan, Instant t) {
        Price now = price(plan, t);
        Instant h = t.atZone(LOCAL).withMinute(0).withSecond(0).withNano(0).toInstant();
        for (int i = 1; i <= 48; i++) {
            Instant c = h.plus(Duration.ofHours(i));
            Price p = price(plan, c);
            if (!p.period().equals(now.period()) || p.cents() != now.cents()) return c;
        }
        return null;
    }

    /** Plain-language description of a plan's periods for the current season. */
    public List<String> describe(RatePlan plan, LocalDate d) {
        RateSet r = ratesOn(d);
        boolean winter = isWinter(d);
        return switch (plan) {
            case ULO -> List.of(
                    fmt("Ultra-low overnight %s¢: 11 pm to 7 am, every day", r.getUloOvernight()),
                    fmt("Weekend off-peak %s¢: 7 am to 11 pm on weekends and holidays", r.getUloWeekendOff()),
                    fmt("Mid-peak %s¢: weekdays 7 am to 4 pm and 9 pm to 11 pm", r.getUloMid()),
                    fmt("On-peak %s¢: weekdays 4 pm to 9 pm", r.getUloOn()));
            case TOU -> winter ? List.of(
                    fmt("Off-peak %s¢: weekdays 7 pm to 7 am, all day on weekends and holidays", r.getTouOff()),
                    fmt("Mid-peak %s¢: weekdays 11 am to 5 pm", r.getTouMid()),
                    fmt("On-peak %s¢: weekdays 7 am to 11 am and 5 pm to 7 pm (winter)", r.getTouOn()))
                    : List.of(
                    fmt("Off-peak %s¢: weekdays 7 pm to 7 am, all day on weekends and holidays", r.getTouOff()),
                    fmt("Mid-peak %s¢: weekdays 7 am to 11 am and 5 pm to 7 pm", r.getTouMid()),
                    fmt("On-peak %s¢: weekdays 11 am to 5 pm (summer)", r.getTouOn()));
            case TIERED -> List.of(
                    fmt("Tier 1 %s¢: first " + (winter ? r.getTierThresholdWinter() : r.getTierThresholdSummer()) + " kWh each month", r.getTier1()),
                    fmt("Tier 2 %s¢: everything above that", r.getTier2()));
        };
    }

    private static String fmt(String pattern, double cents) {
        String c = cents == Math.floor(cents) ? String.format(Locale.ROOT, "%.0f", cents) : String.format(Locale.ROOT, "%.1f", cents);
        return String.format(Locale.ROOT, pattern, c);
    }
}
