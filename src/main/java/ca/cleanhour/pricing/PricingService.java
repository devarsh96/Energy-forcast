package ca.cleanhour.pricing;

import ca.cleanhour.carbon.CarbonService;
import ca.cleanhour.carbon.Dashboard;
import ca.cleanhour.pricing.PricingSnapshot.*;
import ca.cleanhour.store.HourRow;
import ca.cleanhour.store.HourlyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static ca.cleanhour.pricing.Tariff.LOCAL;

/**
 * Combines household rate plans (OEB RPP), the wholesale Ontario Zonal Price and the carbon forecast.
 * Like the dashboard, the page reads a cached snapshot; the charge planner runs on request (it is cheap).
 */
@Service
public class PricingService {

    private static final Logger log = LoggerFactory.getLogger(PricingService.class);

    private final HourlyStore store;
    private final Tariff tariff;
    private final CarbonService carbon;
    private volatile PricingSnapshot snapshot;

    public PricingService(HourlyStore store, Tariff tariff, CarbonService carbon) {
        this.store = store;
        this.tariff = tariff;
        this.carbon = carbon;
    }

    public Optional<PricingSnapshot> snapshot() { return Optional.ofNullable(snapshot); }

    public Tariff tariff() { return tariff; }

    public synchronized void rebuild() {
        try {
            snapshot = build(Instant.now());
        } catch (RuntimeException e) {
            log.error("Could not build pricing", e);
        }
    }

    PricingSnapshot build(Instant now) {
        Instant hour = now.truncatedTo(ChronoUnit.HOURS);
        LocalDate today = now.atZone(LOCAL).toLocalDate();
        RateSet rates = tariff.ratesOn(today);

        Map<RatePlan, List<String>> periods = new EnumMap<>(RatePlan.class);
        Map<RatePlan, PlanNow> planNow = new EnumMap<>(RatePlan.class);
        for (RatePlan p : RatePlan.values()) {
            periods.put(p, tariff.describe(p, today));
            Tariff.Price cur = tariff.price(p, now);
            Instant next = tariff.nextChange(p, now);
            Tariff.Price np = next == null ? null : tariff.price(p, next);
            planNow.put(p, new PlanNow(cur.period(), cur.label(), cur.cents(), next,
                    np == null ? null : np.label(), np == null ? null : np.cents()));
        }

        List<HourRow> rows = store.range(hour.minus(Duration.ofDays(8)), hour.plus(Duration.ofHours(25)));
        Map<Instant, HourRow> byStart = new HashMap<>();
        rows.forEach(r -> byStart.put(r.start, r));

        List<PriceHour> next24 = next24(hour, byStart);
        GridNow grid = gridNow(hour, byStart, rows);

        List<HourRow> history = store.range(Instant.EPOCH, hour.plus(Duration.ofHours(1)));
        return new PricingSnapshot(now, rates, periods, tariff.hstPct(), planNow, grid, next24,
                heatmap(rows, hour), daily(history), evWindowCost(), notes());
    }

    // ---------------------------------------------------------------- next 24 hours

    private List<PriceHour> next24(Instant hour, Map<Instant, HourRow> byStart) {
        Map<Instant, Double> intensity = new HashMap<>();
        carbon.dashboard().ifPresent(d -> intensity.put(d.current().start(), d.current().intensity()));
        for (Dashboard.HourPoint p : carbon.currentForecast()) intensity.put(p.start(), p.intensity());

        List<PriceHour> out = new ArrayList<>();
        Double lastIntensity = null;
        for (int k = 0; k < 24; k++) {
            Instant t = hour.plus(Duration.ofHours(k));
            Map<RatePlan, PlanPrice> plans = new EnumMap<>(RatePlan.class);
            for (RatePlan p : RatePlan.values()) {
                Tariff.Price pr = tariff.price(p, t);
                plans.put(p, new PlanPrice(pr.period(), pr.cents()));
            }
            HourRow r = byStart.get(t);
            Double grid = null;
            String src = null;
            if (r != null) {
                if (k == 0 && r.priceRealtime != null) { grid = r.priceRealtime; src = "real-time"; }
                else if (r.pricePredispatch != null) { grid = r.pricePredispatch; src = "pre-dispatch"; }
                else if (r.priceDayAhead != null) { grid = r.priceDayAhead; src = "day-ahead"; }
            }
            Double g = intensity.get(t);
            if (g == null) g = lastIntensity; // carry the nearest earlier forecast forward over small gaps
            if (g != null) lastIntensity = g;
            out.add(new PriceHour(t, plans, grid == null ? null : r2(grid / 10), src, g));
        }
        // Fill any leading hours that had no intensity with the first known value.
        Double first = out.stream().map(PriceHour::intensity).filter(Objects::nonNull).findFirst().orElse(null);
        if (first != null) {
            for (int i = 0; i < out.size() && out.get(i).intensity() == null; i++) {
                PriceHour p = out.get(i);
                out.set(i, new PriceHour(p.start(), p.plans(), p.gridCents(), p.gridSource(), first));
            }
        }
        return out;
    }

    private GridNow gridNow(Instant hour, Map<Instant, HourRow> byStart, List<HourRow> rows) {
        // Latest hour at or before now that has a real-time price, else the scheduled price for this hour.
        Instant at = null;
        Double v = null;
        String src = null;
        for (int k = 0; k < 3 && v == null; k++) {
            HourRow r = byStart.get(hour.minus(Duration.ofHours(k)));
            if (r != null && r.priceRealtime != null) { v = r.priceRealtime; at = r.start; src = "real-time"; }
        }
        if (v == null) {
            HourRow r = byStart.get(hour);
            if (r != null && (r.pricePredispatch != null || r.priceDayAhead != null)) {
                v = r.pricePredispatch != null ? r.pricePredispatch : r.priceDayAhead;
                at = hour;
                src = r.pricePredispatch != null ? "pre-dispatch" : "day-ahead";
            }
        }
        Instant weekAgo = hour.minus(Duration.ofDays(7));
        double[] week = rows.stream().filter(r -> !r.start.isBefore(weekAgo) && !r.start.isAfter(hour))
                .map(HourRow::gridPrice).filter(Objects::nonNull).mapToDouble(Double::doubleValue).sorted().toArray();
        Double avg = week.length == 0 ? null : r2(Arrays.stream(week).average().orElse(0) / 10);
        Double lo = week.length == 0 ? null : r2(percentile(week, 0.25) / 10);
        Double hi = week.length == 0 ? null : r2(percentile(week, 0.75) / 10);
        String level = null;
        if (v != null && lo != null) level = v / 10 <= lo ? "low" : v / 10 >= hi ? "high" : "typical";
        String gas = carbon.dashboard().map(d -> d.gasPace().level()).orElse(null);
        return new GridNow(at, v == null ? null : r2(v / 10), v == null ? null : r2(v), src, avg, lo, hi, level, gas);
    }

    // ---------------------------------------------------------------- history

    private List<HeatDay> heatmap(List<HourRow> rows, Instant hour) {
        // Sum and count per local hour, so the repeated 1 am on the November clock change is averaged.
        TreeMap<LocalDate, double[][]> days = new TreeMap<>();
        for (HourRow r : rows) {
            Double p = r.gridPrice();
            if (p == null || r.start.isAfter(hour)) continue;
            ZonedDateTime local = r.start.atZone(LOCAL);
            double[][] d = days.computeIfAbsent(local.toLocalDate(), k -> new double[2][24]);
            d[0][local.getHour()] += p / 10;
            d[1][local.getHour()]++;
        }
        while (days.size() > 7) days.pollFirstEntry();
        return days.entrySet().stream().map(e -> {
            List<Double> v = new ArrayList<>(24);
            for (int h = 0; h < 24; h++) v.add(e.getValue()[1][h] == 0 ? null : r2(e.getValue()[0][h] / e.getValue()[1][h]));
            return new HeatDay(e.getKey(), v);
        }).toList();
    }

    private List<DayPrice> daily(List<HourRow> rows) {
        TreeMap<LocalDate, DoubleSummaryStatistics> days = new TreeMap<>();
        for (HourRow r : rows) {
            Double p = r.gridPrice();
            if (p == null) continue;
            days.computeIfAbsent(r.start.atZone(LOCAL).toLocalDate(), d -> new DoubleSummaryStatistics()).accept(p / 10);
        }
        List<DayPrice> out = new ArrayList<>();
        days.forEach((d, s) -> out.add(new DayPrice(d, (int) s.getCount(), r2(s.getAverage()), r2(s.getMin()), r2(s.getMax()))));
        return out;
    }

    // ---------------------------------------------------------------- EV costs

    /** Cost of the dashboard's cleanest 3-hour window on each plan. */
    private Map<RatePlan, EvCost> evWindowCost() {
        Map<RatePlan, EvCost> out = new EnumMap<>(RatePlan.class);
        carbon.dashboard().map(Dashboard::evWindow).ifPresent(ev -> {
            long hours = Math.max(1, Duration.between(ev.start(), ev.end()).toHours());
            double kwh = ev.chargeKwh();
            for (RatePlan p : RatePlan.values()) {
                double cost = 0;
                for (int k = 0; k < hours; k++) {
                    cost += kwh / hours * tariff.price(p, ev.start().plus(Duration.ofHours(k))).cents() / 100;
                }
                double peak = kwh * tariff.ratesOn(ev.start().atZone(LOCAL).toLocalDate()).peak(p) / 100;
                out.put(p, new EvCost(ev.start(), ev.end(), kwh, r2(cost), r2(peak), r2(peak - cost)));
            }
        });
        return out;
    }

    /** Slots for the planner: the next 24 hours priced on one plan, with forecast carbon intensity. */
    List<ChargePlanner.Slot> slots(RatePlan plan, boolean overTier, Instant now) {
        PricingSnapshot s = snapshot;
        List<PriceHour> hours = s != null ? s.next24() : List.of();
        Instant hour = now.truncatedTo(ChronoUnit.HOURS);
        List<ChargePlanner.Slot> out = new ArrayList<>();
        for (PriceHour h : hours) {
            if (h.start().isBefore(hour)) continue;
            double cents = tariff.price(plan, h.start(), overTier).cents();
            // No carbon forecast for this hour: leave it null rather than inventing one.
            out.add(new ChargePlanner.Slot(h.start(), cents, h.intensity()));
        }
        return out;
    }

    public ChargePlan plan(RatePlan plan, double kwh, double kw, Instant readyBy, boolean overTier) {
        Instant now = Instant.now();
        ChargePlanner.Result result = ChargePlanner.plan(slots(plan, overTier, now), kwh, kw, readyBy);
        Map<RatePlan, ChargePlanner.Window> byPlan = new EnumMap<>(RatePlan.class);
        for (RatePlan p : RatePlan.values()) {
            ChargePlanner.Result r = p == plan ? result : ChargePlanner.plan(slots(p, overTier, now), kwh, kw, readyBy);
            ChargePlanner.Window w = r.cheapest() != null ? r.cheapest() : r.now();
            if (w != null) byPlan.put(p, w);
        }
        double peakCents = overTier || plan != RatePlan.TIERED
                ? tariff.ratesOn(now.atZone(LOCAL).toLocalDate()).peak(plan)
                : tariff.ratesOn(now.atZone(LOCAL).toLocalDate()).getTier1();
        return new ChargePlan(plan, overTier, result, peakCents, r2(kwh * peakCents / 100), byPlan);
    }

    private List<String> notes() {
        return List.of(
                "Household prices are the Ontario Energy Board's Regulated Price Plan commodity prices. Your bill also has delivery and regulatory charges and HST, minus the Ontario Electricity Rebate.",
                "Time-of-Use and Ultra-Low Overnight periods follow the local clock. Weekends and the OEB's holidays are off-peak all day.",
                "On the Tiered plan an EV charge is priced at tier 1 unless you say your home is already over the monthly threshold.",
                "The grid price is IESO's Ontario Zonal Price: the wholesale price set every 5 minutes. Households on the Regulated Price Plan don't pay it directly, but it shows when the grid is under strain, which usually lines up with gas running.",
                "Future grid prices come from IESO's pre-dispatch run for today and the day-ahead market for tomorrow. Past hours use the real-time price where available, otherwise day-ahead.");
    }

    static double percentile(double[] sorted, double p) {
        if (sorted.length == 0) return 0;
        int idx = (int) Math.round(p * (sorted.length - 1));
        return sorted[Math.min(sorted.length - 1, Math.max(0, idx))];
    }

    static double r2(double v) { return Math.round(v * 100) / 100.0; }
}
