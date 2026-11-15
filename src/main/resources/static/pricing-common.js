/*
 * Shared pricing helpers used on every page:
 *  - settings (rate plan, full-bill estimate, EV planner inputs) kept in this browser
 *  - the "Plan" selector in the nav, with the bill settings in its dropdown
 *  - a small hourly bar chart with a highlighted window (Dashboard and Savings Calculator)
 * Pages listen for the "cleanhour:settings" event to re-render when the plan changes.
 */
(() => {
  "use strict";
  const KEY = "cleanhour.pricing.v1";
  const EVENT = "cleanhour:settings";
  const DEFAULTS = { plan: "ULO", allIn: false, adder: 4.0, overTier: false, showGrid: true,
    battery: 60, socFrom: 30, socTo: 80, kw: 7.2, ready: "7", perWeek: 3 };
  const PLAN_NAME = { ULO: "Ultra-Low Overnight", TOU: "Time-of-Use", TIERED: "Tiered" };
  const PLAN_SHORT = { ULO: "ULO", TOU: "TOU", TIERED: "Tiered" };
  const PERIOD_COLOR = {
    "ultra-low": "var(--good)", "off-peak": "var(--good)", "weekend-off-peak": "var(--f-hydro)",
    "mid-peak": "var(--warning)", "on-peak": "var(--critical)", "tier-1": "var(--f-nuclear)", "tier-2": "var(--f-nuclear)",
  };
  const WALL_EFFICIENCY = 0.9;

  function load() {
    try {
      const s = JSON.parse(localStorage.getItem(KEY) || "{}");
      return { ...DEFAULTS, ...(s && typeof s === "object" ? s : {}) };
    } catch (_) { return { ...DEFAULTS }; }
  }
  function save(s) { try { localStorage.setItem(KEY, JSON.stringify(s)); } catch (_) { /* storage unavailable */ } }
  /** Save and tell every listener on the page. */
  function update(patch) {
    const s = { ...load(), ...patch };
    save(s);
    window.dispatchEvent(new CustomEvent(EVENT, { detail: s }));
    return s;
  }
  function onChange(fn) { window.addEventListener(EVENT, (e) => fn(e.detail)); }

  /** Multiplier from commodity to full bill: 1 + HST − OER. */
  function billFactor(p) { return 1 + ((p && p.hstPct) || 13) / 100 - (p && p.rates ? p.rates.oerPct : 0) / 100; }
  /** cents/kWh shown to the user, commodity or full-bill estimate. */
  function cents(c, s, p) { return s.allIn ? (c + Number(s.adder || 0)) * billFactor(p) : c; }
  /** dollars for a session of kwh, commodity cost in dollars as input. */
  function dollars(cost, kwh, s, p) { return s.allIn ? (cost + kwh * Number(s.adder || 0) / 100) * billFactor(p) : cost; }

  const moneyFmt = new Intl.NumberFormat("en-CA", { style: "currency", currency: "CAD", minimumFractionDigits: 2, maximumFractionDigits: 2 });
  const c1Fmt = new Intl.NumberFormat("en-CA", { minimumFractionDigits: 1, maximumFractionDigits: 1 });
  const esc = (t) => String(t).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/"/g, "&quot;");

  // ---------------------------------------------------------------- prices (shared fetch)
  let pricesPromise = null;
  /** The /api/v1/prices snapshot. Cached per page load unless {fresh: true}. */
  function prices(opts) {
    if (!pricesPromise || (opts && opts.fresh)) {
      pricesPromise = CH.getJson("api/v1/prices").catch((e) => { pricesPromise = null; throw e; });
    }
    return pricesPromise;
  }

  // ---------------------------------------------------------------- EV planner helpers
  const fmtH23 = new Intl.DateTimeFormat("en-CA", { timeZone: "America/Toronto", hour: "2-digit", hourCycle: "h23" });
  const localHour = (d) => Number(fmtH23.format(d));

  function kwhFromWall(s) {
    const add = Math.max(0, (Number(s.socTo) - Number(s.socFrom)) / 100) * Math.max(0, Number(s.battery));
    return Math.round((add / WALL_EFFICIENCY) * 10) / 10;
  }
  /**
   * The part of your EV charge that fits the dashboard's 3-hour window at your charger's power.
   * {kwh: energy in the window, kw, full: the whole charge from the wall}
   */
  function evCharge3h(s) {
    const kw = Math.max(1, Number(s.kw) || 7.2), full = kwhFromWall(s);
    return { kw, full: full > 0 ? full : kw * 3, kwh: full > 0 ? Math.min(full, kw * 3) : kw * 3 };
  }
  /** "on-peak", or for Tiered the tier the whole charge is compared with. */
  function peakWord(s) { return s.plan === "TIERED" ? (s.overTier ? "tier 2" : "tier 1") : "on-peak"; }

  /** Next time within the forecast hours that the local clock reads the chosen "ready by" hour, or null. */
  function readyByInstant(s, snap) {
    if (s.ready === "" || s.ready == null || !snap) return null;
    const h = Number(s.ready), now = Date.now();
    for (const p of snap.next24) {
      const t = new Date(p.start);
      if (t.getTime() > now && localHour(t) === h) return t;
    }
    const last = snap.next24[snap.next24.length - 1];
    return last ? new Date(new Date(last.start).getTime() + 3600e3) : null;
  }
  /** Calls /api/v1/charge-plan with the saved planner inputs. Resolves to null when there's nothing to plan. */
  async function chargePlan(s, snap) {
    const kwh = kwhFromWall(s);
    if (!(kwh > 0) || !(Number(s.kw) >= 1)) return null;
    const q = new URLSearchParams({ kwh, kw: s.kw, plan: s.plan, overTier: !!s.overTier });
    const rb = readyByInstant(s, snap);
    if (rb) q.set("readyBy", rb.toISOString());
    return CH.getJson("api/v1/charge-plan?" + q);
  }

  // ---------------------------------------------------------------- nav plan selector
  function mountNav() {
    const nav = document.querySelector(".nav");
    if (!nav || nav.querySelector(".plan-wrap")) return;
    const wrap = document.createElement("div");
    wrap.className = "plan-wrap";
    wrap.innerHTML =
      `<button type="button" class="plan-btn" aria-haspopup="dialog" aria-expanded="false" aria-controls="plan-panel"></button>` +
      `<div class="plan-panel" id="plan-panel" role="dialog" aria-label="Rate plan and bill settings" hidden>` +
      `  <div class="pp-row"><span class="pp-k">Rate plan</span>` +
      `    <div class="seg" role="group" aria-label="Rate plan">` +
      Object.keys(PLAN_NAME).map((p) => `<button type="button" data-plan="${p}">${PLAN_SHORT[p]}</button>`).join("") +
      `    </div></div>` +
      `  <p class="pp-name"></p>` +
      `  <label class="check"><input type="checkbox" data-k="allIn"> Show full-bill estimate</label>` +
      `  <label class="field pp-adder">Delivery and regulatory charges (¢/kWh)<input type="number" min="0" max="30" step="0.1" data-k="adder"></label>` +
      `  <label class="check pp-tier"><input type="checkbox" data-k="overTier"> Already over the monthly tier-1 limit</label>` +
      `  <p class="pp-basis"></p>` +
      `</div>`;
    nav.appendChild(wrap);
    const btn = wrap.querySelector(".plan-btn"), panel = wrap.querySelector(".plan-panel");

    function paint() {
      const s = load();
      btn.innerHTML = `Plan: <strong>${PLAN_SHORT[s.plan]}</strong>${s.allIn ? " · full bill" : ""} <span aria-hidden="true">▾</span>`;
      btn.title = `${PLAN_NAME[s.plan]} rate plan. Click to change it or the bill settings.`;
      panel.querySelectorAll(".seg button").forEach((b) => b.setAttribute("aria-pressed", String(b.dataset.plan === s.plan)));
      panel.querySelector(".pp-name").textContent = `${PLAN_NAME[s.plan]}. Costs on every page use this plan.`;
      panel.querySelector('[data-k="allIn"]').checked = !!s.allIn;
      const adder = panel.querySelector('[data-k="adder"]');
      if (document.activeElement !== adder) adder.value = s.adder;
      panel.querySelector(".pp-adder").style.display = s.allIn ? "" : "none";
      panel.querySelector(".pp-tier").style.display = s.plan === "TIERED" ? "" : "none";
    }
    function paintBasis() {
      prices().then((p) => {
        panel.querySelector(".pp-basis").textContent =
          `Full bill ≈ (price + delivery) × ${c1Fmt.format(billFactor(p) * 100)}%: HST ${Math.round(p.hstPct)}% minus the ` +
          `${c1Fmt.format(p.rates.oerPct)}% Ontario Electricity Rebate. Delivery charges vary by utility; check your bill. Saved in this browser.`;
      }).catch(() => { panel.querySelector(".pp-basis").textContent = "Saved in this browser."; });
    }
    function open(v) {
      panel.hidden = !v;
      btn.setAttribute("aria-expanded", String(v));
      if (v) { paint(); paintBasis(); }
    }
    btn.addEventListener("click", (e) => { e.stopPropagation(); open(panel.hidden); });
    panel.addEventListener("click", (e) => e.stopPropagation());
    document.addEventListener("click", () => open(false));
    document.addEventListener("keydown", (e) => { if (e.key === "Escape" && !panel.hidden) { open(false); btn.focus(); } });
    panel.querySelectorAll(".seg button").forEach((b) => b.addEventListener("click", () => update({ plan: b.dataset.plan })));
    panel.querySelector('[data-k="allIn"]').addEventListener("change", (e) => update({ allIn: e.target.checked }));
    panel.querySelector('[data-k="overTier"]').addEventListener("change", (e) => update({ overTier: e.target.checked }));
    panel.querySelector('[data-k="adder"]').addEventListener("input", (e) => update({ adder: Math.max(0, Number(e.target.value) || 0) }));
    onChange(paint);
    paint();
  }

  // ---------------------------------------------------------------- hourly chart
  /**
   * Bars per hour with an optional highlighted window and extra lines.
   * opts.bars:   [{start: Date|string, v: number, color, tip: html}]
   * opts.window: {start, end} (Date|string) or null; bars outside it are dimmed and a box is drawn around it
   * opts.lines:  [{values: [number|null] aligned with bars, color, dash, right}] (right = right-hand axis)
   * opts.yFormat, opts.rightFormat, opts.rightUnit, opts.label, opts.height, opts.windowLabel
   */
  function hourChart(el, opts) {
    const bars = opts.bars || [];
    if (!bars.length) { el.innerHTML = `<p class="empty-note">No data yet.</p>`; return; }
    const lines = opts.lines || [];
    const hasRight = lines.some((l) => l.right);
    const W = Math.max(280, el.clientWidth || 600), H = opts.height || (W < 520 ? 200 : 240);
    const m = { l: 36, r: hasRight ? 40 : 10, t: 22, b: 24 };
    const iw = W - m.l - m.r, ih = H - m.t - m.b;
    const leftVals = bars.map((b) => b.v).concat(...lines.filter((l) => !l.right).map((l) => l.values.filter((v) => v != null)));
    const maxL = CH.niceMax(Math.max(1, ...leftVals) * 1.08);
    const rightVals = [].concat(...lines.filter((l) => l.right).map((l) => l.values.filter((v) => v != null)));
    const maxR = hasRight ? CH.niceMax(Math.max(1, ...rightVals) * 1.08) : 1;
    const y = (v) => m.t + ih - (v / maxL) * ih;
    const yr = (v) => m.t + ih - (v / maxR) * ih;
    const bw = iw / bars.length, gap = Math.min(3, bw * 0.25);
    const t0 = new Date(bars[0].start).getTime();
    const ws = opts.window ? new Date(opts.window.start).getTime() : NaN;
    const we = opts.window ? new Date(opts.window.end).getTime() : NaN;
    const yf = opts.yFormat || ((v) => CH.fmt.n0(v));
    const labelEvery = bw >= 22 ? 3 : 6;

    let s = "";
    for (let i = 0; i <= 4; i++) {
      const v = (maxL / 4) * i;
      s += `<line class="${i === 0 ? "baseline" : "gridline"}" x1="${m.l}" x2="${W - m.r}" y1="${y(v)}" y2="${y(v)}"/>`;
      s += `<text x="${m.l - 6}" y="${y(v) + 4}" text-anchor="end">${yf(v)}</text>`;
      if (hasRight) s += `<text x="${W - m.r + 6}" y="${yr((maxR / 4) * i) + 4}" text-anchor="start">${(opts.rightFormat || ((x) => CH.fmt.n0(x)))((maxR / 4) * i)}</text>`;
    }
    if (hasRight && opts.rightUnit) s += `<text x="${W - m.r + 6}" y="${m.t - 8}" text-anchor="start">${opts.rightUnit}</text>`;
    bars.forEach((b, i) => {
      const t = new Date(b.start), ms = t.getTime();
      const x = m.l + i * bw + gap / 2, w = Math.max(1, bw - gap);
      const top = y(b.v), h = m.t + ih - top, r = Math.min(4, w / 2, Math.max(0, h));
      const inWin = ms + 3600e3 > ws && ms < we;
      s += `<path class="bar" data-i="${i}" fill="${b.color}" opacity="${inWin || !opts.window ? 1 : 0.55}" d="M${x},${m.t + ih} V${top + r} Q${x},${top} ${x + r},${top} H${x + w - r} Q${x + w},${top} ${x + w},${top + r} V${m.t + ih} Z"/>`;
      if (localHour(t) % labelEvery === 0) s += `<text x="${x + w / 2}" y="${H - 6}" text-anchor="middle">${CH.fmt.hour(t)}</text>`;
    });
    if (opts.window && ws < t0 + bars.length * 3600e3 && we > t0) {
      const x0 = m.l + Math.max(0, (ws - t0) / 3600e3) * bw;
      const x1 = m.l + Math.min(bars.length, (we - t0) / 3600e3) * bw;
      s += `<rect x="${x0}" y="${m.t - 4}" width="${Math.max(2, x1 - x0)}" height="${ih + 4}" fill="none" stroke="var(--best)" stroke-width="2" rx="4"/>`;
      s += `<text class="bestlabel" x="${(x0 + x1) / 2}" y="${m.t - 8}" text-anchor="middle">${esc(opts.windowLabel || "Selected")}</text>`;
    }
    for (const l of lines) {
      let d = "", pen = false;
      l.values.forEach((v, i) => {
        if (v == null) { pen = false; return; }
        d += `${pen ? "L" : "M"}${(m.l + (i + 0.5) * bw).toFixed(1)},${(l.right ? yr(v) : y(v)).toFixed(1)}`; pen = true;
      });
      s += `<path d="${d}" fill="none" stroke="${l.color}" stroke-width="2"${l.dash ? ' stroke-dasharray="5 4"' : ""}/>`;
    }
    bars.forEach((_, i) => { s += `<rect class="hit" data-i="${i}" x="${m.l + i * bw}" y="${m.t}" width="${bw}" height="${ih}"/>`; });
    el.innerHTML = `<svg viewBox="0 0 ${W} ${H}" style="height:${H}px" role="img" aria-label="${esc(opts.label || "hourly chart")}">${s}</svg>`;
    el.querySelectorAll(".hit").forEach((hEl) => {
      const i = +hEl.dataset.i, bar = el.querySelector(`.bar[data-i="${i}"]`);
      hEl.addEventListener("mousemove", (e) => { bar.classList.add("hover"); CH.tip.show(e, bars[i].tip); });
      hEl.addEventListener("mouseleave", () => { bar.classList.remove("hover"); CH.tip.hide(); });
    });
  }

  window.CHP = { KEY, DEFAULTS, PLAN_NAME, PLAN_SHORT, PERIOD_COLOR, load, save, update, onChange, billFactor, cents, dollars,
    prices, kwhFromWall, evCharge3h, peakWord, readyByInstant, chargePlan, hourChart, localHour, esc,
    money: (v) => moneyFmt.format(v), c1: (v) => c1Fmt.format(v) };

  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", mountNav); else mountNav();
})();
