# Clean Hour Ontario

A live carbon-intensity dashboard for Ontario's electricity grid. It reads IESO's public reports,
converts the fuel mix into grams of CO₂e per kWh, and keeps about 3 months of hourly history.

**Dashboard** (`/`), top to bottom
- Top row: carbon intensity right now (gauge, with a coloured gas-level dot), your price right now on the plan you
  picked (with the IESO grid price as one line), and the best 3-hour EV charging window with its cost
- Last 24 hours and the next 24, with a Carbon / Price switch. Price view: your plan's price by hour, the grid price
  and the carbon forecast on one chart, with the cheapest charge boxed
- Plan comparison for your EV (cost per charge and per month on each plan) next to today's rate periods
- Fuel mix stacked area (24 hours or 7 days) and a donut of today's fuel shares
- Actual vs. IESO day-ahead forecast (forecast drawn dashed), with forecast error
- Today's emissions hour by hour, and the last 7 days by day
- Demand vs. gas output (same MW axis)
- 7-day × 24-hour heatmap and a 3-month calendar, each with a Carbon / Price switch

**Savings Calculator** (`/calculator.html`)
- EV charge planner: battery, charge now/to %, charger kW and a ready-by time give the cheapest, cleanest and
  best-balance start times (merged into one card when they agree), with cost, savings vs. now and vs. on-peak, and kg CO₂e
- Household tasks (laundry, dishwasher, dryer, custom) with editable kWh and run time: best start time, kg CO₂e and cost saved
- Side charts that stay in view while scrolling: carbon for the next 24 hours, and your plan's price with an optional
  grid price line, both highlighting the selected charge or task

**Plan selector** (top bar, every page): pick Ultra-Low Overnight, Time-of-Use or Tiered. The dropdown also holds the
optional full-bill estimate (delivery charge, HST and the Ontario Electricity Rebate). All costs on the Dashboard,
Savings Calculator and monthly report follow it. Settings are kept in the browser.

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
| `GET /api/v1/prices` | Rate plans, price now, next 24 h of plan prices + grid price + carbon, grid price history |
| `GET /api/v1/charge-plan?kwh=&kw=&plan=&readyBy=&overTier=` | Cheapest, cleanest and balanced charging windows, plus the cheapest cost on each plan |
| `GET /api/v1/sources` | Data sources, last fetched, coverage, emission factors |

Health check: `GET /actuator/health`.

## Data sources

| Report | Used for | Refresh |
|---|---|---|
| [GenOutputCapability](https://reports-public.ieso.ca/public/GenOutputCapability/) | Actual hourly output of every transmission-connected generator, with fuel type (live file + dated files for the last 7 days) | every 5 min |
| [GenOutputbyFuelHourly](https://reports-public.ieso.ca/public/GenOutputbyFuelHourly/) | Yearly file of hourly output by fuel, used to backfill 3 months | start-up, then every 12 h if gaps |
| [Adequacy3](https://reports-public.ieso.ca/public/Adequacy3/) | IESO's scheduled output by fuel per hour: day-ahead forecast and next-24-hour forecast | every 30 min; archive backfill every 12 h |
| [Demand](https://reports-public.ieso.ca/public/Demand/) | Hourly Ontario demand | every 30 min |
| [RealtimeOntarioZonalPrice](https://reports-public.ieso.ca/public/RealtimeOntarioZonalPrice/) | 5-minute real-time grid price, averaged per hour | every 5 min |
| [PredispHourlyOntarioZonalPrice](https://reports-public.ieso.ca/public/PredispHourlyOntarioZonalPrice/) | Forecast grid price for the rest of today | every 30 min |
| [DAHourlyOntarioZonalPrice](https://reports-public.ieso.ca/public/DAHourlyOntarioZonalPrice/) | Day-ahead grid price for tomorrow, and the 3-month price history | every 30 min; backfill every 12 h |
| [OEB electricity rates](https://www.oeb.ca/consumer-information-and-protection/electricity-rates) | Household RPP prices (ULO, TOU, Tiered) | kept in `application.yml`, update each Nov 1 |

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

**Gas level dot.** Gas is Ontario's usual marginal source: nuclear runs flat, hydro and wind take what they
can, and gas follows the rest of demand. Gas ramps up every day, so comparing it only with its weekly range would
flag almost every waking hour. The dot uses two tests: gas's share of supply now, and where it sits in its range over
the past week (10th to 90th percentile):

- Green (low): under 15% of supply, or in the bottom fifth of the week's range.
- Red (high): over 30% of supply and in the top fifth of the week's range.
- Amber (moderate): everything in between.

The line next to the dot gives the share and whether gas is ramping up, easing off or steady (a change of 150 MW or
more since the last hour, or 300 MW or more scheduled for the next hour). Hover for the detail.

Monthly "gas-led hours" use a simpler range test against the month's own gas range.

**Next-24-hour forecast and best windows (no ML).** For each hour: if IESO's latest Adequacy3 schedule covers it,
use its intensity shifted by the average actual-minus-scheduled gap over today's recent hours (up to 12);
otherwise use the average of that hour over the past 7 days. The EV window (3 hours) and calculator windows
(task run time) are the consecutive forecast hours with the lowest average.

**Calculator.** Energy is spread evenly over the run time. "Right now" uses the latest IESO hour, then the
forecast. The driving comparison assumes about 190 g CO₂/km for an average gasoline car.

**Household prices.** `cleanhour.pricing.rate-sets` in `application.yml` holds one block per OEB price change
(commodity ¢/kWh and the Ontario Electricity Rebate %). Add a block each November 1; older blocks keep the
history priced correctly. `Tariff` applies the OEB rules on the local clock:

- Ultra-Low Overnight: 11 pm–7 am every day; weekdays mid-peak 7 am–4 pm and 9–11 pm, on-peak 4–9 pm;
  weekends and holidays 7 am–11 pm weekend off-peak.
- Time-of-Use: off-peak weekdays 7 pm–7 am and all day on weekends and holidays. Winter (Nov–Apr) on-peak 7–11 am and
  5–7 pm with mid-peak 11 am–5 pm; summer swaps them.
- Tiered: an EV charge is priced at tier 1 unless the home is already over the monthly threshold.
- Holidays: the OEB list (including Civic Holiday). One that falls on a weekend moves to the next weekday.

**Grid price.** IESO's Ontario Zonal Price replaced HOEP with Market Renewal (May 2025). Past hours use the
real-time hourly average where the app has it, otherwise day-ahead. Upcoming hours use pre-dispatch (today), then
day-ahead (tomorrow). It is a wholesale price that RPP households don't pay directly, so the UI keeps it apart from "your price".

**Charge planner (no ML).** Energy from the wall = battery × (target − now) ÷ 0.9. A charge starts at the top of an
hour and runs at full power, and the last hour can be partial. Every start time that finishes by the ready-by time is
priced (kWh × plan price) and carbon-scored (kWh × forecast g/kWh). Cheapest and cleanest are the minimums. Balanced
minimises 0.5 × cost + 0.5 × CO₂, each scaled 0–1 across the candidates. Full-bill estimate =
(price + delivery) × (1 + HST − OER). It doesn't change which window wins.

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
  pricing/Tariff.java           OEB rate plans: price of plan P at time T, holidays, seasons
  pricing/ChargePlanner.java    cheapest / cleanest / balanced charging window (pure, unit-tested)
  pricing/PricingService.java   pricing snapshot: price now, next 24 h, grid price history, EV costs
  web/PricingController.java    /api/v1/prices and /api/v1/charge-plan
src/main/resources/static/      pages: index, calculator, pricing, reports, monthly (+ charts.js shared helpers)
Dockerfile, docker-compose.yml
```
