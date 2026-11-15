package ca.cleanhour.carbon;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Everything the dashboard page needs, rebuilt from the store after each refresh. */
public record Dashboard(
        Instant generatedAt,
        Current current,
        GasPace gasPace,
        List<HourPoint> last24h,
        List<HeatmapDay> heatmap,
        List<HourPoint> forecast,
        EvWindow evWindow,
        Charts charts,
        Map<String, Double> emissionFactors,
        List<String> notes,
        Freshness freshness
) {
    /**
     * How current the data is. {@code dataThrough} is the end of the latest actual hour;
     * {@code stale} is true when that is more than 3 hours ago (IESO or this app's fetch is behind).
     */
    public record Freshness(Instant dataThrough, double ageHours, boolean stale) {}

    /** One hour of grid data. {@code start} is the start of the hour (UTC instant). */
    public record HourPoint(Instant start, double intensity, String band, double totalMw, double gasMw,
                            String source) {}

    public record FuelShare(String fuel, double mw, double share, Double factor) {}

    public record Current(Instant start, double intensity, String band, double totalMw,
                          List<FuelShare> mix, Double changeVsPrevHour, Double avg24h,
                          Double min24h, Double max24h) {}

    /** Gas level now: low, moderate or high, from its share of supply and where it sits in this week's range. */
    public record GasPace(String level, String headline, String detail, double gasMw, double gasShare,
                          double gasBaselineMw, double gasPeakMw, double flexibleRatio, Double gasChangeMw) {}

    /** Heatmap row: one local (Toronto) calendar day, 24 values indexed by local hour; null = no data. */
    public record HeatmapDay(LocalDate date, List<Double> values) {}

    public record EvWindow(Instant start, Instant end, double avgIntensity, String band,
                           double nowIntensity, Double worstAvgIntensity, Instant worstStart,
                           double savingVsNowPct, double chargeKwh, double chargerKw,
                           double kgCo2Best, double kgCo2Now, String basis) {}

    // ---- extra charts

    public record Charts(
            List<LinePoint> intensityLine,
            Accuracy accuracy,
            List<MixPoint> mix24h,
            List<MixPoint> mix7d,
            List<FuelShare> todayShares,
            List<HourTotal> todayHourly,
            List<DayTotal> weekByDay,
            List<DayTotal> calendar,
            List<DemandGas> demandGas
    ) {}

    /** Actual intensity and IESO's day-ahead forecast intensity for one hour (either may be null). */
    public record LinePoint(Instant t, Double actual, Double dayAhead) {}

    /** How close IESO's day-ahead forecast came to actual intensity. */
    public record Accuracy(int hours, double meanAbsError, double meanError, double meanAbsPctError) {}

    public record MixPoint(Instant t, Map<String, Double> mw) {}

    public record HourTotal(Instant t, double tonnes, double mwh, double intensity) {}

    /** Totals for one Toronto calendar day. */
    public record DayTotal(LocalDate date, int hours, double tonnes, double mwh, double intensity) {}

    public record DemandGas(Instant t, Double demand, double gas) {}
}
