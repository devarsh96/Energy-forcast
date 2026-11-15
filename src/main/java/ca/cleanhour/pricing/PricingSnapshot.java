package ca.cleanhour.pricing;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Everything the Dashboard pricing widgets need, rebuilt after each data refresh. Prices are cents per kWh unless named otherwise. */
public record PricingSnapshot(
        Instant generatedAt,
        RateSet rates,
        Map<RatePlan, List<String>> periods,
        double hstPct,
        Map<RatePlan, PlanNow> now,
        GridNow grid,
        List<PriceHour> next24,
        List<HeatDay> gridHeatmap,
        List<DayPrice> gridDaily,
        Map<RatePlan, EvCost> evWindowCost,
        List<String> notes
) {
    /** What a plan charges this hour and when that changes. */
    public record PlanNow(String period, String label, double cents, Instant nextChange, String nextLabel, Double nextCents) {}

    /**
     * Wholesale Ontario Zonal Price for the current hour. {@code level} compares it with the last 7 days:
     * "low" (cheapest quarter), "typical" or "high" (dearest quarter).
     */
    public record GridNow(Instant hour, Double cents, Double dollarsPerMwh, String source,
                          Double avg7d, Double low7d, Double high7d, String level, String gasLevel) {}

    public record PlanPrice(String period, double cents) {}

    /** One hour of the next 24: price on each plan, grid price (with where it came from) and carbon intensity. */
    public record PriceHour(Instant start, Map<RatePlan, PlanPrice> plans, Double gridCents, String gridSource,
                            Double intensity) {}

    /** Grid price by local hour (0..23) for one Toronto day; null = no data. */
    public record HeatDay(LocalDate date, List<Double> values) {}

    public record DayPrice(LocalDate date, int hours, double avg, double min, double max) {}

    /** Cost of the dashboard's EV charge (3 hours at 7.2 kW) in its cleanest 3-hour window, and the same charge at on-peak. */
    public record EvCost(Instant start, Instant end, double kwh, double cost, double peakCost, double saving) {}

    /** Response of the charge planner API. Costs are commodity only, in dollars. */
    public record ChargePlan(RatePlan plan, boolean overTierThreshold, ChargePlanner.Result result,
                             double peakCents, double peakCost, Map<RatePlan, ChargePlanner.Window> cheapestByPlan) {}
}
