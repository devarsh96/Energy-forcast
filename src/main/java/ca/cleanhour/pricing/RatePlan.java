package ca.cleanhour.pricing;

/** The three Ontario Energy Board Regulated Price Plan options for households. */
public enum RatePlan {
    ULO("Ultra-Low Overnight"),
    TOU("Time-of-Use"),
    TIERED("Tiered");

    private final String title;

    RatePlan(String title) { this.title = title; }

    public String title() { return title; }

    public static RatePlan parse(String s) {
        if (s == null || s.isBlank()) return ULO;
        return RatePlan.valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
