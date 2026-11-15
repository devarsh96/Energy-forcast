package ca.cleanhour.carbon;

import ca.cleanhour.carbon.Dashboard.DayTotal;
import ca.cleanhour.carbon.Dashboard.FuelShare;
import ca.cleanhour.ieso.Fuel;
import ca.cleanhour.pricing.RatePlan;
import ca.cleanhour.pricing.RateSet;
import ca.cleanhour.pricing.Tariff;
import ca.cleanhour.store.HourRow;
import ca.cleanhour.store.HourlyStore;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.TextStyle;
import java.util.*;

import static ca.cleanhour.carbon.CarbonService.LOCAL;
import static ca.cleanhour.carbon.CarbonService.round;

/** Queries over the stored history: raw hourly rows, daily totals, data sources and monthly reports. */
@Service
public class ReportService {

    private final HourlyStore store;
    private final CarbonCalculator calc;
    private final CarbonService carbon;
    private final IngestService ingest;
    private final Tariff tariff;

    public ReportService(HourlyStore store, CarbonCalculator calc, CarbonService carbon, IngestService ingest, Tariff tariff) {
        this.store = store;
        this.calc = calc;
        this.carbon = carbon;
        this.ingest = ingest;
        this.tariff = tariff;
    }

    // ---------------------------------------------------------------- raw hourly data

    /**
     * One row of the raw data table. Times are Toronto local; {@code zone} (EST or EDT) tells apart the two
     * 1 am hours on the November clock change.
     */
    public record HourlyRecord(Instant start, String localTime, String zone, LocalDate date, int hour,
                               Map<String, Double> fuelMw, Double totalMw, Double demandMw,
                               Double intensity, Double dayAheadIntensity, Double tonnesCo2,
                               Double gridPrice) {}

    public List<HourlyRecord> hourly(LocalDate from, LocalDate to, Set<Fuel> fuels) {
        Instant a = from.atStartOfDay(LOCAL).toInstant();
        Instant b = to.plusDays(1).atStartOfDay(LOCAL).toInstant();
        List<HourlyRecord> out = new ArrayList<>();
        for (HourRow r : store.range(a, b)) {
            if (r.actual == null) continue;
            ZonedDateTime local = r.start.atZone(LOCAL);
            Map<String, Double> mw = new LinkedHashMap<>();
            for (Fuel f : Fuel.values()) {
                if (fuels.contains(f)) mw.put(f.name(), round(r.actual.getOrDefault(f, 0.0)));
            }
            boolean ok = calc.complete(r.actual);
            String zone = local.getOffset().getTotalSeconds() == -4 * 3600 ? "EDT" : "EST";
            out.add(new HourlyRecord(r.start, local.toLocalDateTime().toString(), zone, local.toLocalDate(), local.getHour(), mw,
                    round(CarbonService.totalMw(r.actual)), r.demand,
                    ok ? round(calc.intensity(r.actual)) : null,
                    calc.complete(r.dayAhead) ? round(calc.intensity(r.dayAhead)) : null,
                    ok ? round(calc.emissionsTonnes(r.actual)) : null,
                    r.gridPrice() == null ? null : Math.round(r.gridPrice() * 100) / 100.0));
        }
        return out;
    }

    public List<DayTotal> daily(LocalDate from, LocalDate to) {
        Instant a = from.atStartOfDay(LOCAL).toInstant();
        Instant b = to.plusDays(1).atStartOfDay(LOCAL).toInstant();
        return carbon.dailyTotals(store.range(a, b));
    }

    /** First and last Toronto dates with actual data. */
    public record Coverage(LocalDate first, LocalDate last, boolean backfillRunning) {}

    public Coverage coverage() {
        LocalDate first = store.firstActual().map(t -> t.atZone(LOCAL).toLocalDate()).orElse(null);
        LocalDate last = carbon.dashboard().map(d -> d.current().start().atZone(LOCAL).toLocalDate()).orElse(null);
        return new Coverage(first, last, ingest.backfillRunning());
    }

    // ---------------------------------------------------------------- sources

    public record Source(String id, String title, String url, String usedFor, String refresh, Instant lastUpdated) {}

    public List<Source> sources(String baseUrl) {
        Map<String, Instant> u = store.sourceUpdated();
        return List.of(
                new Source(IngestService.SRC_OUTPUT, "Generator Output and Capability",
                        baseUrl + "/GenOutputCapability/",
                        "Actual hourly output of every transmission-connected generator, tagged by fuel. Drives the live gauge, the last 7 days and the gas indicator.",
                        "Checked every 5 minutes (IESO publishes hourly)", u.get(IngestService.SRC_OUTPUT)),
                new Source(IngestService.SRC_YEARLY, "Generator Output by Fuel Type, Hourly (yearly file)",
                        baseUrl + "/GenOutputbyFuelHourly/",
                        "Hourly output by fuel for the whole year. Used to backfill the 3-month history.",
                        "At start-up and every 12 hours if history has gaps", u.get(IngestService.SRC_YEARLY)),
                new Source(IngestService.SRC_SCHEDULE, "Adequacy Report (Adequacy3)",
                        baseUrl + "/Adequacy3/",
                        "IESO's scheduled output by fuel for each hour. The last version before each day starts is saved as the day-ahead forecast; the newest version drives the next-24-hour forecast.",
                        "Every 30 minutes; archive backfill every 12 hours", u.get(IngestService.SRC_SCHEDULE)),
                new Source(IngestService.SRC_DEMAND, "Hourly Demand",
                        baseUrl + "/Demand/",
                        "Ontario demand per hour (MW), used for the demand vs. gas chart and the data table.",
                        "Every 30 minutes", u.get(IngestService.SRC_DEMAND)),
                new Source(IngestService.SRC_PRICE_RT, "Real-time Ontario Zonal Price (5-minute)",
                        baseUrl + "/RealtimeOntarioZonalPrice/",
                        "Wholesale price for each 5-minute interval of the current hour, averaged to an hourly grid price for the Dashboard and the data table.",
                        "Every 5 minutes", u.get(IngestService.SRC_PRICE_RT)),
                new Source(IngestService.SRC_PRICE_PD, "Pre-dispatch Hourly Ontario Zonal Price",
                        baseUrl + "/PredispHourlyOntarioZonalPrice/",
                        "IESO's latest forecast of the hourly grid price for the rest of today.",
                        "Every 30 minutes", u.get(IngestService.SRC_PRICE_PD)),
                new Source(IngestService.SRC_PRICE_DA, "Day-ahead Hourly Ontario Zonal Price",
                        baseUrl + "/DAHourlyOntarioZonalPrice/",
                        "Day-ahead market price for each hour: tomorrow's grid price once published (early afternoon), and the 3-month price history.",
                        "Every 30 minutes; history backfill every 12 hours", u.get(IngestService.SRC_PRICE_DA)),
                new Source("OEB-RPP", "OEB Regulated Price Plan prices",
                        "https://www.oeb.ca/consumer-information-and-protection/electricity-rates",
                        "Household Time-of-Use, Ultra-Low Overnight and Tiered prices. Set by the Ontario Energy Board each November; kept in the app's configuration.",
                        "Rates effective " + tariff.ratesOn(LocalDate.now(LOCAL)).getEffective(), null));
    }

    // ---------------------------------------------------------------- monthly report

    public record HourStat(Instant start, double intensity) {}
    public record Block(int startHour, int endHour, double intensity) {}
    public record WeekdayAvg(String day, double intensity) {}

    public record MonthlyReport(
            YearMonth month, boolean inProgress, int hours, int expectedHours,
            double totalTonnes, double totalMwh, double avgIntensity, Double prevMonthAvgIntensity,
            DayTotal cleanestDay, DayTotal dirtiestDay, HourStat cleanestHour, HourStat dirtiestHour,
            int gasLedHours, double gasLedShare, double gasAvgMw, double gasShareOfOutput, String gasRule,
            List<Double> hourOfDay, Block bestBlock, Block worstBlock, List<WeekdayAvg> weekdays,
            List<FuelShare> fuelShares, List<DayTotal> daily,
            Double forecastMeanAbsError, Integer forecastHours,
            PriceSummary prices) {}

    /**
     * Grid price for the month (cents per kWh, real-time where available, otherwise day-ahead) and
     * what a typical EV month would cost on each household plan.
     */
    public record PriceSummary(int hours, double avg, HourPrice cheapest, HourPrice dearest,
                               List<Double> hourOfDay, Block cheapestBlock, Block dearestBlock,
                               double gasLedAvg, double otherAvg, double evKwh,
                               Map<RatePlan, Double> evCostBest, Map<RatePlan, Double> evCostPeak,
                               RateSet rates) {}

    public record HourPrice(Instant start, double cents) {}

    public List<YearMonth> availableMonths() {
        Coverage c = coverage();
        if (c.first() == null || c.last() == null) return List.of();
        List<YearMonth> out = new ArrayList<>();
        for (YearMonth m = YearMonth.from(c.last()); !m.isBefore(YearMonth.from(c.first())); m = m.minusMonths(1)) out.add(m);
        return out;
    }

    public Optional<MonthlyReport> monthly(YearMonth month) {
        Instant a = month.atDay(1).atStartOfDay(LOCAL).toInstant();
        Instant b = month.plusMonths(1).atDay(1).atStartOfDay(LOCAL).toInstant();
        List<HourRow> rows = store.range(a, b).stream().filter(r -> calc.complete(r.actual)).toList();
        if (rows.isEmpty()) return Optional.empty();

        double tonnes = 0, mwh = 0, gasSum = 0;
        double[] hourSum = new double[24];
        int[] hourN = new int[24];
        double[] wdSum = new double[7];
        int[] wdN = new int[7];
        EnumMap<Fuel, Double> mix = new EnumMap<>(Fuel.class);
        HourStat cleanest = null, dirtiest = null;
        double fcAbs = 0;
        int fcN = 0;
        double[] gas = new double[rows.size()], gasShare = new double[rows.size()];
        int i = 0;
        for (HourRow r : rows) {
            double t = calc.emissionsTonnes(r.actual), m = calc.countedMw(r.actual), v = calc.intensity(r.actual);
            tonnes += t; mwh += m;
            double g = r.actual.getOrDefault(Fuel.GAS, 0.0);
            gasShare[i] = m > 0 ? g / m : 0;
            gas[i++] = g; gasSum += g;
            ZonedDateTime local = r.start.atZone(LOCAL);
            hourSum[local.getHour()] += v; hourN[local.getHour()]++;
            wdSum[local.getDayOfWeek().getValue() - 1] += v; wdN[local.getDayOfWeek().getValue() - 1]++;
            r.actual.forEach((f, x) -> mix.merge(f, x, Double::sum));
            if (cleanest == null || v < cleanest.intensity()) cleanest = new HourStat(r.start, round(v));
            if (dirtiest == null || v > dirtiest.intensity()) dirtiest = new HourStat(r.start, round(v));
            if (calc.complete(r.dayAhead)) { fcAbs += Math.abs(calc.intensity(r.dayAhead) - v); fcN++; }
        }

        // Gas-led hours: the same rule as the live "High gas" dot (over 30% of supply and in the top fifth of
        // the gas range), with this month's typical low and high (10th and 90th percentile) as the range.
        double[] sorted = gas.clone();
        Arrays.sort(sorted);
        double lo = CarbonService.percentile(sorted, 0.10), hi = CarbonService.percentile(sorted, 0.90);
        Set<Instant> gasLedHours = new HashSet<>();
        for (int k = 0; k < gas.length; k++) {
            double ratio = Math.max(0, Math.min(1, (gas[k] - lo) / Math.max(1, hi - lo)));
            if ("high".equals(CarbonService.gasLevel(gasShare[k], ratio))) gasLedHours.add(rows.get(k).start);
        }
        int gasLed = gasLedHours.size();

        List<Double> profile = new ArrayList<>();
        for (int h = 0; h < 24; h++) profile.add(hourN[h] == 0 ? null : round(hourSum[h] / hourN[h]));
        Block best = block(profile, true), worst = block(profile, false);

        List<WeekdayAvg> weekdays = new ArrayList<>();
        for (int d = 0; d < 7; d++) {
            if (wdN[d] > 0) weekdays.add(new WeekdayAvg(
                    DayOfWeek.of(d + 1).getDisplayName(TextStyle.FULL, Locale.CANADA), round(wdSum[d] / wdN[d])));
        }

        List<DayTotal> daily = carbon.dailyTotals(rows);
        DayTotal cleanDay = daily.stream().filter(d -> d.hours() >= 20).min(Comparator.comparingDouble(DayTotal::intensity)).orElse(null);
        DayTotal dirtyDay = daily.stream().filter(d -> d.hours() >= 20).max(Comparator.comparingDouble(DayTotal::intensity)).orElse(null);

        Double prevAvg = null;
        YearMonth prev = month.minusMonths(1);
        List<HourRow> prevRows = store.range(prev.atDay(1).atStartOfDay(LOCAL).toInstant(), a);
        double pt = 0, pm = 0;
        for (HourRow r : prevRows) if (calc.complete(r.actual)) { pt += calc.emissionsTonnes(r.actual); pm += calc.countedMw(r.actual); }
        if (pm > 0) prevAvg = round(pt * 1000 / pm);

        YearMonth nowMonth = YearMonth.now(LOCAL);
        int expected = (int) Duration.between(a, b).toHours();
        double counted = calc.countedMw(mix);
        return Optional.of(new MonthlyReport(month, !month.isBefore(nowMonth), rows.size(), expected,
                round(tonnes), round(mwh), round(tonnes * 1000 / mwh), prevAvg,
                cleanDay, dirtyDay, cleanest, dirtiest,
                gasLed, CarbonService.round4((double) gasLed / rows.size()), round(gasSum / rows.size()),
                counted > 0 ? CarbonService.round4(mix.getOrDefault(Fuel.GAS, 0.0) / counted) : 0,
                String.format(Locale.ROOT, "Hours when gas was over 30%% of supply and in the top fifth of this month's range (%,.0f to %,.0f MW), the same rule as the dashboard's \"High gas\" dot", lo, hi),
                profile, best, worst, weekdays, carbon.shares(mix), daily,
                fcN == 0 ? null : round(fcAbs / fcN), fcN == 0 ? null : fcN,
                priceSummary(month, rows, gasLedHours)));
    }

    /** Typical EV month: 3 charges of 40 kWh a week. */
    static final double EV_MONTH_KWH = 40 * 3 * 52 / 12.0;

    private PriceSummary priceSummary(YearMonth month, List<HourRow> rows, Set<Instant> gasLedHours) {
        double sum = 0, gasSum = 0, otherSum = 0;
        int n = 0, gasN = 0, otherN = 0;
        double[] hourSum = new double[24];
        int[] hourN = new int[24];
        HourPrice lo = null, hi = null;
        for (HourRow r : rows) {
            Double p = r.gridPrice();
            if (p == null) continue;
            double c = p / 10;
            sum += c; n++;
            int h = r.start.atZone(LOCAL).getHour();
            hourSum[h] += c; hourN[h]++;
            if (gasLedHours.contains(r.start)) { gasSum += c; gasN++; } else { otherSum += c; otherN++; }
            if (lo == null || c < lo.cents()) lo = new HourPrice(r.start, round2(c));
            if (hi == null || c > hi.cents()) hi = new HourPrice(r.start, round2(c));
        }
        RateSet rates = tariff.ratesOn(month.atEndOfMonth().isAfter(LocalDate.now(LOCAL)) ? LocalDate.now(LOCAL) : month.atEndOfMonth());
        Map<RatePlan, Double> best = new EnumMap<>(RatePlan.class), peak = new EnumMap<>(RatePlan.class);
        for (RatePlan pl : RatePlan.values()) {
            best.put(pl, round2(EV_MONTH_KWH * rates.lowest(pl) / 100));
            peak.put(pl, round2(EV_MONTH_KWH * (pl == RatePlan.TIERED ? rates.getTier1() : rates.peak(pl)) / 100));
        }
        if (n == 0) return new PriceSummary(0, 0, null, null, List.of(), null, null, 0, 0, round(EV_MONTH_KWH), best, peak, rates);
        List<Double> profile = new ArrayList<>();
        for (int h = 0; h < 24; h++) profile.add(hourN[h] == 0 ? null : round2(hourSum[h] / hourN[h]));
        return new PriceSummary(n, round2(sum / n), lo, hi, profile, block(profile, true), block(profile, false),
                gasN == 0 ? 0 : round2(gasSum / gasN), otherN == 0 ? 0 : round2(otherSum / otherN),
                round(EV_MONTH_KWH), best, peak, rates);
    }

    private static double round2(double v) { return Math.round(v * 100) / 100.0; }

    /** Best (or worst) 3-hour block of the average day, wrapping past midnight. */
    private static Block block(List<Double> profile, boolean lowest) {
        Block out = null;
        for (int s = 0; s < 24; s++) {
            double sum = 0;
            int n = 0;
            for (int k = 0; k < 3; k++) {
                Double v = profile.get((s + k) % 24);
                if (v != null) { sum += v; n++; }
            }
            if (n < 3) continue;
            double avg = sum / 3;
            if (out == null || (lowest ? avg < out.intensity() : avg > out.intensity())) {
                out = new Block(s, (s + 3) % 24, round(avg));
            }
        }
        return out;
    }
}
