package ca.cleanhour.carbon;

import ca.cleanhour.CleanHourProperties;
import ca.cleanhour.ieso.DayReport;
import ca.cleanhour.ieso.Fuel;
import ca.cleanhour.ieso.IesoClient;
import ca.cleanhour.pricing.PricingService;
import ca.cleanhour.pricing.RateSet;
import ca.cleanhour.pricing.Tariff;
import ca.cleanhour.store.HourlyStore;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Regression tests for the Nov 2026 data audit. */
class DataFixesTest {

    @TempDir Path tmp;

    private CleanHourProperties props(String baseUrl) {
        CleanHourProperties p = new CleanHourProperties();
        Map<Fuel, Double> f = new EnumMap<>(Fuel.class);
        f.put(Fuel.NUCLEAR, 12.0); f.put(Fuel.GAS, 490.0); f.put(Fuel.HYDRO, 24.0);
        p.setEmissionFactors(f);
        p.setStorePath(tmp.resolve("store.json").toString());
        p.setBackfillDays(10);
        p.getIeso().setHistoryDays(7);
        p.getIeso().setBaseUrl(baseUrl);
        return p;
    }

    private static Tariff tariff() {
        return new Tariff(List.of(new RateSet(LocalDate.of(2025, 11, 1), 9.1, 14.6, 18.9, 3.7, 9.1, 14.6, 37.7, 11.2, 13.2, 15.7)), 13);
    }

    private static EnumMap<Fuel, Double> mix(double nuclear, double gas, double hydro) {
        EnumMap<Fuel, Double> m = new EnumMap<>(Fuel.class);
        m.put(Fuel.NUCLEAR, nuclear); m.put(Fuel.GAS, gas); m.put(Fuel.HYDRO, hydro);
        return m;
    }

    private IngestService ingest(CleanHourProperties p, HourlyStore store) {
        CarbonService carbon = new CarbonService(store, new CarbonCalculator(p), p);
        return new IngestService(new IesoClient(p), store, carbon, new PricingService(store, tariff(), carbon), p);
    }

    @Test
    void networkErrorDoesNotMarkPriceDaysAsMissing() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) { port = s.getLocalPort(); } // closed: connection refused
        CleanHourProperties p = props("http://127.0.0.1:" + port + "/public");
        HourlyStore store = new HourlyStore(p);
        ingest(p, store).backfill();
        LocalDate today = LocalDate.now(HourlyStore.EST);
        for (int i = 1; i <= 10; i++) assertFalse(store.priceAttempted(today.minusDays(i)), "day " + i + " should be retried");
    }

    @Test
    void notFoundMarksPastPriceDaysAsMissing() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> { ex.sendResponseHeaders(404, -1); ex.close(); });
        server.start();
        try {
            CleanHourProperties p = props("http://127.0.0.1:" + server.getAddress().getPort() + "/public");
            HourlyStore store = new HourlyStore(p);
            ingest(p, store).backfill();
            LocalDate today = LocalDate.now(HourlyStore.EST);
            assertTrue(store.priceAttempted(today.minusDays(3)));
            assertFalse(store.priceAttempted(today), "today can still be published");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void legacyPriceAttemptedEntriesAreDroppedOnLoad() throws Exception {
        CleanHourProperties p = props("http://unused");
        Files.writeString(Path.of(p.getStorePath()), "{\"rows\":[],\"priceAttempted\":[\"2026-11-01\"],\"priceNotFound\":[\"2026-08-01\"]}");
        HourlyStore store = new HourlyStore(p);
        java.lang.reflect.Method load = HourlyStore.class.getDeclaredMethod("load");
        load.setAccessible(true);
        load.invoke(store);
        assertFalse(store.priceAttempted(LocalDate.of(2026, 11, 1)));
        assertTrue(store.priceAttempted(LocalDate.of(2026, 8, 1)));
    }

    @Test
    void staleDataIsFlaggedAndForecastStartsAtTheCurrentHour() {
        CleanHourProperties p = props("http://unused");
        HourlyStore store = new HourlyStore(p);
        LocalDate d = LocalDate.of(2026, 11, 10);
        Map<Integer, EnumMap<Fuel, Double>> hours = new TreeMap<>();
        for (int he = 1; he <= 24; he++) hours.put(he, mix(8000, 1000 + he * 100, 4000));
        store.putActual(new DayReport(d, hours));
        CarbonService carbon = new CarbonService(store, new CarbonCalculator(p), p);

        // Two days after the last actual hour
        Instant now = HourlyStore.startOf(d, 24).plus(Duration.ofHours(49)).plusSeconds(1200);
        Dashboard dash = carbon.build(now, Map.of());
        assertTrue(dash.freshness().stale());
        assertEquals(48.3, dash.freshness().ageHours(), 0.1);
        Instant nowHour = now.truncatedTo(java.time.temporal.ChronoUnit.HOURS);
        assertEquals(nowHour, dash.forecast().get(0).start());
        assertFalse(dash.evWindow().start().isBefore(nowHour), "EV window must not be in the past");
        assertEquals(21.6, dash.evWindow().chargeKwh(), 1e-9);

        // Fresh: latest actual ended 40 minutes ago
        Dashboard fresh = carbon.build(HourlyStore.startOf(d, 24).plus(Duration.ofMinutes(100)), Map.of());
        assertFalse(fresh.freshness().stale());
    }

    @Test
    void repeatedFallBackHourIsAveragedInHeatmap() {
        CleanHourProperties p = props("http://unused");
        HourlyStore store = new HourlyStore(p);
        // Nov 1 2026: 1 am EDT is 05:00Z (EST HE1), 1 am EST is 06:00Z (EST HE2)
        LocalDate d = LocalDate.of(2026, 11, 1);
        Map<Integer, EnumMap<Fuel, Double>> hours = new TreeMap<>();
        for (int he = 1; he <= 24; he++) hours.put(he, mix(10000, 0, 0)); // 12 g/kWh
        hours.put(2, mix(5000, 0, 5000));                                   // 18 g/kWh
        store.putActual(new DayReport(d, hours));
        CarbonService carbon = new CarbonService(store, new CarbonCalculator(p), p);
        Dashboard dash = carbon.build(HourlyStore.startOf(d, 24).plus(Duration.ofHours(2)), Map.of());
        Dashboard.HeatmapDay nov1 = dash.heatmap().stream().filter(h -> h.date().equals(d)).findFirst().orElseThrow();
        assertEquals(15.0, nov1.values().get(1), 1e-9); // (12 + 18) / 2
        assertEquals(12.0, nov1.values().get(2), 1e-9);
    }

    @Test
    void partialHoursCountAsMissingSoTheDayIsFetchedAgain() {
        CleanHourProperties p = props("http://unused");
        HourlyStore store = new HourlyStore(p);
        CarbonService carbon = new CarbonService(store, new CarbonCalculator(p), p);
        LocalDate d = LocalDate.of(2026, 11, 10);
        Map<Integer, EnumMap<Fuel, Double>> hours = new TreeMap<>();
        for (int he = 1; he <= 24; he++) hours.put(he, mix(8000, 2000, 4000));
        hours.put(24, mix(900, 0, 0)); // read before most units had reported
        store.putActual(new DayReport(d, hours));
        assertEquals(24, store.actualHours(d));
        assertEquals(23, store.actualHours(d, carbon::complete));
    }

    @Test
    void pastYearDemandIsOnlyFinalOnceItHasTheLastHour() {
        CleanHourProperties p = props("http://unused");
        HourlyStore store = new HourlyStore(p);
        Instant last = HourlyStore.startOf(LocalDate.of(2025, 12, 31), 24);
        store.putDemand(Map.of(LocalDate.of(2025, 12, 31), Map.of(23, 15000.0)), Instant.EPOCH);
        assertNull(store.demand(last));
        store.putDemand(Map.of(LocalDate.of(2025, 12, 31), Map.of(24, 14500.0)), Instant.EPOCH);
        assertEquals(14500.0, store.demand(last));
    }

    @Test
    void droppedConnectionIsRetriedOnce() throws Exception {
        // First connection is closed without a reply (what IESO's server sometimes does); the second answers 404.
        try (ServerSocket server = new ServerSocket(0)) {
            Thread t = new Thread(() -> {
                try {
                    server.accept().close();
                    try (java.net.Socket c = server.accept()) {
                        c.getInputStream().read(new byte[4096]);
                        c.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    }
                } catch (java.io.IOException ignored) { }
            });
            t.setDaemon(true);
            t.start();
            IesoClient client = new IesoClient(props("http://127.0.0.1:" + server.getLocalPort() + "/public"));
            IesoClient.Fetched<?> r = client.fetchDayAheadPriceResult(LocalDate.of(2026, 11, 10));
            assertTrue(r.notFound(), "the retry should reach the server's real answer");
        }
    }
}
