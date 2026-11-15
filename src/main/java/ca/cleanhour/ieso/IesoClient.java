package ca.cleanhour.ieso;

import ca.cleanhour.CleanHourProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads IESO public reports with streaming parsers (low memory, suits a 1 GB host).
 * <ul>
 *   <li>GenOutputCapability: actual hourly output of every generator with its fuel type (live + dated files).</li>
 *   <li>GenOutputbyFuelHourly: one file per year with hourly output by fuel (used for backfill).</li>
 *   <li>Adequacy3: IESO's scheduled output by fuel for each hour of a delivery day, many versions per day.</li>
 *   <li>Demand: one CSV per year with hourly Ontario demand.</li>
 *   <li>DAHourlyOntarioZonalPrice / PredispHourlyOntarioZonalPrice: hourly Ontario Zonal Price, day-ahead and pre-dispatch.</li>
 *   <li>RealtimeOntarioZonalPrice: 5-minute real-time Ontario Zonal Price, one file per hour.</li>
 * </ul>
 * IESO hours are "hour ending" 1..24 in Eastern Standard Time.
 */
@Component
public class IesoClient {

    private static final Logger log = LoggerFactory.getLogger(IesoClient.class);
    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter LISTING_TIME = DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm", Locale.ENGLISH);
    private static final Pattern ADEQUACY_LINK = Pattern.compile(
            "href=\"(PUB_Adequacy3_(\\d{8})_v(\\d+)\\.xml)\".*?(\\d{2}-[A-Za-z]{3}-\\d{4} \\d{2}:\\d{2})");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final XMLInputFactory xml;
    private final String baseUrl;

    public IesoClient(CleanHourProperties props) {
        this.baseUrl = props.getIeso().getBaseUrl();
        this.xml = XMLInputFactory.newFactory();
        xml.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        xml.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    }

    public String baseUrl() { return baseUrl; }

    /** Actual generator output. {@code date == null} fetches the live (current day) report. */
    public Optional<DayReport> fetchOutput(LocalDate date) {
        String file = date == null ? "PUB_GenOutputCapability.xml" : "PUB_GenOutputCapability_" + YMD.format(date) + ".xml";
        return fetch("/GenOutputCapability/" + file, this::parseOutput).filter(r -> r.date() != null);
    }

    /** Current version of IESO's scheduled output by fuel for a delivery day. */
    public Optional<DayReport> fetchSchedule(LocalDate date) {
        return fetchScheduleFile("PUB_Adequacy3_" + YMD.format(date) + ".xml");
    }

    public Optional<DayReport> fetchScheduleFile(String file) {
        return fetchScheduleFileResult(file).value();
    }

    public Fetched<DayReport> fetchScheduleFileResult(String file) {
        Fetched<DayReport> f = fetchResult("/Adequacy3/" + file, this::parseSchedule);
        return new Fetched<>(f.value().filter(r -> r.date() != null), f.notFound());
    }

    /**
     * Result of one download. {@code notFound} is true only when IESO answered HTTP 404, meaning the file
     * really doesn't exist. Network errors, timeouts and other HTTP errors leave it false, so callers retry later.
     */
    public record Fetched<T>(Optional<T> value, boolean notFound) {}

    /** Hourly output by fuel for a whole year, keyed by EST date. */
    public Optional<Map<LocalDate, DayReport>> fetchYearlyFuel(int year) {
        return fetch("/GenOutputbyFuelHourly/PUB_GenOutputbyFuelHourly_" + year + ".xml", this::parseYearlyFuel);
    }

    /** Hourly Ontario demand (MW) for a year: EST date -> hour ending -> MW. */
    public Optional<Map<LocalDate, Map<Integer, Double>>> fetchDemand(int year) {
        return fetch("/Demand/PUB_Demand_" + year + ".csv", IesoClient::parseDemand);
    }

    /** Day-ahead hourly Ontario Zonal Price. {@code date == null} fetches the latest file (usually tomorrow, once published). */
    public Optional<PriceDay> fetchDayAheadPrice(LocalDate date) {
        return fetchDayAheadPriceResult(date).value();
    }

    /** Same as {@link #fetchDayAheadPrice}, but says whether IESO answered "not found" (as opposed to a network error). */
    public Fetched<PriceDay> fetchDayAheadPriceResult(LocalDate date) {
        String file = date == null ? "PUB_DAHourlyOntarioZonalPrice.xml" : "PUB_DAHourlyOntarioZonalPrice_" + YMD.format(date) + ".xml";
        Fetched<PriceDay> f = fetchResult("/DAHourlyOntarioZonalPrice/" + file, this::parseHourlyPrice);
        Optional<PriceDay> v = f.value().filter(p -> p.date() != null && !p.hours().isEmpty());
        return new Fetched<>(v, f.notFound());
    }

    /** Latest pre-dispatch hourly Ontario Zonal Price for the current day. */
    public Optional<PriceDay> fetchPredispatchPrice() {
        return fetch("/PredispHourlyOntarioZonalPrice/PUB_PredispHourlyOntarioZonalPrice.xml", this::parseHourlyPrice)
                .filter(p -> p.date() != null && !p.hours().isEmpty());
    }

    /** Real-time 5-minute prices for one hour. {@code date == null} fetches the current hour. */
    public Optional<RealtimePrice> fetchRealtimePrice(LocalDate date, int hourEnding) {
        String file = date == null ? "PUB_RealtimeOntarioZonalPrice.xml"
                : String.format(Locale.ROOT, "PUB_RealtimeOntarioZonalPrice_%s%02d.xml", YMD.format(date), hourEnding);
        return fetch("/RealtimeOntarioZonalPrice/" + file, this::parseRealtimePrice);
    }

    /** One published version of an Adequacy3 file. {@code published} is the listing time (Eastern). */
    public record ScheduleVersion(String file, LocalDate deliveryDate, int version, LocalDateTime published) {}

    /** All versioned Adequacy3 files in the archive directory. */
    public Optional<List<ScheduleVersion>> fetchScheduleListing() {
        return fetch("/Adequacy3/", in -> {
            String html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            List<ScheduleVersion> out = new ArrayList<>();
            for (String line : html.split("\n")) {
                Matcher m = ADEQUACY_LINK.matcher(line);
                if (m.find()) {
                    try {
                        out.add(new ScheduleVersion(m.group(1), LocalDate.parse(m.group(2), YMD),
                                Integer.parseInt(m.group(3)), LocalDateTime.parse(m.group(4), LISTING_TIME)));
                    } catch (RuntimeException ignored) {
                        // skip malformed rows
                    }
                }
            }
            return out;
        });
    }

    // ------------------------------------------------------------------ parsing

    DayReport parseOutput(InputStream in) throws XMLStreamException {
        return parse(in, "Date", "Generator", "Outputs", "Output", "Hour");
    }

    DayReport parseSchedule(InputStream in) throws XMLStreamException {
        return parse(in, "DeliveryDate", "InternalResource", "Schedules", "Schedule", "DeliveryHour");
    }

    private interface Parser<T> { T parse(InputStream in) throws XMLStreamException, IOException; }

    private <T> Optional<T> fetch(String path, Parser<T> parser) {
        return fetchResult(path, parser).value();
    }

    private <T> Fetched<T> fetchResult(String path, Parser<T> parser) {
        URI uri = URI.create(baseUrl + path);
        try {
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(120))
                    .header("User-Agent", "clean-hour-dashboard/0.2")
                    .GET().build();
            HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = res.body()) {
                if (res.statusCode() != 200) {
                    log.info("IESO {} returned HTTP {}", uri, res.statusCode());
                    return new Fetched<>(Optional.empty(), res.statusCode() == 404);
                }
                return new Fetched<>(Optional.ofNullable(parser.parse(body)), false);
            }
        } catch (IOException | XMLStreamException | RuntimeException e) {
            log.warn("Failed to read IESO {}: {}", uri, e.toString());
            return new Fetched<>(Optional.empty(), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Fetched<>(Optional.empty(), false);
        }
    }

    /** Walker for: unit (FuelType, ..., series (point (hourTag, EnergyMW))). */
    private DayReport parse(InputStream in, String dateTag, String unitTag, String seriesTag, String pointTag, String hourTag)
            throws XMLStreamException {
        XMLStreamReader r = xml.createXMLStreamReader(in);
        Deque<String> path = new ArrayDeque<>();
        StringBuilder text = new StringBuilder();
        DayReport report = null;
        Fuel fuel = null;
        Integer hour = null;
        Double mw = null;
        try {
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    String name = r.getLocalName();
                    path.push(name);
                    text.setLength(0);
                    if (name.equals(unitTag)) fuel = null;
                    if (name.equals(pointTag)) { hour = null; mw = null; }
                } else if (ev == XMLStreamConstants.CHARACTERS || ev == XMLStreamConstants.CDATA) {
                    text.append(r.getText());
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    String name = path.pop();
                    String value = text.toString().trim();
                    text.setLength(0);
                    String parent = path.peek();
                    if (name.equals(dateTag) && report == null && value.length() >= 10) {
                        report = new DayReport(LocalDate.parse(value.substring(0, 10)));
                    } else if (name.equals("FuelType") && unitTag.equals(parent)) {
                        fuel = Fuel.parse(value);
                    } else if (pointTag.equals(parent) && name.equals(hourTag)) {
                        hour = parseInt(value);
                    } else if (pointTag.equals(parent) && name.equals("EnergyMW")) {
                        mw = parseDouble(value);
                    } else if (name.equals(pointTag) && seriesTag.equals(parent) && path.contains(unitTag)) {
                        if (report != null && fuel != null && hour != null && mw != null && hour >= 1 && hour <= 24) {
                            report.add(hour, fuel, Math.max(0, mw));
                        }
                    }
                }
            }
        } finally {
            r.close();
        }
        return report == null ? new DayReport(null) : report;
    }

    /** DailyData(Day, HourlyData(Hour, FuelTotal(Fuel, EnergyValue(Output)))). */
    Map<LocalDate, DayReport> parseYearlyFuel(InputStream in) throws XMLStreamException {
        XMLStreamReader r = xml.createXMLStreamReader(in);
        Map<LocalDate, DayReport> out = new TreeMap<>();
        StringBuilder text = new StringBuilder();
        DayReport day = null;
        Integer hour = null;
        Fuel fuel = null;
        try {
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    text.setLength(0);
                    if (r.getLocalName().equals("FuelTotal")) fuel = null;
                } else if (ev == XMLStreamConstants.CHARACTERS || ev == XMLStreamConstants.CDATA) {
                    text.append(r.getText());
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    String name = r.getLocalName();
                    String value = text.toString().trim();
                    text.setLength(0);
                    switch (name) {
                        case "Day" -> {
                            if (value.length() >= 10) {
                                LocalDate d = LocalDate.parse(value.substring(0, 10));
                                day = out.computeIfAbsent(d, DayReport::new);
                            }
                        }
                        case "Hour" -> hour = parseInt(value);
                        case "Fuel" -> fuel = Fuel.parse(value);
                        case "Output" -> {
                            Double mw = parseDouble(value);
                            if (day != null && hour != null && fuel != null && mw != null && hour >= 1 && hour <= 24) {
                                day.add(hour, fuel, Math.max(0, mw));
                            }
                        }
                        default -> { }
                    }
                }
            }
        } finally {
            r.close();
        }
        return out;
    }

    /** DeliveryDate, then HourlyPriceComponents(PricingHour, ZonalPrice, ...) repeated per hour. */
    PriceDay parseHourlyPrice(InputStream in) throws XMLStreamException {
        XMLStreamReader r = xml.createXMLStreamReader(in);
        StringBuilder text = new StringBuilder();
        PriceDay day = null;
        Integer hour = null;
        Double price = null;
        try {
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    text.setLength(0);
                    if (r.getLocalName().equals("HourlyPriceComponents")) { hour = null; price = null; }
                } else if (ev == XMLStreamConstants.CHARACTERS || ev == XMLStreamConstants.CDATA) {
                    text.append(r.getText());
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    String value = text.toString().trim();
                    text.setLength(0);
                    switch (r.getLocalName()) {
                        case "DeliveryDate" -> { if (day == null && value.length() >= 10) day = new PriceDay(LocalDate.parse(value.substring(0, 10))); }
                        case "PricingHour" -> hour = parseInt(value);
                        case "ZonalPrice" -> price = parseDouble(value);
                        case "HourlyPriceComponents" -> {
                            if (day != null && hour != null && price != null && hour >= 1 && hour <= 24) day.hours().put(hour, price);
                        }
                        default -> { }
                    }
                }
            }
        } finally {
            r.close();
        }
        return day;
    }

    /**
     * DeliveryDate, DeliveryHour, then ZonalPrice(Interval, LmpCap, ...) x 12 and AveragePrice(LmpCap, ...).
     * Intervals not yet run are empty elements.
     */
    RealtimePrice parseRealtimePrice(InputStream in) throws XMLStreamException {
        XMLStreamReader r = xml.createXMLStreamReader(in);
        StringBuilder text = new StringBuilder();
        LocalDate date = null;
        Integer hour = null;
        Double average = null;
        double sum = 0;
        int n = 0;
        boolean inAverage = false;
        try {
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    text.setLength(0);
                    if (r.getLocalName().equals("AveragePrice")) inAverage = true;
                } else if (ev == XMLStreamConstants.CHARACTERS || ev == XMLStreamConstants.CDATA) {
                    text.append(r.getText());
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    String value = text.toString().trim();
                    text.setLength(0);
                    switch (r.getLocalName()) {
                        case "DeliveryDate" -> { if (date == null && value.length() >= 10) date = LocalDate.parse(value.substring(0, 10)); }
                        case "DeliveryHour" -> { if (hour == null) hour = parseInt(value); }
                        case "LmpCap" -> {
                            Double v = parseDouble(value);
                            if (v != null) {
                                if (inAverage) average = v;
                                else { sum += v; n++; }
                            }
                        }
                        case "AveragePrice" -> inAverage = false;
                        default -> { }
                    }
                }
            }
        } finally {
            r.close();
        }
        if (date == null || hour == null || hour < 1 || hour > 24 || n == 0) return null;
        return new RealtimePrice(date, hour, average != null ? average : sum / n, n);
    }

    /** CSV: Date,Hour,Market Demand,Ontario Demand (lines starting with backslashes are headers). */
    static Map<LocalDate, Map<Integer, Double>> parseDemand(InputStream in) throws IOException {
        Map<LocalDate, Map<Integer, Double>> out = new TreeMap<>();
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = br.readLine()) != null) {
            if (line.isEmpty() || !Character.isDigit(line.charAt(0))) continue;
            String[] p = line.split(",");
            if (p.length < 4) continue;
            try {
                LocalDate d = LocalDate.parse(p[0].trim());
                int h = Integer.parseInt(p[1].trim());
                double ontario = Double.parseDouble(p[3].trim());
                if (h >= 1 && h <= 24) out.computeIfAbsent(d, k -> new TreeMap<>()).put(h, ontario);
            } catch (RuntimeException ignored) {
                // skip malformed rows
            }
        }
        return out;
    }

    private static Integer parseInt(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return null; }
    }

    private static Double parseDouble(String s) {
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return null; }
    }
}
