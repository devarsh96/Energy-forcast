(() => {
  "use strict";

  const TZ = "America/Toronto";
  const REFRESH_MS = 5 * 60 * 1000;
  const GAUGE_MAX = 250;
  const FUEL_ORDER = ["NUCLEAR", "GAS", "HYDRO", "WIND", "SOLAR", "BIOFUEL", "OTHER"];
  const FUEL_NAME = { NUCLEAR: "Nuclear", GAS: "Gas", HYDRO: "Hydro", WIND: "Wind", SOLAR: "Solar", BIOFUEL: "Biofuel", OTHER: "Storage & other" };
  const BANDS = [
    { max: 40, label: "very clean", color: "var(--good)" },
    { max: 80, label: "clean", color: "var(--good)" },
    { max: 130, label: "moderate", color: "var(--warning)" },
    { max: 180, label: "high", color: "var(--serious)" },
    { max: Infinity, label: "very high", color: "var(--critical)" },
  ];
  // Sequential blue ramp, light (clean) -> dark (dirty).
  const RAMP = ["#cde2fb", "#9ec5f4", "#6da7ec", "#3987e5", "#256abf", "#184f95", "#0d366b"];

  const $ = (id) => document.getElementById(id);
  const fmtHour = new Intl.DateTimeFormat("en-CA", { timeZone: TZ, hour: "numeric", hour12: true });
  const fmtTime = new Intl.DateTimeFormat("en-CA", { timeZone: TZ, hour: "numeric", minute: "2-digit", hour12: true });
  const fmtDay = new Intl.DateTimeFormat("en-CA", { timeZone: TZ, weekday: "short", month: "short", day: "numeric" });
  const fmtDayLong = new Intl.DateTimeFormat("en-CA", { timeZone: TZ, weekday: "long", month: "long", day: "numeric" });
  const n0 = new Intl.NumberFormat("en-CA", { maximumFractionDigits: 0 });
  const n1 = new Intl.NumberFormat("en-CA", { maximumFractionDigits: 1 });

  const band = (v) => BANDS.find((b) => v < b.max);
  const hourLabel = (d) => fmtHour.format(d).replace(/\s?([ap])\.?m\.?/i, (_, p) => p.toLowerCase() + "m");
  const timeLabel = (d) => fmtTime.format(d).replace(/\s?([ap])\.?m\.?/i, (_, p) => " " + p.toLowerCase() + "m");
  const localDayKey = (d) => new Intl.DateTimeFormat("en-CA", { timeZone: TZ }).format(d);

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
  const tip = $("tooltip");
  function showTip(evt, html) {
    tip.innerHTML = html;
    tip.hidden = false;
    const pad = 12, r = tip.getBoundingClientRect();
    let x = evt.clientX + pad, y = evt.clientY + pad;
    if (x + r.width > window.innerWidth - 8) x = evt.clientX - r.width - pad;
    if (y + r.height > window.innerHeight - 8) y = evt.clientY - r.height - pad;
    tip.style.left = Math.max(8, x) + "px";
    tip.style.top = Math.max(8, y) + "px";
  }
  function hideTip() { tip.hidden = true; }

  // ---------------------------------------------------------------- gauge
  function polar(cx, cy, r, frac) {
    const a = Math.PI * (1 - frac);
    return [cx + r * Math.cos(a), cy - r * Math.sin(a)];
  }
  function arc(cx, cy, r, f0, f1) {
    const [x0, y0] = polar(cx, cy, r, f0), [x1, y1] = polar(cx, cy, r, f1);
    return `M${x0.toFixed(2)},${y0.toFixed(2)} A${r},${r} 0 0 1 ${x1.toFixed(2)},${y1.toFixed(2)}`;
  }
  function renderGauge(v) {
    const cx = 120, cy = 128, r = 100;
    let s = "";
    // Band zones on a thin outer track
    let lo = 0;
    for (const b of BANDS) {
      const hi = Math.min(b.max, GAUGE_MAX);
      if (hi <= lo) continue;
      s += `<path d="${arc(cx, cy, r + 12, lo / GAUGE_MAX + 0.004, hi / GAUGE_MAX - 0.004)}" stroke="${b.color}" stroke-width="4" fill="none" stroke-linecap="butt" opacity=".9"/>`;
      lo = hi;
    }
    s += `<path d="${arc(cx, cy, r, 0, 1)}" stroke="var(--grid)" stroke-width="14" fill="none" stroke-linecap="round"/>`;
    const f = Math.max(0.005, Math.min(1, v / GAUGE_MAX));
    s += `<path d="${arc(cx, cy, r, 0, f)}" stroke="${band(v).color}" stroke-width="14" fill="none" stroke-linecap="round"/>`;
    for (const t of [0, 50, 100, 150, 200, 250]) {
      const [x, y] = polar(cx, cy, r - 22, t / GAUGE_MAX);
      s += `<text x="${x.toFixed(1)}" y="${(y + 4).toFixed(1)}" text-anchor="middle" font-size="10" fill="var(--muted)">${t}</text>`;
    }
    $("gauge").innerHTML = s;
  }

  // ---------------------------------------------------------------- sections
  function renderCurrent(d) {
    const c = d.current, b = band(c.intensity);
    renderGauge(c.intensity);
    $("now-value").textContent = n0.format(c.intensity);
    const chip = $("now-band");
    chip.textContent = b.label.charAt(0).toUpperCase() + b.label.slice(1);
    chip.style.setProperty("--chip", b.color);
    const ch = c.changeVsPrevHour;
    const chText = ch == null ? "–" : (ch > 0 ? "+" : ch < 0 ? "−" : "") + n0.format(Math.abs(ch));
    $("now-stats").innerHTML =
      `<div><dt>vs last hour</dt><dd>${chText}</dd></div>` +
      `<div><dt>24 h average</dt><dd>${n0.format(c.avg24h)}</dd></div>` +
      `<div><dt>24 h range</dt><dd>${n0.format(c.min24h)}–${n0.format(c.max24h)}</dd></div>`;
    const start = new Date(c.start), end = new Date(start.getTime() + 3600e3);
    $("now-hour").textContent = `Latest IESO hour: ${timeLabel(start)}–${timeLabel(end)} · ${n0.format(c.totalMw)} MW generated`;
  }

  function renderGas(d) {
    const g = d.gasPace;
    $("pace").dataset.level = g.level;
    $("pace-icon").textContent = g.level === "setting" ? "!" : g.level === "supporting" ? "~" : "✓";
    $("pace-head").textContent = g.headline;
    $("pace-detail").textContent = g.detail;
    $("pace-fill").style.width = Math.round(g.flexibleRatio * 100) + "%";

    const mix = [...d.current.mix].sort((a, b) => FUEL_ORDER.indexOf(a.fuel) - FUEL_ORDER.indexOf(b.fuel));
    $("mixbar").innerHTML = mix.map((m) =>
      `<span style="flex:${m.mw} 0 0;background:var(--f-${m.fuel.toLowerCase()})" data-fuel="${m.fuel}"></span>`).join("");
    $("mixbar").setAttribute("aria-label", "Fuel mix: " + mix.map((m) => `${FUEL_NAME[m.fuel]} ${Math.round(m.share * 100)}%`).join(", "));
    $("mixbar").querySelectorAll("span").forEach((el) => {
      const m = mix.find((x) => x.fuel === el.dataset.fuel);
      el.addEventListener("mousemove", (e) => showTip(e,
        `<div class="tt-s">${FUEL_NAME[m.fuel]}</div><div class="tt-v">${n0.format(m.mw)} MW</div>` +
        `<div class="tt-s">${n1.format(m.share * 100)}% of output${m.factor != null ? ` · ${m.factor} g/kWh` : " · not counted"}</div>`));
      el.addEventListener("mouseleave", hideTip);
    });
    $("mixlegend").innerHTML = mix.map((m) =>
      `<li><span class="sw" style="background:var(--f-${m.fuel.toLowerCase()})"></span>${FUEL_NAME[m.fuel]} <b>${Math.round(m.share * 100)}%</b></li>`).join("");
  }

  function renderEv(d) {
    const ev = d.evWindow;
    if (!ev) { $("ev-window").textContent = "Not enough forecast data yet"; return; }
    const s = new Date(ev.start), e = new Date(ev.end);
    $("ev-window").textContent = `${timeLabel(s)} – ${timeLabel(e)}`;
    const today = localDayKey(new Date()), day = localDayKey(s);
    const tomorrow = localDayKey(new Date(Date.now() + 86400e3));
    const dayWord = day === today ? "Today" : day === tomorrow ? "Tomorrow" : fmtDayLong.format(s);
    $("ev-day").textContent = `${dayWord} · about ${n0.format(ev.avgIntensity)} g CO₂e/kWh (${ev.band})`;

    const rows = [
      { label: "Best window", v: ev.avgIntensity, best: true },
      { label: "Right now", v: ev.nowIntensity },
    ];
    if (ev.worstAvgIntensity != null) rows.push({ label: "Worst window", v: ev.worstAvgIntensity });
    const max = Math.max(...rows.map((r) => r.v), 1);
    let html = rows.map((r) =>
      `<div class="ev-row"><span>${r.label}</span><div class="bar${r.best ? " best" : ""}" style="width:${(r.v / max) * 100}%"></div><b>${n0.format(r.v)}</b></div>`).join("");
    const kgBest = ev.kgCo2For40KwhBest, kgNow = ev.kgCo2For40KwhNow;
    if (ev.savingVsNowPct > 1) {
      html += `<div class="ev-save">A 40 kWh charge then emits about <strong>${n1.format(kgBest)} kg CO₂e</strong>, versus ${n1.format(kgNow)} kg right now (${n0.format(ev.savingVsNowPct)}% less).</div>`;
    } else {
      html += `<div class="ev-save">Now is about as clean as it gets today: a 40 kWh charge emits about <strong>${n1.format(kgNow)} kg CO₂e</strong>.</div>`;
    }
    $("ev-compare").innerHTML = html;
    $("ev-basis").textContent = "Forecast basis: " + ev.basis + ".";
  }

  function renderTimeline(d) {
    const pts = [
      ...d.last24h.map((p) => ({ ...p, kind: "actual" })),
      ...d.forecast.map((p) => ({ ...p, kind: "forecast" })),
    ];
    const ev = d.evWindow;
    const bestStart = ev ? new Date(ev.start).getTime() : NaN, bestEnd = ev ? new Date(ev.end).getTime() : NaN;
    pts.forEach((p) => {
      const t = new Date(p.start).getTime();
      p.best = p.kind === "forecast" && t >= bestStart && t < bestEnd;
    });
    const el = $("timeline");
    const W = Math.max(320, el.clientWidth), H = el.clientWidth < 520 ? 200 : 240;
    const m = { l: 34, r: 8, t: 22, b: 24 };
    const iw = W - m.l - m.r, ih = H - m.t - m.b;
    const maxV = Math.max(50, ...pts.map((p) => p.intensity));
    const step = maxV > 150 ? 50 : 25;
    const yMax = Math.ceil(maxV / step) * step;
    const y = (v) => m.t + ih - (v / yMax) * ih;
    const bw = iw / pts.length;
    const gap = Math.min(2, bw * 0.25);

    let s = "";
    for (let v = 0; v <= yMax; v += step) {
      s += `<line class="${v === 0 ? "baseline" : "gridline"}" x1="${m.l}" x2="${W - m.r}" y1="${y(v)}" y2="${y(v)}"/>`;
      s += `<text x="${m.l - 6}" y="${y(v) + 4}" text-anchor="end">${v}</text>`;
    }
    pts.forEach((p, i) => {
      const x = m.l + i * bw + gap / 2, w = Math.max(1, bw - gap);
      const top = y(p.intensity), h = m.t + ih - top;
      const r = Math.min(4, w / 2, h);
      const fill = p.best ? "var(--best)" : p.kind === "actual" ? "var(--actual)" : "var(--forecast)";
      const path = `M${x},${m.t + ih} V${top + r} Q${x},${top} ${x + r},${top} H${x + w - r} Q${x + w},${top} ${x + w},${top + r} V${m.t + ih} Z`;
      s += `<path class="bar" data-i="${i}" d="${path}" fill="${fill}"/>`;
      const date = new Date(p.start);
      if (Math.round(date.getTime() / 3600e3) % 3 === 0 && (bw > 9 || i % 2 === 0)) {
        s += `<text x="${x + w / 2}" y="${H - 6}" text-anchor="middle">${hourLabel(date)}</text>`;
      }
    });
    const nowX = m.l + d.last24h.length * bw;
    s += `<line class="nowline" x1="${nowX}" x2="${nowX}" y1="${m.t - 6}" y2="${m.t + ih}"/>`;
    s += `<text class="nowlabel" x="${nowX - 4}" y="${m.t - 10}" text-anchor="end">Actual</text>`;
    s += `<text class="nowlabel" x="${nowX + 4}" y="${m.t - 10}">Forecast</text>`;
    const bi = pts.findIndex((p) => p.best);
    if (bi >= 0) {
      const bx = m.l + (bi + 1.5) * bw;
      const by = Math.min(...pts.filter((p) => p.best).map((p) => y(p.intensity))) - 6;
      s += `<text class="bestlabel" x="${bx}" y="${Math.max(m.t + 8, by)}" text-anchor="middle">Charge</text>`;
    }
    pts.forEach((p, i) => {
      s += `<rect class="hit" data-i="${i}" x="${m.l + i * bw}" y="${m.t}" width="${bw}" height="${ih}"/>`;
    });
    el.innerHTML = `<svg viewBox="0 0 ${W} ${H}" style="height:${H}px" role="img" aria-label="Hourly carbon intensity, last 24 hours actual and next 24 hours forecast">${s}</svg>`;

    el.querySelectorAll(".hit").forEach((h) => {
      const i = +h.dataset.i, p = pts[i];
      const bar = el.querySelector(`.bar[data-i="${i}"]`);
      h.addEventListener("mousemove", (e) => {
        bar.classList.add("hover");
        const s0 = new Date(p.start), e0 = new Date(s0.getTime() + 3600e3);
        const src = p.kind === "actual" ? "Actual" : p.source === "ieso-schedule" ? "Forecast from IESO schedule" : "Forecast from recent pattern";
        showTip(e, `<div class="tt-s">${fmtDay.format(s0)}, ${timeLabel(s0)}–${timeLabel(e0)}</div>` +
          `<div class="tt-v">${n0.format(p.intensity)} g/kWh</div>` +
          `<div class="tt-s">${src}${p.best ? " · best charging window" : ""}</div>` +
          (p.gasMw ? `<div class="tt-s">Gas ${n0.format(p.gasMw)} MW</div>` : ""));
      });
      h.addEventListener("mouseleave", () => { bar.classList.remove("hover"); hideTip(); });
    });
  }

  function renderHeatmap(d) {
    const days = d.heatmap;
    const vals = days.flatMap((x) => x.values).filter((v) => v != null);
    const lo = Math.min(...vals), hi = Math.max(...vals);
    const t = (v) => (hi > lo ? (v - lo) / (hi - lo) : 0.5);
    let html = `<div></div>`;
    for (let h = 0; h < 24; h++) html += `<div class="collabel">${h % 3 === 0 ? hourLabel(new Date(Date.UTC(2026, 0, 1, h + 5))) : ""}</div>`;
    let minCell = null, maxCell = null;
    days.forEach((day, di) => day.values.forEach((v, h) => {
      if (v == null) return;
      if (!minCell || v < minCell.v) minCell = { di, h, v };
      if (!maxCell || v > maxCell.v) maxCell = { di, h, v };
    }));
    days.forEach((day, di) => {
      const date = new Date(day.date + "T12:00:00-05:00");
      html += `<div class="rowlabel">${fmtDay.format(date)}</div>`;
      day.values.forEach((v, h) => {
        if (v == null) { html += `<div class="cell empty" role="gridcell" aria-label="No data"></div>`; return; }
        const cls = (minCell && minCell.di === di && minCell.h === h) ? " min" : (maxCell && maxCell.di === di && maxCell.h === h) ? " max" : "";
        html += `<div class="cell${cls}" role="gridcell" data-d="${di}" data-h="${h}" aria-label="${fmtDay.format(date)} hour ${h}: ${Math.round(v)} g/kWh" style="background:${rampColor(t(v))}"></div>`;
      });
    });
    const el = $("heatmap");
    el.innerHTML = html;
    el.querySelectorAll(".cell[data-d]").forEach((c) => {
      const day = days[+c.dataset.d], h = +c.dataset.h, v = day.values[h];
      const hourStart = new Date(Date.UTC(2026, 0, 1, h + 5)), hourEnd = new Date(Date.UTC(2026, 0, 1, h + 6));
      c.addEventListener("mousemove", (e) => showTip(e,
        `<div class="tt-s">${fmtDay.format(new Date(day.date + "T12:00:00-05:00"))}, ${hourLabel(hourStart)}–${hourLabel(hourEnd)}</div>` +
        `<div class="tt-v">${n0.format(v)} g/kWh</div><div class="tt-s">${band(v).label}</div>`));
      c.addEventListener("mouseleave", hideTip);
    });
    $("ramp-legend").innerHTML = `<span>${n0.format(lo)}</span><span class="ramp" style="background:linear-gradient(90deg,${RAMP.join(",")})"></span><span>${n0.format(hi)} g/kWh</span>`;
    if (minCell && maxCell) {
      const dl = (c) => fmtDay.format(new Date(days[c.di].date + "T12:00:00-05:00"));
      const hl = (h) => hourLabel(new Date(Date.UTC(2026, 0, 1, h + 5)));
      $("heat-summary").textContent =
        `▼ Cleanest hour: ${dl(minCell)} at ${hl(minCell.h)} (${n0.format(minCell.v)} g/kWh). ` +
        `▲ Dirtiest: ${dl(maxCell)} at ${hl(maxCell.h)} (${n0.format(maxCell.v)} g/kWh).`;
    }
  }

  function renderNotes(d) {
    const tb = document.querySelector("#factors tbody");
    tb.innerHTML = FUEL_ORDER.filter((f) => d.emissionFactors[f] != null)
      .map((f) => `<tr><td>${FUEL_NAME[f]}</td><td>${d.emissionFactors[f]}</td></tr>`).join("") +
      `<tr><td>Storage & other</td><td>not counted</td></tr>`;
    $("notes").innerHTML = d.notes.map((n) => `<li>${n.replace(/</g, "&lt;")}</li>`).join("");
  }

  // ---------------------------------------------------------------- load loop
  let last = null;
  function renderAll(d) {
    last = d;
    renderCurrent(d);
    renderGas(d);
    renderEv(d);
    renderTimeline(d);
    renderHeatmap(d);
    renderNotes(d);
    if (window.renderExtras) {
      try { window.renderExtras(d); } catch (e) { console.error("extra charts", e); }
    }
    $("app").setAttribute("aria-busy", "false");
    $("updated").textContent = `Updated ${timeLabel(new Date(d.generatedAt))} · refreshes every 5 min`;
  }

  async function load() {
    try {
      const res = await fetch("api/dashboard", { cache: "no-store" });
      if (res.status === 503) {
        $("updated").textContent = "Fetching the first IESO reports…";
        setTimeout(load, 5000);
        return;
      }
      if (!res.ok) throw new Error("HTTP " + res.status);
      renderAll(await res.json());
    } catch (e) {
      $("updated").innerHTML = `<span class="error">Could not load data (${e.message}). Retrying…</span>`;
    }
    setTimeout(load, REFRESH_MS);
  }

  let rt;
  window.addEventListener("resize", () => { clearTimeout(rt); rt = setTimeout(() => last && renderTimeline(last), 150); });
  load();
})();
