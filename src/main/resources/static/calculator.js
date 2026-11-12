(() => {
  "use strict";
  const { fmt } = CH;
  const $ = (id) => document.getElementById(id);
  const KEY = "cleanhour.tasks.v1";
  const KG_PER_KM = 0.19;
  const DEFAULTS = [
    { name: "EV charge", kwh: 40, hours: 5 },
    { name: "Laundry (washer)", kwh: 2, hours: 1 },
    { name: "Dishwasher", kwh: 1.5, hours: 2 },
    { name: "Clothes dryer", kwh: 3, hours: 1 },
  ];

  let tasks = load();
  let selected = 0;
  let data = null; // { now: {start, intensity}, hours: [{start, intensity, source}] }

  function load() {
    try {
      const raw = localStorage.getItem(KEY);
      const t = raw && JSON.parse(raw);
      if (Array.isArray(t) && t.length) return t;
    } catch (_) { /* storage unavailable */ }
    return DEFAULTS.map((x) => ({ ...x }));
  }
  function save() {
    try { localStorage.setItem(KEY, JSON.stringify(tasks)); } catch (_) { /* ignore */ }
  }

  /** Intensity per hour if the task starts now: current hour, then forecast hours. */
  function nowSeries() {
    return [data.now.intensity, ...data.hours.map((h) => h.intensity)];
  }

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
    // Starting now can itself be the best choice.
    if (best < 0 || nowG <= bestG) {
      return { hours, kwh, best: -1, start: new Date(new Date(data.now.start).getTime() + 3600e3), nowKg: nowG / 1000, bestKg: nowG / 1000 };
    }
    return { hours, kwh, best, start: new Date(fc[best].start), nowKg: nowG / 1000, bestKg: bestG / 1000 };
  }

  function dayWord(d) {
    const today = fmt.key(new Date()), tomorrow = fmt.key(new Date(Date.now() + 86400e3)), k = fmt.key(d);
    return k === today ? "today" : k === tomorrow ? "tomorrow" : fmt.day(d);
  }

  function renderTasks() {
    const el = $("tasks");
    el.innerHTML = "";
    tasks.forEach((t, i) => {
      const row = document.createElement("div");
      row.className = "task";
      row.setAttribute("role", "group");
      row.style.cursor = "pointer";
      if (i === selected) row.style.borderColor = "var(--best)";
      row.innerHTML =
        `<div class="name-cell"><input type="text" aria-label="Task name" value="${escapeAttr(t.name)}" data-f="name"></div>` +
        `<label class="field"><span class="sr">kWh</span><input type="number" min="0.1" max="200" step="0.1" aria-label="Energy in kWh" value="${t.kwh}" data-f="kwh"></label>` +
        `<label class="field"><span class="sr">hours</span><input type="number" min="1" max="24" step="1" aria-label="Run time in hours" value="${t.hours}" data-f="hours"></label>` +
        `<div class="result" aria-live="polite"></div>` +
        `<button class="remove" type="button" aria-label="Remove ${escapeAttr(t.name)}" title="Remove">×</button>`;
      row.querySelectorAll("input").forEach((inp) => {
        inp.addEventListener("input", () => {
          const f = inp.dataset.f;
          t[f] = f === "name" ? inp.value : Number(inp.value);
          save();
          updateResults();
        });
        inp.addEventListener("focus", () => select(i));
      });
      row.querySelector(".remove").addEventListener("click", (e) => {
        e.stopPropagation();
        tasks.splice(i, 1);
        if (selected >= tasks.length) selected = Math.max(0, tasks.length - 1);
        save(); renderTasks(); updateResults();
      });
      row.addEventListener("click", () => select(i));
      el.appendChild(row);
    });
    el.querySelectorAll(".sr").forEach((s) => (s.style.display = "none"));
  }

  function select(i) {
    if (selected === i) return;
    selected = i;
    document.querySelectorAll(".task").forEach((r, k) => (r.style.borderColor = k === i ? "var(--best)" : ""));
    renderChart();
  }

  function updateResults() {
    if (!data) return;
    let totNow = 0, totBest = 0;
    document.querySelectorAll(".task").forEach((row, i) => {
      const r = compute(tasks[i]);
      const out = row.querySelector(".result");
      if (!r) { out.textContent = "Not enough forecast hours for this run time."; return; }
      totNow += r.nowKg; totBest += r.bestKg;
      const end = new Date(r.start.getTime() + r.hours * 3600e3);
      const saved = r.nowKg - r.bestKg;
      if (r.best < 0) {
        out.innerHTML = `<strong>Start now</strong> · about ${fmt.n1(r.nowKg)} kg CO₂e. Now is already the cleanest time in the next 24 hours.`;
      } else {
        const pct = r.nowKg > 0 ? (saved / r.nowKg) * 100 : 0;
        out.innerHTML = `<strong>${fmt.time(r.start)}–${fmt.time(end)} ${dayWord(r.start)}</strong><br>` +
          `${fmt.n2(r.bestKg)} kg vs ${fmt.n2(r.nowKg)} kg now · <span class="save">save ${fmt.n2(saved)} kg (${fmt.n0(pct)}%)</span>`;
      }
    });
    const saved = totNow - totBest;
    $("totals").innerHTML =
      `<div><div class="k">CO₂ saved</div><div class="hero">${fmt.n1(saved)} kg</div></div>` +
      `<div><div class="k">Less than running everything now</div><div class="hero">${totNow > 0 ? fmt.n0((saved / totNow) * 100) : 0}%</div></div>` +
      `<div><div class="k">Same as not driving about</div><div class="hero">${fmt.n0(saved / KG_PER_KM)} km</div></div>` +
      `<div><div class="k">Running everything now emits</div><div class="hero">${fmt.n1(totNow)} kg</div></div>`;
    renderChart();
  }

  function renderChart() {
    if (!data) return;
    const r = tasks[selected] ? compute(tasks[selected]) : null;
    const bars = [{ start: data.now.start, intensity: data.now.intensity, kind: "now" },
      ...data.hours.map((h) => ({ ...h, kind: "forecast" }))];
    let bestFrom = null, bestTo = null;
    if (r) {
      bestFrom = r.start.getTime();
      bestTo = bestFrom + r.hours * 3600e3;
      if (r.best < 0) bestFrom = new Date(data.now.start).getTime(), bestTo = bestFrom + r.hours * 3600e3;
    }
    CH.barChart($("chart"), {
      label: "Carbon intensity now and next 24 hours",
      bars: bars.map((b) => {
        const t = new Date(b.start), ms = t.getTime(), end = new Date(ms + 3600e3);
        const inBest = bestFrom != null && ms >= bestFrom && ms < bestTo;
        return {
          label: fmt.hour(t), v: b.intensity,
          color: inBest ? "var(--best)" : b.kind === "now" ? "var(--actual)" : "var(--forecast)",
          tip: `<div class="tt-s">${fmt.day(t)}, ${fmt.time(t)}–${fmt.time(end)}</div><div class="tt-v">${fmt.n0(b.intensity)} g/kWh</div>` +
            `<div class="tt-s">${b.kind === "now" ? "Latest IESO hour" : b.source === "ieso-schedule" ? "Forecast from IESO schedule" : "Forecast from recent pattern"}${inBest ? " · best window" : ""}</div>`,
        };
      }),
      yFormat: (v) => fmt.n0(v),
    });
    $("chart-title").textContent = tasks[selected]
      ? `Carbon intensity, now and the next 24 hours: ${tasks[selected].name || "task"}`
      : "Carbon intensity, now and the next 24 hours";
  }

  function escapeAttr(s) { return String(s).replace(/&/g, "&amp;").replace(/"/g, "&quot;").replace(/</g, "&lt;"); }
  fmt.n2 = (v) => new Intl.NumberFormat("en-CA", { maximumFractionDigits: v < 1 ? 2 : 1 }).format(v);

  $("add").addEventListener("click", () => {
    tasks.push({ name: "My task", kwh: 1, hours: 1 });
    selected = tasks.length - 1;
    save(); renderTasks(); updateResults();
    const inputs = document.querySelectorAll(".task input[type=text]");
    inputs[inputs.length - 1].select();
  });
  $("reset").addEventListener("click", () => {
    tasks = DEFAULTS.map((x) => ({ ...x }));
    selected = 0;
    save(); renderTasks(); updateResults();
  });

  async function loadData() {
    try {
      const d = await CH.getJson("api/dashboard");
      data = { now: { start: d.current.start, intensity: d.current.intensity }, hours: d.forecast };
      $("updated").textContent = `Forecast updated ${fmt.time(new Date(d.generatedAt))}`;
      $("basis").textContent = d.evWindow ? "Forecast basis: " + d.evWindow.basis + "." : "";
      updateResults();
    } catch (e) {
      $("updated").innerHTML = `<span class="error">${e.status === 503 ? "Fetching the first IESO reports…" : "Could not load the forecast (" + e.message + ")."} Retrying…</span>`;
      setTimeout(loadData, 5000);
      return;
    }
    setTimeout(loadData, 5 * 60 * 1000);
  }

  renderTasks();
  loadData();
  CH.onResize(renderChart);
})();
