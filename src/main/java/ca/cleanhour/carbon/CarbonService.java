package ca.cleanhour.carbon;

import ca.cleanhour.CleanHourProperties;
import ca.cleanhour.carbon.Dashboard.*;
import ca.cleanhour.ieso.Fuel;
import ca.cleanhour.store.HourRow;
import ca.cleanhour.store.HourlyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

import static ca.cleanhour.store.HourlyStore.EST;

/**
 * Builds the dashboard snapshot from the store. The page only ever reads this cached snapshot,
 * so visitors never hit IESO directly.
 */
@Service
public class CarbonService {

    private static final Logger log = LoggerFactory.getLogger(CarbonService.class);

    public static final ZoneId LOCAL = ZoneId.of("America/Toronto");
    static final int EV_WINDOW_HOURS = 3;
    static final double EV_CHARGE_KWH = 40;

    private final HourlyStore store;
    private final CarbonCalculator calc;
    private final int historyDays;

    private volatile Dashboard snapshot;
    private volatile List<HourPoint> forecast = List.of();

    public CarbonService(HourlyStore store, CarbonCalculator calc, CleanHourProperties props) {
        this.store = store;
        this.calc = calc;
        this.historyDays = Math.max(1, props.getIeso().getHistoryDays());
    }

    public Optional<Dashboard> dashboard() {
        return Optional.ofNullable(snapshot);
    }

    public synchronized void rebuild(Map<Instant, EnumMap<Fuel, Double>> latestSchedule) {
        try {
            Dashboard built = build(Instant.now(), latestSchedule);
            if (built != null) snapshot = built;
        } catch (RuntimeException e) {
            log.error("Could not build dashboard", e);
        }
    }

    // ---------------------------------------------------------------- building the snapshot

    /** A complete actual hour. */
    record Hour(Instant start, Map<Fuel, Double> mix, double intensity, Double demand) {
        double gas() { return mix.getOrDefault(Fuel.GAS, 0.0); }
        int hourEnding() { return start.atOffset(EST).getHour() + 1; }
    }

    private List<Hour> actualHours(List<HourRow> rows) {
        List<Hour> out = new ArrayList<>();
        for (HourRow r : rows) {
            if (calc.complete(r.actual)) out.add(new Hour(r.start, r.actual, calc.intensity(r.actual), r.demand));
        }
        return out;
    }

    Dashboard build(Instant now, Map<Instant, EnumMap<Fuel, Double>> latestSchedule) {
        Instant histFrom = now.minus(Duration.ofDays(historyDays + 1));
        List<HourRow> rows = store.range(histFrom, now.plus(Duration.ofDays(2)));
        List<Hour> actuals = actualHours(rows);
        if (actuals.isEmpty()) return null;

        Hour cur = actuals.get(actuals.size() - 1);
        Hour prev = actuals.size() > 1 ? actuals.get(actuals.size() - 2) : null;
        List<Hour> last24 = actuals.subList(Math.max(0, actuals.size() - 24), actuals.size());

        DoubleSummaryStatistics s24 = last24.stream().mapToDouble(Hour::intensity).summaryStatistics();
        Current current = new Current(cur.start(), round(cur.intensity()), CarbonCalculator.band(cur.intensity()),
                round(totalMw(cur.mix())), shares(cur.mix()),
                prev == null ? null : round(cur.intensity() - prev.intensity()),
                round(s24.getAverage()), round(s24.getMin()), round(s24.getMax()));

        List<HourPoint> fc = forecast(actuals, cur, latestSchedule);
        this.forecast = fc;
        GasPace gas = gasPace(actuals, cur, prev, fc);

        Map<String, Double> factors = new LinkedHashMap<>();
        calc.factors().forEach((f, v) -> factors.put(f.name(), v));

        return new Dashboard(now, current, gas,
                last24.stream().map(h -> point(h, "actual")).toList(),
                heatmap(actuals), fc, evWindow(fc, cur), charts(now, rows, actuals, cur), factors, notes());
    }

    /** The latest forward forecast (next 24 hours), shared with the calculator API. */
    public List<HourPoint> currentForecast() { return forecast; }

    private HourPoint point(Hour h, String source) {
        return new HourPoint(h.start(), round(h.intensity()), CarbonCalculator.band(h.intensity()),
                round(totalMw(h.mix())), round(h.gas()), source);
    }

    static double totalMw(Map<Fuel, Double> mix) {
        return mix.values().stream().mapToDouble(Double::doubleValue).sum();
    }

    List<FuelShare> shares(Map<Fuel, Double> mix) {
        double total = totalMw(mix);
        return mix.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .sorted(Map.Entry.<Fuel, Double>comparingByValue().reversed())
                .map(e -> new FuelShare(e.getKey().name(), round(e.getValue()),
                        total > 0 ? round4(e.getValue() / total) : 0, calc.factors().get(e.getKey())))
                .toList();
    }

    private List<HeatmapDay> heatmap(List<Hour> actuals) {
        TreeMap<LocalDate, Double[]> days = new TreeMap<>();
        for (Hour h : actuals) {
            ZonedDateTime local = h.start().atZone(LOCAL);
            days.computeIfAbsent(local.toLocalDate(), d -> new Double[24])[local.getHour()] = round(h.intensity());
        }
        while (days.size() > historyDays) days.pollFirstEntry();
        return days.entrySet().stream()
                .map(e -> new HeatmapDay(e.getKey(), Arrays.asList(e.getValue())))
                .toList();
    }

    /**
     * Next 24 hours. Uses IESO's latest fuel schedules (Adequacy3) where published, shifted by today's
     * average gap between actual and scheduled intensity. Falls back to the average of the same hour
     * over the recent history.
     */
    private List<HourPoint> forecast(List<Hour> actuals, Hour cur, Map<Instant, EnumMap<Fuel, Double>> latestSchedule) {
        Map<Instant, Double> schedIntensity = new HashMap<>();
        latestSchedule.forEach((t, mix) -> { if (calc.complete(mix)) schedIntensity.put(t, calc.intensity(mix)); });

        List<Hour> overlap = actuals.stream().filter(a -> schedIntensity.containsKey(a.start())).toList();
        double bias = overlap.subList(Math.max(0, overlap.size() - 12), overlap.size()).stream()
                .mapToDouble(a -> a.intensity() - schedIntensity.get(a.start()))
                .average().orElse(0);

        Map<Integer, DoubleSummaryStatistics> byHour = new HashMap<>();
        Map<Integer, DoubleSummaryStatistics> gasByHour = new HashMap<>();
        for (Hour a : actuals) {
            byHour.computeIfAbsent(a.hourEnding(), k -> new DoubleSummaryStatistics()).accept(a.intensity());
            gasByHour.computeIfAbsent(a.hourEnding(), k -> new DoubleSummaryStatistics()).accept(a.gas());
        }

        List<HourPoint> out = new ArrayList<>();
        for (int k = 1; k <= 24; k++) {
            Instant start = cur.start().plus(Duration.ofHours(k));
            EnumMap<Fuel, Double> sched = latestSchedule.get(start);
            if (sched != null && calc.complete(sched)) {
                double v = Math.max(0, calc.intensity(sched) + bias);
                out.add(new HourPoint(start, round(v), CarbonCalculator.band(v), round(totalMw(sched)),
                        round(sched.getOrDefault(Fuel.GAS, 0.0)), "ieso-schedule"));
            } else {
                int he = start.atOffset(EST).getHour() + 1;
                DoubleSummaryStatistics st = byHour.get(he);
                if (st == null) continue;
                double v = st.getAverage();
                out.add(new HourPoint(start, round(v), CarbonCalculator.band(v), 0,
                        round(gasByHour.get(he).getAverage()), "recent-pattern"));
            }
        }
        return out;
    }

    private GasPace gasPace(List<Hour> actuals, Hour cur, Hour prev, List<HourPoint> forecast) {
        Instant weekAgo = cur.start().minus(Duration.ofDays(7));
        double[] gas = actuals.stream().filter(h -> h.start().isAfter(weekAgo)).mapToDouble(Hour::gas).sorted().toArray();
        double base = percentile(gas, 0.10);
        double peak = percentile(gas, 0.90);
        double g = cur.gas();
        double ratio = clamp((g - base) / Math.max(1, peak - base));
        Double change = prev == null ? null : g - prev.gas();
        double total = calc.countedMw(cur.mix());
        double share = total > 0 ? g / total : 0;
        boolean rising = change != null && change >= 150;
        double nextGas = forecast.isEmpty() ? g : forecast.get(0).gasMw();

        String level, headline, detail;
        double flexible = Math.max(0, g - base);
        String gasNow = String.format(Locale.ROOT, "Gas plants are producing %,.0f MW (%.0f%% of supply)", g, share * 100);
        if (ratio >= 0.5 || (ratio >= 0.3 && rising)) {
            level = "setting";
            headline = "Gas is setting the pace right now";
            detail = String.format(Locale.ROOT,
                    "%s, about %,.0f MW above this week's low. Extra demand right now is most likely met by gas at ~%.0f g/kWh.",
                    gasNow, flexible, calc.factors().getOrDefault(Fuel.GAS, 490.0));
        } else if (ratio >= 0.2 || rising) {
            level = "supporting";
            headline = "Gas is in the mix, but not leading";
            detail = String.format(Locale.ROOT,
                    "%s, %,.0f MW above this week's low. Gas is filling some of the gap.", gasNow, flexible);
        } else {
            level = "clean";
            headline = "Clean power is setting the pace";
            detail = String.format(Locale.ROOT,
                    "%s, close to this week's minimum. Nuclear, hydro and wind are covering most of the swing.", gasNow);
        }
        if (nextGas - g >= 300) detail += " Gas is scheduled to ramp up next hour.";
        else if (g - nextGas >= 300) detail += " Gas is scheduled to ease off next hour.";

        return new GasPace(level, headline, detail, round(g), round4(share), round(base), round(peak),
                round4(ratio), change == null ? null : round(change));
    }

    /** Lowest-average window of {@code hours} consecutive forecast hours. Returns the start index or -1. */
    static int bestWindow(List<HourPoint> fc, int hours, boolean lowest) {
        int best = -1;
        double bestAvg = lowest ? Double.MAX_VALUE : -1;
        for (int i = 0; i + hours <= fc.size(); i++) {
            if (Duration.between(fc.get(i).start(), fc.get(i + hours - 1).start()).toHours() != hours - 1) continue;
            double avg = 0;
            for (int j = i; j < i + hours; j++) avg += fc.get(j).intensity();
            avg /= hours;
            if (lowest ? avg < bestAvg : avg > bestAvg) { bestAvg = avg; best = i; }
        }
        return best;
    }

    private static double avg(List<HourPoint> fc, int from, int hours) {
        double s = 0;
        for (int j = from; j < from + hours; j++) s += fc.get(j).intensity();
        return s / hours;
    }

    private EvWindow evWindow(List<HourPoint> fc, Hour cur) {
        int best = bestWindow(fc, EV_WINDOW_HOURS, true);
        if (best < 0) return null;
        int worst = bestWindow(fc, EV_WINDOW_HOURS, false);
        double bestAvg = avg(fc, best, EV_WINDOW_HOURS);
        Double worstAvg = worst < 0 ? null : avg(fc, worst, EV_WINDOW_HOURS);
        boolean allScheduled = fc.subList(best, best + EV_WINDOW_HOURS).stream()
                .allMatch(p -> p.source().equals("ieso-schedule"));
        String basis = allScheduled
                ? "IESO fuel schedules, adjusted by today's gap between scheduled and actual output"
                : "Average of the same hours over the last " + historyDays + " days";
        double now = cur.intensity();
        return new EvWindow(fc.get(best).start(), fc.get(best).start().plus(Duration.ofHours(EV_WINDOW_HOURS)),
                round(bestAvg), CarbonCalculator.band(bestAvg), round(now),
                worstAvg == null ? null : round(worstAvg), worst < 0 ? null : fc.get(worst).start(),
                now > 0 ? round((now - bestAvg) / now * 100) : 0,
                round(bestAvg * EV_CHARGE_KWH / 1000), round(now * EV_CHARGE_KWH / 1000), basis);
    }

    // ---------------------------------------------------------------- extra charts

    private Charts charts(Instant now, List<HourRow> rows, List<Hour> actuals, Hour cur) {
        Instant weekFrom = cur.start().minus(Duration.ofDays(7)).plus(Duration.ofHours(1));
        Instant dayFrom = cur.start().minus(Duration.ofHours(23));

        // Forecast vs actual: last 7 days plus whatever day-ahead forecast exists ahead.
        List<LinePoint> line = new ArrayList<>();
        double absErr = 0, err = 0, pctErr = 0;
        int n = 0;
        for (HourRow r : rows) {
            if (r.start.isBefore(weekFrom)) continue;
            Double a = calc.complete(r.actual) ? round(calc.intensity(r.actual)) : null;
            Double d = calc.complete(r.dayAhead) ? round(calc.intensity(r.dayAhead)) : null;
            if (a == null && d == null) continue;
            if (a == null && !r.start.isAfter(cur.start())) continue; // gaps in the past
            line.add(new LinePoint(r.start, a, d));
            if (a != null && d != null) {
                absErr += Math.abs(a - d); err += d - a; pctErr += Math.abs(a - d) / a; n++;
            }
        }
        Accuracy acc = n == 0 ? null : new Accuracy(n, round(absErr / n), round(err / n), round(pctErr / n * 100));

        List<MixPoint> mix7d = new ArrayList<>();
        List<MixPoint> mix24 = new ArrayList<>();
        List<DemandGas> dg = new ArrayList<>();
        for (Hour h : actuals) {
            if (h.start().isBefore(weekFrom)) continue;
            Map<String, Double> m = new LinkedHashMap<>();
            for (Fuel f : Fuel.values()) m.put(f.name(), round(h.mix().getOrDefault(f, 0.0)));
            MixPoint p = new MixPoint(h.start(), m);
            mix7d.add(p);
            if (!h.start().isBefore(dayFrom)) mix24.add(p);
            dg.add(new DemandGas(h.start(), h.demand(), round(h.gas())));
        }

        // Today (Toronto calendar day) by hour, and today's fuel shares.
        LocalDate today = now.atZone(LOCAL).toLocalDate();
        List<HourTotal> todayHourly = new ArrayList<>();
        EnumMap<Fuel, Double> todayMix = new EnumMap<>(Fuel.class);
        for (Hour h : actuals) {
            if (!h.start().atZone(LOCAL).toLocalDate().equals(today)) continue;
            double t = calc.emissionsTonnes(h.mix());
            double mwh = calc.countedMw(h.mix());
            todayHourly.add(new HourTotal(h.start(), round(t), round(mwh), round(h.intensity())));
            h.mix().forEach((f, v) -> todayMix.merge(f, v, Double::sum));
        }
        // Early in the day there is little data; fall back to the last 24 hours for the donut.
        if (todayHourly.size() < 3) {
            todayMix.clear();
            actuals.stream().filter(h -> !h.start().isBefore(dayFrom))
                    .forEach(h -> h.mix().forEach((f, v) -> todayMix.merge(f, v, Double::sum)));
        }

        List<DayTotal> allDays = dailyTotals(store.range(Instant.EPOCH, now.plus(Duration.ofDays(1))));
        List<DayTotal> week = allDays.subList(Math.max(0, allDays.size() - 7), allDays.size());

        return new Charts(line, acc, mix24, mix7d, shares(todayMix), todayHourly, week, allDays, dg);
    }

    /** Totals per Toronto calendar day over complete actual hours. */
    public List<DayTotal> dailyTotals(List<HourRow> rows) {
        TreeMap<LocalDate, double[]> days = new TreeMap<>();
        for (HourRow r : rows) {
            if (!calc.complete(r.actual)) continue;
            double[] d = days.computeIfAbsent(r.start.atZone(LOCAL).toLocalDate(), k -> new double[3]);
            d[0]++;
            d[1] += calc.emissionsTonnes(r.actual);
            d[2] += calc.countedMw(r.actual);
        }
        List<DayTotal> out = new ArrayList<>();
        days.forEach((date, d) -> out.add(new DayTotal(date, (int) d[0], round(d[1]), round(d[2]),
                d[2] > 0 ? round(d[1] * 1000 / d[2]) : 0)));
        return out;
    }

    private List<String> notes() {
        return List.of(
                "Generation by fuel comes from the IESO Generator Output and Capability report (hourly, Ontario transmission-connected units).",
                "Intensity = sum(output x emission factor) / total output, using IPCC AR5 lifecycle median factors.",
                "Imports, exports, storage and embedded (distribution-connected) generation are not included.",
                "The day-ahead forecast is the last IESO Adequacy report published before each day began, saved by this app.",
                "Forward hours use IESO's latest Adequacy report fuel schedules, corrected by today's forecast error; otherwise the recent average for that hour.",
                "IESO reports hours in Eastern Standard Time; times on this page are shown in Toronto local time.");
    }

    // ---------------------------------------------------------------- helpers

    static double percentile(double[] sorted, double p) {
        if (sorted.length == 0) return 0;
        int idx = (int) Math.round(p * (sorted.length - 1));
        return sorted[Math.min(sorted.length - 1, Math.max(0, idx))];
    }

    private static double clamp(double v) { return Math.max(0, Math.min(1, v)); }
    static double round(double v) { return Math.round(v * 10) / 10.0; }
    static double round4(double v) { return Math.round(v * 10000) / 10000.0; }
}
