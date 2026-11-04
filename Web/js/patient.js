// Patient workspace: list · record with the Godot 3D chart at the centre · tooth rail.
import { $, $$, esc, clone, uid, arr, initials, money, compactMoney, parseMinor, minorToText, zoned, zonedMs, todayIn, fmtMs, fmtIso, ageFrom, download, debounce } from "./util.js";
import { setFormatLang } from "./util.js";
import { isLatin, currentLang } from "./i18n.js";
import * as D from "./domain.js";
import { S, save, can, patient, say, createPatientLogin, removePatientLogin, attachImage, resolveConflict, setPref } from "./store.js";
import { Viewer } from "./viewer3d.js";
import { I } from "./icons.js";
import * as Reports from "./reports.js";
import { modal, field, input, textarea, select, check, chips, values, bookDialog, invoiceDialog, receivePaymentDialog, refundDialog, voidDialog, receiptText, shareText, setAppointmentStatus, confirmBox } from "./dialogs.js";

export const P = { pid: "", tab: "overview", tooth: "", surface: "", multi: [], multiMode: false, q: "", filter: "All", archived: false, collapsed: false, listmode: false, earlier: null, dentition: "" };
let ctx = { render() {}, go() {} };
export const setCtx = (c) => (ctx = c);
const N = () => S.notation;
const L = (k) => D.toothLabel(k, N());
const z = () => D.tz(S.config);
const c = () => D.cur(S.config);
const badge = (s) => ({ Completed: "green", "In progress": "amber", Planned: "teal", Accepted: "blue", Cancelled: "red", Declined: "red", Referred: "violet", Deferred: "" }[s] ?? "");
const stCls = (s) => "st-" + String(s).replace(/ /g, "-").replace("Checked-in", "Checked-in");
const alertsOf = (p) => String(p.allergies || "").split(/[,;\n]+/).map((x) => x.trim()).filter(Boolean);
const avatarBg = (p) => (/^f/i.test(p.gender || "") ? "linear-gradient(135deg,#EC4899,#7C3AED)" : "linear-gradient(135deg,#3B82F6,#0F766E)");

// ============================================================================ lenses & badges
export const LENSES = {
  status: { label: "Clinical status", legend: [["#3b82f6", "Treated"], ["#14b8a6", "Open plan"], ["#f59e0b", "Finding"]] },
  plan: { label: "Treatment plan", legend: [["#14b8a6", "Planned"], ["#2563eb", "Accepted"], ["#f59e0b", "In progress"], ["#a855f7", "Deferred / referred"]] },
  balance: { label: "Balance due", legend: [["#ef4444", "Mostly unpaid"], ["#f59e0b", "Part paid"], ["#22c55e", "Fully paid"]] },
  recency: { label: "Last treated", legend: [["#14b8a6", "Last 30 days"], ["#3b82f6", "Last 6 months"], ["#94a3b8", "Earlier"]] },
  none: { label: "Anatomy only", legend: [] },
};
const ABBR = { "Root canal": "RCT", "Filling (composite)": "Filling", "Filling (amalgam)": "Amalgam", "Scaling & polishing": "Scaling", Extraction: "Extract", Orthodontic: "Ortho" };
function allFacts(p) {
  const keys = new Set([...Object.keys(p.chart?.teeth || {}), ...(p.treatments || []).map((t) => t.tooth)]);
  return [...keys].filter((k) => /^([1-4][1-8]|[5-8][1-5])$/.test(k)).map((k) => D.toothFacts(p, k));
}
function lensMap(p, mode) {
  const out = {};
  if (P.multiMode) { P.multi.forEach((k) => (out[k] = "#0f766e")); return out; }
  const now = Date.now();
  for (const f of allFacts(p)) {
    let col = "";
    if (mode === "status") col = f.findings.length ? "#f59e0b" : f.open.length ? "#14b8a6" : f.done.length || p.chart?.teeth?.[f.key]?.treated ? "#3b82f6" : "";
    else if (mode === "plan") { const s = f.open[0]?.status; col = { Planned: "#14b8a6", Accepted: "#2563eb", "In progress": "#f59e0b", Deferred: "#a855f7", Referred: "#a855f7" }[s] || ""; }
    else if (mode === "balance") col = f.fee ? (f.due === 0 ? "#22c55e" : f.due >= f.fee / 2 ? "#ef4444" : "#f59e0b") : "";
    else if (mode === "recency" && f.lastAt) { const d = (now - f.lastAt) / 86400000; col = d <= 30 ? "#14b8a6" : d <= 183 ? "#3b82f6" : "#94a3b8"; }
    if (col) out[f.key] = col;
  }
  return out;
}
function badgeMap(p) {
  const out = {};
  for (const f of allFacts(p)) {
    const parts = [];
    if (f.open[0]) parts.push((f.open[0].status === "In progress" ? "In progress: " : "+ ") + (ABBR[f.open[0].procedure] || f.open[0].procedure));
    if (f.due > 0) parts.push("due " + compactMoney(f.due, c()));
    if (!parts.length && f.findings.length) parts.push(f.findings[0].replace(/ \(.\)$/, ""));
    if (!parts.length && f.lost) continue;
    if (parts.length) out[f.key] = { text: parts.slice(0, 2).join(" · "), color: f.due > 0 ? "#b45309" : f.open.length ? "#0f766e" : "#92400e" };
  }
  return out;
}
function tooltip(key) {
  const p = patient(P.pid);
  if (!p || !key) return "";
  const f = D.toothFacts(p, key);
  const last = f.items[0];
  const pills = [
    f.lost && `<span class="pill">${esc(D.pretty(f.presence))}</span>`, f.replacement !== "none" && `<span class="pill">${esc(D.pretty(f.replacement))}</span>`,
    ...f.findings.map((x) => `<span class="pill a">${esc(x)}</span>`), ...f.restorations.map((x) => `<span class="pill b">${esc(x)}</span>`),
    ...f.open.map((x) => `<span class="pill t">${esc(x.status)}: ${esc(x.procedure)}</span>`),
  ].filter(Boolean);
  return `<div class="t"><span>${esc(L(key))}</span>${esc(D.toothName(key))}</div>
    <div class="row">${pills.join("") || `<span class="pill">${f.assessment === "assessed" ? "Assessed · no coded findings" : "Not assessed"}</span>`}</div>
    ${last ? `<hr><div><b>${esc(last.procedure)}</b> · ${esc(last.status)}</div><div style="opacity:.75">${fmtMs(last.performedAt || last.at, z())}${last.doctor ? " · " + esc(last.doctor) : ""}</div>` : ""}
    ${f.items.length > 1 ? `<div style="opacity:.75;margin-top:2px">${f.items.length} treatments on this tooth</div>` : ""}
    ${f.fee ? `<div class="money"><span>Fee ${money(f.fee, c())}</span><span style="color:#86efac">Paid ${money(f.paid, c())}</span><span style="color:${f.due ? "#fcd34d" : "#86efac"}">${f.due ? "Due " + money(f.due, c()) : "Fully paid"}</span></div>` : ""}
    <div style="opacity:.6;margin-top:8px;font-size:11px">Click to select · FDI ${key}</div>`;
}

// ============================================================================ 3D stage
function stageChart(p) {
  const chart = P.earlier ? clone(P.earlier) : D.displayChart(p);
  chart.readOnly = !!P.earlier || !can("recordTreatment");
  return chart;
}
function stageUi(p) {
  const st = Viewer.state, sel = P.tooth;
  const seg = (name, opts, cur) => `<div class="glass v3d-seg">${opts.map((o) => `<button class="${cur === o ? "on" : ""}" data-v3d="${name}" data-arg="${o}">${o}</button>`).join("")}</div>`;
  const tog = (k, icon, on, title) => `<button class="v3d-btn ${on ? "on" : ""}" data-v3d="toggle" data-arg="${k}" title="${title}">${I(icon)}</button>`;
  const counts = D.chartCounts(p), due = D.patientDue(p);
  const next = upcoming(p)[0];
  const facts = allFacts(p);
  const issues = facts.filter((f) => f.findings.length || f.due > 0 || f.open.length).sort((a, b) => b.due - a.due || b.open.length - a.open.length).slice(0, 8);
  const quads = ["UR", "UL", "LR", "LL"].map((q) => {
    const fs = facts.filter((f) => D.quadrantOf(f.key) === q);
    return { q, t: fs.filter((f) => f.done.length).length, pl: fs.filter((f) => f.open.length).length, fi: fs.filter((f) => f.findings.length).length, m: fs.filter((f) => f.lost).length };
  });
  const sf = sel ? D.toothFacts(p, sel) : null;
  const legend = (LENSES[st.lens] || LENSES.status).legend;
  return `
  <div class="v3d-top">
    ${seg("view", ["Front", "Upper", "Lower", "Left", "Right"], st.view)}
    ${seg("arch", ["Both", "Upper", "Lower"], st.arch)}
    <span class="spacer"></span>
    <div class="glass v3d-seg">
      ${tog("labels", "hash", st.labels, "Tooth numbers")}${tog("roots", "root", st.roots, "Roots (X-ray look)")}${tog("rotate", "rotate", st.rotate, "Turn slowly when idle")}
      ${tog("badges", "tag", st.badges, "Info tags over teeth")}${tog("sounds", "sound", st.sounds, "Tap sound")}
      <button class="v3d-btn" data-v3d="zoom" data-arg="out" title="Zoom out">${I("minus")}</button><button class="v3d-btn" data-v3d="zoom" data-arg="in" title="Zoom in">${I("plus")}</button>
      <button class="v3d-btn ${P.multiMode ? "on" : ""}" data-v3d="multi" title="Select several teeth">${I("clip")}</button>
      <button class="v3d-btn" data-v3d="fs" title="${Viewer.fullscreen ? "Exit full screen (Esc)" : "Full screen"}">${I(Viewer.fullscreen ? "shrink" : "expand")}</button>
    </div>
    <div class="v3d-selrow">${P.earlier ? `<div class="glass v3d-sel">${I("clock")}Earlier chart · read only <button class="btn sm" data-v3d="current">Back to current</button></div>`
    : P.multiMode ? `<div class="glass v3d-sel">${P.multi.length ? `<span class="b">${P.multi.map(L).join(", ")}</span>` : "Click teeth to add them"}${P.multi.length && can("recordTreatment") ? `<button class="btn primary sm" data-v3d="recordMulti">${I("plus")}Record treatment (${P.multi.length})</button>` : ""}<button class="btn sm" data-v3d="multi">Done</button></div>`
      : sel ? `<div class="glass v3d-sel"><span class="b">${esc(L(sel))}</span><span>${esc(D.toothName(sel))}${P.surface ? " · " + esc(D.surfaceName(sel, P.surface)) : ""}</span>${can("recordTreatment") ? `<button class="btn primary sm" data-v3d="record">${I("plus")}Record treatment</button>` : ""}<button class="btn sm" data-v3d="clear">${I("x")}</button></div>` : ""}</div>
  </div>
  <div class="glass v3d-glance">
    ${sf ? `<div><h5>Selected · ${esc(L(sel))}</h5><div style="font-weight:800;margin:2px 0 6px">${esc(D.toothName(sel))}</div>
      <div class="row" style="flex-wrap:wrap;gap:4px">${[...sf.findings.map((x) => `<span class="badge amber">${esc(x)}</span>`), ...sf.restorations.map((x) => `<span class="badge blue">${esc(x)}</span>`), ...sf.open.map((x) => `<span class="badge teal">${esc(x.status)}: ${esc(x.procedure)}</span>`), sf.lost ? `<span class="badge">${esc(D.pretty(sf.presence))}</span>` : ""].join("") || `<span class="badge">No coded findings</span>`}</div>
      ${sf.fee ? `<div class="small" style="margin-top:6px;font-weight:700">Fee ${money(sf.fee, c())} · <span style="color:${sf.due ? "var(--amber)" : "var(--green)"}">${sf.due ? "Due " + money(sf.due, c()) : "Fully paid"}</span></div>` : ""}
      <div class="small muted" style="margin-top:4px">${sf.items.length} treatment${sf.items.length === 1 ? "" : "s"}${sf.items[0] ? " · last " + fmtMs(sf.items[0].at, z()) : ""}</div></div>` : ""}
    <div><h5>At a glance</h5><div class="mini" style="margin-top:6px">
      <div><b style="color:#2563eb">${counts.treated}</b><span>Treated</span></div><div><b style="color:#0f766e">${counts.planned}</b><span>Planned</span></div>
      <div><b style="color:#b45309">${counts.findings}</b><span>Findings</span></div><div><b style="color:#64748b">${counts.missing}</b><span>Missing</span></div></div></div>
    <div><h5>Balance due</h5><div class="big" style="color:${due ? "#b45309" : "#15803d"}">${money(due, c())}</div></div>
    ${next ? `<div><h5>Next visit</h5><div style="font-weight:700">${fmtMs(next.startAt, z(), true)}</div><div class="small muted">${esc(next.reason)} · ${esc(next.doctor)}</div></div>` : ""}
    ${alertsOf(p).length ? `<div><h5>Medical alerts</h5><div class="row" style="flex-wrap:wrap;gap:4px;margin-top:4px">${alertsOf(p).map((a) => `<span class="badge red">${esc(a)}</span>`).join("")}</div></div>` : ""}
    <div><h5>Quadrants</h5><div class="v3d-quads" style="margin-top:6px">${quads.map((x) => `<button data-v3d="quad" data-arg="${x.q}"><b>${x.q}</b>${x.t} treated · ${x.pl} plan<br>${x.fi} finding · ${x.m} missing</button>`).join("")}</div></div>
    ${issues.length ? `<div><h5>Needs attention</h5>${issues.map((f) => `<button class="v3d-issue" data-v3d="pick" data-arg="${f.key}"><span class="k" style="background:${f.due ? "#b45309" : f.findings.length ? "#f59e0b" : "#0f766e"}">${esc(L(f.key))}</span><span>${esc([f.open[0] && f.open[0].procedure, f.findings[0], f.due && "due " + money(f.due, c())].filter(Boolean).join(" · "))}</span></button>`).join("")}</div>` : ""}
  </div>
  <div class="glass v3d-lens"><div class="row"><span class="small" style="font-weight:800;color:#64748b;margin-right:2px">LENS</span>${Object.entries(LENSES).map(([k, v]) => `<button class="lchip ${st.lens === k ? "on" : ""}" data-v3d="lens" data-arg="${k}">${v.label}</button>`).join("")}</div>
    ${legend.length ? `<div class="v3d-legend">${legend.map(([col, l]) => `<span><i style="background:${col}"></i>${l}</span>`).join("")}</div>` : ""}</div>
  <div class="v3d-hint">Drag to rotate · right-drag to pan · scroll to zoom · hover for details</div>`;
}
function refreshStage() {
  const p = patient(P.pid); if (!p) return;
  Viewer.renderUi(stageUi(p));
  Viewer.setLens(lensMap(p, Viewer.state.lens));
  Viewer.setBadges(badgeMap(p));
}
function stageAction(act, arg) {
  const st = Viewer.state, p = patient(P.pid);
  switch (act) {
    case "view": st.view = arg; Viewer.call("setView", arg); st.arch = { Upper: "Upper", Lower: "Lower" }[arg] || "Both"; break;
    case "arch": st.arch = arg; Viewer.call("setArch", arg); break;
    case "toggle":
      st[arg] = !st[arg];
      if (arg === "labels") Viewer.call("setLabels", st.labels);
      if (arg === "roots") Viewer.call("setRoots", st.roots);
      if (arg === "rotate") Viewer.call("setAutoRotate", st.rotate);
      if (arg === "sounds") { Viewer.call("setSounds", st.sounds); setPref("dc-sounds", st.sounds); }
      if (arg === "badges") Viewer.setBadges(badgeMap(p));
      break;
    case "zoom": Viewer.call("zoom", arg); return;
    case "fs": Viewer.setFullscreen(!Viewer.fullscreen); return;
    case "lens": st.lens = arg; break;
    case "multi": P.multiMode = !P.multiMode; P.multi = P.multiMode && P.tooth ? [P.tooth] : []; Viewer.call("clearSelection"); break;
    case "recordMulti": recordDialog(P.multi.slice()); return;
    case "record": recordDialog([P.tooth], null, P.surface); return;
    case "clear": Viewer.call("clearSelection"); P.tooth = ""; P.surface = ""; ctx.render(); return;
    case "pick": Viewer.call("selectTooth", arg); return;
    case "quad": { const v = arg[0] === "U" ? "Upper" : "Lower"; st.view = v; st.arch = v; Viewer.call("setView", v); break; }
    case "current": P.earlier = null; Viewer.setChart(stageChart(p)); break;
  }
  try { localStorage.setItem("dc-stage", JSON.stringify(st)); } catch { /* ignore */ }
  refreshStage();
}
try { Object.assign(Viewer.state, JSON.parse(localStorage.getItem("dc-stage") || "{}")); } catch { /* ignore */ }
Viewer.state.sounds = S.sounds;

function mountStage(p) {
  const slot = $("#stage-slot");
  if (!slot) return;
  Viewer.notation = N();
  const ok = Viewer.mount(slot, stageChart(p), {
    action: stageAction,
    ready: () => { if (P.tooth) Viewer.call("selectTooth", P.tooth); refreshStage(); },
    refreshOverlays: () => refreshStage(),
    refreshUi: () => refreshStage(),
    focus: (key, surface) => {
      if (P.multiMode) {
        if (key) { P.multi = P.multi.includes(key) ? P.multi.filter((k) => k !== key) : [...P.multi, key]; setTimeout(() => Viewer.call("clearSelection"), 250); refreshStage(); }
        return;
      }
      if (key === P.tooth && surface === P.surface) return;
      P.tooth = key; P.surface = surface;
      ctx.render();
    },
    record: (key, surface) => recordDialog([key], null, surface),
    tooltip,
    escape: () => { if (Viewer.fullscreen) Viewer.setFullscreen(false); },
  });
  if (!ok) {
    slot.innerHTML = `<div class="stage-fallback"><div class="notice" style="margin-bottom:12px">${I("alert")}<span>This browser can't run the 3D chart (WebGL 2 is off). Showing the 2D chart instead. Chrome or Edge on a PC works best.</span></div>${odontogram(p)}${LEGEND2D}</div>`;
    return;
  }
  if (Viewer.ready) { Viewer.call("selectTooth", P.tooth || ""); refreshStage(); }
}

// ============================================================================ 2D odontogram (Chart tab, fallback)
const isPost = (n) => n % 10 >= 4;
function surfClass(t, s) {
  if (!t || D.LOST.includes(t.presence)) return "";
  const o = t.surfaces?.[s] || {};
  if ((o.findings || []).length || (s === "W" && (t.findings || []).length)) return "finding";
  if ((o.restorations || []).length) return "treated";
  if ((t.treatmentStates || []).length) return "planned";
  if (t.treated) return "treated";
  return "";
}
function toothG(n, x, y, t) {
  const q = Math.floor(n / 10), upper = [1, 2, 5, 6].includes(q), rs = [1, 4, 5, 8].includes(q);
  const sz = 44, i = 13, Lx = x, T = y, R = x + sz, B = y + sz, il = x + i, it = y + i, ir = x + sz - i, ib = y + sz - i;
  const top = upper ? "F" : "L", bot = upper ? "L" : "F", left = rs ? "D" : "M", right = rs ? "M" : "D", center = isPost(n) ? "O" : "I";
  const sel = P.tooth === String(n) || P.multi.includes(String(n));
  const lost = t && D.LOST.includes(t.presence);
  const cls = ["tooth", t?.assessment === "assessed" ? "assessed" : "", lost ? "missing" : "", sel ? "sel" : ""].join(" ");
  const polys = [[top, `${Lx},${T} ${R},${T} ${ir},${it} ${il},${it}`], [right, `${R},${T} ${R},${B} ${ir},${ib} ${ir},${it}`], [bot, `${Lx},${B} ${R},${B} ${ir},${ib} ${il},${ib}`], [left, `${Lx},${T} ${il},${it} ${il},${ib} ${Lx},${B}`]];
  const ss = (s) => (sel && P.surface === s ? " selsf" : "");
  const whole = surfClass(t, "W");
  let mk = "";
  if (lost) mk = `<text class="mk" x="${x + sz / 2}" y="${y + sz / 2 + 8}" fill="var(--missing)">×</text>`;
  else if ((t?.treatmentStates || []).length) mk = `<text class="mk" x="${x + sz / 2}" y="${y + sz / 2 + 7}" fill="var(--brand)" style="font-size:18px">+</text>`;
  return `<g class="${cls}" data-tooth="${n}"><title>${esc(L(n))} · ${esc(D.toothName(n))}</title><rect class="frame" x="${Lx - 4}" y="${T - 4}" width="${sz + 8}" height="${sz + 8}" rx="9"/>
    ${polys.map(([s, pts]) => `<polygon class="sf ${surfClass(t, s) || whole}${ss(s)}" data-s="${s}" points="${pts}"/>`).join("")}
    <rect class="sf ${surfClass(t, center) || whole}${ss(center)}" data-s="${center}" x="${il}" y="${it}" width="${ir - il}" height="${ib - it}" rx="2"/>${mk}
    <text class="n" x="${x + sz / 2}" y="${upper ? T - 10 : B + 20}">${esc(L(n))}</text></g>`;
}
export function odontogram(p) {
  const chart = D.displayChart(p), teeth = chart.teeth || {}, d = chart.dentition || "permanent";
  const step = 54, gap = 26, w = 16 * step + gap + 20;
  const perm = [[[18, 17, 16, 15, 14, 13, 12, 11], [21, 22, 23, 24, 25, 26, 27, 28]], [[48, 47, 46, 45, 44, 43, 42, 41], [31, 32, 33, 34, 35, 36, 37, 38]]];
  const prim = [[[55, 54, 53, 52, 51], [61, 62, 63, 64, 65]], [[85, 84, 83, 82, 81], [71, 72, 73, 74, 75]]];
  const rows = d === "primary" ? [[prim[0], 3], [prim[1], 3]] : d === "mixed" ? [[perm[0], 0], [prim[0], 3], [prim[1], 3], [perm[1], 0]] : [[perm[0], 0], [perm[1], 0]];
  let out = "", y = 46;
  rows.forEach(([pair, off], idx) => {
    if (idx === rows.length / 2) y += 22;
    let x = 10 + off * step;
    pair[0].forEach((n) => { out += toothG(n, x, y, teeth[n]); x += step; }); x += gap;
    pair[1].forEach((n) => { out += toothG(n, x, y, teeth[n]); x += step; });
    y += 78;
  });
  const h = y + 6, mid = 10 + 8 * step + gap / 2 - 5;
  return `<div class="odo-wrap"><svg class="odo" viewBox="0 0 ${w} ${h}" role="img" aria-label="2D dental chart">
    <line class="midline" x1="${mid}" y1="6" x2="${mid}" y2="${h - 6}"/><line class="midline" x1="6" y1="${h / 2 + 2}" x2="${w - 6}" y2="${h / 2 + 2}"/>
    <text class="q" x="10" y="11">Patient right</text><text class="q" x="${w - 10}" y="11" text-anchor="end">Patient left</text>${out}</svg></div>`;
}
const LEGEND2D = `<div class="legend" style="margin-top:6px"><span><i style="background:var(--unassessed)"></i>Not assessed</span><span><i style="background:var(--assessed)"></i>Assessed</span><span><i style="background:var(--treated)"></i>Treated / restoration</span><span><i style="background:var(--brand-soft)"></i>Planned +</span><span><i style="background:var(--amber-fill)"></i>Finding</span><span><i style="background:var(--surface-3);border-style:dashed"></i>Lost ×</span></div>`;

// ============================================================================ rail: selected tooth + history
function payBlock(p, t) {
  const fee = D.treatmentFee(p, t);
  const billing = can("billing");
  if (!fee) return billing && t.status !== "Cancelled" ? `<button class="btn sm" style="margin-top:8px" data-act="pay" data-id="${t.id}">${I("wallet")}Set fee / add payment</button>` : "";
  const pc = Math.min(100, (fee.paid / fee.total) * 100);
  const pays = fee.invoice.payments || [];
  return `<div class="pay ${fee.due ? "due" : ""}"><div class="nums num"><span>Fee ${money(fee.total, c())}</span><span style="color:var(--green)">Paid ${money(fee.paid, c())}</span><span style="color:${fee.due ? "var(--amber)" : "var(--green)"}">${fee.due ? "Due " + money(fee.due, c()) : "Fully paid"}</span></div>
    <div class="track"><i style="width:${pc}%"></i></div>
    ${pays.length ? `<div class="small muted">${pays.map((x, i) => `Visit ${i + 1} · ${fmtMs(x.at, z())} · ${money(x.amount, c())} · ${esc(x.method)}${x.reference ? " · " + esc(x.reference) : ""} <button class="linkish small" data-act="receipt" data-inv="${fee.invoice.id}" data-pay="${x.id}">Receipt</button> · <button class="linkish small" data-act="rcptpdf" data-inv="${fee.invoice.id}" data-pay="${x.id}">PDF</button>`).join("<br>")}</div>` : ""}
    ${billing ? `<div class="row" style="margin-top:8px"><button class="btn sm" data-act="pay" data-id="${t.id}">${I("plus")}${fee.due ? "Add payment" : "Change fee"}</button></div>` : ""}</div>`;
}
function timeline(p, tooth) {
  const list = (p.treatments || []).filter((t) => !tooth || t.tooth === tooth).sort((a, b) => (b.at || 0) - (a.at || 0));
  if (!list.length) return `<div class="empty small">No treatment recorded${tooth ? " for this tooth" : ""} yet.${tooth && can("recordTreatment") ? `<br><button class="btn sm primary" style="margin-top:10px" data-act="record" data-teeth="${tooth}">${I("plus")}Record treatment</button>` : ""}</div>`;
  return `<div class="timeline">${list.map((t) => `<div class="tl ${t.status === "Planned" ? "planned" : t.status === "Cancelled" ? "cancel" : ""}">
    <div class="between"><b>${esc(t.procedure)} <span class="muted" style="font-weight:600">· <button class="linkish" data-act="tooth" data-k="${t.tooth}">${esc(L(t.tooth))}</button>${t.surface && t.surface !== "Whole tooth" ? " " + esc(t.surface) : ""}</span></b><span class="badge ${badge(t.status)}">${esc(t.status)}</span></div>
    <div class="m">${fmtMs(t.performedAt || t.at, z())}${t.doctor ? " · " + esc(t.doctor) : ""}</div>
    ${t.notes ? `<div class="small" style="margin-top:4px">${esc(t.notes)}</div>` : ""}
    ${payBlock(p, t)}
    ${can("recordTreatment") ? `<div class="row" style="margin-top:6px"><button class="btn ghost sm" data-act="edit" data-id="${t.id}">${I("edit")}Edit</button>${(t.events || []).length > 1 ? `<span class="small muted">${t.events.length - 1} change${t.events.length > 2 ? "s" : ""} logged</span>` : ""}</div>` : ""}
  </div>`).join("")}</div>`;
}
function railContent(p) {
  const k = P.tooth;
  let top = "";
  if (k) {
    const f = D.toothFacts(p, k);
    const layout = (() => { const rs = [1, 4, 5, 8].includes(+k[0]); const ctr = D.topSurface(k); return ["", "F", "", rs ? "D" : "M", ctr, rs ? "M" : "D", "", "L", ""]; })();
    top = `<div class="tooth-card"><div class="between" style="align-items:flex-start"><div><div class="rail-title" style="margin:0 0 2px">Selected tooth</div><h4>${esc(L(k))} ${N() !== "fdi" ? `<span class="muted" style="font-weight:600;font-size:13px">(FDI ${k})</span>` : ""}</h4><div class="small muted">${esc(D.toothName(k))}</div>
      <div class="small" style="margin-top:4px">${esc(D.pretty(f.assessment))} · ${esc(D.pretty(f.presence))}${f.replacement !== "none" ? " · " + esc(D.pretty(f.replacement)) : ""}</div></div>
      <div class="surfpad" aria-label="Surface">${layout.map((s, i) => (s ? `<button class="${P.surface === s ? "on" : ""}" data-act="surface" data-s="${s}" title="${esc(D.surfaceName(k, s))}">${s}</button>` : i === 0 ? `<button class="${!P.surface ? "on" : ""}" data-act="surface" data-s="" title="Whole tooth">W</button>` : "<span></span>")).join("")}</div></div>
      <div class="alerts" style="margin:10px 0">${[...f.findings.map((x) => `<span class="badge amber">${esc(x)}</span>`), ...f.restorations.map((x) => `<span class="badge blue">${esc(x)}</span>`), ...f.open.map((x) => `<span class="badge teal">${esc(x.status)}</span>`)].join("") || `<span class="badge">No coded findings</span>`}</div>
      <div class="row" style="flex-wrap:wrap">${can("recordTreatment") ? `<button class="btn primary sm" data-act="record" data-teeth="${k}">${I("plus")}Record treatment</button><button class="btn sm" data-act="chartstatus">${I("clip")}Chart status</button>` : `<span class="ro-note">${I("lock")}View only · treatment entries are added by the dentist.</span>`}</div></div>`;
  }
  return `${top}<div class="between"><div class="rail-title">Treatment history ${k ? "· " + esc(L(k)) : "· all teeth"}</div>${k ? `<button class="btn ghost sm" data-act="alltheeth">All teeth</button>` : ""}</div>${timeline(p, k)}`;
}

// ============================================================================ tabs
function upcoming(p) {
  const now = Date.now();
  return (p.appointments || []).filter((a) => !D.TERMINAL_APPOINTMENTS.includes(a.status)).map((a) => ({ ...a, startAt: safeStart(a) })).filter((a) => a.startAt >= now - 3600000).sort((a, b) => a.startAt - b.startAt);
}
const safeStart = (a) => { try { return D.appointmentMillis(a, S.config); } catch { return 0; } };
function tabOverview(p) {
  const up = upcoming(p), past = (p.appointments || []).filter((a) => !up.some((u) => u.id === a.id)).sort((a, b) => safeStart(b) - safeStart(a)).slice(0, 5);
  const editP = can("managePatients"), clin = can("recordTreatment");
  return `<div class="card rail-inline" style="margin-bottom:16px"><div class="card-b">${railContent(p)}</div></div>
  <div class="ov-grid" style="margin-top:0">
    <div class="card"><div class="card-h"><h3>Profile</h3><div class="r">${editP ? `<button class="btn sm" data-act="profile">${I("edit")}Edit profile</button>` : ""}</div></div><div class="card-b"><dl class="kv">
      <dt>Date of birth</dt><dd>${p.dob ? `${fmtIso(p.dob)} (${ageFrom(p.dob, z())} y)` : "Not recorded"}</dd><dt>Gender</dt><dd>${esc(p.gender || "Not recorded")}</dd><dt>Phone</dt><dd class="num">${esc(p.phone || "Not recorded")}</dd>
      <dt>Address</dt><dd>${esc(p.address || "Not recorded")}</dd><dt>Medical alerts</dt><dd>${alertsOf(p).map((a) => `<span class="badge red" style="margin:0 4px 4px 0">${esc(a)}</span>`).join("") || "None recorded"}</dd>
      <dt>Record</dt><dd class="mono small">${esc(p.id.slice(0, 18))}${p.archived ? ' <span class="badge">Archived</span>' : ""}</dd></dl></div></div>
    <div class="card"><div class="card-h"><h3>Clinical story</h3><div class="r">${clin ? `<button class="btn sm" data-act="story">Update</button>` : ""}</div></div><div class="card-b"><p style="margin:0;color:var(--ink-2);white-space:pre-wrap">${esc(p.story || "No clinical narrative yet.")}</p></div></div>
    <div class="card"><div class="card-h"><h3>Appointments</h3><div class="r">${can("manageAppointments") ? `<button class="btn sm" data-act="book">${I("plus")}Book</button>` : ""}</div></div><div class="card-b">
      ${up.map((a) => apptRow(p, a, true)).join("") || `<p class="muted small" style="margin:0">No upcoming appointments.</p>`}
      ${past.length ? `<div class="rail-title" style="margin-top:12px">Earlier</div>${past.map((a) => apptRow(p, a, false)).join("")}` : ""}
      <hr class="sep"><div class="set-row" style="padding:0"><div><b>Appointment reminders</b><small>Patient consents to reminders</small></div><label class="switch"><input type="checkbox" data-act="consent" ${p.communicationConsent ? "checked" : ""} ${editP ? "" : "disabled"}><i></i></label></div></div></div>
    <div class="card"><div class="card-h"><h3>Patient app access</h3></div><div class="card-b">${p.portal?.loginId ? `<div class="between"><div><div class="small muted">Patient app ID</div><div class="mono" style="font-size:18px;font-weight:600">${esc(p.portal.loginId)}</div></div><span class="badge green">Active</span></div>
      ${can("patientLogins") ? `<div class="row" style="margin-top:12px;flex-wrap:wrap"><button class="btn sm" data-act="login">Change ID / password</button><button class="btn sm danger" data-act="logoutapp">Turn off</button></div>` : ""}`
      : `<p class="muted small" style="margin:0 0 10px">No patient app login yet. The patient signs in with a Patient ID and password you choose.</p>${can("patientLogins") ? `<button class="btn sm primary" data-act="login">Create patient login</button>` : ""}`}</div></div>
    <div class="card"><div class="card-h"><h3>Clinical images</h3><div class="r">${clin ? `<label class="btn sm">${I("img")}Attach image<input type="file" accept="image/*" data-act="attach" hidden></label>` : ""}</div></div><div class="card-b"><div class="imgs">${(p.images || []).map((im, i) => `<div class="img-tile" data-act="viewimg" data-i="${i}" data-path="${esc(im.path)}"><span>${esc(im.format?.toUpperCase() || "IMG")} · ${Math.round((im.bytes || 0) / 1024)} KB</span></div>`).join("") || `<p class="muted small" style="margin:0">No images attached.</p>`}</div></div></div>
    <div class="card"><div class="card-h"><h3>Records</h3></div><div class="card-b"><div class="row" style="flex-wrap:wrap"><button class="btn sm" data-act="pdf">${I("print")}Patient report PDF</button><button class="btn sm" data-act="json">${I("download")}Export JSON</button>${editP ? `<button class="btn sm ${p.archived ? "" : "danger"}" data-act="archive">${I("archive")}${p.archived ? "Restore patient" : "Archive patient"}</button>` : ""}</div><p class="small muted" style="margin:10px 0 0">Exported files contain sensitive patient information; choose the destination carefully.</p></div></div>
  </div>`;
}
function apptRow(p, a, actions) {
  const ok = actions && can("manageAppointments");
  return `<div class="list-item"><div class="mono" style="width:92px;font-size:12px">${fmtMs(safeStart(a), z(), true).replace(" · ", "<br>")}</div><div class="grow"><b>${esc(a.reason)}</b><small>${esc(a.doctor)}${a.room ? " · " + esc(a.room) : ""}${a.bookedBy === "patient" ? " · patient app" : ""}</small></div>
    <span class="badge ${stCls(a.status)}">${esc(a.status)}</span>${ok ? `<button class="btn ghost sm" data-act="appt" data-id="${a.id}" title="Change">${I("edit")}</button>` : ""}</div>`;
}
function tabChart(p) {
  const k = P.tooth || "", t = p.chart?.teeth?.[k] || {};
  const d = p.chart?.dentition || "permanent";
  const ro = !can("recordTreatment");
  return `<div class="card"><div class="card-h"><h3>Assess teeth</h3><span class="sub hide-sm">Assessment, presence, findings and restorations are recorded as separate facts</span>
    <div class="r"><div class="seg">${["permanent", "primary", "mixed"].map((x) => `<button class="${d === x ? "on" : ""}" data-act="dentition" data-d="${x}" ${ro ? "disabled" : ""}>${x[0].toUpperCase() + x.slice(1)}</button>`).join("")}</div></div></div>
    <div class="card-b" id="odo2d">${odontogram(p)}${LEGEND2D}</div></div>
  ${k ? `<div class="ov-grid"><div class="card"><div class="card-h"><h3>Chart status · ${esc(L(k))}</h3><span class="sub">${esc(D.toothName(k))}</span></div><div class="card-b"><form id="cs" class="grid-form" onsubmit="return false">
    ${field("Assessment", select("assessment", [["assessed", "Assessed"], ["not_assessed", "Not assessed"], ["unable_to_assess", "Unable to assess"]], t.assessment || "assessed"))}
    ${field("Presence", select("presence", D.PRESENCE.map((x) => [x, D.pretty(x)]), t.presence || "present"))}
    ${field("Replacement", select("replacement", [["not recorded", "Not recorded"], ...D.REPLACEMENTS.map((x) => [x, D.pretty(x)])], t.replacement || "not recorded"))}
    ${field("Surface", select("surface", [["Whole tooth", "Whole tooth"], ...["M", "D", "F", "L", D.topSurface(k)].map((s) => [s, `${s} · ${D.surfaceName(k, s)}`])], P.surface || "Whole tooth"))}
    ${field("Add finding", select("finding", [["none", "None"], ...D.FINDINGS.slice(0, 5).map((x) => [x, D.pretty(x)])]))}
    ${field("Add restoration (select a surface)", select("restoration", [["none", "None"], ...D.RESTORATIONS.slice(0, 7).map((x) => [x, D.pretty(x)])]))}
    <div class="field" style="grid-column:1/-1">${check("clearFindings", "Resolve findings at selected scope")}</div>
    ${field("Planned procedure (optional)", `<input class="input" name="procedure" list="dl-proc" placeholder="e.g. Filling (composite)">${procList()}`)}${field("Clinical note", input("note", ""))}
    <div class="field" style="grid-column:1/-1"><div class="row">${ro ? `<span class="ro-note">${I("lock")}You can view the chart; recording needs "Record & edit treatment".</span>` : `<button class="btn primary" data-act="savestatus">${I("check")}Save assessment</button>`}<span class="err" id="cserr"></span></div></div></form></div></div>
    <div class="card"><div class="card-b"><div class="notice teal">${I("alert")}<span>An unrecorded tooth is unassessed, never automatically healthy. Record missing or extracted teeth explicitly.</span></div>
    <p class="small muted">Click a surface on the chart to pick it. The 3D model above shows the same chart (schema v2, FDI keys).</p></div></div></div>`
    : `<div class="empty">Click a tooth on the chart (or on the 3D model) to assess it.</div>`}`;
}
function tabEncounters(p) {
  const list = (p.encounters || []).slice().reverse();
  const clin = can("recordTreatment");
  return `<div class="between" style="margin-bottom:12px"><span class="muted small">Finalized notes keep their original text. Corrections are amendments.</span>${clin ? `<button class="btn primary" data-act="newenc">${I("plus")}New encounter</button>` : ""}</div>
  <div style="display:grid;gap:12px">${list.map((x) => `<div class="card"><div class="card-h"><h3>${fmtMs(x.at, z())} · ${esc(x.complaint)}</h3><div class="r">${(x.amendments || []).length ? `<span class="badge violet">${x.amendments.length} amendment${x.amendments.length > 1 ? "s" : ""}</span>` : ""}<span class="badge ${x.status === "Draft" ? "amber" : "green"}">${esc(x.status)}</span></div></div>
    <div class="card-b"><dl class="kv"><dt>Clinician</dt><dd>${esc(x.doctor)}</dd><dt>Examination</dt><dd>${esc(x.examination || "Not entered")}</dd><dt>Diagnosis</dt><dd>${esc(x.diagnosis || "Not entered")}</dd><dt>Plan</dt><dd>${esc(x.plan || "Not entered")}</dd></dl>
    ${(x.amendments || []).map((a) => `<div class="notice" style="margin-top:10px;background:var(--accent-soft);color:var(--accent)">${I("edit")}<span>Amendment · ${fmtMs(a.at, z())}: ${esc(a.reason)}<br><span style="font-weight:500">${esc(a.text)}</span></span></div>`).join("")}
    ${clin ? `<div class="row" style="margin-top:12px">${x.status === "Draft" ? `<button class="btn sm" data-act="editenc" data-id="${x.id}">Edit draft</button><button class="btn sm primary" data-act="finenc" data-id="${x.id}">Finalize</button>` : `<button class="btn sm" data-act="amendenc" data-id="${x.id}">Amend with reason</button>`}</div>` : ""}</div></div>`).join("") || `<div class="empty">No encounters yet.</div>`}</div>`;
}
function tabTreatments(p) {
  const clin = can("recordTreatment");
  const list = (p.treatments || []).slice().sort((a, b) => (b.at || 0) - (a.at || 0));
  return `<div class="card"><div class="card-h"><h3>Treatment plan</h3><div class="r">${clin ? `<button class="btn primary sm" data-act="record" data-teeth="${P.tooth || ""}">${I("plus")}Record treatment</button>` : ""}</div></div>
  <div class="table-wrap"><table class="t"><thead><tr><th>Tooth</th><th>Procedure</th><th>Status</th><th class="hide-sm">Clinician</th><th>Date</th><th style="text-align:right">Fee</th><th style="text-align:right">Due</th><th></th></tr></thead><tbody>
  ${list.map((t) => { const fee = D.treatmentFee(p, t); return `<tr class="click" data-act="tooth" data-k="${t.tooth}"><td class="mono">${esc(L(t.tooth))}</td><td><b>${esc(t.procedure)}</b><div class="small muted">${esc(t.surface || "")}</div></td><td><span class="badge ${badge(t.status)}">${esc(t.status)}</span></td><td class="hide-sm">${esc(t.doctor || "")}</td><td>${fmtMs(t.at, z())}</td>
    <td class="num" style="text-align:right">${fee ? money(fee.total, c()) : "-"}</td><td class="num" style="text-align:right;color:${fee?.due ? "var(--amber)" : "var(--muted)"}">${fee ? money(fee.due, c()) : "-"}</td>
    <td>${clin ? `<div class="row" style="gap:4px">${(D.NEXT_STATES[t.status] || []).map((n) => `<button class="btn sm" data-act="move" data-id="${t.id}" data-to="${n}">${n}</button>`).join("")}</div>` : ""}</td></tr>`; }).join("") || `<tr><td colspan="8" class="empty">No treatment yet. Use Record treatment on the 3D model above.</td></tr>`}
  </tbody></table></div></div>
  <div class="notice" style="margin-top:12px">${I("alert")}<span>Completing care doesn't resolve findings automatically; reassess the tooth in Chart status.</span></div>
  ${(p.performedProcedures || []).length ? `<div class="card" style="margin-top:16px"><div class="card-h"><h3>Performed procedures</h3></div><div class="card-b">${(p.performedProcedures || []).slice().reverse().map((x) => `<div class="list-item"><div class="grow"><b>${esc(x.procedure)} · ${esc(L(x.tooth))}${x.voided ? ' <span class="badge">voided</span>' : ""}</b><small>${fmtMs(x.at, z())} · ${esc(x.doctor || "")} · ${esc(x.outcome || "")}</small>${(x.amendments || []).map((a) => `<div class="small" style="color:var(--accent)">Amendment: ${esc(a.reason)} · ${esc(a.text)}</div>`).join("")}</div>${clin ? `<button class="btn ghost sm" data-act="amendperf" data-id="${x.id}">Amend</button>` : ""}</div>`).join("")}</div></div>` : ""}`;
}
function invoiceCard(p, v) {
  const tot = D.invoiceTotal(v), got = D.paid(v) - D.refunded(v), due = Math.max(0, D.balance(v)), st = D.invoiceState(v), cc = v.currency || c(), b = can("billing");
  return `<div class="card"><div class="card-h"><h3>${esc(v.description || "Invoice")}</h3><div class="r"><span class="badge ${({ Paid: "green", "Partially paid": "amber", Unpaid: "red", Voided: "" })[st]}">${st}</span></div></div>
  <div class="card-b"><div class="small muted" style="margin-bottom:6px">${fmtMs(v.at, z())}${v.treatmentRef ? " · treatment fee" : ""}</div><table style="width:100%" class="inv-lines">${(v.lines || [{ description: v.description, unitPrice: v.amount, quantity: 1 }]).map((l) => `<tr><td>${esc(l.description)}${l.quantity > 1 ? " × " + l.quantity : ""}</td><td class="num" style="text-align:right">${money(l.unitPrice * l.quantity, cc)}</td></tr>`).join("")}</table>
  ${v.discount ? `<div class="total-row"><span class="muted">Discount</span><span class="num">- ${money(v.discount, cc)}</span></div>` : ""}${v.taxBps ? `<div class="total-row"><span class="muted">Tax ${v.taxBps / 100}%</span><span></span></div>` : ""}
  <div class="total-row big"><span>Total</span><span class="num">${money(tot, cc)}</span></div><div class="total-row"><span class="muted">Received</span><span class="num" style="color:var(--green)">${money(got, cc)}</span></div><div class="total-row"><span class="muted">Balance</span><span class="num" style="color:${due ? "var(--amber)" : "var(--muted)"}">${money(due, cc)}</span></div>
  ${(v.payments || []).map((x) => `<div class="small muted">${fmtMs(x.at, z())} · ${money(x.amount, cc)} · ${esc(x.method)} <button class="linkish small" data-act="receipt" data-inv="${v.id}" data-pay="${x.id}">Receipt</button> · <button class="linkish small" data-act="rcptpdf" data-inv="${v.id}" data-pay="${x.id}">PDF</button></div>`).join("")}
  ${(v.refunds || []).map((x) => `<div class="small" style="color:var(--red)">Refund ${money(x.amount, cc)} · ${esc(x.reason)}</div>`).join("")}${v.voidReason ? `<div class="small" style="color:var(--red)">Voided: ${esc(v.voidReason)}</div>` : ""}
  ${b && st !== "Voided" ? `<div class="row" style="margin-top:10px;flex-wrap:wrap">${due ? `<button class="btn sm primary" data-act="receive" data-inv="${v.id}">${I("wallet")}Receive payment</button>` : ""}<button class="btn sm" data-act="shareinv" data-inv="${v.id}">${I("share")}Share invoice</button><button class="btn sm" data-act="invpdf" data-inv="${v.id}">${I("download")}Invoice PDF</button><button class="btn sm ghost" data-act="refund" data-inv="${v.id}">Refund</button><button class="btn sm ghost danger" data-act="void" data-inv="${v.id}">Void</button></div>` : ""}</div></div>`;
}
export { invoiceCard };
function tabBilling(p) {
  return `<div class="between" style="margin-bottom:12px"><span class="muted small">Balance due <b style="color:var(--amber)">${money(D.patientDue(p), c())}</b></span>${can("billing") ? `<button class="btn primary" data-act="invoice">${I("plus")}Create invoice</button>` : ""}</div>
  <div style="display:grid;gap:12px;grid-template-columns:repeat(auto-fill,minmax(320px,1fr))">${(p.invoices || []).slice().reverse().map((v) => invoiceCard(p, v)).join("") || `<div class="empty">Issued invoices and received payments will appear here.</div>`}</div>`;
}
function tabHistory(p) {
  const h = p.history || [];
  return `<div class="card"><div class="card-b"><div class="timeline">${h.map((x, i) => [x, i]).reverse().map(([x, i]) => `<div class="tl"><b>${esc(x.text)}</b><div class="m">${fmtMs(x.at, z(), true)}${x.actor ? " · " + esc(x.actor) : ""}</div>
    ${x.previousValues && Object.keys(x.previousValues).length ? `<div class="history-prev">Changed: ${Object.keys(x.previousValues).join(", ")}</div>${x.previousValues.chart ? `<div class="row" style="margin-top:4px"><button class="btn ghost sm" data-act="compare" data-i="${i}">Compare chart</button><button class="btn ghost sm" data-act="earlier" data-i="${i}">${I("cube")}View earlier chart in 3D</button></div>` : ""}` : ""}</div>`).join("") || `<div class="empty">No history yet.</div>`}</div></div></div>`;
}
function earlierChart(p, index) {
  const earlier = clone(p);
  const h = p.history || [];
  for (let i = h.length - 1; i >= index; i--) { const before = h[i].previousValues; if (before) for (const k of Object.keys(before)) earlier[k] = clone(before[k]); }
  return earlier;
}
const chartSummary = (chart) => Object.entries(chart?.teeth || {}).filter(([, t]) => (t.findings || []).length || t.surfaces || D.LOST.includes(t.presence) || (t.treatmentStates || []).length || t.replacement)
  .map(([k, t]) => `${L(k)}: ${D.pretty(t.presence)}${(t.findings || []).length ? "; " + t.findings.map(D.pretty).join(", ") : ""}${Object.entries(t.surfaces || {}).map(([s, o]) => `; ${s} ${[...(o.findings || []), ...(o.restorations || [])].map(D.pretty).join(", ")}`).join("")}`);

// ============================================================================ page
export function renderPatients(id) {
  if (id && id !== P.pid) { P.pid = id; P.tooth = ""; P.surface = ""; P.multi = []; P.earlier = null; }
  const q = P.q.trim().toLowerCase(), today = todayIn(z());
  const list = S.patients.filter((p) => (P.archived ? true : !p.archived) && (!q || [p.name, p.phone, p.id, p.portal?.loginId || ""].join(" ").toLowerCase().includes(q)) &&
    (P.filter === "All" || (P.filter === "Dues" && D.patientDue(p) > 0) || (P.filter === "Alerts" && alertsOf(p).length) || (P.filter === "Today" && (p.appointments || []).some((a) => String(a.date).startsWith(today) && a.status !== "Cancelled"))))
    .sort((a, b) => a.name.localeCompare(b.name));
  let p = patient(P.pid);
  if (!p) { p = list[0] || S.patients[0]; if (p) { P.pid = p.id; history.replaceState(null, "", "#/patients/" + p.id); } }
  const listHtml = `<section class="plist"><div class="head">
    <div class="between"><b>Patients <span class="muted" style="font-weight:600">${list.length}</span></b>${can("managePatients") ? `<button class="btn primary sm" data-act="register">${I("plus")}Register</button>` : ""}</div>
    <input class="input" id="pq" placeholder="Search name, phone, or patient ID" value="${esc(P.q)}" autocomplete="off">
    <div class="chips">${["All", "Today", "Dues", "Alerts"].map((f) => `<button class="chip ${P.filter === f ? "on" : ""}" data-act="filter" data-f="${f}">${f}</button>`).join("")}</div>
    <label class="row small" style="font-weight:600;color:var(--muted)"><input type="checkbox" data-act="archived" ${P.archived ? "checked" : ""}> Include archived</label></div>
    <div class="items">${list.map((x) => { const d = D.patientDue(x); return `<div class="pitem ${p && x.id === p.id ? "on" : ""}" data-act="open" data-id="${x.id}"><div class="avatar" style="background:${avatarBg(x)}">${initials(x.name)}</div><div class="grow"><b>${esc(x.name)}${x.archived ? ' <span class="badge">Archived</span>' : ""}</b><small class="num">${esc(x.phone || "")}${x.portal?.loginId ? " · " + esc(x.portal.loginId) : ""}</small></div><div style="text-align:right">${alertsOf(x).length ? `<span class="badge red" title="${esc(alertsOf(x).join(", "))}">${I("alert")}</span>` : ""}${d ? `<div class="small num" style="color:var(--amber);font-weight:700">${money(d, c())}</div>` : ""}</div></div>`; }).join("") || `<div class="empty">No matching patient.${can("managePatients") ? `<br><button class="btn sm" style="margin-top:8px" data-act="register">Register patient</button>` : ""}</div>`}</div></section>`;
  if (!p) {
    return `<div class="pw listmode" id="pw">${listHtml}<section class="pcenter"><div class="empty" style="margin-top:12vh"><h2>Your patient list starts here</h2><p>Register a patient to create their profile and dental chart.</p>${can("managePatients") ? `<button class="btn primary" data-act="register">${I("plus")}Register patient</button>` : ""}</div></section></div>`;
  }
  const due = D.patientDue(p);
  const conflict = S.conflicts[p.id];
  const viewClinical = can("viewClinical");
  const tabs = [["overview", "Overview"], ["chart", "Chart"], ["encounters", "Encounters", (p.encounters || []).filter((e) => e.status === "Draft").length], ["treatments", "Treatments", (p.treatments || []).length], ["billing", "Billing"], ["history", "History"]]
    .filter(([k]) => viewClinical || ["overview", "billing"].includes(k));
  if (!tabs.some(([k]) => k === P.tab)) P.tab = "overview";
  const body = { overview: tabOverview, chart: tabChart, encounters: tabEncounters, treatments: tabTreatments, billing: tabBilling, history: tabHistory }[P.tab](p);
  return `<div class="pw ${P.listmode ? "listmode" : ""} ${P.collapsed ? "collapsed" : ""}" id="pw">${listHtml}
  <section class="pcenter" id="pcenter">
    <button class="btn ghost sm plist-back" style="margin-bottom:10px" data-act="listmode">${I("chevl")}All patients</button>
    <div class="phead"><div class="avatar lg" style="background:${avatarBg(p)}">${initials(p.name)}</div>
      <div style="flex:1;min-width:220px"><h1 class="h1">${esc(p.name)}</h1>
        <div class="meta">${p.dob ? `<span>${I("user")}${esc(p.gender || "")}${p.gender ? ", " : ""}${ageFrom(p.dob, z())} y</span>` : ""}${p.phone ? `<span>${I("phone")}${esc(p.phone)}</span>` : ""}${p.portal?.loginId ? `<span>${I("lock")}App ID ${esc(p.portal.loginId)}</span>` : ""}<span>Rev ${p.revision || 0}</span></div>
        <div class="alerts">${alertsOf(p).map((a) => `<span class="badge red">${I("alert")}${esc(a)}</span>`).join("") || `<span class="badge">No medical alerts</span>`}${due ? `<span class="badge amber">Due ${money(due, c())}</span>` : ""}${p.archived ? `<span class="badge">Archived</span>` : ""}</div></div>
      <div class="row" style="flex-wrap:wrap"><button class="btn hide-sm" data-act="collapse" title="Show or hide the patient list">${I(P.collapsed ? "chev" : "chevl")}</button>${can("manageAppointments") ? `<button class="btn" data-act="book">${I("cal")}Book</button>` : ""}${can("billing") ? `<button class="btn" data-act="tab" data-t="billing">${I("wallet")}Billing</button>` : ""}${can("recordTreatment") ? `<button class="btn primary" data-act="record" data-teeth="${P.tooth || ""}">${I("plus")}Record treatment</button>` : ""}</div></div>
    ${conflict ? `<div class="notice red" style="margin-top:14px">${I("alert")}<span>Another device changed this patient. <button class="linkish" data-act="conflict">Review conflict · retain draft and load server version</button></span></div>` : ""}
    ${viewClinical ? `<div class="stage-slot" id="stage-slot" style="margin-top:16px"></div>` : `<div class="notice" style="margin-top:16px">${I("lock")}<span>Your role doesn't include viewing charts. Ask your admin for "View charts & treatment history".</span></div>`}
    <div class="tabs" role="tablist">${tabs.map(([k, l, b]) => `<button class="${P.tab === k ? "on" : ""}" data-act="tab" data-t="${k}">${l}${b ? `<span class="badge">${b}</span>` : ""}</button>`).join("")}</div>
    ${body}
  </section>
  <section class="prail" id="prail">${viewClinical ? railContent(p) : ""}</section></div>`;
}
export function afterRenderPatients() {
  const p = patient(P.pid);
  const pq = $("#pq");
  if (pq) pq.oninput = debounce(() => { P.q = pq.value; ctx.render(); setTimeout(() => { const n = $("#pq"); n?.focus(); n?.setSelectionRange(n.value.length, n.value.length); }); }, 150);
  if (!p) return Viewer.unmount();
  if ($("#stage-slot")) mountStage(p); else Viewer.unmount();
  const odo = $("#odo2d");
  if (odo) odo.onclick = (e) => {
    const g = e.target.closest("[data-tooth]"); if (!g) return;
    const k = g.dataset.tooth, s = e.target.dataset?.s || "";
    P.tooth = k; P.surface = P.tooth === k && s ? s : "";
    Viewer.call("selectTooth", k); ctx.render();
  };
}

// ============================================================================ actions (event delegation from app.js)
export async function patientAction(act, el, e) {
  const p = patient(P.pid);
  const ds = el.dataset;
  switch (act) {
    case "open": P.listmode = false; ctx.go("patients/" + ds.id); return;
    case "filter": P.filter = ds.f; break;
    case "archived": P.archived = el.checked; break;
    case "listmode": P.listmode = true; break;
    case "collapse": P.collapsed = !P.collapsed; break;
    case "tab": P.tab = ds.t; break;
    case "register": registerDialog(); return;
    case "profile": registerDialog(p); return;
    case "tooth": if (e.target.closest("button,select") && act !== "tooth") return; P.tooth = ds.k; P.surface = ""; Viewer.call("selectTooth", ds.k); break;
    case "alltheeth": P.tooth = ""; P.surface = ""; Viewer.call("clearSelection"); break;
    case "surface": P.surface = ds.s; break;
    case "record": recordDialog(ds.teeth ? ds.teeth.split(",").filter(Boolean) : P.tooth ? [P.tooth] : [], null, P.surface); return;
    case "edit": { const t = p.treatments.find((x) => x.id === ds.id); recordDialog([t.tooth], t); return; }
    case "pay": payDialog(p, p.treatments.find((x) => x.id === ds.id)); return;
    case "receipt": { const inv = p.invoices.find((v) => v.id === ds.inv); const pay = inv.payments.find((x) => x.id === ds.pay); shareText("Payment receipt", receiptText(p, inv, pay)); return; }
    case "shareinv": { const v = p.invoices.find((x) => x.id === ds.inv); shareText("Invoice", `${S.config.name}\nINVOICE\nPatient: ${p.name}\nDate: ${fmtMs(v.at, z())}\n${(v.lines || []).map((l) => `${l.description} × ${l.quantity}: ${money(l.unitPrice * l.quantity, v.currency)}`).join("\n")}\nTotal: ${money(D.invoiceTotal(v), v.currency)}\nPaid: ${money(D.paid(v) - D.refunded(v), v.currency)}\nBalance: ${money(Math.max(0, D.balance(v)), v.currency)}`); return; }
    case "receive": receivePaymentDialog(p.id, ds.inv); return;
    case "refund": refundDialog(p.id, ds.inv); return;
    case "void": voidDialog(p.id, ds.inv); return;
    case "invoice": invoiceDialog(p.id); return;
    case "chartstatus": P.tab = "chart"; break;
    case "dentition": { const copy = clone(p); copy.chart ||= D.emptyChart(); copy.chart.dentition = ds.d; await save(copy, `Chart inventory: ${ds.d}`); return; }
    case "savestatus": {
      const v = values($("#cs"));
      try { const copy = clone(p); const text = D.applyChartStatus(copy, P.tooth, { ...v, surface: v.surface }); const err = await save(copy, text); if (err) $("#cserr").textContent = err; } catch (err) { $("#cserr").textContent = err.message; }
      return;
    }
    case "story": modal({ title: "Clinical story", body: field("Clinical narrative", textarea("story", p.story || "", 'style="min-height:200px"')), onSave: (v) => { const copy = clone(p); copy.story = v.story; return save(copy, "Clinical story amended"); } }); return;
    case "consent": { const copy = clone(p); copy.communicationConsent = el.checked; await save(copy, "Reminder consent updated"); return; }
    case "book": bookDialog({ pid: p.id }); return;
    case "appt": apptDialog(p, ds.id); return;
    case "login": loginDialog(p); return;
    case "logoutapp": confirmBox("Turn off patient app access", `${esc(p.name)} will no longer be able to sign in to the patient app.`, "Turn off", async () => { await removePatientLogin(p.id); say("Patient app access turned off"); }); return;
    case "attach": { const f = el.files?.[0]; if (!f) return; say("Compressing and uploading…"); try { await attachImage(p.id, f); } catch (err) { say(err.message, "error"); } return; }
    case "viewimg": { const url = await S.backend.imageUrl(ds.path).catch(() => ""); modal({ title: "Clinical image", wide: true, noFooter: true, body: url ? `<img src="${esc(url)}" style="max-width:100%;border-radius:12px" alt="Clinical image">` : `<div class="empty">The image couldn't be loaded.</div>` }); return; }
    case "pdf": pdf(() => Reports.patientPdf(p)); return;
    case "invpdf": { const v = p.invoices.find((x) => x.id === ds.inv); pdf(() => Reports.invoicePdf(p, v)); return; }
    case "rcptpdf": { const v = p.invoices.find((x) => x.id === ds.inv); pdf(() => Reports.receiptPdf(p, v, v.payments.find((x) => x.id === ds.pay))); return; }
    case "json": download(`patient-${p.id}.json`, JSON.stringify(Object.fromEntries(Object.entries(p).filter(([k]) => !["remoteVersion"].includes(k))), null, 2)); return;
    case "archive": modal({ title: p.archived ? "Restore archived patient" : "Archive patient", body: field("Reason", input("reason", "")), saveLabel: p.archived ? "Restore" : "Archive", danger: !p.archived,
      onSave: (v) => { if (!v.reason.trim()) return "A reason is required"; const copy = clone(p); copy.archived = !p.archived; return save(copy, `Patient archive state amended: ${v.reason.trim()}`); } }); return;
    case "conflict": await resolveConflict(p.id); break;
    case "newenc": case "editenc": encounterDialog(p, ds.id); return;
    case "finenc": { const copy = clone(p); Object.assign(copy.encounters.find((x) => x.id === ds.id), { status: "Finalized", finalizedAt: Date.now() }); await save(copy, "Encounter finalized"); return; }
    case "amendenc": amendDialog("Amend finalized encounter", (v) => { const copy = clone(p); arr(copy.encounters.find((x) => x.id === ds.id), "amendments").push({ ...v, id: uid(), at: Date.now() }); return save(copy, `Finalized encounter amended: ${v.reason}`); }); return;
    case "amendperf": amendDialog("Amend procedure record", (v) => { const copy = clone(p); arr(copy.performedProcedures.find((x) => x.id === ds.id), "amendments").push({ ...v, id: uid(), at: Date.now() }); return save(copy, `Performed procedure amended: ${v.reason}`); }); return;
    case "move": moveDialog(p, ds.id, ds.to); return;
    case "compare": { const before = earlierChart(p, +ds.i); modal({ title: "Compare chart", wide: true, noFooter: true, body: `<div class="grid2"><div><div class="rail-title">Before this change</div><pre style="white-space:pre-wrap;margin:0;font:12.5px/1.6 'JetBrains Mono',monospace">${esc(chartSummary(before.chart).join("\n") || "No recorded findings")}</pre></div><div><div class="rail-title">Current chart</div><pre style="white-space:pre-wrap;margin:0;font:12.5px/1.6 'JetBrains Mono',monospace">${esc(chartSummary(p.chart).join("\n") || "No recorded findings")}</pre></div></div>` }); return; }
    case "earlier": { const before = earlierChart(p, +ds.i); P.earlier = { ...D.displayChart(before) }; Viewer.setChart(stageChart(p)); refreshStage(); $("#stage-slot")?.scrollIntoView({ behavior: "smooth", block: "center" }); return; }
    default: return;
  }
  ctx.render();
}

// ============================================================================ dialogs
const procList = () => `<datalist id="dl-proc">${[...new Set([...D.PROCEDURES.map((x) => x[0]), ...(S.config.services || []).filter((s) => s.active !== false).map((s) => s.name)])].map((x) => `<option>${esc(x)}</option>`).join("")}</datalist>`;
export function recordDialog(teeth, editing = null, surface = "") {
  const p = patient(P.pid);
  if (!can("recordTreatment")) return say("You don't have access to record and edit treatment.", "error");
  if (!teeth.length) { say("Select a tooth on the 3D model first (or switch on multi-select).", "error"); return; }
  const docs = D.clinicians(S.config);
  const top = D.topSurface(teeth[0]);
  const cur = editing ? (editing.surface === "Whole tooth" ? [] : String(editing.surface || "").split("").map((x) => (x === top ? "Top" : x))) : surface ? [surface === top ? "Top" : surface] : [];
  const ymd = (ms) => zoned(ms, z()).date;
  const fee = editing ? D.treatmentFee(p, editing) : null;
  modal({
    title: editing ? `Edit treatment · ${esc(L(editing.tooth))}` : `Record treatment · ${teeth.map((k) => esc(L(k))).join(", ")}`, wide: true,
    body: `<div class="grid-form">
      ${field("Procedure", `<input class="input" name="procedure" list="dl-proc" value="${esc(editing?.procedure || "")}" placeholder="Pick or type your own">${procList()}`)}
      <div class="field"><label>Status</label>${chips("status", editing ? ["Completed", "In progress", "Planned", "Cancelled"] : ["Completed", "In progress", "Planned"], editing?.status || "Completed", true)}</div>
      <div class="field" style="grid-column:1/-1"><label>Surfaces (optional)</label>${chips("surfaces", [["M", "Mesial"], ["D", "Distal"], ["F", "Buccal / Labial"], ["L", "Lingual / Palatal"], ["Top", "Occlusal / Incisal"]], cur)}</div>
      ${field("Doctor", `<input class="input" name="doctor" list="dl-docs" value="${esc(editing?.doctor || docs.find((d) => d === S.user?.name) || docs[0] || "")}"><datalist id="dl-docs">${docs.map((d) => `<option>${esc(d)}</option>`).join("")}</datalist>`)}
      ${field("Date", input("date", editing ? ymd(editing.at) : todayIn(z()), 'type="date"'))}
      ${!editing && can("billing") ? field(`Total fee (${c()}, optional)`, input("fee", "", 'inputmode="decimal" placeholder="Filled from Services"'), "Creates the fee so payments can be added per visit") : fee ? field("Fee", `<div class="small" style="padding-top:10px">${money(fee.total, c())} · use Add payment to change</div>`) : ""}
      ${field("Notes / outcome", textarea("notes", editing?.notes || ""), "", true)}</div>
      ${editing ? `<p class="small muted">The previous values are kept in the treatment's change log and the patient history.</p>` : ""}`,
    saveLabel: "Save",
    onOpen: (f) => { if (f.fee) f.procedure.addEventListener("change", () => { const fee0 = D.serviceFee(S.config, f.procedure.value); if (fee0 != null && !f.fee.value) f.fee.value = minorToText(fee0, c()); }); },
    onSave: async (v) => {
      const at = zonedMs(v.date, "12:00", z());
      const copy = clone(p);
      const data = { teeth, procedure: v.procedure || "", status: v.status || "Completed", surfaces: v.surfaces, doctor: v.doctor || "", at, notes: v.notes || "" };
      const text = editing ? D.editTreatment(copy, editing.id, data, N()) : D.recordTreatment(copy, data, N());
      let feeText = "";
      if (!editing && v.fee && v.fee.trim()) {
        const feeMinor = parseMinor(v.fee, c());
        const first = copy.treatments[copy.treatments.length - 1];
        feeText = " · " + D.saveTreatmentPayment(copy, first, { fee: feeMinor, amount: null, method: "Cash", note: "", at: Date.now() }, S.config, N());
      }
      P.tooth = teeth.length === 1 ? teeth[0] : P.tooth; P.multi = []; P.multiMode = false;
      return save(copy, text + feeText);
    },
  });
}
export function payDialog(p, t) {
  const fee = D.treatmentFee(p, t);
  const last = fee?.invoice.payments?.slice(-1)[0];
  const svc = D.serviceFee(S.config, t.procedure);
  modal({
    title: `${fee ? "Add payment" : "Set fee / add payment"} · ${esc(t.procedure)} ${esc(L(t.tooth))}`,
    body: `<div class="grid-form">
      ${field(`Total fee (${c()})`, input("fee", fee ? minorToText(fee.total, c()) : svc != null ? minorToText(svc, c()) : "", 'inputmode="decimal"'), fee ? `Paid so far ${money(fee.paid, c())}` : "")}
      ${field(`Paid at this visit (${c()})`, input("amount", last ? minorToText(Math.min(last.amount, fee.due || last.amount), c()) : "", 'inputmode="decimal"'), "Leave empty to only save the fee")}
      <div class="field" style="grid-column:1/-1"><label>Method</label>${chips("method", D.METHODS, last?.method || "Cash", true)}</div>
      ${field("Payment date", input("date", todayIn(z()), 'type="date"'))}${field("Note (optional)", input("note", "", `placeholder="e.g. visit ${(fee?.invoice.payments?.length || 0) + 1} of 5"`))}</div>
      <p class="small muted">The fee and payments also appear as an invoice in Billing, so receipts, refunds and Reports include them.</p>`,
    saveLabel: "Save",
    onSave: async (v) => {
      if (!v.fee.trim()) return "Enter the total fee for this treatment";
      const feeMinor = parseMinor(v.fee, c());
      const amount = v.amount.trim() ? parseMinor(v.amount, c()) : null;
      const at = Math.min(Date.now(), zonedMs(v.date, zoned(Date.now(), z()).time, z()));
      const copy = clone(p);
      const tt = copy.treatments.find((x) => x.id === t.id);
      const text = D.saveTreatmentPayment(copy, tt, { fee: feeMinor, amount, method: v.method || "Cash", note: v.note || "", at }, S.config, N());
      return save(copy, text);
    },
  });
}
function moveDialog(p, id, to) {
  const t = p.treatments.find((x) => x.id === id);
  const docs = D.clinicians(S.config);
  const completed = to === "Completed";
  modal({
    title: `${to} · ${esc(t.procedure)}`,
    body: completed ? `<div class="grid-form">${field("Doctor", `<input class="input" name="doctor" list="dl-d2" value="${esc(t.doctor || docs[0] || "")}"><datalist id="dl-d2">${docs.map((d) => `<option>${esc(d)}</option>`).join("")}</datalist>`)}
      ${field("Restoration outcome", select("restoration", ["none", "composite", "amalgam", "glass_ionomer", "sealant", "crown", "inlay", "onlay"].map((x) => [x, D.pretty(x)])))}${field("Outcome", textarea("outcome", ""), "", true)}</div>`
      : field("Reason / decision", input("reason", "")),
    onSave: async (v) => {
      if (completed && (!v.doctor.trim() || !v.outcome.trim())) return "Record the clinician and the performed procedure outcome";
      if (!completed && !v.reason.trim()) return "Record the decision reason";
      const copy = clone(p);
      return save(copy, D.moveTreatment(copy, id, to, v));
    },
  });
}
function encounterDialog(p, id) {
  const e = id ? p.encounters.find((x) => x.id === id) : null;
  const docs = D.clinicians(S.config);
  modal({
    title: e ? "Edit encounter" : "New encounter", wide: true,
    body: `<div class="grid-form">${field("Clinician", `<input class="input" name="doctor" list="dl-d3" value="${esc(e?.doctor || docs[0] || "")}"><datalist id="dl-d3">${docs.map((d) => `<option>${esc(d)}</option>`).join("")}</datalist>`)}
      ${field("Complaint", input("complaint", e?.complaint || ""))}${field("Examination", textarea("examination", e?.examination || ""), "", true)}${field("Diagnosis", textarea("diagnosis", e?.diagnosis || ""))}${field("Plan", textarea("plan", e?.plan || ""))}</div>`,
    onSave: async (v) => {
      if (!v.doctor.trim() || !v.complaint.trim()) return "Clinician and complaint are required";
      const copy = clone(p);
      if (e) { Object.assign(copy.encounters.find((x) => x.id === id), v); return save(copy, "Encounter draft updated"); }
      arr(copy, "encounters").push({ ...v, id: uid(), at: Date.now(), status: "Draft", amendments: [] });
      return save(copy, "Clinical encounter started");
    },
  });
}
function amendDialog(title, fn) {
  modal({ title, body: `<div class="grid-form">${field("Reason", input("reason", ""), "", true)}${field("Correction", textarea("text", ""), "", true)}</div>`,
    onSave: (v) => (!v.reason.trim() || !v.text.trim() ? "Reason and correction are required" : fn(v)) });
}
function apptDialog(p, id) {
  const a = p.appointments.find((x) => x.id === id);
  modal({
    title: `Appointment · ${esc(a.date)}`, noFooter: true,
    body: `<dl class="kv"><dt>Doctor</dt><dd>${esc(a.doctor)}</dd><dt>Reason</dt><dd>${esc(a.reason)}</dd><dt>Status</dt><dd><span class="badge ${stCls(a.status)}">${esc(a.status)}</span></dd></dl>
      <div class="chips" style="margin-top:14px">${["Confirmed", "Checked in", "In progress", "Completed", "Cancelled", "No show"].map((s) => `<button type="button" class="chip ${a.status === s ? "on" : ""}" data-st="${s}">${s}</button>`).join("")}</div>
      <div class="row" style="margin-top:14px"><button type="button" class="btn" id="resched">Reschedule / edit</button></div>`,
    onOpen: (f) => {
      f.addEventListener("click", async (e) => { const b = e.target.closest("[data-st]"); if (b) { await setAppointmentStatus(p.id, id, b.dataset.st); apptDialog(patient(p.id), id); } });
      $("#resched", f).onclick = () => bookDialog({ pid: p.id, appointment: a });
    },
  });
}
export function registerDialog(p = null) {
  const alerts = ["Penicillin allergy", "Diabetic", "On blood thinners", "Hypertension", "Pregnant", "Asthma", "Latex allergy"];
  modal({
    title: p ? "Edit profile" : "Register patient", wide: true,
    body: `${p ? "" : `<div class="notice teal" style="margin-bottom:14px">${I("search")}<span>Search Patients first so you don't create a duplicate. Similar names appear below as you type.</span></div>`}<div class="grid-form">
      ${field("Full name", input("name", p?.name || "", 'autocomplete="off"'), '<span id="dupes"></span>')}${field("Phone", input("phone", p?.phone || "", 'placeholder="01XXX-XXXXXX"'))}
      ${field("Date of birth", input("dob", p?.dob || "", 'type="date"'))}${field("Gender", select("gender", ["", "Female", "Male", "Other"].map((x) => [x, x || "Not recorded"]), p?.gender || ""))}
      ${field("Address", input("address", p?.address || ""), "", true)}
      ${field("Medical alerts / allergies", textarea("allergies", p?.allergies || "", 'style="min-height:60px"'), "Separate with commas. Quick add: " + alerts.map((a) => `<button type="button" class="linkish small" data-add="${a}">${a}</button>`).join(" · "), true)}
      ${p ? "" : `<div class="field" style="grid-column:1/-1">${check("consent", "Patient consents to appointment notifications", true)}</div>`}</div>`,
    saveLabel: p ? "Save" : "Register patient",
    onOpen: (f) => {
      f.addEventListener("click", (e) => { const b = e.target.closest("[data-add]"); if (b) { f.allergies.value = [f.allergies.value.trim(), b.dataset.add].filter(Boolean).join(", "); } });
      if (!p) f.name.addEventListener("input", () => { const t = f.name.value.trim().toLowerCase(); const m = t.length > 2 ? S.patients.filter((x) => x.name.toLowerCase().includes(t)).slice(0, 3) : []; $("#dupes", f).innerHTML = m.length ? "Similar: " + m.map((x) => `${esc(x.name)} (${esc(x.phone || "")})`).join(", ") : ""; });
    },
    onSave: async (v) => {
      if (p) {
        const copy = clone(p);
        Object.assign(copy, { name: v.name.trim(), phone: v.phone.trim(), dob: v.dob, gender: v.gender, address: v.address.trim(), allergies: v.allergies.trim() });
        return save(copy, "Patient profile amended");
      }
      const id = await (await import("./store.js")).createPatient(v);
      if (!id) return false;
      ctx.go("patients/" + id);
    },
  });
}
function loginDialog(p) {
  const sid = p.portal?.loginId || p.name.split(/\s+/)[0].toUpperCase().replace(/[^A-Z0-9]/g, "").slice(0, 8) + String(Math.floor(Math.random() * 90 + 10));
  const pw = D.randomText(D.PASSWORD_ALPHABET, 6);
  modal({
    title: p.portal ? "Change patient app ID / password" : "Create patient login",
    body: `<div class="grid-form">${field("Patient ID", input("id", sid), "4–12 letters or numbers, e.g. RAHIM01")}${field("Password", input("pw", pw), "6+ characters, shown only once")}</div>
      ${p.portal ? `<p class="small muted">The old password stops working at once.</p>` : ""}`,
    saveLabel: p.portal ? "Change login" : "Create login",
    onSave: async (v) => {
      const id = await createPatientLogin(p.id, v.id, v.pw);
      setTimeout(() => modal({ title: "Share with the patient", noFooter: false, cancelLabel: "Done",
        body: `<p>Give these to ${esc(p.name)}. The password is shown only now.</p><div class="kv"><dt>Patient ID</dt><dd class="mono" style="font-size:18px">${esc(id)}</dd><dt>Password</dt><dd class="mono" style="font-size:18px">${esc(v.pw)}</dd><dt>Sign in at</dt><dd>${esc(location.origin + location.pathname)}#/portal</dd></div>`,
        onSave: () => shareText("Dental Care patient app", `${S.config.name}\nPatient app sign-in\n${location.origin + location.pathname}#/portal\nPatient ID: ${id}\nPassword: ${v.pw}`), saveLabel: "Copy / share" }), 50);
    },
  });
}
async function pdf(make) {
  say("Preparing PDF…");
  setFormatLang(isLatin() ? document.documentElement.lang : "en");
  try { await make(); say("PDF downloaded"); } catch (err) { say("PDF export failed: " + (err.message || err), "error"); } finally { setFormatLang(null); }
}
export { pdf };
function printSummary(p) {
  const el = $("#print");
  const ct = D.chartCounts(p);
  el.innerHTML = `<h1>${esc(S.config.name)} · Care summary</h1><div>${esc(p.name)} · ${esc(p.phone || "")} · DOB ${esc(p.dob || "-")} · printed ${new Date().toLocaleString()}</div>
    <p><b>Medical alerts:</b> ${esc(p.allergies || "None recorded")}</p><p><b>Clinical story:</b> ${esc(p.story || "-")}</p>
    <p><b>Chart:</b> ${ct.treated} treated · ${ct.planned} planned · ${ct.findings} with findings · ${ct.missing} missing (FDI numbering)</p>
    <h3>Treatments</h3><table><tr><th>Date</th><th>Tooth</th><th>Procedure</th><th>Status</th><th>Doctor</th><th>Fee / paid / due</th></tr>${(p.treatments || []).map((t) => { const f = D.treatmentFee(p, t); return `<tr><td>${fmtMs(t.at, z())}</td><td>${esc(L(t.tooth))} (${t.tooth})</td><td>${esc(t.procedure)} ${esc(t.surface || "")}</td><td>${esc(t.status)}</td><td>${esc(t.doctor || "")}</td><td>${f ? `${money(f.total, c())} / ${money(f.paid, c())} / ${money(f.due, c())}` : "-"}</td></tr>`; }).join("")}</table>
    <h3>Encounters</h3><table><tr><th>Date</th><th>Clinician</th><th>Complaint</th><th>Diagnosis</th><th>Plan</th></tr>${(p.encounters || []).map((e) => `<tr><td>${fmtMs(e.at, z())}</td><td>${esc(e.doctor)}</td><td>${esc(e.complaint)}</td><td>${esc(e.diagnosis || "")}</td><td>${esc(e.plan || "")}</td></tr>`).join("")}</table>
    <p><b>Balance due:</b> ${money(D.patientDue(p), c())}</p><p style="font-size:11px;color:#555">This summary supports, and does not replace, professional clinical judgement.</p>`;
  setTimeout(() => print(), 50);
}
