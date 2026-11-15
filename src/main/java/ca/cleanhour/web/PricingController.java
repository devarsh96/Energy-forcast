package ca.cleanhour.web;

import ca.cleanhour.pricing.PricingService;
import ca.cleanhour.pricing.RatePlan;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Map;

/** Public, read-only pricing API: household rate plans, the grid price and the EV charge planner. */
@RestController
@RequestMapping("/api/v1")
@CrossOrigin(origins = "*", methods = RequestMethod.GET)
public class PricingController {

    private final PricingService pricing;

    public PricingController(PricingService pricing) {
        this.pricing = pricing;
    }

    /** Rate plans, price now, the next 24 hours (plan prices, grid price, carbon) and grid price history. */
    @GetMapping("/prices")
    public ResponseEntity<Object> prices() {
        return pricing.snapshot().<ResponseEntity<Object>>map(s -> ResponseEntity.ok()
                        .cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic()).body(s))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("status", "loading", "message", "Fetching the first IESO reports, try again shortly.")));
    }

    /**
     * When to charge. {@code kwh}: energy to add; {@code kw}: charger power; {@code plan}: ULO, TOU or TIERED;
     * {@code readyBy}: ISO instant the charge must finish by (optional); {@code overTier}: Tiered plan only,
     * true if the home is already past the tier-1 threshold this month.
     */
    @GetMapping("/charge-plan")
    public ResponseEntity<Object> chargePlan(@RequestParam(defaultValue = "36") double kwh,
                                             @RequestParam(defaultValue = "7.2") double kw,
                                             @RequestParam(defaultValue = "ULO") String plan,
                                             @RequestParam(required = false) String readyBy,
                                             @RequestParam(defaultValue = "false") boolean overTier) {
        if (!(kwh > 0 && kwh <= 200)) return bad("kwh must be between 0 and 200");
        if (!(kw >= 1 && kw <= 350)) return bad("kw must be between 1 and 350");
        RatePlan p;
        try { p = RatePlan.parse(plan); } catch (IllegalArgumentException e) {
            return bad("plan must be one of " + Arrays.toString(RatePlan.values()));
        }
        Instant ready = null;
        if (readyBy != null && !readyBy.isBlank()) {
            try { ready = Instant.parse(readyBy); } catch (DateTimeParseException e) { return bad("readyBy must be an ISO instant, e.g. 2026-11-15T12:00:00Z"); }
        }
        if (pricing.snapshot().isEmpty()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("status", "loading"));
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(pricing.plan(p, kwh, kw, ready, overTier));
    }

    private static ResponseEntity<Object> bad(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }
}
