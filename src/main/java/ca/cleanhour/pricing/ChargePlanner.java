package ca.cleanhour.pricing;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds when to charge. Pure function over a list of hourly slots, so it is easy to test.
 * A charge starts at the top of an hour and runs continuously at the charger's power until the
 * energy is delivered; the last hour can be partial.
 */
public final class ChargePlanner {

    private ChargePlanner() { }

    /** One hour: start, price (cents per kWh) and carbon intensity (g CO2e per kWh, null when there is no forecast). */
    public record Slot(Instant start, double cents, Double gPerKwh) {}

    /** One candidate charging session. Cost in dollars (commodity), CO2 in kg (null when any hour has no carbon forecast). */
    public record Window(Instant start, Instant end, double hours, double cost, Double kgCo2,
                         double avgCents, Double avgIntensity) {}

    public record Result(double kwh, double kw, double hours, Window now, Window cheapest, Window cleanest,
                         Window balanced, int candidates, String note) {}

    /**
     * @param slots    consecutive hourly slots, the first being the current hour
     * @param kwh      energy to add to the battery
     * @param kw       charger power
     * @param readyBy  the charge must finish by this time, or null for "any time in the slots"
     */
    public static Result plan(List<Slot> slots, double kwh, double kw, Instant readyBy) {
        double hours = kwh / kw;
        if (slots.isEmpty() || kwh <= 0 || kw <= 0) {
            return new Result(kwh, kw, hours, null, null, null, null, 0, "Nothing to plan.");
        }
        List<Window> all = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            Window w = window(slots, i, kwh, kw);
            if (w == null) break; // later starts have even fewer hours left
            if (readyBy != null && w.end().isAfter(readyBy)) continue;
            all.add(w);
        }
        Window now = window(slots, 0, kwh, kw);
        if (all.isEmpty()) {
            String note = now == null
                    ? "This charge needs more hours than the forecast covers. Try a faster charger or a smaller top-up."
                    : "The charge can't finish by the ready-by time. Showing what starting now would cost.";
            return new Result(kwh, kw, hours, now, null, null, null, 0, note);
        }
        if (all.stream().anyMatch(w -> w.kgCo2() == null)) {
            // Without a carbon forecast for every hour, only cost can be compared honestly.
            Window cheap = all.get(0);
            for (Window w : all) if (w.cost() < cheap.cost() - 1e-9) cheap = w;
            return new Result(kwh, kw, hours, now, cheap, null, null, all.size(),
                    "No carbon forecast is available for some of these hours, so only cost is compared.");
        }
        Window cheap = all.get(0), clean = all.get(0);
        double minC = Double.MAX_VALUE, maxC = -1, minG = Double.MAX_VALUE, maxG = -1;
        for (Window w : all) {
            if (w.cost() < cheap.cost() - 1e-9 || (Math.abs(w.cost() - cheap.cost()) < 1e-9 && w.kgCo2() < cheap.kgCo2())) cheap = w;
            if (w.kgCo2() < clean.kgCo2() - 1e-9 || (Math.abs(w.kgCo2() - clean.kgCo2()) < 1e-9 && w.cost() < clean.cost())) clean = w;
            minC = Math.min(minC, w.cost()); maxC = Math.max(maxC, w.cost());
            minG = Math.min(minG, w.kgCo2()); maxG = Math.max(maxG, w.kgCo2());
        }
        // Balanced: equal weight on cost and carbon, each scaled 0..1 across the candidate windows.
        Window bal = null;
        double bestScore = Double.MAX_VALUE;
        for (Window w : all) {
            double c = maxC > minC ? (w.cost() - minC) / (maxC - minC) : 0;
            double g = maxG > minG ? (w.kgCo2() - minG) / (maxG - minG) : 0;
            double score = 0.5 * c + 0.5 * g;
            if (score < bestScore - 1e-9) { bestScore = score; bal = w; }
        }
        return new Result(kwh, kw, hours, now, cheap, clean, bal, all.size(), null);
    }

    /** Charging that starts at slot {@code i}, or null if the slots run out first. */
    static Window window(List<Slot> slots, int i, double kwh, double kw) {
        double left = kwh, cost = 0, grams = 0, hours = 0;
        boolean carbonKnown = true;
        int j = i;
        while (left > 1e-9) {
            if (j >= slots.size()) return null;
            Slot s = slots.get(j);
            if (j > i && !s.start().equals(slots.get(j - 1).start().plus(Duration.ofHours(1)))) return null; // gap
            double e = Math.min(left, kw);
            cost += e * s.cents() / 100.0;
            if (s.gPerKwh() == null) carbonKnown = false;
            else grams += e * s.gPerKwh();
            hours += e / kw;
            left -= e;
            j++;
        }
        Instant start = slots.get(i).start();
        Instant end = start.plusSeconds(Math.round(hours * 3600));
        return new Window(start, end, r2(hours), r2(cost), carbonKnown ? r2(grams / 1000.0) : null,
                r2(cost * 100 / kwh), carbonKnown ? r1(grams / kwh) : null);
    }

    static double r1(double v) { return Math.round(v * 10) / 10.0; }
    static double r2(double v) { return Math.round(v * 100) / 100.0; }
}
