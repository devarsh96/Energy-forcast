package ca.cleanhour;

import ca.cleanhour.ieso.Fuel;
import ca.cleanhour.pricing.RateSet;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "cleanhour")
public class CleanHourProperties {

    private final Ieso ieso = new Ieso();
    private final Pricing pricing = new Pricing();
    private Map<Fuel, Double> emissionFactors = new EnumMap<>(Fuel.class);
    /** JSON file holding stored history. */
    private String storePath = "data/clean-hour-store.json";
    /** Days of history to backfill and keep. */
    private int backfillDays = 92;

    public String getStorePath() { return storePath; }
    public void setStorePath(String storePath) { this.storePath = storePath; }
    public int getBackfillDays() { return backfillDays; }
    public void setBackfillDays(int backfillDays) { this.backfillDays = backfillDays; }

    public Ieso getIeso() { return ieso; }
    public Pricing getPricing() { return pricing; }
    public Map<Fuel, Double> getEmissionFactors() { return emissionFactors; }
    public void setEmissionFactors(Map<Fuel, Double> emissionFactors) { this.emissionFactors = emissionFactors; }

    public static class Ieso {
        private String baseUrl = "https://reports-public.ieso.ca/public";
        private int historyDays = 7;
        private long refreshMs = 300_000;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public int getHistoryDays() { return historyDays; }
        public void setHistoryDays(int historyDays) { this.historyDays = historyDays; }
        public long getRefreshMs() { return refreshMs; }
        public void setRefreshMs(long refreshMs) { this.refreshMs = refreshMs; }
    }

    /** OEB Regulated Price Plan prices. Add a new rate set each November; older sets price the history. */
    public static class Pricing {
        private double hstPct = 13;
        private List<RateSet> rateSets = new ArrayList<>();

        public double getHstPct() { return hstPct; }
        public void setHstPct(double hstPct) { this.hstPct = hstPct; }
        public List<RateSet> getRateSets() { return rateSets; }
        public void setRateSets(List<RateSet> rateSets) { this.rateSets = rateSets; }
    }
}
