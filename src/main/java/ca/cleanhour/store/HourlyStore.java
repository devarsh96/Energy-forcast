package ca.cleanhour.store;

import ca.cleanhour.CleanHourProperties;
import ca.cleanhour.ieso.DayReport;
import ca.cleanhour.ieso.Fuel;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * In-memory hourly store, saved to a small JSON file so history (and the day-ahead forecasts,
 * which IESO overwrites) survives restarts. About 100 days x 24 hours: well under 1 MB.
 */
@Component
public class HourlyStore {

    private static final Logger log = LoggerFactory.getLogger(HourlyStore.class);
    public static final ZoneOffset EST = ZoneOffset.ofHours(-5);

    private final ConcurrentSkipListMap<Instant, HourRow> rows = new ConcurrentSkipListMap<>();
    private final Map<String, Instant> sourceUpdated = new ConcurrentHashMap<>();
    private final Set<LocalDate> dayAheadAttempted = ConcurrentHashMap.newKeySet();
    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private volatile boolean dirty;

    public HourlyStore(CleanHourProperties props) {
        this.file = Path.of(props.getStorePath());
    }

    /** Serialized form. */
    public static class Snapshot {
        public List<HourRow> rows = new ArrayList<>();
        public Map<String, Instant> sourceUpdated = new HashMap<>();
        public Set<LocalDate> dayAheadAttempted = new HashSet<>();
    }

    public static Instant startOf(LocalDate estDate, int hourEnding) {
        return estDate.atTime(hourEnding - 1, 0).toInstant(EST);
    }

    @PostConstruct
    void load() {
        if (!Files.exists(file)) return;
        try {
            Snapshot s = mapper.readValue(file.toFile(), Snapshot.class);
            s.rows.forEach(r -> { if (r.start != null) rows.put(r.start, r); });
            sourceUpdated.putAll(s.sourceUpdated);
            dayAheadAttempted.addAll(s.dayAheadAttempted);
            log.info("Loaded {} stored hours from {}", rows.size(), file.toAbsolutePath());
        } catch (IOException e) {
            log.warn("Could not read {} ({}); starting empty", file, e.toString());
        }
    }

    public synchronized void save() {
        if (!dirty) return;
        try {
            Path dir = file.toAbsolutePath().getParent();
            if (dir != null) Files.createDirectories(dir);
            Snapshot s = new Snapshot();
            s.rows = new ArrayList<>(rows.values());
            s.sourceUpdated = new HashMap<>(sourceUpdated);
            s.dayAheadAttempted = new HashSet<>(dayAheadAttempted);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writeValue(tmp.toFile(), s);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            dirty = false;
        } catch (IOException e) {
            log.warn("Could not save {}: {}", file, e.toString());
        }
    }

    private HourRow row(Instant start) {
        return rows.computeIfAbsent(start, HourRow::new);
    }

    public void putActual(DayReport report) {
        report.hours().forEach((he, mix) -> {
            if (mix.isEmpty()) return;
            row(startOf(report.date(), he)).actual = new EnumMap<>(mix);
        });
        dirty = true;
    }

    public void putDayAhead(DayReport report) {
        report.hours().forEach((he, mix) -> {
            if (mix.isEmpty()) return;
            row(startOf(report.date(), he)).dayAhead = new EnumMap<>(mix);
        });
        dayAheadAttempted.add(report.date());
        dirty = true;
    }

    public void putDemand(Map<LocalDate, Map<Integer, Double>> demand, Instant from) {
        demand.forEach((date, hours) -> hours.forEach((he, mw) -> {
            Instant s = startOf(date, he);
            if (!s.isBefore(from)) row(s).demand = mw;
        }));
        dirty = true;
    }

    public void markDayAheadAttempted(LocalDate d) { dayAheadAttempted.add(d); dirty = true; }
    public boolean dayAheadAttempted(LocalDate d) { return dayAheadAttempted.contains(d); }

    public void touch(String source) { sourceUpdated.put(source, Instant.now()); dirty = true; }
    public Map<String, Instant> sourceUpdated() { return Collections.unmodifiableMap(sourceUpdated); }

    /** Rows with start in [from, to). */
    public List<HourRow> range(Instant from, Instant to) {
        return new ArrayList<>(rows.subMap(from, true, to, false).values());
    }

    public Optional<Instant> firstActual() {
        return rows.values().stream().filter(r -> r.actual != null).map(r -> r.start).findFirst();
    }

    /** Number of hours with actual data for an EST delivery date. */
    public int actualHours(LocalDate estDate) {
        Instant from = startOf(estDate, 1);
        return (int) rows.subMap(from, true, from.plus(Duration.ofHours(24)), false).values().stream()
                .filter(r -> r.actual != null).count();
    }

    public boolean hasDayAhead(LocalDate estDate) {
        Instant from = startOf(estDate, 1);
        return rows.subMap(from, true, from.plus(Duration.ofHours(24)), false).values().stream()
                .anyMatch(r -> r.dayAhead != null);
    }

    public void pruneBefore(Instant cutoff) {
        if (rows.headMap(cutoff).isEmpty()) return;
        rows.headMap(cutoff).clear();
        LocalDate d = cutoff.atOffset(EST).toLocalDate();
        dayAheadAttempted.removeIf(x -> x.isBefore(d));
        dirty = true;
    }

    public static Map<Fuel, Double> copy(Map<Fuel, Double> m) {
        return m == null ? null : new EnumMap<>(m);
    }
}
