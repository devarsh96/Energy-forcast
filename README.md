# Clean Hour Ontario

A live carbon-intensity dashboard for Ontario's electricity grid. It reads IESO's public reports,
converts the fuel mix into grams of CO₂e per kWh, and keeps about 3 months of hourly history.

**Dashboard** (`/`)
- Live intensity gauge for the latest IESO hour, with the fuel mix behind it
- "Who is setting the pace?": a rule-based read of whether gas is the marginal source right now
- Best 3-hour EV charging window in the next 24 hours
- Last 24 hours (actual) and next 24 hours (forecast)
- Actual vs. IESO day-ahead forecast (forecast drawn dashed), with forecast error
- Fuel mix stacked area (24 hours or 7 days) and a donut of today's fuel shares
- Today's emissions hour by hour, and the last 7 days by day
- Demand vs. gas output (same MW axis)
- 7-day × 24-hour heatmap and a 3-month calendar heatmap of daily intensity

**Carbon Calculator** (`/calculator.html`): EV charge, laundry, dishwasher and dryer with editable kWh and run time,
plus custom tasks. Shows the best start time, kg CO₂e saved versus starting now, and a driving-distance equivalent.

**Reports** (`/reports.html`)
- Monthly reports (`/monthly.html?month=YYYY-MM`) with a PDF download
- Raw hourly data table with date and fuel filters, sorting, and Excel download
- Data sources with last-fetched times
- Public read-only API docs

Spring Boot 3 (Java 21), plain HTML/JS front end, packaged with Docker. Sized for a 1 GB host
(heap capped at 256 MB, container limit 512 MB). No database: history is a small JSON file on a Docker volume.

## Run it

You only need Docker (no local Java or Maven).

```bash
docker compose up -d --build
```

Open http://localhost:8080. The live data appears about 10 seconds after start-up. On the first start the
app also loads 3 months of history in the background, which takes about a minute.
Use a different port with `HOST_PORT=8090 docker compose up -d --build` (PowerShell: `$env:HOST_PORT=8090; docker compose up -d --build`).

```bash
docker compose logs -f
docker compose down
```

History is kept in the `clean-hour-data` volume, so it survives restarts and rebuilds.
`docker compose down -v` deletes it.

## API

Public, read-only, no key, CORS open. Dates are Toronto local `YYYY-MM-DD`.

| Endpoint | Returns |
|---|---|
| `GET /api/v1/current` | Latest hour: intensity, band, fuel mix, gas indicator |
| `GET /api/v1/forecast` | Next 24 hours and the best 3-hour window |
| `GET /api/v1/hourly?from=&to=&fuels=` | Raw hourly rows (default last 7 days, max 100 days) |
| `GET /api/v1/hourly.xlsx?from=&to=&fuels=` | Same rows as an Excel workbook |
| `GET /api/v1/daily?from=&to=` | Daily totals: hours, tonnes CO₂e, MWh, intensity |
| `GET /api/v1/monthly/{YYYY-MM}` | Monthly report |
| `GET /api/v1/months` | Months with data |
| `GET /api/v1/sources` | Data sources, last fetched, coverage, emission factors |

Health check: `GET /actuator/health`.

## Data sources

| Report | Used for | Refresh |
|---|---|---|
| [GenOutputCapability](https://reports-public.ieso.ca/public/GenOutputCapability/) | Actual hourly output of every transmission-connected generator, with fuel type (live file + dated files for the last 7 days) | every 5 min |
| [GenOutputbyFuelHourly](https://reports-public.ieso.ca/public/GenOutputbyFuelHourly/) | Yearly file of hourly output by fuel, used to backfill 3 months | start-up, then every 12 h if gaps |
| [Adequacy3](https://reports-public.ieso.ca/public/Adequacy3/) | IESO's scheduled output by fuel per hour: day-ahead forecast and next-24-hour forecast | every 30 min; archive backfill every 12 h |
| [Demand](https://reports-public.ieso.ca/public/Demand/) | Hourly Ontario demand | every 30 min |

IESO publishes hours as "hour ending 1–24" in Eastern Standard Time all year. The app converts these to
real instants and the pages show Toronto local time.

## Method

**Intensity** for an hour = Σ(MW of fuel × emission factor) / Σ(MW of counted fuels).
**Emissions** (tonnes) for an hour = Σ(MW × factor) / 1000.

Emission factors are lifecycle medians from IPCC AR5 Working Group III (2014), Annex III, Table A.III.2:

| Fuel | g CO₂e/kWh | IPCC technology |
|---|---|---|
| Nuclear | 12 | Nuclear |
| Gas | 490 | Gas, combined cycle |
| Hydro | 24 | Hydropower |
| Wind | 11 | Wind onshore |
| Solar | 48 | Solar PV, utility scale |
| Biofuel | 230 | Biomass, dedicated |

They live in `src/main/resources/application.yml` (`cleanhour.emission-factors`).
Storage and "other" units are left out, since storage only shifts energy already counted.
Imports, exports and embedded (distribution-connected) generation are not in the IESO output reports.
Ontario's gas fleet includes simple-cycle and cogeneration plants, so 490 g/kWh (combined cycle) is a
slightly low estimate for gas.

**Day-ahead forecast.** IESO republishes the Adequacy3 report many times a day. For each delivery day the app
saves the last version published before that day began, converts it to g CO₂e/kWh, and compares it with
actual intensity. IESO's archive only keeps every version for roughly the last 45 days (older days keep just
the final, same-day version), so the forecast-vs-actual history starts there and grows from then on.

**Gas pace indicator.** Gas is Ontario's usual marginal source: nuclear runs flat, hydro and wind take what they
can, and gas follows the rest of demand. The rule compares current gas output with its range over the past
week (10th to 90th percentile):

- "Gas is setting the pace" when gas is in the upper half of that range, or at least 30% of the way up
  and rising by 150 MW or more since the last hour.
- "Gas is in the mix" when gas is at least 20% of the way up its range, or rising.
- "Clean power is setting the pace" otherwise.

Monthly "gas-led hours" use the same idea with the month's own gas range.

**Next-24-hour forecast and best windows (no ML).** For each hour: if IESO's latest Adequacy3 schedule covers it,
use its intensity shifted by the average actual-minus-scheduled gap over today's recent hours (up to 12);
otherwise use the average of that hour over the past 7 days. The EV window (3 hours) and calculator windows
(task run time) are the consecutive forecast hours with the lowest average.

**Calculator.** Energy is spread evenly over the run time. "Right now" uses the latest IESO hour, then the
forecast. The driving comparison assumes about 190 g CO₂/km for an average gasoline car.

**Monthly PDF.** Generated in the browser from the report page (html2canvas + jsPDF, loaded from cdnjs on click),
always in the light theme at a fixed width.

## Project layout

```
src/main/java/ca/cleanhour/
  ieso/IesoClient.java          streaming readers for the four IESO reports
  store/HourlyStore.java        in-memory hourly store, saved to JSON
  carbon/CarbonCalculator.java  fuel mix -> g CO2e/kWh and tonnes
  carbon/IngestService.java     scheduled live refresh and history backfill
  carbon/CarbonService.java     dashboard snapshot: gauge, gas pace, forecast, charts
  carbon/ReportService.java     hourly table, daily totals, sources, monthly report
  web/ApiController.java        public /api/v1 endpoints + Excel export
  web/DashboardController.java  /api/dashboard for the dashboard page
src/main/resources/static/      pages: index, calculator, reports, monthly (+ charts.js shared helpers)
Dockerfile, docker-compose.yml
```
