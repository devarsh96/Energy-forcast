(() => {
  "use strict";
  const { fmt, FUELS, FUEL_NAME } = CH;
  const $ = (id) => document.getElementById(id);
  const fuelColor = (f) => `var(--f-${f.toLowerCase()})`;
  const monthLabel = (s) => new Date(s + "-15T12:00:00Z").toLocaleString("en-CA", { month: "long", year: "numeric", timeZone: "UTC" });
  const hourName = (h) => fmt.hour(new Date(Date.UTC(2026, 0, 1, h + 5)));
  let report = null;

  async function init() {
    let months = [];
    try { months = (await CH.getJson("api/v1/months")).months; } catch (_) { /* handled below */ }
    const want = new URLSearchParams(location.search).get("month");
    const month = months.includes(want) ? want : (months[1] || months[0]);
    $("month").innerHTML = months.map((m, i) => `<option value="${m}"${m === month ? " selected" : ""}>${monthLabel(m)}${i === 0 ? " (so far)" : ""}</option>`).join("");
    $("month").addEventListener("change", () => {
      history.replaceState(null, "", "?month=" + $("month").value);
      load($("month").value);
    });
    $("pdf").addEventListener("click", downloadPdf);
    if (month) load(month);
    else $("summary").innerHTML = `<p class="error">No data yet. The app is still loading history from IESO.</p>`;
  }

  async function load(month) {
    try {
      report = await CH.getJson("api/v1/monthly/" + month);
      render();
    } catch (e) {
      $("summary").innerHTML = `<p class="error">Could not load the report (${e.message}).</p>`;
    }
  }

  function render() {
    const r = report;
    const name = monthLabel(r.month);
    document.title = `${name} carbon report`;
    $("title").textContent = `${name} carbon report`;
    $("subtitle").textContent = `Ontario electricity grid · ${fmt.n0(r.hours)} of ${fmt.n0(r.expectedHours)} hours` + (r.inProgress ? " (month in progress)" : "");

    const diff = r.prevMonthAvgIntensity != null ? r.avgIntensity - r.prevMonthAvgIntensity : null;
    const tiles = [
      ["Average intensity", `${fmt.n0(r.avgIntensity)}`, "g CO₂e/kWh" + (diff != null ? ` · ${diff >= 0 ? "+" : "−"}${fmt.n0(Math.abs(diff))} vs last month` : "")],
      ["Emissions", r.totalTonnes >= 1e6 ? `${fmt.n1(r.totalTonnes / 1e6)}M` : `${fmt.n0(r.totalTonnes / 1000)}k`, "tonnes CO₂e"],
      ["Generation", `${fmt.n1(r.totalMwh / 1e6)}`, "TWh"],
      ["Gas-led hours", `${fmt.n0(r.gasLedHours)}`, `${fmt.n0(r.gasLedShare * 100)}% of hours`],
      ["Gas share of output", `${fmt.n0(r.gasShareOfOutput * 100)}%`, `${fmt.n0(r.gasAvgMw)} MW on average`],
      ["Day-ahead forecast error", r.forecastMeanAbsError != null ? `±${fmt.n0(r.forecastMeanAbsError)}` : "–",
        r.forecastHours ? `g/kWh over ${fmt.n0(r.forecastHours)} hours` : "no saved forecasts this month"],
    ];
    $("tiles").innerHTML = tiles.map(([k, v, s]) => `<div class="tile"><div class="k">${k}</div><div class="v">${v}</div><div class="s">${s}</div></div>`).join("");

    const b = r.bestBlock, w = r.worstBlock;
    const p = [];
    p.push(`<p>Ontario's grid averaged <strong>${fmt.n0(r.avgIntensity)} g CO₂e/kWh</strong> in ${name}` +
      (diff != null ? `, ${Math.abs(diff) < 1 ? "about the same as" : diff < 0 ? fmt.n0(-diff) + " g lower than" : fmt.n0(diff) + " g higher than"} the month before.` : ".") + ` ` +
      `Generation of ${fmt.n1(r.totalMwh / 1e6)} TWh produced about ${fmt.n0(r.totalTonnes / 1000)} thousand tonnes of CO₂e.</p>`);
    if (b && w) p.push(`<p>On average the <strong>cleanest time of day was ${hourName(b.startHour)}–${hourName(b.endHour)}</strong> (${fmt.n0(b.intensity)} g/kWh) and the dirtiest was ${hourName(w.startHour)}–${hourName(w.endHour)} (${fmt.n0(w.intensity)} g/kWh). Shifting flexible use like EV charging into the clean block cuts its emissions by about ${fmt.n0((1 - b.intensity / w.intensity) * 100)}% compared with the dirtiest block.</p>`);
    p.push(`<p>Gas led on <strong>${fmt.n0(r.gasLedHours)} hours</strong> (${fmt.n0(r.gasLedShare * 100)}%) and supplied ${fmt.n0(r.gasShareOfOutput * 100)}% of output.` +
      (r.cleanestDay && r.dirtiestDay ? ` The cleanest day was ${fmt.day(fmt.isoDay(r.cleanestDay.date))} (${fmt.n0(r.cleanestDay.intensity)} g/kWh); the dirtiest was ${fmt.day(fmt.isoDay(r.dirtiestDay.date))} (${fmt.n0(r.dirtiestDay.intensity)} g/kWh).` : "") + `</p>`);
    $("summary").innerHTML = p.join("");

    // daily bars, coloured on the sequential ramp
    const vals = r.daily.map((d) => d.intensity), lo = Math.min(...vals), hi = Math.max(...vals);
    CH.barChart($("daily-chart"), {
      label: "Average intensity by day",
      bars: r.daily.map((d) => {
        const dt = fmt.isoDay(d.date);
        return {
          label: String(Number(d.date.slice(8))), v: d.intensity,
          color: CH.rampColor(hi > lo ? 0.15 + 0.85 * (d.intensity - lo) / (hi - lo) : 0.5),
          tip: `<div class="tt-s">${fmt.day(dt)}${d.hours < 24 ? ` (${d.hours} h)` : ""}</div><div class="tt-v">${fmt.n0(d.intensity)} g/kWh</div>` +
            `<div class="tt-s">${fmt.n0(d.tonnes / 1000)}k t CO₂e · ${fmt.n0(d.mwh / 1000)} GWh</div>`,
        };
      }),
      yFormat: (v) => fmt.n0(v),
    });
    $("daily-note").textContent = `Darker bars are dirtier days. Range ${fmt.n0(lo)}–${fmt.n0(hi)} g/kWh.`;

    // hour-of-day profile
    const inBlock = (h, blk) => blk && [0, 1, 2].some((k) => (blk.startHour + k) % 24 === h);
    CH.barChart($("hour-chart"), {
      label: "Average intensity by hour of day",
      bars: r.hourOfDay.map((v, h) => ({
        label: hourName(h), v: v || 0,
        color: inBlock(h, b) ? "var(--best)" : inBlock(h, w) ? "var(--f-gas)" : "var(--forecast)",
        tip: `<div class="tt-s">${hourName(h)}–${hourName((h + 1) % 24)}</div><div class="tt-v">${v == null ? "–" : fmt.n0(v)} g/kWh</div>` +
          `${inBlock(h, b) ? '<div class="tt-s">Cleanest block</div>' : inBlock(h, w) ? '<div class="tt-s">Dirtiest block</div>' : ""}`,
      })),
      yFormat: (v) => fmt.n0(v),
    });

    CH.barChart($("weekday-chart"), {
      label: "Average intensity by weekday",
      color: "var(--f-nuclear)",
      bars: r.weekdays.map((d) => ({ label: d.day.slice(0, 3), v: d.intensity, tip: `<div class="tt-s">${d.day}</div><div class="tt-v">${fmt.n0(d.intensity)} g/kWh</div>` })),
      yFormat: (v) => fmt.n0(v),
      valueLabels: (bar) => fmt.n0(bar.v),
    });
    if (r.weekdays.length) {
      const best = r.weekdays.reduce((a, c) => (c.intensity < a.intensity ? c : a));
      $("weekday-note").textContent = `${best.day} was the cleanest day of the week on average.`;
    }

    const hl = [];
    if (r.cleanestDay) hl.push(["Cleanest day", `${fmt.day(fmt.isoDay(r.cleanestDay.date))}`, `${fmt.n0(r.cleanestDay.intensity)} g/kWh`]);
    if (r.dirtiestDay) hl.push(["Dirtiest day", `${fmt.day(fmt.isoDay(r.dirtiestDay.date))}`, `${fmt.n0(r.dirtiestDay.intensity)} g/kWh`]);
    if (r.cleanestHour) hl.push(["Cleanest hour", `${fmt.day(new Date(r.cleanestHour.start))}, ${fmt.time(new Date(r.cleanestHour.start))}`, `${fmt.n0(r.cleanestHour.intensity)} g/kWh`]);
    if (r.dirtiestHour) hl.push(["Dirtiest hour", `${fmt.day(new Date(r.dirtiestHour.start))}, ${fmt.time(new Date(r.dirtiestHour.start))}`, `${fmt.n0(r.dirtiestHour.intensity)} g/kWh`]);
    if (b) hl.push(["Best time of day", `${hourName(b.startHour)}–${hourName(b.endHour)}`, `${fmt.n0(b.intensity)} g/kWh`]);
    if (w) hl.push(["Worst time of day", `${hourName(w.startHour)}–${hourName(w.endHour)}`, `${fmt.n0(w.intensity)} g/kWh`]);
    $("highlights").querySelector("tbody").innerHTML = hl.map(([k, a, v]) => `<tr><td>${k}</td><td>${a}</td><td>${v}</td></tr>`).join("");
    $("gas-rule").textContent = "Gas-led hours: " + r.gasRule + ".";

    const shares = r.fuelShares.slice().sort((a, c) => FUELS.indexOf(a.fuel) - FUELS.indexOf(c.fuel));
    const low = shares.filter((s) => s.fuel !== "GAS" && s.fuel !== "OTHER").reduce((a, s) => a + s.share, 0);
    CH.donut($("donut"), {
      label: "Fuel shares for the month", centerValue: Math.round(low * 100) + "%", centerLabel: "low-carbon",
      slices: shares.map((s) => ({ v: s.mw, color: fuelColor(s.fuel), tip: `<div class="tt-s">${FUEL_NAME[s.fuel]}</div><div class="tt-v">${fmt.n1(s.share * 100)}%</div>` })),
    });
    $("donut-legend").innerHTML = shares.map((s) => `<li><span class="sw" style="background:${fuelColor(s.fuel)}"></span>${FUEL_NAME[s.fuel]} <b>${fmt.n1(s.share * 100)}%</b></li>`).join("");
    $("generated").textContent = `Generated ${fmt.day(new Date())}, ${fmt.time(new Date())} by Clean Hour.`;
  }

  // ---------------------------------------------------------------- PDF
  function loadScript(src) {
    return new Promise((resolve, reject) => {
      if (document.querySelector(`script[src="${src}"]`)) return resolve();
      const s = document.createElement("script");
      s.src = src; s.onload = resolve; s.onerror = () => reject(new Error("Could not load " + src));
      document.head.appendChild(s);
    });
  }

  /** html2canvas cannot resolve CSS variables inside SVG, so bake computed colours into the elements. */
  function bakeSvgColors(root) {
    root.querySelectorAll("svg *").forEach((n) => {
      const cs = getComputedStyle(n);
      if (cs.fill && cs.fill !== "none") n.setAttribute("fill", cs.fill);
      if (cs.stroke && cs.stroke !== "none") n.setAttribute("stroke", cs.stroke);
      n.style.fontFamily = cs.fontFamily;
      n.style.fontSize = cs.fontSize;
      n.style.fontWeight = cs.fontWeight;
    });
  }

  async function downloadPdf() {
    if (!report) return;
    const btn = $("pdf");
    btn.disabled = true;
    btn.textContent = "Preparing PDF…";
    const root = document.documentElement;
    const prevTheme = root.getAttribute("data-theme");
    try {
      await loadScript("https://cdnjs.cloudflare.com/ajax/libs/html2canvas/1.4.1/html2canvas.min.js");
      await loadScript("https://cdnjs.cloudflare.com/ajax/libs/jspdf/2.5.1/jspdf.umd.min.js");
      // Render in the light theme at a fixed width so the PDF looks the same on every device.
      root.setAttribute("data-theme", "light");
      document.body.classList.add("pdf-capture");
      const main = $("report");
      const prevWidth = main.style.width;
      main.style.width = "1000px";
      render();
      bakeSvgColors(main);

      const { jsPDF } = window.jspdf;
      const pdf = new jsPDF({ unit: "pt", format: "a4" });
      const pw = pdf.internal.pageSize.getWidth(), ph = pdf.internal.pageSize.getHeight(), margin = 32;
      const usable = pw - margin * 2;
      pdf.setFont("helvetica", "bold"); pdf.setFontSize(18);
      pdf.text($("title").textContent, margin, margin + 10);
      pdf.setFont("helvetica", "normal"); pdf.setFontSize(10); pdf.setTextColor(90);
      pdf.text($("subtitle").textContent, margin, margin + 28);
      let y = margin + 44;
      const blocks = [...main.children];
      for (const block of blocks) {
        const canvas = await window.html2canvas(block, { scale: 2, backgroundColor: "#f9f9f7", logging: false, useCORS: true });
        const h = (canvas.height / canvas.width) * usable;
        if (y + h > ph - margin && y > margin + 50) { pdf.addPage(); y = margin; }
        pdf.addImage(canvas.toDataURL("image/jpeg", 0.9), "JPEG", margin, y, usable, Math.min(h, ph - margin * 2));
        y += h + 12;
      }
      const pages = pdf.getNumberOfPages();
      for (let i = 1; i <= pages; i++) {
        pdf.setPage(i); pdf.setFontSize(8); pdf.setTextColor(140);
        pdf.text(`Clean Hour Ontario · ${monthLabel(report.month)} · page ${i} of ${pages}`, margin, ph - 14);
      }
      pdf.save(`clean-hour-report-${report.month}.pdf`);
      main.style.width = prevWidth;
    } catch (e) {
      alert("Could not create the PDF: " + e.message);
    } finally {
      if (prevTheme == null) root.removeAttribute("data-theme"); else root.setAttribute("data-theme", prevTheme);
      document.body.classList.remove("pdf-capture");
      $("report").style.width = "";
      render();
      btn.disabled = false;
      btn.textContent = "Download PDF";
    }
  }

  init();
  CH.onResize(() => report && render());
})();
