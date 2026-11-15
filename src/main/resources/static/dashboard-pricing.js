/* Dashboard pricing: your price card, the price view of the 24-hour chart, plan comparison, rate periods,
   grid price heatmap and calendar, plus the EV cost line. The plan selector lives in the nav. */
(() => {
  "use strict";
  const { fmt, tip, rampColor } = CH;
  const $ = (id) => document.getElementById(id);

  let s = CHP.load();
  let snap = null;          // /api/v1/prices
  let plan = null;          // /api/v1/charge-plan

  const esc = (t) => String(t).replace(/&/g, "&amp;").replace(/</g, "&lt;");
  const dayWord = (d) => {
    const today = fmt.key(new Date()), tomorrow = fmt.key(new Date(Date.now() + 86400e3)), k = fmt.key(d);
    return k === today ? "today" : k === tomorrow ? "tomorrow" : fmt.day(d);
  };
  const c = (v) => CHP.cents(v, s, snap);
  const unit = () => (s.allIn ? "¢ per kWh, full-bill estimate" : "¢ per kWh, electricity only");

  CHP.onChange((next) => {
    const planChanged = next.plan !== s.plan || next.overTier !== s.overTier;
    s = next;
    renderAll();
    if (planChanged) requestPlan();
  });

  // ---------------------------------------------------------------- Carbon / Price switches
  const VIEW_KEY = "cleanhour.dashviews.v1";
  let views = {};
  try { views = JSON.parse(localStorage.getItem(VIEW_KEY)) || {}; } catch (_) { views = {}; }
  document.querySelectorAll(".card.switchable").forEach((card, i) => {
    const set = (view, save) => {
      card.dataset.view = view;
      card.querySelectorAll("[data-pane]").forEach((p) => { p.hidden = p.dataset.pane !== view; });
      card.querySelectorAll(".seg button[data-view]").forEach((b) => b.setAttribute("aria-pressed", String(b.dataset.view === view)));
      if (save) {
        views[i] = view;
        try { localStorage.setItem(VIEW_KEY, JSON.stringify(views)); } catch (_) { /* private mode */ }
        window.dispatchEvent(new Event("resize"));   // charts drawn while hidden have no width
      }
    };
    card.querySelectorAll(".seg button[data-view]").forEach((b) => b.addEventListener("click", () => set(b.dataset.view, true)));
    if (views[i] === "price") set("price", false);
  });

  // ---------------------------------------------------------------- your price card
  function renderNow() {
    const n = snap.now[s.plan];
    $("plan-price").textContent = CHP.c1(c(n.cents));
    $("plan-unit").textContent = unit();
    const chip = $("plan-period");
    chip.textContent = n.label;
    chip.style.setProperty("--chip", CHP.PERIOD_COLOR[n.period] || "var(--muted)");
    if (n.nextChange) {
      const t = new Date(n.nextChange);
      $("plan-next").innerHTML = `Until ${fmt.time(t)}${dayWord(t) === "today" ? "" : " " + dayWord(t)}, then <strong>${esc(n.nextLabel)} ${CHP.c1(c(n.nextCents))}¢</strong>.`;
    } else {
      $("plan-next").textContent = s.plan === "TIERED" ? "The same price all day. It goes up once your home passes the monthly tier-1 limit." : "";
    }
    $("plan-title").textContent = `${CHP.PLAN_NAME[s.plan]} plan, OEB prices effective ${fmt.date(fmt.isoDay(snap.rates.effective))}.`;

    const g = snap.grid, gl = $("grid-line");
    if (g.cents == null) { gl.textContent = "Grid price: waiting for IESO's price reports."; return; }
    const lv = { low: ["low for this week", "var(--good)"], typical: ["typical for this week", "var(--warning)"], high: ["high for this week", "var(--critical)"] }[g.level] || ["", "var(--muted)"];
    gl.innerHTML = `<span class="gl-dot" style="background:${lv[1]}"></span>Grid price <strong>${CHP.c1(g.cents)}¢</strong> wholesale, ${lv[0]}`;
    gl.onmousemove = (e) => tip.show(e,
      `<div class="tt-s">IESO ${esc(g.source)} Ontario Zonal Price, ${fmt.time(new Date(g.hour))}</div>` +
      `<div class="tt-v">${CHP.c1(g.cents)}¢/kWh · ${fmt.n0(g.dollarsPerMwh)} $/MWh</div>` +
      (g.avg7d == null ? "" : `<div class="tt-s">7-day average ${CHP.c1(g.avg7d)}¢ · typical ${CHP.c1(g.low7d)}–${CHP.c1(g.high7d)}¢</div>`) +
      `<div class="tt-s">Wholesale, not what your plan charges.</div>`);
    gl.onmouseleave = tip.hide;
  }

  // ---------------------------------------------------------------- EV cost line
  function renderEvCost() {
    const el = $("ev-cost"), ev = snap.evWindowCost && snap.evWindowCost[s.plan];
    if (!el || !ev) return;
    const cost = CHP.dollars(ev.cost, ev.kwh, s, snap), peak = CHP.dollars(ev.peakCost, ev.kwh, s, snap);
    const peakWord = s.plan === "TIERED" ? "tier 2" : "on-peak";
    el.innerHTML = `On ${CHP.PLAN_SHORT[s.plan]} this ${CH.fmt.n1(ev.kwh)} kWh charge costs about <strong>${CHP.money(cost)}</strong>` +
      (peak - cost >= 0.01 ? `, ${CHP.money(peak - cost)} less than at ${peakWord}` : "") +
      `. <a href="calculator.html">Plan your charge →</a>`;
  }

  function renderStrip() {
    const hours = snap.next24;
    const w = plan && plan.result ? (plan.result.cheapest || plan.result.now) : null;
    CHP.hourChart($("strip"), {
      label: "Next 24 hours: price on your plan, grid price and carbon intensity",
      bars: hours.map((h) => {
        const t = new Date(h.start), e2 = new Date(t.getTime() + 3600e3), pp = h.plans[s.plan];
        return {
          start: h.start, v: c(pp.cents), color: CHP.PERIOD_COLOR[pp.period] || "var(--f-nuclear)",
          tip: `<div class="tt-s">${fmt.day(t)}, ${fmt.time(t)}–${fmt.time(e2)}</div>` +
            `<div class="tt-v">${CHP.c1(c(pp.cents))}¢/kWh</div>` +
            `<div class="tt-s">${CHP.PLAN_SHORT[s.plan]} ${pp.period.replace(/-/g, " ")}${s.allIn ? " · full-bill estimate" : ""}</div>` +
            `<div class="tt-s">Grid ${h.gridCents == null ? "–" : CHP.c1(h.gridCents) + "¢ (" + h.gridSource + ")"}</div>` +
            `<div class="tt-s">Carbon ${h.intensity == null ? "–" : fmt.n0(h.intensity) + " g/kWh"}</div>`,
        };
      }),
      lines: [
        { values: hours.map((h) => h.gridCents), color: "var(--ink-2)", dash: true },
        { values: hours.map((h) => h.intensity), color: "var(--f-gas)", right: true },
      ],
      yFormat: (v) => fmt.n0(v) + "¢", rightUnit: "g/kWh",
      window: w, windowLabel: "Cheapest charge",
      height: $("strip").clientWidth < 520 ? 220 : 260,
    });
    $("strip-title").textContent = `Next 24 hours: ${CHP.PLAN_NAME[s.plan]} price, the grid price and carbon`;
    const periods = [...new Set(hours.map((h) => h.plans[s.plan].period))];
    $("strip-legend").innerHTML = periods.map((p) => `<li><span class="sw" style="background:${CHP.PERIOD_COLOR[p]}"></span>${p.replace(/-/g, " ").replace(/^./, (x) => x.toUpperCase())}</li>`).join("") +
      `<li><span class="sw sw-dash" style="border-color:var(--ink-2)"></span>Grid price</li>` +
      `<li><span class="sw" style="background:var(--f-gas);height:3px"></span>Carbon (right axis)</li>`;
    $("strip-basis").innerHTML = "Bars: what your plan charges each hour. Dashed line: IESO wholesale price (pre-dispatch today, day-ahead tomorrow). Orange line: forecast g CO₂e/kWh. " +
      (w ? `The box marks the cheapest time for your EV charge (${fmt.n1(plan.result.kwh)} kWh). <a href="calculator.html">Plan it in the Savings Calculator →</a>` : "");
  }

  // ---------------------------------------------------------------- planner
  let planTimer = null, planSeq = 0;
  function requestPlan() {
    clearTimeout(planTimer);
    planTimer = setTimeout(fetchPlan, 250);
  }
  async function fetchPlan() {
    if (!snap) return;
    const seq = ++planSeq;
    try {
      const d = await CHP.chargePlan(s, snap);
      if (seq !== planSeq) return;
      plan = d;
    } catch (_) {
      plan = null;
    }
    renderStrip(); renderPlanTable();
  }

  function renderPlanTable() {
    const tb = document.querySelector("#plan-table tbody");
    if (!plan || !plan.cheapestByPlan) {
      tb.innerHTML = "";
      $("plan-table-note").innerHTML = `Set up your EV in the <a href="calculator.html">Savings Calculator</a> to compare plans.`;
      return;
    }
    const kwh = plan.result.kwh, perMonth = (Number(s.perWeek) || 0) * 52 / 12;
    const rows = Object.entries(plan.cheapestByPlan).map(([p, w]) => ({ p, cost: CHP.dollars(w.cost, kwh, s, snap), w }));
    const best = Math.min(...rows.map((r) => r.cost));
    tb.innerHTML = rows.map((r) => `<tr${r.p === s.plan ? ' class="current"' : ""}>` +
      `<td>${CHP.PLAN_NAME[r.p]}${r.p === s.plan ? " (yours)" : ""}</td>` +
      `<td>${CHP.money(r.cost)}</td><td>${CHP.money(r.cost * perMonth)}</td>` +
      `<td>${r.cost - best < 0.005 ? "<strong>Best</strong>" : "+" + CHP.money((r.cost - best) * perMonth) + "/mo"}</td></tr>`).join("");
    $("plan-table-note").innerHTML = `${fmt.n1(kwh)} kWh per charge at each plan's cheapest time that fits your ready-by time, ${s.perWeek} charges a week (change these in the <a href="calculator.html">Savings Calculator</a>). ` +
      `This only counts charging: switching plans also changes what the rest of your home pays, so check the OEB's bill calculator before you switch.`;
  }

  // ---------------------------------------------------------------- periods, history
  function renderPeriods() {
    $("periods").innerHTML = Object.entries(snap.periods).map(([p, lines]) =>
      `<div class="period-block${p === s.plan ? " current" : ""}"><h3>${CHP.PLAN_NAME[p]}</h3><ul>${lines.map((l) => `<li>${esc(l)}</li>`).join("")}</ul></div>`).join("");
    $("rates-effective").textContent = `OEB Regulated Price Plan, effective ${fmt.date(fmt.isoDay(snap.rates.effective))}. Electricity (commodity) prices only.`;
  }

  function renderHeatmap() {
    const days = snap.gridHeatmap;
    const vals = days.flatMap((x) => x.values).filter((v) => v != null);
    const el = $("price-heatmap");
    if (!vals.length) { el.innerHTML = `<p class="empty-note" style="grid-column:1/-1">No grid prices stored yet.</p>`; return; }
    const lo = Math.min(...vals), hi = Math.max(...vals);
    const t = (v) => (hi > lo ? (v - lo) / (hi - lo) : 0.5);
    const hl = (h) => fmt.hour(new Date(Date.UTC(2026, 0, 1, h + 5)));
    let minCell = null, maxCell = null;
    days.forEach((day, di) => day.values.forEach((v, h) => {
      if (v == null) return;
      if (!minCell || v < minCell.v) minCell = { di, h, v };
      if (!maxCell || v > maxCell.v) maxCell = { di, h, v };
    }));
    let html = `<div></div>`;
    for (let h = 0; h < 24; h++) html += `<div class="collabel">${h % 3 === 0 ? hl(h) : ""}</div>`;
    days.forEach((day, di) => {
      const date = fmt.isoDay(day.date);
      html += `<div class="rowlabel">${fmt.day(date)}</div>`;
      day.values.forEach((v, h) => {
        if (v == null) { html += `<div class="cell empty" role="gridcell" aria-label="No data"></div>`; return; }
        const cls = minCell && minCell.di === di && minCell.h === h ? " min" : maxCell && maxCell.di === di && maxCell.h === h ? " max" : "";
        html += `<div class="cell${cls}" role="gridcell" data-d="${di}" data-h="${h}" aria-label="${fmt.day(date)} ${hl(h)}: ${CHP.c1(v)} cents" style="background:${rampColor(t(v))}"></div>`;
      });
    });
    el.innerHTML = html;
    el.querySelectorAll(".cell[data-d]").forEach((cell) => {
      const day = days[+cell.dataset.d], h = +cell.dataset.h, v = day.values[h];
      cell.addEventListener("mousemove", (e) => tip.show(e,
        `<div class="tt-s">${fmt.day(fmt.isoDay(day.date))}, ${hl(h)}–${hl((h + 1) % 24)}</div><div class="tt-v">${CHP.c1(v)}¢/kWh</div><div class="tt-s">${fmt.n0(v * 10)} $/MWh</div>`));
      cell.addEventListener("mouseleave", tip.hide);
    });
    $("price-heat-legend").innerHTML = CH.rampLegend(lo, hi, "¢/kWh");
    if (minCell && maxCell) {
      const dl = (cc) => fmt.day(fmt.isoDay(days[cc.di].date));
      $("price-heat-summary").textContent = `▼ Cheapest hour: ${dl(minCell)} at ${hl(minCell.h)} (${CHP.c1(minCell.v)}¢). ▲ Dearest: ${dl(maxCell)} at ${hl(maxCell.h)} (${CHP.c1(maxCell.v)}¢). Wholesale price; real-time where available, otherwise day-ahead.`;
    }
  }

  function renderCalendar() {
    const days = snap.gridDaily.filter((d) => d.hours >= 12);
    const res = CH.calendar($("price-calendar"), {
      label: "Daily average grid price",
      days: days.map((d) => ({ date: d.date, v: d.avg,
        tip: `<div class="tt-s">${fmt.day(fmt.isoDay(d.date))}</div><div class="tt-v">${CHP.c1(d.avg)}¢/kWh average</div><div class="tt-s">Range ${CHP.c1(d.min)}–${CHP.c1(d.max)}¢ · ${d.hours} h</div>` })),
    });
    if (!res) return;
    $("price-cal-legend").innerHTML = CH.rampLegend(res.lo, res.hi, "¢/kWh");
    if (days.length) {
      const lo = days.reduce((a, b) => (b.avg < a.avg ? b : a)), hi = days.reduce((a, b) => (b.avg > a.avg ? b : a));
      $("price-cal-summary").textContent = `Cheapest day: ${fmt.day(fmt.isoDay(lo.date))} (${CHP.c1(lo.avg)}¢). Dearest: ${fmt.day(fmt.isoDay(hi.date))} (${CHP.c1(hi.avg)}¢). ${days.length} days of IESO prices.`;
    }
  }

  function renderAll() {
    if (!snap) return;
    renderNow();
    renderEvCost();
    renderStrip();
    renderPlanTable();
    renderPeriods();
    renderHeatmap();
    renderCalendar();
    $("price-notes").innerHTML = snap.notes.map((n) => `<li>${esc(n)}</li>`).join("");
  }

  async function load() {
    try {
      snap = await CHP.prices({ fresh: true });
      renderAll();
      requestPlan();
    } catch (e) {
      $("plan-next").textContent = e.status === 503 ? "Fetching the first IESO price reports…" : "Prices are not available right now. Retrying…";
      setTimeout(load, 15000);
      return;
    }
    setTimeout(load, 5 * 60 * 1000);
  }

  load();
  CH.onResize(() => snap && renderStrip());
})();
