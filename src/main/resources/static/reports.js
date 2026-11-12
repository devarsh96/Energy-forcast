(() => {
  "use strict";
  const { fmt, FUELS, FUEL_NAME } = CH;
  const $ = (id) => document.getElementById(id);
  const PAGE = 50;

  let rows = [], cols = [], sortKey = "start", sortDir = -1, page = 0;

  // ---------------------------------------------------------------- months
  async function loadMonths() {
    try {
      const m = await CH.getJson("api/v1/months");
      const label = (s) => new Date(s + "-15T12:00:00Z").toLocaleString("en-CA", { month: "long", year: "numeric", timeZone: "UTC" });
      $("months").innerHTML = m.months.length
        ? m.months.map((s, i) => `<a class="btn${i === 0 ? "" : ""}" href="monthly.html?month=${s}">${label(s)}${i === 0 ? " (so far)" : ""}</a>`).join("")
        : `<span class="muted">No months available yet.</span>`;
    } catch (e) {
      $("months").innerHTML = `<span class="error">Could not load months (${e.message}).</span>`;
    }
  }

  // ---------------------------------------------------------------- table
  function initFilters() {
    const today = fmt.key(new Date());
    const weekAgo = fmt.key(new Date(Date.now() - 6 * 86400e3));
    $("from").value = weekAgo;
    $("to").value = today;
    $("to").max = today;
    $("fuels").innerHTML = FUELS.map((f) =>
      `<label><input type="checkbox" value="${f}" checked> ${FUEL_NAME[f]}</label>`).join("");
    $("filters").addEventListener("submit", (e) => { e.preventDefault(); loadTable(); });
    $("fuels").addEventListener("change", updateXlsxLink);
    $("from").addEventListener("change", updateXlsxLink);
    $("to").addEventListener("change", updateXlsxLink);
    $("prev").addEventListener("click", () => { page = Math.max(0, page - 1); renderTable(); });
    $("next").addEventListener("click", () => { page = Math.min(pages() - 1, page + 1); renderTable(); });
    updateXlsxLink();
  }

  function query() {
    const fuels = [...$("fuels").querySelectorAll("input:checked")].map((i) => i.value);
    const p = new URLSearchParams({ from: $("from").value, to: $("to").value });
    if (fuels.length) p.set("fuels", fuels.join(","));
    return p.toString();
  }
  function updateXlsxLink() { $("xlsx").href = "api/v1/hourly.xlsx?" + query(); }

  async function loadTable() {
    updateXlsxLink();
    $("count").textContent = "Loading…";
    try {
      const d = await CH.getJson("api/v1/hourly?" + query());
      const fuels = d.fuels;
      cols = [
        { key: "start", label: "Time", get: (r) => r.start, show: (r) => `${fmt.day(new Date(r.start))}, ${fmt.time(new Date(r.start))}` },
        ...FUELS.filter((f) => fuels.includes(f)).map((f) => ({ key: f, label: `${FUEL_NAME[f]} MW`, get: (r) => r.fuelMw[f], show: (r) => num(r.fuelMw[f]) })),
        { key: "totalMw", label: "Total MW", get: (r) => r.totalMw, show: (r) => num(r.totalMw) },
        { key: "demandMw", label: "Demand MW", get: (r) => r.demandMw, show: (r) => num(r.demandMw) },
        { key: "intensity", label: "g CO₂e/kWh", get: (r) => r.intensity, show: (r) => num(r.intensity) },
        { key: "dayAheadIntensity", label: "Day-ahead g/kWh", get: (r) => r.dayAheadIntensity, show: (r) => num(r.dayAheadIntensity) },
        { key: "tonnesCo2", label: "t CO₂e", get: (r) => r.tonnesCo2, show: (r) => num(r.tonnesCo2) },
      ];
      rows = d.rows;
      page = 0;
      renderTable();
    } catch (e) {
      rows = []; cols = [];
      $("table").querySelector("thead").innerHTML = "";
      $("table").querySelector("tbody").innerHTML = "";
      $("count").innerHTML = `<span class="error">${e.message}</span>`;
    }
  }

  const num = (v) => (v == null ? "–" : fmt.n0(v));
  const pages = () => Math.max(1, Math.ceil(rows.length / PAGE));

  function renderTable() {
    const col = cols.find((c) => c.key === sortKey) || cols[0];
    const sorted = rows.slice().sort((a, b) => {
      const x = col.get(a), y = col.get(b);
      if (x == null && y == null) return 0;
      if (x == null) return 1;
      if (y == null) return -1;
      return (x < y ? -1 : x > y ? 1 : 0) * sortDir;
    });
    const thead = $("table").querySelector("thead"), tbody = $("table").querySelector("tbody");
    thead.innerHTML = "<tr>" + cols.map((c) =>
      `<th scope="col" data-k="${c.key}" tabindex="0" aria-sort="${c.key === sortKey ? (sortDir > 0 ? "ascending" : "descending") : "none"}">${c.label}</th>`).join("") + "</tr>";
    const slice = sorted.slice(page * PAGE, page * PAGE + PAGE);
    tbody.innerHTML = slice.map((r) => "<tr>" + cols.map((c) => `<td>${c.show(r)}</td>`).join("") + "</tr>").join("") ||
      `<tr><td colspan="${cols.length}">No data for this range.</td></tr>`;
    thead.querySelectorAll("th").forEach((th) => {
      const go = () => {
        const k = th.dataset.k;
        if (sortKey === k) sortDir = -sortDir; else { sortKey = k; sortDir = k === "start" ? -1 : -1; }
        page = 0; renderTable();
      };
      th.addEventListener("click", go);
      th.addEventListener("keydown", (e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); go(); } });
    });
    $("count").textContent = `${fmt.n0(rows.length)} hours`;
    $("page").textContent = `Page ${page + 1} of ${pages()}`;
    $("prev").disabled = page === 0;
    $("next").disabled = page >= pages() - 1;
  }

  // ---------------------------------------------------------------- sources
  async function loadSources() {
    try {
      const s = await CH.getJson("api/v1/sources");
      const ago = (iso) => {
        if (!iso) return "not fetched yet";
        const t = new Date(iso), mins = Math.round((Date.now() - t) / 60000);
        const when = `${fmt.day(t)}, ${fmt.time(t)}`;
        return mins < 1 ? `${when} (just now)` : mins < 120 ? `${when} (${mins} min ago)` : when;
      };
      $("sources").innerHTML = s.sources.map((x) =>
        `<div class="source"><h3>${x.title}</h3><p>${x.usedFor}</p>` +
        `<p class="meta">Last fetched: ${ago(x.lastUpdated)}<br>${x.refresh}</p>` +
        `<p class="meta"><a href="${x.url}" target="_blank" rel="noopener">${x.url.replace("https://", "")}</a></p></div>`).join("");
      const c = s.coverage;
      if (c.first) {
        $("coverage").textContent = `History: ${fmt.day(fmt.isoDay(c.first))} to ${fmt.day(fmt.isoDay(c.last))}` +
          (c.backfillRunning ? " · loading older data…" : "");
        $("from").min = c.first;
      }
    } catch (e) {
      $("sources").innerHTML = `<span class="error">Could not load sources (${e.message}).</span>`;
    }
  }

  // ---------------------------------------------------------------- API docs
  function renderApi() {
    const base = location.origin + location.pathname.replace(/[^/]*$/, "") + "api/v1";
    const eps = [
      ["/current", "The latest hour: intensity, band, fuel mix and the gas indicator.", "/current"],
      ["/forecast", "The next 24 hours of forecast intensity and the best 3-hour window.", "/forecast"],
      ["/hourly?from=&to=&fuels=", "Raw hourly rows. <code>from</code>/<code>to</code> default to the last 7 days; <code>fuels</code> is a comma list (NUCLEAR, GAS, HYDRO, WIND, SOLAR, BIOFUEL, OTHER). Up to 100 days.", "/hourly?from=2026-11-01&to=2026-11-07&fuels=GAS,HYDRO"],
      ["/hourly.xlsx?from=&to=&fuels=", "The same rows as an Excel workbook.", "/hourly.xlsx?from=2026-11-01&to=2026-11-07"],
      ["/daily?from=&to=", "Totals per day: hours, tonnes CO₂e, MWh and average intensity.", "/daily?from=2026-10-01&to=2026-10-31"],
      ["/monthly/{YYYY-MM}", "The monthly report as JSON.", "/monthly/2026-10"],
      ["/months", "Months that have data.", "/months"],
      ["/sources", "Data sources, when each was last fetched, history coverage and emission factors.", "/sources"],
    ];
    $("endpoints").innerHTML = eps.map(([path, desc, ex]) =>
      `<div class="endpoint"><h3><span class="method">GET</span><code>/api/v1${path}</code></h3><p>${desc}</p>` +
      `<pre class="code">curl "${base}${ex}"</pre></div>`).join("");
  }

  initFilters();
  renderApi();
  loadMonths();
  loadSources();
  loadTable();
})();
