/* Extra dashboard charts: forecast vs actual, fuel mix area, donut, today/week totals, demand vs gas, calendar. */
(() => {
  "use strict";
  const { fmt, FUELS, FUEL_NAME } = CH;
  const $ = (id) => document.getElementById(id);
  const fuelColor = (f) => `var(--f-${f.toLowerCase()})`;
  let last = null;
  let mixRange = "24h";

  function renderForecastVsActual(d) {
    const c = d.charts;
    const pts = c.intensityLine.map((p) => ({ t: new Date(p.t), values: { actual: p.actual, dayAhead: p.dayAhead } }));
    $("fva-legend").innerHTML = CH.legend([
      { label: "Actual", color: "var(--f-nuclear)" },
      { label: "IESO day-ahead forecast", color: "var(--f-gas)", dash: true },
    ]);
    CH.timeChart($("fva-chart"), {
      label: "Actual vs day-ahead forecast carbon intensity",
      points: pts,
      nowT: new Date(new Date(d.current.start).getTime() + 3600e3),
      series: [
        { key: "actual", name: "Actual", color: "var(--f-nuclear)" },
        { key: "dayAhead", name: "Day-ahead", color: "var(--f-gas)", dash: true },
      ],
      yFormat: (v) => fmt.n0(v),
      tooltip: (p) => {
        const a = p.values.actual, f = p.values.dayAhead;
        const end = new Date(p.t.getTime() + 3600e3);
        let h = `<div class="tt-s">${fmt.day(p.t)}, ${fmt.time(p.t)}–${fmt.time(end)}</div>`;
        if (a != null) h += `<div><b>${fmt.n0(a)}</b> g/kWh actual</div>`;
        if (f != null) h += `<div><b>${fmt.n0(f)}</b> g/kWh forecast</div>`;
        if (a != null && f != null) h += `<div class="tt-s">Forecast was ${f >= a ? "+" : "−"}${fmt.n0(Math.abs(f - a))} off</div>`;
        return h;
      },
    });
    const acc = c.accuracy;
    $("fva-kpis").innerHTML = acc
      ? `<div><dt>Typical error</dt><dd>±${fmt.n0(acc.meanAbsError)} g/kWh</dd></div>` +
        `<div><dt>Error as % of actual</dt><dd>${fmt.n1(acc.meanAbsPctError)}%</dd></div>` +
        `<div><dt>Forecast bias</dt><dd>${acc.meanError >= 0 ? "+" : "−"}${fmt.n0(Math.abs(acc.meanError))} g/kWh</dd></div>` +
        `<div><dt>Hours compared</dt><dd>${fmt.n0(acc.hours)}</dd></div>`
      : `<div><dt>Accuracy</dt><dd>Waiting for forecasts to load</dd></div>`;
  }

  function renderMix(d) {
    const src = mixRange === "24h" ? d.charts.mix24h : d.charts.mix7d;
    const present = FUELS.filter((f) => src.some((p) => (p.mw[f] || 0) > 0));
    CH.timeChart($("mix-chart"), {
      label: "Fuel mix stacked area",
      points: src.map((p) => ({ t: new Date(p.t), values: p.mw })),
      series: present.map((f) => ({ key: f, name: FUEL_NAME[f], color: fuelColor(f), area: true })),
      yFormat: (v) => fmt.n0(v / 1000) + "k",
      tooltip: (p) => {
        const total = present.reduce((a, f) => a + (p.values[f] || 0), 0);
        const rows = present.slice().reverse().map((f) =>
          `<div><span class="sw" style="background:${fuelColor(f)}"></span> ${FUEL_NAME[f]} <b>${fmt.n0(p.values[f] || 0)}</b> MW</div>`).join("");
        return `<div class="tt-s">${fmt.day(p.t)}, ${fmt.time(p.t)}</div>${rows}<div class="tt-s">Total ${fmt.n0(total)} MW</div>`;
      },
    });
    $("mix-area-legend").innerHTML = CH.legend(present.map((f) => ({ label: FUEL_NAME[f], color: fuelColor(f) })));
  }

  function renderDonut(d) {
    const shares = d.charts.todayShares.slice().sort((a, b) => FUELS.indexOf(a.fuel) - FUELS.indexOf(b.fuel));
    const totalMwh = shares.reduce((a, s) => a + s.mw, 0);
    const lowCarbon = shares.filter((s) => s.fuel !== "GAS" && s.fuel !== "OTHER").reduce((a, s) => a + s.share, 0);
    $("donut-title").textContent = d.charts.todayHourly.length >= 3 ? "Today's fuel shares" : "Fuel shares, last 24 hours";
    CH.donut($("donut"), {
      label: "Fuel shares",
      centerValue: Math.round(lowCarbon * 100) + "%",
      centerLabel: "low-carbon",
      slices: shares.map((s) => ({
        v: s.mw, color: fuelColor(s.fuel),
        tip: `<div class="tt-s">${FUEL_NAME[s.fuel]}</div><div class="tt-v">${fmt.n1(s.share * 100)}%</div><div class="tt-s">${fmt.n0(s.mw)} MWh</div>`,
      })),
    });
    $("donut-legend").innerHTML = shares.map((s) =>
      `<li><span class="sw" style="background:${fuelColor(s.fuel)}"></span>${FUEL_NAME[s.fuel]} <b>${Math.round(s.share * 100)}%</b></li>`).join("") +
      `<li class="legend-note">${fmt.n0(totalMwh)} MWh generated</li>`;
  }

  function renderToday(d) {
    const hrs = d.charts.todayHourly;
    CH.barChart($("today-chart"), {
      label: "Emissions by hour today",
      slots: 24,
      color: "var(--f-nuclear)",
      bars: hrs.map((h) => {
        const t = new Date(h.t), end = new Date(t.getTime() + 3600e3);
        return {
          label: fmt.hour(t), v: h.tonnes,
          tip: `<div class="tt-s">${fmt.time(t)}–${fmt.time(end)}</div><div class="tt-v">${fmt.n0(h.tonnes)} t CO₂e</div>` +
            `<div class="tt-s">${fmt.n0(h.mwh)} MWh · ${fmt.n0(h.intensity)} g/kWh</div>`,
        };
      }),
      yFormat: (v) => fmt.n0(v) + " t",
    });
    const tot = hrs.reduce((a, h) => a + h.tonnes, 0), mwh = hrs.reduce((a, h) => a + h.mwh, 0);
    $("today-summary").textContent = hrs.length
      ? `${fmt.n0(tot)} tonnes CO₂e from ${fmt.n0(mwh)} MWh over ${hrs.length} hour${hrs.length === 1 ? "" : "s"} so far today (${fmt.n0(tot * 1000 / mwh)} g/kWh on average).`
      : "No complete hours yet today.";
  }

  function renderWeek(d) {
    const days = d.charts.weekByDay;
    CH.barChart($("week-chart"), {
      label: "Emissions by day, last 7 days",
      color: "var(--f-nuclear)",
      bars: days.map((x) => {
        const dt = fmt.isoDay(x.date);
        return {
          label: fmt.day(dt).split(",")[0], v: x.tonnes,
          tip: `<div class="tt-s">${fmt.day(dt)}${x.hours < 24 ? ` (${x.hours} h so far)` : ""}</div>` +
            `<div class="tt-v">${fmt.n0(x.tonnes / 1000)}k t CO₂e</div><div class="tt-s">${fmt.n0(x.mwh / 1000)} GWh · ${fmt.n0(x.intensity)} g/kWh</div>`,
        };
      }),
      yFormat: (v) => fmt.n0(v / 1000) + "k t",
    });
    const full = days.filter((x) => x.hours >= 24);
    if (full.length) {
      const best = full.reduce((a, b) => (b.intensity < a.intensity ? b : a));
      const worst = full.reduce((a, b) => (b.intensity > a.intensity ? b : a));
      $("week-summary").textContent = `Cleanest full day: ${fmt.day(fmt.isoDay(best.date))} (${fmt.n0(best.intensity)} g/kWh). ` +
        `Dirtiest: ${fmt.day(fmt.isoDay(worst.date))} (${fmt.n0(worst.intensity)} g/kWh). Today is partial.`;
    }
  }

  function renderDemandGas(d) {
    const pts = d.charts.demandGas.map((p) => ({ t: new Date(p.t), values: { demand: p.demand, gas: p.gas } }));
    $("dg-legend").innerHTML = CH.legend([
      { label: "Ontario demand", color: "var(--f-nuclear)" },
      { label: "Gas output", color: "var(--f-gas)" },
    ]);
    CH.timeChart($("dg-chart"), {
      label: "Ontario demand and gas output",
      points: pts,
      series: [
        { key: "demand", name: "Demand", color: "var(--f-nuclear)" },
        { key: "gas", name: "Gas", color: "var(--f-gas)" },
      ],
      yFormat: (v) => fmt.n0(v / 1000) + "k MW",
      tooltip: (p) => `<div class="tt-s">${fmt.day(p.t)}, ${fmt.time(p.t)}</div>` +
        (p.values.demand != null ? `<div>Demand <b>${fmt.n0(p.values.demand)}</b> MW</div>` : "") +
        `<div>Gas <b>${fmt.n0(p.values.gas)}</b> MW</div>`,
    });
    // Correlation between demand and gas shows how closely gas follows demand.
    const pairs = d.charts.demandGas.filter((p) => p.demand != null);
    if (pairs.length > 10) {
      const n = pairs.length;
      const mx = pairs.reduce((a, p) => a + p.demand, 0) / n, my = pairs.reduce((a, p) => a + p.gas, 0) / n;
      let sxy = 0, sxx = 0, syy = 0;
      pairs.forEach((p) => { sxy += (p.demand - mx) * (p.gas - my); sxx += (p.demand - mx) ** 2; syy += (p.gas - my) ** 2; });
      const r = sxy / Math.sqrt(sxx * syy), slope = sxy / sxx;
      $("dg-summary").textContent = `Over the last 7 days, every extra 1,000 MW of demand came with about ${fmt.n0(slope * 1000)} MW more gas ` +
        `(correlation ${fmt.n1(r)}). Both are in MW on the same axis.`;
    }
  }

  function renderCalendar(d) {
    const days = d.charts.calendar;
    const res = CH.calendar($("calendar"), {
      label: "Daily average carbon intensity",
      days: days.map((x) => ({
        date: x.date, v: x.intensity,
        tip: `<div class="tt-s">${fmt.day(fmt.isoDay(x.date))}${x.hours < 24 ? ` (${x.hours} h)` : ""}</div>` +
          `<div class="tt-v">${fmt.n0(x.intensity)} g/kWh</div><div class="tt-s">${fmt.n0(x.tonnes / 1000)}k t CO₂e</div>`,
      })),
    });
    if (res) $("cal-legend").innerHTML = CH.rampLegend(res.lo, res.hi);
    const full = days.filter((x) => x.hours >= 20);
    if (full.length) {
      const best = full.reduce((a, b) => (b.intensity < a.intensity ? b : a));
      const worst = full.reduce((a, b) => (b.intensity > a.intensity ? b : a));
      $("cal-summary").textContent = `${days.length} days loaded. Cleanest: ${fmt.day(fmt.isoDay(best.date))} (${fmt.n0(best.intensity)} g/kWh). ` +
        `Dirtiest: ${fmt.day(fmt.isoDay(worst.date))} (${fmt.n0(worst.intensity)} g/kWh).` +
        (days.length < 60 ? " Older history is still loading from IESO archives." : "");
    }
  }

  window.renderExtras = (d) => {
    last = d;
    if (!d.charts) return;
    renderForecastVsActual(d);
    renderMix(d);
    renderDonut(d);
    renderToday(d);
    renderWeek(d);
    renderDemandGas(d);
    renderCalendar(d);
  };

  document.querySelectorAll(".seg button[data-range]").forEach((b) => b.addEventListener("click", () => {
    mixRange = b.dataset.range;
    document.querySelectorAll(".seg button[data-range]").forEach((x) => x.setAttribute("aria-pressed", String(x === b)));
    if (last) renderMix(last);
  }));
  CH.onResize(() => last && window.renderExtras(last));
})();
