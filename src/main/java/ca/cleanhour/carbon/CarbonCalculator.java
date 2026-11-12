package ca.cleanhour.carbon;

import ca.cleanhour.CleanHourProperties;
import ca.cleanhour.ieso.Fuel;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * Converts an hourly fuel mix (MW) into grid carbon intensity (g CO2-eq / kWh):
 * intensity = sum(MW_fuel * factor_fuel) / sum(MW_fuel).
 * Fuels without a factor (storage, "other") are left out of both sums.
 */
@Component
public class CarbonCalculator {

    private final Map<Fuel, Double> factors;

    public CarbonCalculator(CleanHourProperties props) {
        this.factors = new EnumMap<>(Fuel.class);
        this.factors.putAll(props.getEmissionFactors());
    }

    public Map<Fuel, Double> factors() {
        return factors;
    }

    public boolean counted(Fuel fuel) {
        return factors.containsKey(fuel);
    }

    /** Total MW of fuels that count towards intensity. */
    public double countedMw(Map<Fuel, Double> mix) {
        double total = 0;
        for (var e : mix.entrySet()) if (counted(e.getKey())) total += e.getValue();
        return total;
    }

    /** g CO2-eq per kWh, or NaN when the mix is empty. */
    public double intensity(Map<Fuel, Double> mix) {
        double mw = 0, grams = 0;
        for (var e : mix.entrySet()) {
            Double f = factors.get(e.getKey());
            if (f == null) continue;
            mw += e.getValue();
            grams += e.getValue() * f;
        }
        return mw <= 0 ? Double.NaN : grams / mw;
    }

    /** Tonnes CO2-eq emitted in one hour by this mix (MW x g/kWh / 1000). */
    public double emissionsTonnes(Map<Fuel, Double> mix) {
        double t = 0;
        for (var e : mix.entrySet()) {
            Double f = factors.get(e.getKey());
            if (f != null) t += e.getValue() * f / 1000.0;
        }
        return t;
    }

    /** True when the mix looks like a complete hour (Ontario never generates less than this). */
    public boolean complete(Map<Fuel, Double> mix) {
        return mix != null && countedMw(mix) >= 3000;
    }

    /** Plain-language band used for colours and labels across the UI. */
    public static String band(double intensity) {
        if (Double.isNaN(intensity)) return "unknown";
        if (intensity < 40) return "very clean";
        if (intensity < 80) return "clean";
        if (intensity < 130) return "moderate";
        if (intensity < 180) return "high";
        return "very high";
    }
}
