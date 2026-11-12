package ca.cleanhour.web;

import ca.cleanhour.carbon.CarbonService;
import ca.cleanhour.carbon.ReportService;
import ca.cleanhour.ieso.Fuel;
import ca.cleanhour.ieso.IesoClient;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Public, read-only API (no login). Everything is served from the app's cache, never live from IESO.
 * Dates are Toronto local calendar dates (YYYY-MM-DD).
 */
@RestController
@RequestMapping("/api/v1")
@CrossOrigin(origins = "*", methods = RequestMethod.GET)
public class ApiController {

    private static final int MAX_DAYS = 100;

    private final CarbonService carbon;
    private final ReportService reports;
    private final IesoClient ieso;

    public ApiController(CarbonService carbon, ReportService reports, IesoClient ieso) {
        this.carbon = carbon;
        this.reports = reports;
        this.ieso = ieso;
    }

    private static ResponseEntity<Object> ok(Object body, int maxAgeSeconds) {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(maxAgeSeconds)).cachePublic()).body(body);
    }

    private static ResponseEntity<Object> bad(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }

    private static ResponseEntity<Object> loading() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("status", "loading", "message", "Fetching the first IESO reports, try again shortly."));
    }

    /** Latest hour: intensity, band, fuel mix and the gas indicator. */
    @GetMapping("/current")
    public ResponseEntity<Object> current() {
        return carbon.dashboard().<ResponseEntity<Object>>map(d -> ok(Map.of(
                "generatedAt", d.generatedAt(), "current", d.current(), "gasPace", d.gasPace()), 60))
                .orElseGet(ApiController::loading);
    }

    /** Next 24 hours of forecast intensity and the best 3-hour charging window. */
    @GetMapping("/forecast")
    public ResponseEntity<Object> forecast() {
        return carbon.dashboard().<ResponseEntity<Object>>map(d -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("generatedAt", d.generatedAt());
            m.put("hours", d.forecast());
            m.put("bestWindow", d.evWindow());
            return ok(m, 60);
        }).orElseGet(ApiController::loading);
    }

    /** Raw hourly data. {@code fuels} is a comma list (default: all). */
    @GetMapping("/hourly")
    public ResponseEntity<Object> hourly(@RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to,
                                         @RequestParam(required = false) String fuels) {
        Query q;
        try { q = Query.of(from, to, fuels); } catch (IllegalArgumentException e) { return bad(e.getMessage()); }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", q.from);
        m.put("to", q.to);
        m.put("fuels", q.fuels);
        m.put("rows", reports.hourly(q.from, q.to, q.fuels));
        return ok(m, 120);
    }

    /** Same as /hourly, as an Excel workbook. */
    @GetMapping("/hourly.xlsx")
    public ResponseEntity<StreamingResponseBody> hourlyXlsx(@RequestParam(required = false) String from,
                                                            @RequestParam(required = false) String to,
                                                            @RequestParam(required = false) String fuels) {
        Query q;
        try { q = Query.of(from, to, fuels); } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        var rows = reports.hourly(q.from, q.to, q.fuels);
        List<String> fuelNames = q.fuels.stream().map(Enum::name).toList();
        String note = "Toronto local dates " + q.from + " to " + q.to + ". Fuel columns: " + String.join(", ", fuelNames) + ".";
        StreamingResponseBody body = out -> XlsxExporter.write(rows, fuelNames, out, note);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"clean-hour-" + q.from + "-to-" + q.to + ".xlsx\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }

    /** Totals per day: hours covered, tonnes CO2e, MWh and average intensity. */
    @GetMapping("/daily")
    public ResponseEntity<Object> daily(@RequestParam(required = false) String from,
                                        @RequestParam(required = false) String to) {
        Query q;
        try { q = Query.of(from, to, null); } catch (IllegalArgumentException e) { return bad(e.getMessage()); }
        return ok(Map.of("from", q.from, "to", q.to, "days", reports.daily(q.from, q.to)), 300);
    }

    /** Where the data comes from and when each source was last fetched. */
    @GetMapping("/sources")
    public ResponseEntity<Object> sources() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("coverage", reports.coverage());
        m.put("sources", reports.sources(ieso.baseUrl()));
        m.put("emissionFactors", carbon.dashboard().map(d -> d.emissionFactors()).orElse(Map.of()));
        return ok(m, 60);
    }

    @GetMapping("/months")
    public ResponseEntity<Object> months() {
        return ok(Map.of("months", reports.availableMonths().stream().map(YearMonth::toString).toList()), 300);
    }

    /** Monthly summary: carbon, gas-led hours, best times. {@code month} is YYYY-MM. */
    @GetMapping("/monthly/{month}")
    public ResponseEntity<Object> monthly(@PathVariable String month) {
        YearMonth ym;
        try { ym = YearMonth.parse(month); } catch (DateTimeParseException e) { return bad("month must be YYYY-MM"); }
        return reports.monthly(ym).<ResponseEntity<Object>>map(r -> ok(r, 300))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "No data for " + month)));
    }

    /** Parsed and validated query parameters. */
    record Query(LocalDate from, LocalDate to, Set<Fuel> fuels) {
        static Query of(String from, String to, String fuels) {
            LocalDate today = LocalDate.now(CarbonService.LOCAL);
            LocalDate t, f;
            try {
                t = to == null || to.isBlank() ? today : LocalDate.parse(to);
                f = from == null || from.isBlank() ? t.minusDays(6) : LocalDate.parse(from);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("Dates must be YYYY-MM-DD");
            }
            if (f.isAfter(t)) throw new IllegalArgumentException("'from' must be on or before 'to'");
            if (f.plusDays(MAX_DAYS).isBefore(t)) throw new IllegalArgumentException("Range is limited to " + MAX_DAYS + " days");
            Set<Fuel> set = EnumSet.noneOf(Fuel.class);
            if (fuels == null || fuels.isBlank()) set = EnumSet.allOf(Fuel.class);
            else for (String s : fuels.split(",")) {
                String u = s.trim().toUpperCase(Locale.ROOT);
                try { set.add(Fuel.valueOf(u)); } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Unknown fuel '" + s.trim() + "'. Use: " + Arrays.toString(Fuel.values()));
                }
            }
            return new Query(f, t, set);
        }
    }
}
