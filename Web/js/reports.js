// PDF reports (A4, vector): full patient record with the 2D dental chart, financial report,
// appointments list, invoice and receipt. Same layout and content as the Android app
// (app/src/main/java/com/spala/dental/ReportPdf.kt). Uses jsPDF + AutoTable from /vendor.
import { zoned, fmtIso, fmtMs, currencyDigits, todayIn, ageFrom, setFormatLang } from "./util.js";
import { pt, isLatin, currentLang } from "./i18n.js";
import * as D from "./domain.js";
import { S } from "./store.js";

let libs = null;
function loadScript(src) {
  return new Promise((res, rej) => { const s = document.createElement("script"); s.src = src; s.onload = res; s.onerror = () => rej(new Error("Could not load " + src)); document.head.appendChild(s); });
}
async function jsPDFClass() {
  if (!libs) libs = (async () => { await loadScript("vendor/jspdf.umd.min.js"); await loadScript("vendor/jspdf.plugin.autotable.min.js"); return window.jspdf.jsPDF; })();
  return libs;
}

const C = { ink: [15, 30, 46], muted: [100, 116, 139], teal: [15, 118, 110], line: [226, 232, 240], soft: [240, 253, 250], amber: [180, 83, 9], green: [21, 128, 61], red: [220, 38, 38], blue: [37, 99, 235] };
const W = 595, H = 842, M = 40;
const tz = () => D.tz(S.config);
const day = (ms) => (ms ? fmtIso(zoned(ms, tz()).date) : "-");
const when = (ms) => (ms ? fmtMs(ms, tz(), true) : "-");
/** PDF-safe money: currency code, no symbol (the built-in PDF fonts have no Taka sign). */
const money = (minor, code) => { const c = code || D.cur(S.config); const d = currencyDigits(c); const v = (Number(minor) || 0) / 10 ** d; return `${c} ${v.toLocaleString("en-IN", { minimumFractionDigits: Number.isInteger(v) ? 0 : d, maximumFractionDigits: d })}`; };
/** Replace characters the standard PDF fonts can't draw. */
const safe = (t) => String(t ?? "").replace(/[→]/g, "->").replace(/œ/g, "oe").replace(/Œ/g, "OE").replace(/[‘’]/g, "'").replace(/[“”]/g, '"').replace(/৳/g, "BDT ").replace(/[^\x00-\xFF–—•…€]/g, "");
/** Translated label (Latin-script languages; other languages print in English). */
const tx = (t) => safe(pt(t));
const pretty = (c) => String(c || "").replace(/_/g, " ");
const L = (k) => D.toothLabel(k, S.notation);
const OPEN = ["Planned", "Accepted", "In progress", "Deferred", "Referred"];

class Report {
  constructor(jsPDF, title, subtitle) {
    setFormatLang(isLatin() ? document.documentElement.lang : "en");
    const bc = /^#([0-9a-f]{6})$/i.exec(S.config.brandColor || "");
    C.teal = bc ? [0, 2, 4].map((i) => parseInt(bc[1].slice(i, i + 2), 16)) : [15, 118, 110];
    this.doc = new jsPDF({ unit: "pt", format: "a4" });
    this.logo = null;
    try { if (S.config.logo) { const pr = this.doc.getImageProperties(S.config.logo); this.logo = { data: S.config.logo, r: pr.width / pr.height }; } } catch { this.logo = null; }
    title = pt(title);
    this.title = title; this.subtitle = subtitle; this.y = 0;
    this.width = W - 2 * M;
    this.pageHeader();
  }
  color(c) { this.doc.setTextColor(...c); }
  font(size, bold = false, c = C.ink) { this.doc.setFont("helvetica", bold ? "bold" : "normal"); this.doc.setFontSize(size); this.color(c); }
  pageHeader() { this.drawHeaderOnly(); this.y = 58; }
  newPage() { this.doc.addPage(); this.pageHeader(); }
  ensure(h) { if (this.y + h > H - 44) this.newPage(); }
  header() {
    this.ensure(48);
    this.font(19, true); this.doc.text(safe(this.title), M, this.y + 18); this.y += 28;
    if (this.subtitle) { this.font(9.5, false, C.muted); for (const l of this.doc.splitTextToSize(safe(this.subtitle), this.width)) { this.doc.text(l, M, this.y + 10); this.y += 13; } }
    this.y += 8;
  }
  heading(t) {
    this.ensure(64); this.y += 10;
    this.font(8.5, true, C.teal); this.doc.text(tx(t).toUpperCase(), M, this.y + 9); this.y += 14;
    this.doc.setDrawColor(...C.line); this.doc.line(M, this.y, W - M, this.y); this.y += 8;
  }
  text(t, size = 9.5, c = C.ink, bold = false, indent = 0) {
    this.font(size, bold, c);
    for (const l of this.doc.splitTextToSize(tx(t), this.width - indent)) { this.ensure(size + 4); this.doc.text(l, M + indent, this.y + size); this.y += size + 4; }
  }
  space(h = 6) { this.y += h; }
  facts(pairs, cols = 2) {
    const cw = this.width / cols;
    for (let i = 0; i < pairs.length; i += cols) {
      const row = pairs.slice(i, i + cols);
      this.font(9.5, true);
      const wrapped = row.map(([, v]) => this.doc.splitTextToSize(tx(v || "-"), cw - 10));
      const h = 12 + Math.max(1, ...wrapped.map((w) => w.length)) * 12 + 6;
      this.ensure(h);
      row.forEach(([label], j) => {
        const x = M + j * cw;
        this.font(7, true, C.muted); this.doc.text(tx(label).toUpperCase(), x, this.y + 8);
        this.font(9.5, true); wrapped[j].forEach((l, k) => this.doc.text(l, x, this.y + 20 + k * 12));
      });
      this.y += h;
    }
  }
  stats(items) {
    if (!items.length) return;
    this.ensure(52);
    const gap = 8, tile = (this.width - gap * (items.length - 1)) / items.length;
    items.forEach(([label, value, c], i) => {
      const x = M + i * (tile + gap);
      this.doc.setFillColor(248, 250, 252); this.doc.setDrawColor(...C.line); this.doc.roundedRect(x, this.y, tile, 44, 6, 6, "FD");
      this.font(7.5, true, C.muted); this.doc.text(tx(label), x + 8, this.y + 14);
      this.font(String(value).length > 14 ? 10 : 13, true, c || C.ink); this.doc.text(this.doc.splitTextToSize(safe(value), tile - 14)[0] || "", x + 8, this.y + 33);
    });
    this.y += 52;
  }
  table(head, rows, weights, right = [], empty = "Nothing recorded.") {
    if (!rows.length) { this.text(empty, 9, C.muted); return; }
    const total = weights.reduce((a, b) => a + b, 0);
    const columnStyles = {};
    weights.forEach((w, i) => (columnStyles[i] = { cellWidth: (w / total) * this.width, halign: right.includes(i) ? "right" : "left" }));
    this.ensure(36);
    this.doc.autoTable({
      startY: this.y, margin: { left: M, right: M, top: 58, bottom: 44 }, head: [head.map((h) => tx(h).toUpperCase())], body: rows.map((r) => r.map(tx)),
      theme: "plain", columnStyles,
      styles: { font: "helvetica", fontSize: 8.5, textColor: C.ink, cellPadding: { top: 3, bottom: 3, left: 4, right: 4 }, lineColor: C.line, lineWidth: { bottom: 0.5 } },
      headStyles: { fillColor: C.soft, textColor: C.muted, fontSize: 7.5, fontStyle: "bold", lineWidth: 0 },
      didParseCell: (d) => { if (d.section === "head" && right.includes(d.column.index)) d.cell.styles.halign = "right"; },
      didDrawPage: (data) => { if (data.pageNumber > 1) this.drawHeaderOnly(); },
    });
    this.y = this.doc.lastAutoTable.finalY + 6;
  }
  drawHeaderOnly() {
    const d = this.doc, cfg = S.config;
    d.setFillColor(...C.teal); d.rect(0, 0, W, 6, "F");
    let x = M;
    if (this.logo) { const h = 24, w = Math.min(60, h * this.logo.r); try { d.addImage(this.logo.data, "PNG", M, 12, w, h); x = M + w + 8; } catch { /* skip logo */ } }
    this.font(10, true, C.teal); d.text(safe(cfg.name || "Dental Care"), x, 23);
    const line2 = [cfg.address, cfg.clinicPhone].filter(Boolean).join("  ·  ");
    if (line2) { this.font(7, false, C.muted); d.text(d.splitTextToSize(safe(line2), W - M - x - 150)[0] || "", x, 33); }
    this.font(8.5, false, C.muted); d.text(safe(this.title), W - M, 23, { align: "right" });
    d.setDrawColor(...C.line); d.setLineWidth(0.7); d.line(M, 40, W - M, 40);
  }
  watermark() {
    const d = this.doc, name = safe(S.config.name || "");
    try {
      d.setGState(new d.GState({ opacity: 0.06 }));
      if (this.logo) { const h = 220, w = Math.min(320, h * this.logo.r); d.addImage(this.logo.data, "PNG", (W - w) / 2, (H - h) / 2, w, h); }
      if (name.trim()) {
        d.setGState(new d.GState({ opacity: 0.07 }));
        this.font(Math.max(28, Math.min(60, 900 / Math.max(8, name.length))), true, C.teal);
        const tw = d.getTextWidth(name), a = Math.PI / 4;
        d.text(name, W / 2 - (tw / 2) * Math.cos(a), H / 2 + (tw / 2) * Math.sin(a) + 120, { angle: 45 });
      }
    } finally { d.setGState(new d.GState({ opacity: 1 })); }
  }
  /** 2D odontogram: five surfaces per tooth, coloured like the app. */
  chart(chart) {
    const d = chart?.dentition || "permanent", teeth = chart?.teeth || {};
    const upP = [18, 17, 16, 15, 14, 13, 12, 11, 21, 22, 23, 24, 25, 26, 27, 28], loP = [48, 47, 46, 45, 44, 43, 42, 41, 31, 32, 33, 34, 35, 36, 37, 38];
    const upD = [55, 54, 53, 52, 51, 61, 62, 63, 64, 65], loD = [85, 84, 83, 82, 81, 71, 72, 73, 74, 75];
    const rows = d === "primary" ? [[upD, true], [loD, false]] : d === "mixed" ? [[upP, true], [upD, true], [loD, false], [loP, false]] : [[upP, true], [loP, false]];
    const s = 24, step = 29, gap = 14, rh = s + 16;
    this.ensure(rows.length * rh + 44);
    const cw = 16 * step - (step - s) + gap, x0 = M + (this.width - cw) / 2;
    this.y += 6;
    const top = this.y;
    rows.forEach(([keys, upper], idx) => {
      if (idx === rows.length / 2) this.y += 10;
      const ty = this.y + (upper ? 12 : 0), half = keys.length / 2;
      keys.forEach((k, i) => {
        const x = i < half ? x0 + (8 - half + i) * step : x0 + 8 * step + gap + (i - half) * step;
        this.tooth(String(k), teeth[k], x, ty, s, upper);
        this.font(6.5, true, C.muted); this.doc.text(safe(L(String(k))), x + s / 2, upper ? ty - 4 : ty + s + 9, { align: "center" });
      });
      this.y += rh;
    });
    const doc = this.doc;
    doc.setDrawColor(203, 213, 225); doc.setLineWidth(0.6); doc.setLineDashPattern([3, 3], 0);
    const midX = x0 + 8 * step - (step - s) / 2 + gap / 2, midY = top + (rows.length / 2) * rh + 5;
    doc.line(midX, top, midX, this.y); doc.line(x0 - 6, midY, x0 + cw + 6, midY);
    doc.setLineDashPattern([], 0);
    this.font(6.5, true, C.muted); doc.text(tx("Right").toUpperCase(), x0, top - 2); doc.text(tx("Left").toUpperCase(), x0 + cw, top - 2, { align: "right" });
    this.y += 6;
    this.ensure(16);
    let x = M;
    for (const [col, label] of [[[238, 242, 246], "Not assessed"], [[255, 233, 194], "Assessed"], [[96, 165, 250], "Treated"], [[204, 251, 241], "Planned"], [[245, 158, 11], "Finding"], [[248, 250, 252], "Missing"]]) {
      doc.setFillColor(...col); doc.setDrawColor(203, 213, 225); doc.setLineWidth(0.5); doc.rect(x, this.y + 2, 8, 8, "FD");
      const lt = tx(label);
      this.font(7.5, false, C.muted); doc.text(lt, x + 11, this.y + 9.5);
      x += 11 + doc.getTextWidth(lt) + 12;
    }
    this.y += 16;
  }
  tooth(key, t, x, y, s, upper) {
    const doc = this.doc;
    const q = +key[0], pr = [1, 4, 5, 8].includes(q), ant = +key[1] <= 3;
    const lost = t && D.LOST.includes(t.presence) && (!t.replacement || t.replacement === "none");
    const i = s * 0.3;
    const sTop = upper ? "F" : "L", sBot = upper ? "L" : "F", sLeft = pr ? "D" : "M", sRight = pr ? "M" : "D", sMid = ant ? "I" : "O";
    const color = (sf) => {
      if (lost) return [248, 250, 252];
      if (!t) return [238, 242, 246];
      const o = t.surfaces?.[sf];
      if ((o?.findings || []).length || (t.findings || []).length) return [245, 158, 11];
      if ((o?.restorations || []).length) return [96, 165, 250];
      if ((t.treatmentStates || []).length) return [204, 251, 241];
      if (t.treated) return [96, 165, 250];
      return t.assessment === "assessed" ? [255, 233, 194] : [238, 242, 246];
    };
    const poly = (pts, sf) => {
      const segs = []; for (let k = 2; k < pts.length; k += 2) segs.push([pts[k] - pts[k - 2], pts[k + 1] - pts[k - 1]]);
      doc.setFillColor(...color(sf)); doc.setDrawColor(203, 213, 225); doc.setLineWidth(0.6);
      doc.lines(segs, pts[0], pts[1], [1, 1], "FD", true);
    };
    poly([x, y, x + s, y, x + s - i, y + i, x + i, y + i], sTop);
    poly([x + s, y, x + s, y + s, x + s - i, y + s - i, x + s - i, y + i], sRight);
    poly([x + s, y + s, x, y + s, x + i, y + s - i, x + s - i, y + s - i], sBot);
    poly([x, y + s, x, y, x + i, y + i, x + i, y + s - i], sLeft);
    poly([x + i, y + i, x + s - i, y + i, x + s - i, y + s - i, x + i, y + s - i], sMid);
    if (lost) { doc.setDrawColor(100, 116, 139); doc.setLineWidth(1.4); doc.line(x + 5, y + 5, x + s - 5, y + s - 5); doc.line(x + s - 5, y + 5, x + 5, y + s - 5); }
    else if ((t?.treatmentStates || []).length) { this.font(11, true, C.teal); doc.text("+", x + s / 2, y + s / 2 + 4, { align: "center" }); }
  }
  save(name) {
    const n = this.doc.getNumberOfPages();
    for (let p = 1; p <= n; p++) {
      this.doc.setPage(p);
      this.watermark();
      this.font(7.5, false, C.muted);
      this.doc.text(safe(`${when(Date.now())} · ${S.config.name || "Dental Care"}`), M, H - 22);
      this.doc.text(`${p} / ${n}`, W - M, H - 22, { align: "right" });
    }
    setFormatLang(null);
    this.doc.save(name);
  }
}
const fileName = (...parts) => parts.map((p) => String(p).toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "")).filter(Boolean).join("-") + ".pdf";
const state = (v) => D.invoiceState(v).replace("Partially paid", "Part paid");

// ============================================================================ patient record
export async function patientPdf(p) {
  const R = new Report(await jsPDFClass(), "Patient record", `${p.name} · printed ${when(Date.now())}`);
  const c = D.cur(S.config);
  const chart = D.displayChart(p);
  const counts = D.chartCounts(p);
  const due = D.patientDue(p);
  R.header();
  R.heading("Profile");
  R.facts([["Name", p.name], ["Patient record", p.id.slice(0, 18)], ["Date of birth", p.dob ? `${fmtIso(p.dob)} · ${ageFrom(p.dob, tz())} y` : ""], ["Gender", p.gender || ""],
    ["Phone", p.phone || ""], ["Address", p.address || ""], ["Medical alerts / allergies", p.allergies || "None recorded"], ["Appointment reminders", p.communicationConsent ? "Consented" : "Not consented"],
    ["Patient app ID", p.portal?.loginId || "No login"], ["Record status", `${p.archived ? "Archived · " : ""}revision ${p.revision || 0}`]]);
  R.space(4);
  R.stats([["Treated teeth", String(counts.treated), C.blue], ["Open plan", String(counts.planned), C.teal], ["Findings", String(counts.findings), C.amber], ["Missing / lost", String(counts.missing), C.muted], ["Balance due", money(due, c), due ? C.amber : C.green]]);
  R.heading(`Dental chart · ${chart.dentition || "permanent"} dentition · ${{ palmer: "Palmer", fdi: "FDI", universal: "Universal" }[S.notation]} numbering`);
  R.space(10);
  R.chart(chart);
  const keys = Object.keys(chart.teeth || {}).sort((a, b) => D.QUADRANT_ORDER.concat(D.PRIMARY_ORDER).indexOf(+a) - D.QUADRANT_ORDER.concat(D.PRIMARY_ORDER).indexOf(+b));
  R.heading("Tooth findings");
  R.table(["Tooth", "Presence", "Findings", "Restorations", "Open care"], keys.map((k) => {
    const f = D.toothFacts(p, k), t = chart.teeth[k];
    if (!f.findings.length && !f.restorations.length && !f.open.length && !D.LOST.includes(t.presence) && (!t.replacement || t.replacement === "none") && !t.treated) return null;
    return [`${L(k)} (${k})`, pretty(t.presence) + (t.replacement && t.replacement !== "none" ? ` · ${pretty(t.replacement)}` : ""), f.findings.join(", ") || "-", f.restorations.join(", ") || "-", f.open.map((x) => `${x.status}: ${x.procedure}`).join(", ") || "-"];
  }).filter(Boolean), [1, 1.1, 1.6, 1.6, 1.8], [], "No coded findings, restorations or open care.");
  R.heading("Clinical story");
  R.text(p.story || "No clinical narrative recorded.");
  R.heading("Treatment history");
  const shown = new Set();
  R.table(["Date", "Tooth", "Procedure", "Status", "Doctor", "Fee", "Paid", "Due"], (p.treatments || []).slice().sort((a, b) => (b.at || 0) - (a.at || 0)).map((t) => {
    const ref = D.treatmentRef(t), f = shown.has(ref) ? null : D.treatmentFee(p, t); shown.add(ref);
    return [day(t.performedAt || t.at), L(t.tooth), t.procedure + (t.surface && t.surface !== "Whole tooth" ? ` · ${t.surface}` : "") + (t.notes ? `\n${t.notes}` : ""), t.status, t.doctor || "",
      f ? money(f.total, c) : "", f ? money(f.paid, c) : "", f ? money(f.due, c) : ""];
  }), [1.05, 0.7, 2.2, 0.95, 1.3, 0.9, 0.9, 0.9], [5, 6, 7], "No treatment recorded.");
  R.heading("Performed procedures");
  R.table(["Date", "Tooth", "Procedure", "Doctor", "Outcome"], (p.performedProcedures || []).filter((x) => !x.voided).sort((a, b) => (b.at || 0) - (a.at || 0)).map((x) => [day(x.at), L(x.tooth), x.procedure, x.doctor || "", x.outcome || ""]), [1, 0.7, 1.5, 1.3, 2.5]);
  R.heading("Encounters");
  const enc = (p.encounters || []).slice().sort((a, b) => (b.at || 0) - (a.at || 0));
  if (!enc.length) R.text("No encounters recorded.", 9, C.muted);
  for (const e of enc) {
    R.text(`${day(e.at)} · ${e.status} · ${e.doctor}`, 9.5, C.ink, true);
    for (const [l, k] of [["Complaint", "complaint"], ["Examination", "examination"], ["Diagnosis", "diagnosis"], ["Plan", "plan"]]) if (e[k]) R.text(`${l}: ${e[k]}`, 9, C.ink, false, 10);
    for (const a of e.amendments || []) R.text(`Amendment ${day(a.at)}: ${a.reason} · ${a.text}`, 8.5, C.blue, false, 10);
    R.space(4);
  }
  R.heading("Appointments");
  const appt = (a) => { try { return D.appointmentMillis(a, S.config); } catch { return 0; } };
  R.table(["When", "Doctor", "Reason", "Status"], (p.appointments || []).slice().sort((a, b) => appt(b) - appt(a)).map((a) => [when(appt(a)), a.doctor, a.reason, a.status]), [1.4, 1.3, 2, 0.9]);
  R.heading("Billing");
  const inv = (p.invoices || []).slice().sort((a, b) => (b.at || 0) - (a.at || 0));
  R.table(["Date", "Invoice", "Total", "Received", "Balance", "Status"], inv.map((v) => [day(v.at), v.description || "", money(D.invoiceTotal(v), v.currency), money(D.paid(v) - D.refunded(v), v.currency), money(Math.max(0, D.balance(v)), v.currency), state(v)]),
    [1, 2.6, 1, 1, 1, 0.9], [2, 3, 4], "No invoices.");
  const pays = inv.flatMap((v) => (v.payments || []).map((x) => [v, x])).sort((a, b) => b[1].at - a[1].at);
  if (pays.length) { R.text("Payments received", 9, C.muted, true); R.table(["Date", "For", "Method", "Reference", "Amount"], pays.map(([v, x]) => [day(x.at), v.description || "", x.method, x.reference || "", money(x.amount, v.currency)]), [1, 2.4, 0.9, 1.4, 1], [4]); }
  R.heading("Recent history");
  R.table(["When", "Change"], (p.history || []).slice().sort((a, b) => b.at - a.at).slice(0, 40).map((h) => [when(h.at), h.text + (h.actor ? ` · ${h.actor}` : "")]), [1.3, 4.5]);
  R.space(8);
  R.text("This record supports, and does not replace, professional clinical judgement. Tooth keys are stored in FDI.", 7.5, C.muted);
  R.save(fileName("patient-report", p.name));
}

// ============================================================================ financial report
export async function financialPdf(periodLabel, a, b) {
  const R = new Report(await jsPDFClass(), "Financial report", `${periodLabel} · ${tz()} · income counts payments received, not invoices issued`);
  R.header();
  const inP = (ms) => ms >= a && ms < b;
  const invs = S.patients.flatMap((p) => (p.invoices || []).map((v) => [p, v]));
  const def = D.cur(S.config);
  const currencies = [...new Set(invs.map(([, v]) => v.currency || def))].sort();
  for (const c of currencies.length ? currencies : [def]) {
    const mine = invs.filter(([, v]) => (v.currency || def) === c);
    const billed = mine.filter(([, v]) => inP(v.at) && v.status !== "Voided").reduce((s, [, v]) => s + D.invoiceTotal(v), 0);
    const pays = mine.flatMap(([p, v]) => (v.payments || []).filter((x) => inP(x.at)).map((x) => [p, v, x]));
    const received = pays.reduce((s, [, , x]) => s + x.amount, 0);
    const refunds = mine.reduce((s, [, v]) => s + (v.refunds || []).filter((x) => inP(x.at)).reduce((t, x) => t + x.amount, 0), 0);
    const outstanding = mine.reduce((s, [, v]) => s + Math.max(0, D.balance(v)), 0);
    R.heading(`Summary · ${c}`);
    R.stats([["Billed in period", money(billed, c)], ["Payments received", money(received, c), C.green], ["Refunds", money(refunds, c), C.red], ["Net cash", money(received - refunds, c), C.teal], ["Outstanding now", money(outstanding, c), C.amber]]);
    R.text("By payment method", 9, C.muted, true);
    const byM = {}; for (const [, , x] of pays) (byM[x.method || "Other"] ||= []).push(x);
    R.table(["Method", "Payments", "Amount", "Share"], Object.keys(byM).sort().map((m) => { const sum = byM[m].reduce((s, x) => s + x.amount, 0); return [m, String(byM[m].length), money(sum, c), received ? Math.floor((sum * 100) / received) + "%" : "-"]; }), [2, 1, 1.4, 0.8], [1, 2, 3], "No payments in this period.");
    R.heading(`Payments received · ${c}`);
    R.table(["Date", "Patient", "For", "Method", "Amount"], pays.sort((x, y) => x[2].at - y[2].at).map(([p, v, x]) => [day(x.at), p.name, v.description || "", x.method, money(x.amount, c)]), [1, 1.6, 2.4, 0.9, 1.1], [4], "No payments in this period.");
    R.heading(`Invoices issued · ${c}`);
    R.table(["Date", "Patient", "Invoice", "Total", "Balance", "Status"], mine.filter(([, v]) => inP(v.at)).sort((x, y) => x[1].at - y[1].at).map(([p, v]) => [day(v.at), p.name, v.description || "", money(D.invoiceTotal(v), c), money(Math.max(0, D.balance(v)), c), state(v)]), [1, 1.5, 2.3, 1, 1, 0.9], [3, 4], "No invoices issued in this period.");
    const fees = mine.filter(([, v]) => v.treatmentRef && v.status !== "Voided");
    if (fees.length) {
      const by = {};
      for (const [p, v] of fees) { const t = (p.treatments || []).find((x) => D.treatmentRef(x) === v.treatmentRef); const k = t?.procedure || String(v.description || "").split(" · ")[0]; (by[k] ||= []).push(v); }
      R.heading(`Treatment payments by procedure · ${c}`);
      R.table(["Procedure", "Treatments", "Fees", "Received in period", "Due now"], Object.keys(by).sort().map((k) => [k, String(by[k].length), money(by[k].reduce((s, v) => s + D.invoiceTotal(v), 0), c),
        money(by[k].reduce((s, v) => s + (v.payments || []).filter((x) => inP(x.at)).reduce((t, x) => t + x.amount, 0), 0), c), money(by[k].reduce((s, v) => s + Math.max(0, D.balance(v)), 0), c)]), [2.2, 0.9, 1.1, 1.3, 1.1], [1, 2, 3, 4]);
    }
    R.heading(`Balances due by patient · ${c}`);
    const byP = {}; for (const [p, v] of mine) { const d0 = Math.max(0, D.balance(v)); if (d0) { const e = (byP[p.id] ||= [p, 0, 0]); e[1]++; e[2] += d0; } }
    R.table(["Patient", "Phone", "Invoices open", "Due"], Object.values(byP).sort((x, y) => y[2] - x[2]).slice(0, 60).map(([p, n, d0]) => [p.name, p.phone || "", String(n), money(d0, c)]), [2, 1.4, 1, 1.2], [2, 3], "No balances due.");
  }
  const appts = S.patients.flatMap((p) => p.appointments || []).filter((x) => { try { return inP(D.appointmentMillis(x, S.config)); } catch { return false; } });
  R.heading("Appointments in period");
  R.table(["Status", "Count"], D.APPOINTMENT_STATES.map((s) => [s, String(appts.filter((x) => x.status === s).length)]).filter((r) => r[1] !== "0"), [3, 1], [1], "No appointments in this period.");
  const done = S.patients.flatMap((p) => p.treatments || []).filter((t) => t.status === "Completed" && inP(t.performedAt || t.at));
  R.heading("Clinician workload");
  const byD = {}; for (const x of appts) (byD[x.doctor || "Unassigned"] ||= []).push(x);
  R.table(["Doctor", "Appointments", "Completed visits", "Booked minutes", "Procedures"], Object.keys(byD).sort().map((d0) => [d0, String(byD[d0].length), String(byD[d0].filter((x) => x.status === "Completed").length),
    String(byD[d0].filter((x) => x.status !== "Cancelled").reduce((s, x) => s + (x.durationMinutes || 30), 0)), String(done.filter((t) => t.doctor === d0).length)]), [2, 1, 1.1, 1.1, 1], [1, 2, 3, 4], "No appointments in this period.");
  R.heading("Clinical activity");
  const byProc = {}; for (const t of done) byProc[t.procedure] = (byProc[t.procedure] || 0) + 1;
  R.table(["Completed procedure", "Count"], Object.keys(byProc).sort().map((k) => [k, String(byProc[k])]), [3, 1], [1], "No completed procedures in this period.");
  const reg = S.patients.filter((p) => (p.history || []).some((h) => h.text === "Patient registered" && inP(h.at))).length;
  R.text(`${reg} newly registered patients in period · ${S.patients.filter((p) => !p.archived).length} active patients`, 9, C.muted);
  R.save(fileName("financial-report", periodLabel.split(" · ")[0], todayIn(tz())));
}

// ============================================================================ appointments
export async function appointmentsPdf(periodLabel, a, b) {
  const appt = (x) => { try { return D.appointmentMillis(x, S.config); } catch { return 0; } };
  const entries = S.patients.flatMap((p) => (p.appointments || []).map((x) => [p, x])).filter(([, x]) => appt(x) >= a && appt(x) < b).sort((x, y) => appt(x[1]) - appt(y[1]));
  const R = new Report(await jsPDFClass(), "Appointments", `${periodLabel} · ${entries.length} appointments · ${tz()}`);
  R.header();
  R.stats([["Booked", String(entries.filter(([, x]) => x.status !== "Cancelled").length), C.teal], ["Completed", String(entries.filter(([, x]) => x.status === "Completed").length), C.green],
    ["Requested", String(entries.filter(([, x]) => x.status === "Requested").length), C.amber], ["Cancelled / no show", String(entries.filter(([, x]) => ["Cancelled", "No show"].includes(x.status)).length), C.red]]);
  const byDay = {};
  for (const e of entries) (byDay[zoned(appt(e[1]), tz()).date] ||= []).push(e);
  for (const d0 of Object.keys(byDay).sort()) {
    R.heading(fmtIso(d0, true));
    R.table(["Time", "Patient", "Phone", "Doctor", "Reason", "Status"], byDay[d0].map(([p, x]) => [zoned(appt(x), tz()).time, p.name + (p.allergies ? `\nAlert: ${p.allergies}` : ""), p.phone || "", x.doctor + (x.room ? `\n${x.room}` : ""), x.reason, x.status]), [0.7, 1.8, 1.2, 1.4, 1.6, 0.9]);
  }
  if (!entries.length) R.text("No appointments in this period.", 9.5, C.muted);
  R.save(fileName("appointments", periodLabel.split(" · ")[0], todayIn(tz())));
}

// ============================================================================ invoice / receipt
export async function invoicePdf(p, v) {
  const c = v.currency || D.cur(S.config);
  const R = new Report(await jsPDFClass(), "Invoice", `#${v.id.slice(0, 8).toUpperCase()} · ${day(v.at)} · ${state(v)}`);
  R.header();
  R.facts([["Billed to", p.name], ["Phone", p.phone || ""], ["Invoice date", day(v.at)], ["Status", state(v)]]);
  R.heading("Items");
  const lines = v.lines?.length ? v.lines : [{ description: v.description, quantity: 1, unitPrice: v.amount }];
  R.table(["Description", "Qty", "Unit price", "Amount"], lines.map((l) => [l.description, String(l.quantity || 1), money(l.unitPrice, c), money(l.unitPrice * (l.quantity || 1), c)]), [3.4, 0.6, 1.2, 1.2], [1, 2, 3]);
  const totals = [];
  if (v.discount) totals.push(["Discount", "- " + money(v.discount, c)]);
  if (v.taxBps) totals.push(["Tax", v.taxBps / 100 + "%"]);
  totals.push(["Total", money(D.invoiceTotal(v), c)], ["Received", money(D.paid(v) - D.refunded(v), c)], ["Balance due", money(Math.max(0, D.balance(v)), c)]);
  R.table(["", ""], totals, [4, 1.6], [0, 1]);
  if ((v.payments || []).length) { R.heading("Payments"); R.table(["Date", "Method", "Reference", "Amount"], v.payments.map((x) => [when(x.at), x.method, x.reference || "", money(x.amount, c)]), [1.5, 1, 2, 1.2], [3]); }
  if ((v.refunds || []).length) { R.heading("Refunds"); R.table(["Date", "Reason", "Amount"], v.refunds.map((x) => [day(x.at), x.reason, money(x.amount, c)]), [1.2, 3, 1.2], [2]); }
  if (v.voidReason) R.text("Voided: " + v.voidReason, 9.5, C.red, true);
  R.space(12);
  R.text("Thank you. Please keep this invoice for your records.", 8.5, C.muted);
  R.save(fileName("invoice", p.name, v.id.slice(0, 8)));
}
export async function receiptPdf(p, v, x) {
  const c = v.currency || D.cur(S.config);
  const R = new Report(await jsPDFClass(), "Payment receipt", `Receipt #${x.id.slice(0, 8).toUpperCase()} · ${when(x.at)}`);
  R.header();
  R.stats([["Amount received", money(x.amount, c), C.green], ["Method", x.method], ["Balance due", money(Math.max(0, D.balance(v)), c), D.balance(v) > 0 ? C.amber : C.green]]);
  R.facts([["Received from", p.name], ["Phone", p.phone || ""], ["For", v.description || ""], ["Reference", x.reference || ""], ["Invoice total", money(D.invoiceTotal(v), c)], ["Paid to date", money(D.paid(v) - D.refunded(v), c)]]);
  R.space(12);
  R.text("Thank you for your payment.", 9, C.muted);
  R.save(fileName("receipt", p.name, x.id.slice(0, 8)));
}
export function rangeLabel(period, a, b) {
  if (!Number.isFinite(a)) return "All dates";
  return `${period} · ${day(a)} – ${day(b - 1)}`;
}
