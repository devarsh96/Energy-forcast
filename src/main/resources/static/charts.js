/* Clean Hour shared helpers: formatting, tooltip, and small SVG charts that match the dashboard style. */
(() => {
  "use strict";

  const TZ = "America/Toronto";
  const ampm = (s) => s.replace(/\s?([ap])\.?m\.?/i, (_, p) => " " + p.toLowerCase() + "m");
  const fmtHour = new Intl.DateTimeFormat("en-CA", { timeZone: TZ, hour: "numeric", hour12: true });
  const fmtTime = new Intl.DateTimeFormat("en-CA", { timeZone: TZ, hour: "numeric", minute: "2-digit", hour12: true });
  const fmtDay = new Intl.DateTimeFormat("en-CA", { timeZone: TZ, weekday: "short", month: "short", day: "numeric" });
  const fmtDate = new Intl.DateTimeFormat("en-CA", { timeZone: TZ, month: "short", day: "numeric" });
  const fmtKey = new Intl.DateTimeFormat("en-CA", { timeZone: TZ });
  const n0 = new Intl.NumberFormat("en-CA", { maximumFractionDigits: 0 });
  const n1 = new Intl.NumberFormat("en-CA", { maximumFractionDigits: 1 });

  const FUELS = ["NUCLEAR", "GAS", "HYDRO", "WIND", "SOLAR", "BIOFUEL", "OTHER"];
  const FUEL_NAME = { NUCLEAR: "Nuclear", GAS: "Gas", HYDRO: "Hydro", WIND: "Wind", SOLAR: "Solar", BIOFUEL: "Biofuel", OTHER: "Storage & other" };
  const RAMP = ["#cde2fb", "#9ec5f4", "#6da7ec", "#3987e5", "#256abf", "#184f95", "#0d366b"];

  const fmt = {
    hour: (d) => ampm(fmtHour.format(d)).replace(" ", ""),
    time: (d) => ampm(fmtTime.format(d)),
    day: (d) => fmtDay.format(d),
    date: (d) => fmtDate.format(d),
    key: (d) => fmtKey.format(d),
    n0: (v) => n0.format(v),
    n1: (v) => n1.format(v),
    /** "2026-11-08" -> Date at local noon, safe for labels */
    isoDay: (s) => new Date(s + "T12:00:00-05:00"),
  };

  function rampColor(t) {
    t = Math.max(0, Math.min(1, t));
    const x = t * (RAMP.length - 1);
    const i = Math.min(RAMP.length - 2, Math.floor(x));
    const f = x - i;
    const a = hex(RAMP[i]), b = hex(RAMP[i + 1]);
    return `rgb(${a.map((c, k) => Math.round(c + (b[k] - c) * f)).join(",")})`;
  }
  function hex(h) { return [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16)); }

  // ---------------------------------------------------------------- tooltip
  function tipEl() {
    let t = document.getElementById("tooltip");
    if (!t) {
      t = document.createElement("div");
      t.id = "tooltip"; t.className = "tooltip"; t.hidden = true; t.setAttribute("role", "status");
      document.body.appendChild(t);
    }
    return t;
  }
  const tip = {
    show(evt, html) {
      const t = tipEl();
      t.innerHTML = html;
      t.hidden = false;
      const pad = 12, r = t.getBoundingClientRect();
      let x = evt.clientX + pad, y = evt.clientY + pad;
      if (x + r.width > window.innerWidth - 8) x = evt.clientX - r.width - pad;
      if (y + r.height > window.innerHeight - 8) y = evt.clientY - r.height - pad;
      t.style.left = Math.max(8, x) + "px";
      t.style.top = Math.max(8, y) + "px";
    },
    hide() { tipEl().hidden = true; },
  };

  function niceMax(v) {
    if (v <= 0) return 1;
    const p = Math.pow(10, Math.floor(Math.log10(v)));
    for (const m of [1, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10]) if (m * p >= v) return m * p;
    return 10 * p;
  }
  function ticks(max, count = 4) {
    const step = max / count;
    return Array.from({ length: count + 1 }, (_, i) => i * step);
  }
  const compact = (v) => (v >= 10000 ? n0.format(v / 1000) + "k" : v >= 1000 ? n1.format(v / 1000) + "k" : n0.format(v));

  function size(el, h) {
    const W = Math.max(300, el.clientWidth || 600);
    const H = h || (W < 520 ? 200 : 240);
    return { W, H };
  }

  /**
   * Time-series chart: lines and/or one stacked area group, with a crosshair tooltip.
   * opts.points: [{t: Date, values: {key: number|null}}]
   * opts.series: [{key, name, color, dash?, area?}]  (area series are stacked in order)
   */
  function timeChart(el, opts) {
    const { W, H } = size(el, opts.height);
    const m = { l: 42, r: 10, t: 14, b: 24 };
    const iw = W - m.l - m.r, ih = H - m.t - m.b;
    const pts = opts.points;
    if (!pts.length) { el.innerHTML = `<p class="empty-note">No data yet.</p>`; return; }
    const t0 = pts[0].t.getTime(), t1 = pts[pts.length - 1].t.getTime();
    const x = (t) => m.l + (t1 === t0 ? iw / 2 : ((t - t0) / (t1 - t0)) * iw);
    const areas = opts.series.filter((s) => s.area), lines = opts.series.filter((s) => !s.area);

    const stacks = pts.map((p) => {
      let acc = 0;
      const out = {};
      for (const s of areas) { const v = p.values[s.key] || 0; out[s.key] = [acc, acc + v]; acc += v; }
      out.__total = acc;
      return out;
    });
    let maxV = 0;
    stacks.forEach((s) => (maxV = Math.max(maxV, s.__total)));
    pts.forEach((p) => lines.forEach((s) => { const v = p.values[s.key]; if (v != null) maxV = Math.max(maxV, v); }));
    const yMax = opts.yMax || niceMax(maxV * 1.05);
    const y = (v) => m.t + ih - (v / yMax) * ih;

    let s = "";
    for (const v of ticks(yMax)) {
      s += `<line class="${v === 0 ? "baseline" : "gridline"}" x1="${m.l}" x2="${W - m.r}" y1="${y(v)}" y2="${y(v)}"/>`;
      s += `<text x="${m.l - 6}" y="${y(v) + 4}" text-anchor="end">${(opts.yFormat || compact)(v)}</text>`;
    }
    // x labels: pick a step so labels don't collide
    const spanH = (t1 - t0) / 3600e3;
    const labelEvery = spanH <= 30 ? 3 : spanH <= 80 ? 12 : 24;
    let lastX = -1e9;
    pts.forEach((p) => {
      const d = p.t, hr = Number(fmtKeyHour(d));
      if (hr % labelEvery !== 0) return;
      const px = x(d.getTime());
      if (px - lastX < 46) return;
      lastX = px;
      const label = labelEvery >= 24 ? fmt.date(d) : labelEvery === 12 ? (hr === 0 ? fmt.date(d) : fmt.hour(d)) : fmt.hour(d);
      s += `<text x="${px}" y="${H - 6}" text-anchor="middle">${label}</text>`;
    });

    // stacked areas (2px surface seams between bands)
    for (const a of areas) {
      let top = "", bottom = "";
      pts.forEach((p, i) => { top += `${i ? "L" : "M"}${x(p.t.getTime()).toFixed(1)},${y(stacks[i][a.key][1]).toFixed(1)}`; });
      for (let i = pts.length - 1; i >= 0; i--) bottom += `L${x(pts[i].t.getTime()).toFixed(1)},${y(stacks[i][a.key][0]).toFixed(1)}`;
      s += `<path d="${top}${bottom}Z" fill="${a.color}" stroke="var(--surface)" stroke-width="1" stroke-linejoin="round"/>`;
    }
    // lines (break at gaps)
    for (const l of lines) {
      let d = "", pen = false;
      pts.forEach((p) => {
        const v = p.values[l.key];
        if (v == null) { pen = false; return; }
        d += `${pen ? "L" : "M"}${x(p.t.getTime()).toFixed(1)},${y(v).toFixed(1)}`;
        pen = true;
      });
      s += `<path d="${d}" fill="none" stroke="${l.color}" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"${l.dash ? ' stroke-dasharray="5 4"' : ""}/>`;
    }
    if (opts.nowT) {
      const nx = x(opts.nowT.getTime());
      if (nx >= m.l && nx <= W - m.r) {
        s += `<line class="nowline" x1="${nx}" x2="${nx}" y1="${m.t}" y2="${m.t + ih}"/>`;
        s += `<text class="nowlabel" x="${nx + 4}" y="${m.t + 10}">Now</text>`;
      }
    }
    s += `<g class="xhair" visibility="hidden"><line x1="0" x2="0" y1="${m.t}" y2="${m.t + ih}"/>` +
      lines.map((l) => `<circle data-k="${l.key}" r="4" fill="${l.color}" stroke="var(--surface)" stroke-width="2"/>`).join("") + `</g>`;
    s += `<rect class="hitarea" x="${m.l}" y="${m.t}" width="${iw}" height="${ih}" fill="transparent"/>`;
    el.innerHTML = `<svg viewBox="0 0 ${W} ${H}" style="height:${H}px" role="img" aria-label="${opts.label || "chart"}">${s}</svg>`;

    const svg = el.querySelector("svg"), xh = svg.querySelector(".xhair"), hit = svg.querySelector(".hitarea");
    hit.addEventListener("mousemove", (e) => {
      const r = svg.getBoundingClientRect();
      const px = ((e.clientX - r.left) / r.width) * W;
      const tt = t0 + ((px - m.l) / iw) * (t1 - t0);
      let best = 0, bd = Infinity;
      pts.forEach((p, i) => { const d = Math.abs(p.t.getTime() - tt); if (d < bd) { bd = d; best = i; } });
      const p = pts[best], cx = x(p.t.getTime());
      xh.setAttribute("visibility", "visible");
      xh.querySelector("line").setAttribute("x1", cx); xh.querySelector("line").setAttribute("x2", cx);
      xh.querySelectorAll("circle").forEach((c) => {
        const v = p.values[c.dataset.k];
        if (v == null) { c.setAttribute("visibility", "hidden"); return; }
        c.setAttribute("visibility", "visible"); c.setAttribute("cx", cx); c.setAttribute("cy", y(v));
      });
      tip.show(e, opts.tooltip(p));
    });
    hit.addEventListener("mouseleave", () => { xh.setAttribute("visibility", "hidden"); tip.hide(); });
  }
  const fmtHH = new Intl.DateTimeFormat("en-CA", { timeZone: TZ, hour: "2-digit", hourCycle: "h23" });
  function fmtKeyHour(d) { return fmtHH.format(d); }

  /** Vertical bars. opts.bars: [{label, v, color?, tip}] */
  function barChart(el, opts) {
    const { W, H } = size(el, opts.height);
    const m = { l: 42, r: 10, t: 14, b: 24 };
    const iw = W - m.l - m.r, ih = H - m.t - m.b;
    const bars = opts.bars;
    if (!bars.length) { el.innerHTML = `<p class="empty-note">No data yet.</p>`; return; }
    const yMax = opts.yMax || niceMax(Math.max(...bars.map((b) => b.v)) * 1.05);
    const y = (v) => m.t + ih - (v / yMax) * ih;
    const slots = Math.max(bars.length, opts.slots || 0);
    const bw = iw / slots, gap = Math.min(bw * 0.3, 8);
    let s = "";
    for (const v of ticks(yMax)) {
      s += `<line class="${v === 0 ? "baseline" : "gridline"}" x1="${m.l}" x2="${W - m.r}" y1="${y(v)}" y2="${y(v)}"/>`;
      s += `<text x="${m.l - 6}" y="${y(v) + 4}" text-anchor="end">${(opts.yFormat || compact)(v)}</text>`;
    }
    const every = Math.ceil(bars.length / Math.max(1, Math.floor(iw / 44)));
    bars.forEach((b, i) => {
      const bx = m.l + i * bw + gap / 2, w = Math.max(1, bw - gap);
      const top = y(b.v), h = m.t + ih - top, r = Math.min(4, w / 2, h);
      s += `<path class="bar" data-i="${i}" fill="${b.color || opts.color || "var(--f-nuclear)"}" d="M${bx},${m.t + ih} V${top + r} Q${bx},${top} ${bx + r},${top} H${bx + w - r} Q${bx + w},${top} ${bx + w},${top + r} V${m.t + ih} Z"/>`;
      if (i % every === 0) s += `<text x="${bx + w / 2}" y="${H - 6}" text-anchor="middle">${b.label}</text>`;
      if (opts.valueLabels) s += `<text class="vlabel" x="${bx + w / 2}" y="${top - 5}" text-anchor="middle">${opts.valueLabels(b)}</text>`;
    });
    bars.forEach((b, i) => { s += `<rect class="hit" data-i="${i}" x="${m.l + i * bw}" y="${m.t}" width="${bw}" height="${ih}"/>`; });
    el.innerHTML = `<svg viewBox="0 0 ${W} ${H}" style="height:${H}px" role="img" aria-label="${opts.label || "bar chart"}">${s}</svg>`;
    el.querySelectorAll(".hit").forEach((h) => {
      const i = +h.dataset.i, bar = el.querySelector(`.bar[data-i="${i}"]`);
      h.addEventListener("mousemove", (e) => { bar.classList.add("hover"); tip.show(e, bars[i].tip); });
      h.addEventListener("mouseleave", () => { bar.classList.remove("hover"); tip.hide(); });
    });
  }

  /** Donut with a centre figure. opts.slices: [{label, v, color, tip}] */
  function donut(el, opts) {
    const total = opts.slices.reduce((a, b) => a + b.v, 0);
    const R = 80, r = 54, c = 100;
    let a0 = -Math.PI / 2, s = "";
    const gapA = opts.slices.length > 1 ? 0.012 : 0;
    opts.slices.forEach((sl, i) => {
      if (sl.v <= 0) return;
      const a1 = a0 + (sl.v / total) * Math.PI * 2;
      const s0 = a0 + gapA, s1 = Math.max(s0 + 0.001, a1 - gapA);
      const large = s1 - s0 > Math.PI ? 1 : 0;
      const p = (rad, ang) => `${(c + rad * Math.cos(ang)).toFixed(2)},${(c + rad * Math.sin(ang)).toFixed(2)}`;
      s += `<path class="slice" data-i="${i}" fill="${sl.color}" d="M${p(R, s0)} A${R},${R} 0 ${large} 1 ${p(R, s1)} L${p(r, s1)} A${r},${r} 0 ${large} 0 ${p(r, s0)} Z"/>`;
      a0 = a1;
    });
    s += `<text x="100" y="98" text-anchor="middle" class="donut-v">${opts.centerValue || ""}</text>`;
    s += `<text x="100" y="118" text-anchor="middle" class="donut-l">${opts.centerLabel || ""}</text>`;
    el.innerHTML = `<svg viewBox="0 0 200 200" role="img" aria-label="${opts.label || "donut chart"}">${s}</svg>`;
    el.querySelectorAll(".slice").forEach((p) => {
      const sl = opts.slices[+p.dataset.i];
      p.addEventListener("mousemove", (e) => tip.show(e, sl.tip));
      p.addEventListener("mouseleave", tip.hide);
    });
  }

  /** Calendar heatmap: one square per day, weeks as columns (GitHub style). opts.days: [{date:"YYYY-MM-DD", v, tip}] */
  function calendar(el, opts) {
    const days = opts.days;
    if (!days.length) { el.innerHTML = `<p class="empty-note">No data yet.</p>`; return; }
    const vals = days.map((d) => d.v);
    const lo = Math.min(...vals), hi = Math.max(...vals);
    const first = new Date(days[0].date + "T12:00:00Z");
    const startDow = (first.getUTCDay() + 6) % 7; // Monday = 0
    const byKey = new Map(days.map((d) => [d.date, d]));
    const last = new Date(days[days.length - 1].date + "T12:00:00Z");
    const totalDays = Math.round((last - first) / 86400e3) + 1;
    const weeks = Math.ceil((startDow + totalDays) / 7);
    const cell = 14, gap = 3, left = 30, top = 18;
    const W = left + weeks * (cell + gap), H = top + 7 * (cell + gap);
    let s = "";
    ["Mon", "", "Wed", "", "Fri", "", ""].forEach((l, i) => {
      if (l) s += `<text x="0" y="${top + i * (cell + gap) + 11}" class="cal-l">${l}</text>`;
    });
    let lastMonth = -1;
    for (let i = 0; i < totalDays; i++) {
      const d = new Date(first.getTime() + i * 86400e3);
      const key = d.toISOString().slice(0, 10);
      const idx = startDow + i, col = Math.floor(idx / 7), row = idx % 7;
      const cx = left + col * (cell + gap), cy = top + row * (cell + gap);
      if (d.getUTCMonth() !== lastMonth && row <= 1) {
        lastMonth = d.getUTCMonth();
        s += `<text x="${cx}" y="10" class="cal-l">${d.toLocaleString("en-CA", { month: "short", timeZone: "UTC" })}</text>`;
      }
      const item = byKey.get(key);
      if (!item) { s += `<rect x="${cx}" y="${cy}" width="${cell}" height="${cell}" rx="3" class="cal-empty"/>`; continue; }
      const t = hi > lo ? (item.v - lo) / (hi - lo) : 0.5;
      s += `<rect class="cal" data-k="${key}" x="${cx}" y="${cy}" width="${cell}" height="${cell}" rx="3" fill="${rampColor(t)}"/>`;
    }
    el.innerHTML = `<svg viewBox="0 0 ${W} ${H}" style="max-width:${W * 1.6}px" role="img" aria-label="${opts.label || "calendar heatmap"}">${s}</svg>`;
    el.querySelectorAll(".cal").forEach((r) => {
      const item = byKey.get(r.dataset.k);
      r.addEventListener("mousemove", (e) => tip.show(e, item.tip));
      r.addEventListener("mouseleave", tip.hide);
    });
    return { lo, hi };
  }

  function rampLegend(lo, hi, unit) {
    return `<span>${n0.format(lo)}</span><span class="ramp" style="background:linear-gradient(90deg,${RAMP.join(",")})"></span><span>${n0.format(hi)} ${unit || "g/kWh"}</span>`;
  }

  function legend(items) {
    return items.map((i) => `<li><span class="sw${i.dash ? " sw-dash" : ""}" style="${i.dash ? `border-color:${i.color}` : `background:${i.color}`}"></span>${i.label}</li>`).join("");
  }

  async function getJson(url) {
    const res = await fetch(url, { cache: "no-store" });
    if (!res.ok) {
      let msg = "HTTP " + res.status;
      try { const j = await res.json(); msg = j.message || j.error || msg; } catch (_) { /* ignore */ }
      const err = new Error(msg); err.status = res.status; throw err;
    }
    return res.json();
  }

  function onResize(fn) {
    let rt;
    window.addEventListener("resize", () => { clearTimeout(rt); rt = setTimeout(fn, 150); });
  }

  window.CH = { TZ, FUELS, FUEL_NAME, RAMP, fmt, tip, rampColor, timeChart, barChart, donut, calendar, rampLegend, legend, getJson, onResize, niceMax };
})();
