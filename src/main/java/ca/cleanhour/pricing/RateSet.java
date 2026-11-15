package ca.cleanhour.pricing;

import java.time.LocalDate;

/**
 * One set of OEB Regulated Price Plan prices (commodity only, cents per kWh) and the date it took effect.
 * Bound from {@code cleanhour.pricing.rate-sets} in application.yml.
 */
public class RateSet {
    private LocalDate effective;
    private double touOff, touMid, touOn;
    private double uloOvernight, uloWeekendOff, uloMid, uloOn;
    private double tier1, tier2;
    private int tierThresholdWinter = 1000, tierThresholdSummer = 600;
    /** Ontario Electricity Rebate, percent of the pre-tax bill. */
    private double oerPct;

    public RateSet() { }

    public RateSet(LocalDate effective, double touOff, double touMid, double touOn,
                   double uloOvernight, double uloWeekendOff, double uloMid, double uloOn,
                   double tier1, double tier2, double oerPct) {
        this.effective = effective;
        this.touOff = touOff; this.touMid = touMid; this.touOn = touOn;
        this.uloOvernight = uloOvernight; this.uloWeekendOff = uloWeekendOff; this.uloMid = uloMid; this.uloOn = uloOn;
        this.tier1 = tier1; this.tier2 = tier2; this.oerPct = oerPct;
    }

    public LocalDate getEffective() { return effective; }
    public void setEffective(LocalDate effective) { this.effective = effective; }
    public double getTouOff() { return touOff; }
    public void setTouOff(double v) { touOff = v; }
    public double getTouMid() { return touMid; }
    public void setTouMid(double v) { touMid = v; }
    public double getTouOn() { return touOn; }
    public void setTouOn(double v) { touOn = v; }
    public double getUloOvernight() { return uloOvernight; }
    public void setUloOvernight(double v) { uloOvernight = v; }
    public double getUloWeekendOff() { return uloWeekendOff; }
    public void setUloWeekendOff(double v) { uloWeekendOff = v; }
    public double getUloMid() { return uloMid; }
    public void setUloMid(double v) { uloMid = v; }
    public double getUloOn() { return uloOn; }
    public void setUloOn(double v) { uloOn = v; }
    public double getTier1() { return tier1; }
    public void setTier1(double v) { tier1 = v; }
    public double getTier2() { return tier2; }
    public void setTier2(double v) { tier2 = v; }
    public int getTierThresholdWinter() { return tierThresholdWinter; }
    public void setTierThresholdWinter(int v) { tierThresholdWinter = v; }
    public int getTierThresholdSummer() { return tierThresholdSummer; }
    public void setTierThresholdSummer(int v) { tierThresholdSummer = v; }
    public double getOerPct() { return oerPct; }
    public void setOerPct(double v) { oerPct = v; }

    /** Highest price a plan can charge (used for "vs on-peak" comparisons). */
    public double peak(RatePlan plan) {
        return switch (plan) {
            case ULO -> uloOn;
            case TOU -> touOn;
            case TIERED -> tier2;
        };
    }

    /** Lowest price a plan can charge. */
    public double lowest(RatePlan plan) {
        return switch (plan) {
            case ULO -> uloOvernight;
            case TOU -> touOff;
            case TIERED -> tier1;
        };
    }
}
