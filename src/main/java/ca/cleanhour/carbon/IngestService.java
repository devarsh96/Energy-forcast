package ca.cleanhour.carbon;

import ca.cleanhour.CleanHourProperties;
import ca.cleanhour.ieso.DayReport;
import ca.cleanhour.ieso.Fuel;
import ca.cleanhour.ieso.IesoClient;
import ca.cleanhour.ieso.IesoClient.ScheduleVersion;
import ca.cleanhour.ieso.PriceDay;
import ca.cleanhour.pricing.PricingService;
import ca.cleanhour.store.HourlyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static ca.cleanhour.store.HourlyStore.EST;
import static ca.cleanhour.store.HourlyStore.startOf;

/**
 * Pulls IESO data into the store.
 * <ul>
 *   <li>Every 5 minutes: live generator output, recent dated output files with gaps,
 *       and (every 30 minutes) demand plus the current schedules for today and tomorrow.</li>
 *   <li>At start-up and every 12 hours: backfill of the history window from the yearly fuel file
 *       and from archived day-ahead schedules.</li>
 * </ul>
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);
    private static final ZoneId EASTERN = ZoneId.of("America/Toronto");
    private static final Duration SLOW_TTL = Duration.ofMinutes(30);

    public static final String SRC_OUTPUT = "GenOutputCapability";
    public static final String SRC_YEARLY = "GenOutputbyFuelHourly";
    public static final String SRC_SCHEDULE = "Adequacy3";
    public static final String SRC_DEMAND = "Demand";
    public static final String SRC_PRICE_DA = "DAHourlyOntarioZonalPrice";
    public static final String SRC_PRICE_PD = "PredispHourlyOntarioZonalPrice";
    public static final String SRC_PRICE_RT = "RealtimeOntarioZonalPrice";

    private final IesoClient ieso;
    private final HourlyStore store;
    private final CarbonService dashboard;
    private final PricingService pricing;
    private final int historyDays;
    private final int backfillDays;

    /** Latest (not day-ahead) schedule for today and tomorrow, used for the forward forecast. */
    private final Map<Instant, EnumMap<Fuel, Double>> latestSchedule = new ConcurrentHashMap<>();
    private volatile Instant lastSlowFetch = Instant.EPOCH;
    private volatile boolean backfillRunning;
    /** Real-time price hours we already tried to re-fetch (kept in memory only). */
    private final Set<Instant> realtimeAttempted = ConcurrentHashMap.newKeySet();

    public IngestService(IesoClient ieso, HourlyStore store, CarbonService dashboard, PricingService pricing,
                         CleanHourProperties props) {
        this.ieso = ieso;
        this.store = store;
        this.dashboard = dashboard;
        this.pricing = pricing;
        this.historyDays = Math.max(1, props.getIeso().getHistoryDays());
        this.backfillDays = Math.max(historyDays, props.getBackfillDays());
    }

    public boolean backfillRunning() { return backfillRunning; }

    @Scheduled(initialDelay = 1000, fixedDelayString = "${cleanhour.ieso.refresh-ms:300000}")
    public void refreshLive() {
        long t0 = System.currentTimeMillis();
        LocalDate today = LocalDate.now(EST);

        ieso.fetchOutput(null).ifPresent(r -> { store.putActual(r); store.touch(SRC_OUTPUT); });
        for (int i = 1; i <= historyDays; i++) {
            LocalDate d = today.minusDays(i);
            // Re-fetch days with missing or partial hours (e.g. the last hour read before IESO had all units in).
            if (store.actualHours(d, dashboard::complete) < 24) ieso.fetchOutput(d).ifPresent(store::putActual);
        }

        ieso.fetchRealtimePrice(null, 0).ifPresent(p -> { store.putRealtimePrice(p); store.touch(SRC_PRICE_RT); });

        Instant now = Instant.now();
        if (Duration.between(lastSlowFetch, now).compareTo(SLOW_TTL) > 0) {
            fetchSchedules(today);
            fetchDemand(today);
            fetchPrices(now);
            lastSlowFetch = now;
        }

        store.pruneBefore(startOf(today.minusDays(backfillDays + 1), 1));
        dashboard.rebuild(latestSchedule);
        pricing.rebuild();
        store.save();
        log.info("Live refresh done in {} ms", System.currentTimeMillis() - t0);
    }

    private void fetchSchedules(LocalDate today) {
        boolean any = false;
        for (LocalDate d : List.of(today, today.plusDays(1))) {
            Optional<DayReport> r = ieso.fetchSchedule(d);
            if (r.isEmpty()) continue;
            any = true;
            r.get().hours().forEach((he, mix) -> latestSchedule.put(startOf(d, he), new EnumMap<>(mix)));
            // A schedule fetched before its delivery day is a day-ahead forecast; the last one before midnight wins.
            if (d.isAfter(today)) store.putDayAhead(r.get());
        }
        latestSchedule.keySet().removeIf(t -> t.isBefore(startOf(today, 1)));
        if (any) store.touch(SRC_SCHEDULE);
    }

    private void fetchDemand(LocalDate today) {
        Instant from = startOf(today.minusDays(backfillDays), 1);
        Set<Integer> years = new TreeSet<>(List.of(today.minusDays(backfillDays).getYear(), today.getYear()));
        boolean any = false;
        for (int y : years) {
            // A past year's file is final once it has the year's last hour (a fetch early on Jan 1 may not).
            if (y != today.getYear() && store.demand(startOf(LocalDate.of(y, 12, 31), 24)) != null) continue;
            var demand = ieso.fetchDemand(y);
            if (demand.isPresent()) {
                store.putDemand(demand.get(), from);
                store.touch(SRC_DEMAND + y);
                any = true;
            }
        }
        if (any) store.touch(SRC_DEMAND);
    }

    /** Day-ahead (tomorrow, once published), pre-dispatch (rest of today), and any incomplete real-time hours from the last day. */
    private void fetchPrices(Instant now) {
        ieso.fetchDayAheadPrice(null).ifPresent(p -> { store.putDayAheadPrice(p); store.touch(SRC_PRICE_DA); });
        LocalDate today = LocalDate.now(EST);
        if (!store.hasDayAheadPrice(today)) {
            ieso.fetchDayAheadPrice(today).ifPresent(p -> { store.putDayAheadPrice(p); store.touch(SRC_PRICE_DA); });
        }
        ieso.fetchPredispatchPrice().ifPresent(p -> { store.putPredispatchPrice(p); store.touch(SRC_PRICE_PD); });

        Instant currentHour = startOf(today, 1).plus(Duration.ofHours(Duration.between(startOf(today, 1), now).toHours()));
        realtimeAttempted.removeIf(t -> t.isBefore(currentHour.minus(Duration.ofHours(30))));
        for (var r : store.range(currentHour.minus(Duration.ofHours(24)), currentHour)) {
            boolean complete = r.priceRealtimeIntervals != null && r.priceRealtimeIntervals >= 12;
            if (complete || !realtimeAttempted.add(r.start)) continue;
            var est = r.start.atOffset(EST);
            ieso.fetchRealtimePrice(est.toLocalDate(), est.getHour() + 1).ifPresent(store::putRealtimePrice);
        }
    }

    @Scheduled(initialDelay = 15_000, fixedDelay = 12 * 3600_000L)
    public void backfill() {
        backfillRunning = true;
        long t0 = System.currentTimeMillis();
        try {
            LocalDate today = LocalDate.now(EST);
            LocalDate first = today.minusDays(backfillDays);
            backfillActuals(first, today.minusDays(historyDays + 1));
            backfillDayAhead(first, today);
            backfillPrices(first, today);
            dashboard.rebuild(latestSchedule);
            pricing.rebuild();
            store.save();
        } catch (RuntimeException e) {
            log.error("Backfill failed", e);
        } finally {
            backfillRunning = false;
        }
        log.info("Backfill done in {} s", (System.currentTimeMillis() - t0) / 1000);
    }

    private void backfillActuals(LocalDate first, LocalDate last) {
        Set<Integer> years = new TreeSet<>();
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
            if (store.actualHours(d, dashboard::complete) < 24) years.add(d.getYear());
        }
        for (int y : years) {
            ieso.fetchYearlyFuel(y).ifPresent(days -> {
                days.forEach((d, r) -> {
                    if (!d.isBefore(first) && store.actualHours(d, dashboard::complete) < 24) store.putActual(r);
                });
                store.touch(SRC_YEARLY);
            });
        }
    }

    /** Day-ahead Ontario Zonal Price for each past day (IESO keeps about 3 months of daily files). */
    private void backfillPrices(LocalDate first, LocalDate today) {
        int loaded = 0, tried = 0;
        for (LocalDate d = today; !d.isBefore(first); d = d.minusDays(1)) {
            if (store.hasDayAheadPrice(d) || store.priceAttempted(d)) continue;
            tried++;
            IesoClient.Fetched<PriceDay> p = ieso.fetchDayAheadPriceResult(d);
            if (p.value().isPresent()) { store.putDayAheadPrice(p.value().get()); loaded++; }
            // Only give up on a past day when IESO says the file doesn't exist (aged out of the archive).
            // A network error or outage is retried at the next backfill.
            else if (p.notFound() && d.isBefore(today)) store.markPriceAttempted(d);
        }
        if (loaded > 0) store.touch(SRC_PRICE_DA);
        if (tried > 0) log.info("Backfilled day-ahead prices for {} of {} days", loaded, tried);
    }

    /**
     * For each delivery day, use the last Adequacy3 version published before that day began:
     * that is what IESO expected a day ahead.
     */
    private void backfillDayAhead(LocalDate first, LocalDate today) {
        List<LocalDate> missing = new ArrayList<>();
        for (LocalDate d = first; !d.isAfter(today); d = d.plusDays(1)) {
            if (!store.hasDayAhead(d) && !store.dayAheadAttempted(d)) missing.add(d);
        }
        if (missing.isEmpty()) return;
        Optional<List<ScheduleVersion>> listing = ieso.fetchScheduleListing();
        if (listing.isEmpty()) return;
        Map<LocalDate, ScheduleVersion> pick = new HashMap<>();
        for (ScheduleVersion v : listing.get()) {
            LocalDateTime dayStart = v.deliveryDate().atStartOfDay();
            if (!v.published().isBefore(dayStart)) continue;
            pick.merge(v.deliveryDate(), v, (a, b) -> a.version() >= b.version() ? a : b);
        }
        int loaded = 0;
        for (LocalDate d : missing) {
            ScheduleVersion v = pick.get(d);
            boolean gone = true;
            if (v != null) {
                IesoClient.Fetched<DayReport> r = ieso.fetchScheduleFileResult(v.file());
                if (r.value().isPresent()) { store.putDayAhead(r.value().get()); loaded++; continue; }
                gone = r.notFound();
            }
            // Past days with no archived version will never get one; a failed download is retried later.
            if (gone && d.isBefore(today)) store.markDayAheadAttempted(d);
        }
        if (loaded > 0) store.touch(SRC_SCHEDULE);
        log.info("Backfilled day-ahead schedules for {} of {} days", loaded, missing.size());
    }
}
