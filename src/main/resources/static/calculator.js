/* Savings Calculator: EV charge planner, household tasks, and sticky side charts (carbon and price). */
(() => {
  "use strict";
  const { fmt } = CH;
  const $ = (id) => document.getElementById(id);
  const KEY = "cleanhour.tasks.v1";
  const KG_PER_KM = 0.19;
  const DEFAULTS = [
    { name: "Laundry (washer)", kwh: 2, hours: 1 },
    { name: "Dishwasher", kwh: 1.5, hours: 2 },
    { name: "Clothes dryer", kwh: 3, hours: 1 },
  ];
  const OPTION_NAMES = { cheapest: "cheapest", cleanest: "cleanest", balanced: "best balance" };

  let s = CHP.load();
  let tasks = loadTasks();
  let data = null;    // { now: {start, intensity}, hours: [{start, intensity, source}] } from /api/dashboard
  let prices = null;  // /api/v1/prices
  let plan = null;    // /api/v1/charge-plan
  let sel = { type: "ev", key: "cheapest" }; // what the side charts highlight

  const esc = CHP.esc;
  const dayWord = (d) => {
    const today = fmt.key(new Date()), tomorrow = fmt.key(new Date(Date.now() + 86400e3)), k = fmt.key(d);
    return k === today ? "today" : k === tomorrow ? "tomorrow" : fmt.day(d);
  };
  const cap = (t) => t.charAt(0).toUpperCase() + t.slice(1);
  const hourFloor = (ms) => Math.floor(ms / 3600e3) * 3600e3;
  fmt.n2 = (v) => new Intl.NumberFormat("en-CA", { maximumFractionDigits: v < 1 ? 2 : 1 }).format(v);

  function loadTasks() {
    try {
      const raw = localStorage.getItem(KEY);
      const t = raw && JSON.parse(raw);
      if (Array.isArray(t)) return t;
    } catch (_) { /* storage unavailable */ }
    return DEFAULTS.map((x) => ({ ...x }));
  }
  function saveTasks() { try { localStorage.setItem(KEY, JSON.stringify(tasks)); } catch (_) { /* ignore */ } }

  // ---------------------------------------------------------------- EV planner
  function bindPlanner() {
    $("ready").innerHTML = `<option value="">Any time in the next 24 h</option>` +
      Array.from({ length: 24 }, (_, h) => `<option value="${h}">${fmt.time(new Date(Date.UTC(2026, 0, 1, h + 5)))}</option>`).join("");
    const fields = { battery: "battery", "soc-from": "socFrom", "soc-to": "socTo", kw: "kw", ready: "ready", "per-week": "perWeek" };
    Object.entries(fields).forEach(([id, key]) => {
      const el = $(id);
      el.value = s[key];
      el.addEventListener(el.tagName === "SELECT" ? "change" : "input", () => {
        CHP.update({ [key]: el.tagName === "SELECT" ? el.value : Number(el.value) });
      });
    });
  }

  let planTimer = null, planSeq = 0;
  function requestPlan() { clearTimeout(planTimer); planTimer = setTimeout(fetchPlan, 250); }
  async function fetchPlan() {
    if (!prices) return;
    const kwh = CHP.kwhFromWall(s);
    $("plan-energy").textContent = kwh > 0 ? `≈ ${fmt.n1(kwh)} kWh from the wall` : "";
    const seq = ++planSeq;
    let d = null;
    try { d = await CHP.chargePlan(s, prices); } catch (e) {
      if (seq === planSeq) $("planner-note").textContent = "Could not plan the charge (" + e.message + ").";
      return;
    }
    if (seq !== planSeq) return;
    plan = d;
    renderOptions();
    renderSide();
  }

  /** Option cards, with identical windows merged into one card. */
  function groups() {
    const r = plan.result, out = [];
    for (const key of ["cheapest", "cleanest", "balanced"]) {
      const w = r[key];
      if (!w) continue;
      const g = out.find((x) => x.w.start === w.start && x.w.end === w.end);
      if (g) g.keys.push(key); else out.push({ keys: [key], w });
    }
    if (r.now) out.push({ keys: ["now"], w: r.now });
    return out;
  }
  function groupLabel(keys) {
    if (keys[0] === "now") return "Start now";
    const names = keys.map((k) => OPTION_NAMES[k]);
    if (names.length === 3) return "Best time: cheapest and cleanest";
    return cap(names.join(" and "));
  }
  function selectedEvWindow() {
    if (!plan || !plan.result) return null;
    const g = groups().find((x) => x.keys.includes(sel.key)) || groups()[0];
    return g ? { ...g.w, label: groupLabel(g.keys) } : null;
  }

  function renderOptions() {
    if (!plan) {
      $("options").innerHTML = `<p class="empty-note">${prices ? 'Set "Charge to" above "Charge now" to plan a charge.' : "Loading prices…"}</p>`;
      $("planner-note").textContent = "";
      return;
    }
    const r = plan.result, kwh = r.kwh;
    const peak = CHP.dollars(plan.peakCost, kwh, s, prices);
    const nowCost = r.now ? CHP.dollars(r.now.cost, kwh, s, prices) : null;
    const gs = groups();
    if (!gs.some((g) => g.keys.includes(sel.key)) && gs.length) sel.key = gs[0].keys[0];
    const evSelected = sel.type === "ev";
    $("options").innerHTML = gs.map((g) => {
      const w = g.w, isNow = g.keys[0] === "now";
      const st = new Date(w.start), en = new Date(w.end);
      const cost = CHP.dollars(w.cost, kwh, s, prices);
      const vsNow = nowCost != null && !isNow ? nowCost - cost : null;
      const vsPeak = peak - cost;
      const on = evSelected && g.keys.includes(sel.key);
      const dw = dayWord(st);
      return `<button type="button" class="option${on ? " on" : ""}" role="radio" aria-checked="${on}" data-k="${g.keys[0]}">` +
        `<span class="o-label">${groupLabel(g.keys)}</span>` +
        `<span class="o-time">${isNow ? "Now" : fmt.time(st)} – ${fmt.time(en)}</span>` +
        `<span class="o-day">${dw === "today" ? "Today" : dw === "tomorrow" ? "Tomorrow" : dw} · ${fmt.n1(w.hours)} h</span>` +
        `<span class="o-cost">${CHP.money(cost)}</span>` +
        `<span class="o-sub">${CHP.c1(CHP.cents(w.avgCents, s, prices))}¢/kWh average</span>` +
        (w.kgCo2 == null ? `<span class="o-sub">No carbon forecast</span>`
          : `<span class="o-sub">${fmt.n1(w.kgCo2)} kg CO₂e · ${fmt.n0(w.avgIntensity)} g/kWh</span>`) +
        (vsNow != null && Math.abs(vsNow) >= 0.01 ? `<span class="o-save${vsNow < 0 ? " neg" : ""}">${vsNow >= 0 ? "Saves " + CHP.money(vsNow) : "Costs " + CHP.money(-vsNow) + " more"} vs. now</span>` : "") +
        (vsPeak >= 0.01 ? `<span class="o-save">Saves ${CHP.money(vsPeak)} vs. on-peak</span>` : "") +
        `</button>`;
    }).join("");
    $("options").querySelectorAll(".option").forEach((b) => b.addEventListener("click", () => {
      sel = { type: "ev", key: b.dataset.k };
      renderOptions(); markTasks(); renderSide();
    }));
    const rb = CHP.readyByInstant(s, prices);
    const peakTxt = s.plan === "TIERED" && !s.overTier ? "tier 1" : "on-peak";
    $("planner-note").textContent = (r.note ? r.note + " " : "") +
      `${CHP.PLAN_NAME[s.plan]} plan. The ${peakTxt} comparison prices the whole charge at ${CHP.c1(CHP.cents(plan.peakCents, s, prices))}¢/kWh (${CHP.money(peak)}).` +
      (rb ? ` Must finish by ${fmt.time(rb)} ${dayWord(rb)}.` : "") +
      (r.candidates ? ` ${r.candidates} start times checked.` : "");
  }

  // ---------------------------------------------------------------- household tasks
  function nowSeries() { return [data.now.intensity, ...data.hours.map((h) => h.intensity)]; }

  function compute(task) {
    const hours = Math.max(1, Math.min(24, Math.ceil(task.hours || 1)));
    const kwh = Math.max(0, Number(task.kwh) || 0);
    const perHour = kwh / hours;
    const nowS = nowSeries();
    if (nowS.length < hours) return null;
    const nowG = nowS.slice(0, hours).reduce((a, v) => a + v, 0) * perHour;
    const fc = data.hours;
    let best = -1, bestG = Infinity;
    for (let i = 0; i + hours <= fc.length; i++) {
      const span = (new Date(fc[i + hours - 1].start) - new Date(fc[i].start)) / 3600e3;
      if (span !== hours - 1) continue;
      let g = 0;
      for (let j = i; j < i + hours; j++) g += fc[j].intensity;
      g *= perHour;
      if (g < bestG) { bestG = g; best = i; }
    }
    if (best < 0 || nowG <= bestG) {
      return { hours, kwh, best: -1, start: new Date(hourFloor(Date.now())), nowKg: nowG / 1000, bestKg: nowG / 1000 };
    }
    return { hours, kwh, best, start: new Date(fc[best].start), nowKg: nowG / 1000, bestKg: bestG / 1000 };
  }

  /** Dollars to run kwh spread evenly over `hours` hours starting at `start`, on the chosen plan; null if unknown. */
  function costFrom(start, hours, kwh) {
    if (!prices) return null;
    const byStart = new Map(prices.next24.map((h) => [new Date(h.start).getTime(), h.plans[s.plan].cents]));
    let cents = 0;
    const t0 = hourFloor(start.getTime());
    for (let k = 0; k < hours; k++) {
      const c = byStart.get(t0 + k * 3600e3);
      if (c == null) return null;
      cents += (kwh / hours) * c;
    }
    return CHP.dollars(cents / 100, kwh, s, prices);
  }

  function renderTasks() {
    const el = $("tasks");
    el.innerHTML = "";
    if (!tasks.length) el.innerHTML = `<p class="empty-note">No tasks. Use "Add a task" or "Reset to defaults".</p>`;
    tasks.forEach((t, i) => {
      const row = document.createElement("div");
      row.className = "task";
      row.setAttribute("role", "group");
      row.style.cursor = "pointer";
      row.innerHTML =
        `<div class="name-cell"><input type="text" aria-label="Task name" value="${esc(t.name)}" data-f="name"></div>` +
        `<label class="field"><input type="number" min="0.1" max="200" step="0.1" aria-label="Energy in kWh" value="${t.kwh}" data-f="kwh"></label>` +
        `<label class="field"><input type="number" min="1" max="24" step="1" aria-label="Run time in hours" value="${t.hours}" data-f="hours"></label>` +
        `<div class="result" aria-live="polite"></div>` +
        `<button class="remove" type="button" aria-label="Remove ${esc(t.name)}" title="Remove">×</button>`;
      row.querySelectorAll("input").forEach((inp) => {
        inp.addEventListener("input", () => {
          const f = inp.dataset.f;
          t[f] = f === "name" ? inp.value : Number(inp.value);
          saveTasks();
          updateResults();
        });
        inp.addEventListener("focus", () => selectTask(i));
      });
      row.querySelector(".remove").addEventListener("click", (e) => {
        e.stopPropagation();
        tasks.splice(i, 1);
        if (sel.type === "task") sel = { type: "ev", key: sel.key || "cheapest" };
        saveTasks(); renderTasks(); updateResults(); renderOptions();
      });
      row.addEventListener("click", () => selectTask(i));
      el.appendChild(row);
    });
    markTasks();
  }

  function markTasks() {
    document.querySelectorAll(".task").forEach((r, k) => r.classList.toggle("selected", sel.type === "task" && sel.i === k));
  }

  function selectTask(i) {
    if (sel.type === "task" && sel.i === i) return;
    sel = { type: "task", i, key: sel.key };
    markTasks();
    if (plan) renderOptions();
    renderSide();
  }

  function updateResults() {
    if (!data) return;
    let totNow = 0, totBest = 0, costNow = 0, costBest = 0, costKnown = true;
    document.querySelectorAll(".task").forEach((row, i) => {
      const r = compute(tasks[i]);
      const out = row.querySelector(".result");
      if (!r) { out.textContent = "Not enough forecast hours for this run time."; return; }
      totNow += r.nowKg; totBest += r.bestKg;
      const end = new Date(r.start.getTime() + r.hours * 3600e3);
      const saved = r.nowKg - r.bestKg;
      const cNow = costFrom(new Date(), r.hours, r.kwh), cBest = r.best < 0 ? cNow : costFrom(r.start, r.hours, r.kwh);
      if (cNow == null || cBest == null) costKnown = false; else { costNow += cNow; costBest += cBest; }
      const costLine = cNow == null || cBest == null ? "" :
        `<span class="cost-line">${CHP.PLAN_SHORT[s.plan]}: ${CHP.money(cBest)}${r.best < 0 ? "" : ` at that time vs ${CHP.money(cNow)} now`}</span>`;
      if (r.best < 0) {
        out.innerHTML = `<strong>Start now</strong> · about ${fmt.n1(r.nowKg)} kg CO₂e. Now is already the cleanest time in the next 24 hours.` + costLine;
      } else {
        const pct = r.nowKg > 0 ? (saved / r.nowKg) * 100 : 0;
        out.innerHTML = `<strong>${fmt.time(r.start)}–${fmt.time(end)} ${dayWord(r.start)}</strong><br>` +
          `${fmt.n2(r.bestKg)} kg vs ${fmt.n2(r.nowKg)} kg now · <span class="save">save ${fmt.n2(saved)} kg (${fmt.n0(pct)}%)</span>` + costLine;
      }
    });
    const saved = totNow - totBest;
    $("totals").innerHTML =
      `<div><div class="k">CO₂ saved</div><div class="hero">${fmt.n1(saved)} kg</div></div>` +
      `<div><div class="k">Less than running everything now</div><div class="hero">${totNow > 0 ? fmt.n0((saved / totNow) * 100) : 0}%</div></div>` +
      `<div><div class="k">Same as not driving about</div><div class="hero">${fmt.n0(saved / KG_PER_KM)} km</div></div>` +
      (prices && costKnown && tasks.length ? `<div><div class="k">Cost at the cleanest times (${CHP.PLAN_SHORT[s.plan]})</div><div class="hero">${CHP.money(costBest)}</div><div class="k">${costNow - costBest >= 0 ? "saves " + CHP.money(costNow - costBest) : "costs " + CHP.money(costBest - costNow) + " more"} vs. ${CHP.money(costNow)} now</div></div>` : "");
    renderSide();
  }

  // ---------------------------------------------------------------- side charts
  /** {start, end, label} of whatever is selected, or null. */
  function selectedWindow() {
    if (sel.type === "task" && data && tasks[sel.i]) {
      const r = compute(tasks[sel.i]);
      if (!r) return null;
      return { start: r.start, end: new Date(r.start.getTime() + r.hours * 3600e3), label: tasks[sel.i].name || "Task" };
    }
    const w = selectedEvWindow();
    return w ? { start: w.start, end: w.end, label: "EV charge" } : null;
  }

  function renderSide() {
    const w = selectedWindow();
    const what = sel.type === "task" && tasks[sel.i] ? (tasks[sel.i].name || "task") : plan ? "EV charge" : "";
    const sub = w ? ` · ${what}: ${fmt.time(new Date(w.start))}–${fmt.time(new Date(w.end))}` : "";
    $("carbon-title").textContent = "Carbon, next 24 hours" + sub;
    $("price-title").textContent = `${CHP.PLAN_SHORT[s.plan]} price, next 24 hours`;

    if (data) {
      const bars = [{ start: data.now.start, intensity: data.now.intensity, kind: "now" },
        ...data.hours.map((h) => ({ ...h, kind: "forecast" }))];
      CHP.hourChart($("carbon-chart"), {
        label: "Carbon intensity, latest hour and next 24 hours",
        bars: bars.map((b) => {
          const t = new Date(b.start), e = new Date(t.getTime() + 3600e3);
          return {
            start: b.start, v: b.intensity, color: b.kind === "now" ? "var(--actual)" : "var(--forecast)",
            tip: `<div class="tt-s">${fmt.day(t)}, ${fmt.time(t)}–${fmt.time(e)}</div><div class="tt-v">${fmt.n0(b.intensity)} g/kWh</div>` +
              `<div class="tt-s">${b.kind === "now" ? "Latest IESO hour" : b.source === "ieso-schedule" ? "Forecast from IESO schedule" : "Forecast from recent pattern"}</div>`,
          };
        }),
        window: w, windowLabel: w ? w.label : "", height: 200,
      });
    }
    if (prices) {
      const hours = prices.next24, showGrid = s.showGrid !== false;
      $("grid-toggle").setAttribute("aria-pressed", String(showGrid));
      CHP.hourChart($("price-chart"), {
        label: "Price on your plan for the next 24 hours",
        bars: hours.map((h) => {
          const t = new Date(h.start), e = new Date(t.getTime() + 3600e3), pp = h.plans[s.plan];
          return {
            start: h.start, v: CHP.cents(pp.cents, s, prices), color: CHP.PERIOD_COLOR[pp.period] || "var(--f-nuclear)",
            tip: `<div class="tt-s">${fmt.day(t)}, ${fmt.time(t)}–${fmt.time(e)}</div>` +
              `<div class="tt-v">${CHP.c1(CHP.cents(pp.cents, s, prices))}¢/kWh</div>` +
              `<div class="tt-s">${CHP.PLAN_SHORT[s.plan]} ${pp.period.replace(/-/g, " ")}${s.allIn ? " · full-bill estimate" : ""}</div>` +
              `<div class="tt-s">Grid ${h.gridCents == null ? "–" : CHP.c1(h.gridCents) + "¢ (" + h.gridSource + ")"}</div>`,
          };
        }),
        lines: showGrid ? [{ values: hours.map((h) => h.gridCents), color: "var(--ink-2)", dash: true }] : [],
        yFormat: (v) => fmt.n0(v) + "¢",
        window: w, windowLabel: w ? w.label : "", height: 200,
      });
      const periods = [...new Set(hours.map((h) => h.plans[s.plan].period))];
      $("price-legend").innerHTML = periods.map((p) => `<li><span class="sw" style="background:${CHP.PERIOD_COLOR[p]}"></span>${cap(p.replace(/-/g, " "))}</li>`).join("") +
        (showGrid ? `<li><span class="sw sw-dash" style="border-color:var(--ink-2)"></span>Grid (wholesale)</li>` : "");
      $("price-basis").textContent = `Bars: what the ${CHP.PLAN_NAME[s.plan]} plan charges each hour${s.allIn ? " (full-bill estimate)" : ""}. ` +
        (showGrid ? "Dashed: IESO wholesale grid price, which RPP households don't pay directly." : "Turn on Grid price to compare with IESO's wholesale price.");
    }
  }

  // ---------------------------------------------------------------- wiring
  $("grid-toggle").addEventListener("click", () => CHP.update({ showGrid: !(s.showGrid !== false) }));
  $("add").addEventListener("click", () => {
    tasks.push({ name: "My task", kwh: 1, hours: 1 });
    sel = { type: "task", i: tasks.length - 1, key: sel.key };
    saveTasks(); renderTasks(); updateResults(); if (plan) renderOptions();
    const inputs = document.querySelectorAll(".task input[type=text]");
    inputs[inputs.length - 1].select();
  });
  $("reset").addEventListener("click", () => {
    tasks = DEFAULTS.map((x) => ({ ...x }));
    sel = { type: "ev", key: sel.key || "cheapest" };
    saveTasks(); renderTasks(); updateResults(); if (plan) renderOptions();
  });

  CHP.onChange((next) => {
    const replan = ["plan", "overTier", "battery", "socFrom", "socTo", "kw", "ready"].some((k) => next[k] !== s[k]);
    s = next;
    if (replan) requestPlan();
    if (plan) renderOptions();
    updateResults();
    renderSide();
  });

  async function loadPrices() {
    try {
      prices = await CHP.prices({ fresh: true });
      requestPlan();
      updateResults();
    } catch (_) { /* cost and price chart appear once prices load */ }
  }

  async function loadData() {
    loadPrices();
    try {
      const d = await CH.getJson("api/dashboard");
      data = { now: { start: d.current.start, intensity: d.current.intensity }, hours: d.forecast };
      $("updated").textContent = `Forecast updated ${fmt.time(new Date(d.generatedAt))}`;
      $("basis").textContent = d.evWindow ? "Forecast basis: " + d.evWindow.basis + "." : "";
      updateResults();
    } catch (e) {
      $("updated").innerHTML = `<span class="error">${e.status === 503 ? "Fetching the first IESO reports…" : "Could not load the forecast (" + esc(e.message) + ")."} Retrying…</span>`;
      setTimeout(loadData, 5000);
      return;
    }
    setTimeout(loadData, 5 * 60 * 1000);
  }

  bindPlanner();
  renderTasks();
  renderOptions();
  loadData();
  CH.onResize(renderSide);
})();
