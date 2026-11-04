// Dental Care Web: shell, router and the clinic pages. Patient workspace lives in patient.js,
// the patient app in portal.js.
import { $, $$, esc, clone, uid, initials, money, parseMinor, minorToText, zonedMs, zoned, todayIn, addDaysIso, dowOf, hm, minutesOf, fmtIso, fmtMonth, fmtMs, download } from "./util.js";
import * as D from "./domain.js";
import * as St from "./store.js";
import { S, can, patient, say, sync, saveConfig } from "./store.js";
import { I, LOGO } from "./icons.js";
import { Viewer } from "./viewer3d.js";
import { GUIDES } from "./help.js";
import { modal, closeModal, field, input, select, check, chips, values, bookDialog, openSlotsDialog, availabilityDialog, closeDayForDoctor, invoiceDialog, setAppointmentStatus, confirmBox } from "./dialogs.js";
import { P, setCtx, renderPatients, afterRenderPatients, patientAction, registerDialog, invoiceCard } from "./patient.js";
import { renderPortal, portalAction, afterPortal } from "./portal.js";
import * as Reports from "./reports.js";
import { pdf } from "./patient.js";

const A = { route: "home", arg: "", day: "", month: "", schedView: "Calendar", selAppt: null, invFilter: "All", selInv: "", period: "Month", from: "", to: "", selStaff: "", helpQ: "", helpTopic: "All topics", helpId: "start", signTab: "team", theme: null };
const z = () => D.tz(S.config), c = () => D.cur(S.config);
const stCls = (s) => "st-" + String(s).replace(/ /g, "-");
const safeStart = (a) => { try { return D.appointmentMillis(a, S.config); } catch { return 0; } };
try { A.theme = localStorage.getItem("dc-theme"); if (A.theme) document.documentElement.dataset.theme = A.theme; } catch { /* ignore */ }
const isDark = () => (A.theme ? A.theme === "dark" : matchMedia("(prefers-color-scheme: dark)").matches);

export function go(r) { if (location.hash !== "#/" + r) location.hash = "#/" + r; else render(); }
setCtx({ render, go });

// ============================================================================ shell
const NAV = [
  ["Clinic", [["home", "Home", "home"], ["patients", "Patients", "users"], ["schedule", "Appointments", "cal"], ["billing", "Billing", "receipt", "billing"], ["reports", "Reports", "chart", "reports"]]],
  ["Administration", [["team", "Team & roles", "team"], ["settings", "Clinic settings", "cog"]]],
  ["Help", [["help", "How to use", "help"], ["portal", "Patient app", "user"]]],
];
const TITLES = { home: "Home", patients: "Patients", schedule: "Appointments", billing: "Billing", reports: "Reports", team: "Team & roles", settings: "Clinic settings", help: "How to use" };
function allAppointments() {
  const out = [];
  for (const p of S.patients) { if (p.archived) continue; for (const a of p.appointments || []) out.push({ ...a, pid: p.id, pname: p.name, startAt: safeStart(a) }); }
  return out;
}
function next7() { const now = Date.now(); return allAppointments().filter((a) => !D.TERMINAL_APPOINTMENTS.includes(a.status) && a.startAt >= now && a.startAt < now + 7 * 86400000).length; }
function shell(content) {
  const sub = { home: fmtIso(todayIn(z()), true), patients: `${St.activePatientCount()} active records`, schedule: "Calendar, slots and bookings", billing: "Invoices, payments and receipts", reports: "Practice performance", team: "Staff, roles and access", settings: "Clinic, reminders, numbering, subscription", help: "Step-by-step guides" }[A.route] || "";
  const role = St.currentRole();
  const limit = St.patientLimit(), count = St.activePatientCount();
  const n7 = next7();
  return `${S.backend?.demo ? `<div class="demo-bar">${I("spark")}<span>Demo clinic<span class="long">: data stays in this browser. Deploy to Firebase Hosting (or fill js/config.js) to use your real clinic.</span></span><button data-act="demoreset">Reset demo</button></div>` : ""}
  <div class="app ${S.backend?.demo ? "with-demo" : ""} ${A.route === "patients" && innerWidth < 2400 ? "compact-nav" : ""}" id="app">
  <aside class="side">
    <div class="brand">${LOGO}<div><b>Dental Care</b><small>${esc(S.config.name)}</small></div></div>
    <nav class="nav" aria-label="Main">
      ${NAV.map(([h, items]) => `<h6>${h}</h6>` + items.filter(([, , , perm]) => !perm || can(perm)).map(([r, l, ic]) => `<a href="#/${r === "patients" && P.pid ? "patients/" + P.pid : r}" class="${A.route === r ? "on" : ""}" title="${l}">${I(ic)}<span class="lbl">${l}</span>${r === "schedule" && n7 ? `<span class="badge count">${n7}</span>` : ""}</a>`).join("")).join("")}
      <a href="#" data-act="signout" title="Sign out">${I("out")}<span class="lbl">Sign out</span></a>
    </nav>
    <div class="side-foot"><div class="plan">
      <div class="between"><b class="small">${S.complimentary || St.isSuperAdmin() ? "Complimentary" : "Dental Care Pro"}</b><span class="badge ${St.subscriptionActive() ? "teal" : "amber"}">${St.subscriptionActive() ? "Active" : "Not active"}</span></div>
      ${limit ? `<div class="bar"><i style="width:${Math.min(100, (count / limit) * 100)}%"></i></div><div class="small muted num">${count.toLocaleString()} of ${limit.toLocaleString()} patients</div>` : `<div class="small muted num" style="margin-top:6px">${count.toLocaleString()} active patients</div>`}
    </div></div>
  </aside>
  <header class="top">
    <button class="icon-btn menu-btn" data-act="drawer" aria-label="Menu">${I("menu")}</button>
    <div class="crumb">${TITLES[A.route] || ""}<small>${esc(sub)}</small></div>
    <div class="search"><button data-act="cmdk" aria-label="Search">${I("search")}<span class="t">Search patients, phone, ID or jump to a page</span><kbd style="margin-left:auto">Ctrl K</kbd></button></div>
    <div class="top-actions">
      ${can("managePatients") ? `<button class="btn primary hide-sm" data-act="register">${I("plus")}<span>Register patient</span></button>` : ""}
      <button class="icon-btn" data-act="sync" title="Sync: fetch others' changes and patient app bookings" aria-label="Sync">${I("sync", S.busy ? "spin" : "")}</button>
      <button class="icon-btn hide-sm" data-act="theme" title="Theme" aria-label="Toggle theme">${I(isDark() ? "sun" : "moon")}</button>
      <div class="me" title="${esc(S.user?.email || "")}"><div class="avatar">${initials(S.user?.name || "")}</div><div><b>${esc(S.user?.name || "")}</b><small>${esc(D.roleTitle(role))}</small></div></div>
    </div>
  </header>
  <main class="main" id="main">${!St.subscriptionActive() && !["settings", "help"].includes(A.route) ? paywall() : content}</main>
  <nav class="bnav" aria-label="Bottom">
    ${[["home", "Home", "home"], ["patients", "Patients", "users"], ["schedule", "Schedule", "cal"], ["reports", "Reports", "chart"], ["settings", "Settings", "cog"]].map(([r, l, ic]) => `<a href="#/${r === "patients" && P.pid ? "patients/" + P.pid : r}" class="${A.route === r ? "on" : ""}">${I(ic)}${l}</a>`).join("")}
  </nav></div><div class="print-only" id="print"></div>`;
}
function paywall() {
  return `<div class="page"><div class="card" style="max-width:640px;margin:8vh auto"><div class="card-b" style="padding:32px">
    <h1 class="h1">Subscription needed</h1><p class="muted">One Dental Care Pro subscription covers the whole clinic on Android and the web. It is bought by the owner or an admin in the Android app (Settings › Subscription). The web unlocks automatically after the next sync.</p>
    <p class="small muted">Up to 1,000 patients: US$${D.PLAN.offerUsd}/month during the launch offer (normally US$${D.PLAN.regularUsd}). Each further 1,000 patients adds the same again. Patients never pay.</p>
    <div class="row" style="margin-top:16px"><button class="btn primary" data-act="sync">${I("sync")}I've subscribed · check again</button><a class="btn" href="#/help">How to use</a></div></div></div></div>`;
}

// ============================================================================ home
function pageHome() {
  const today = todayIn(z()), now = Date.now(), nowMin = zoned(now, z()).minutes;
  const appts = allAppointments();
  const todays = appts.filter((a) => zoned(a.startAt, z()).date === today);
  const active = todays.filter((a) => a.status !== "Cancelled");
  const payments = S.patients.flatMap((p) => (p.invoices || []).flatMap((v) => (v.payments || []).map((x) => ({ ...x, refund: 0 }))));
  const refunds = S.patients.flatMap((p) => (p.invoices || []).flatMap((v) => v.refunds || []));
  const dayKey = (ms) => zoned(ms, z()).date;
  const receivedToday = payments.filter((x) => dayKey(x.at) === today).reduce((a, x) => a + x.amount, 0) - refunds.filter((x) => dayKey(x.at) === today).reduce((a, x) => a + x.amount, 0);
  const due = S.patients.reduce((a, p) => a + D.patientDue(p), 0);
  const openPlans = S.patients.reduce((a, p) => a + (p.treatments || []).filter((t) => D.OPEN_TREATMENT.includes(t.status)).length, 0);
  const requested = appts.filter((a) => a.status === "Requested" && a.startAt > now - 86400000);
  const drafts = S.patients.flatMap((p) => (p.encounters || []).filter((e) => e.status === "Draft").map((e) => ({ p, e })));
  const conflicts = Object.keys(S.conflicts).map(patient).filter(Boolean);
  const limit = St.patientLimit(), count = St.activePatientCount();
  const slots = D.slotsFor(S.config, S.patients, today, [], 0);
  const doctors = [...new Set([...slots.map((s) => s.doctor), ...todays.map((a) => a.doctor)])];
  const first = Math.min(9 * 60, ...slots.map((s) => s.minutes), ...todays.map((a) => zoned(a.startAt, z()).minutes)), lastM = Math.max(21 * 60, ...slots.map((s) => s.minutes + s.durationMinutes), ...todays.map((a) => zoned(a.startAt, z()).minutes + (a.durationMinutes || 30)));
  const S0 = Math.floor(first / 60) * 60, S1 = Math.ceil(lastM / 60) * 60, W = S1 - S0;
  const pct = (m) => (((m - S0) / W) * 100).toFixed(2) + "%";
  const colorOf = (d) => ["#0F766E", "#2563EB", "#7C3AED", "#B45309", "#0891B2", "#DB2777"][Math.max(0, doctors.indexOf(d)) % 6];
  const lanes = doctors.map((d) => {
    const ap = todays.filter((a) => a.doctor === d);
    const open = slots.filter((s) => s.doctor === d && !s.patientId && s.startAt > now);
    return `<div class="lane"><div class="who"><div class="avatar" style="background:${colorOf(d)}">${initials(d)}</div><div>${esc(d.replace(/^Dr\.?\s*/, ""))}<small>${ap.filter((a) => a.status !== "Cancelled").length} visits · ${open.length} open</small></div></div>
      <div class="track">${open.map((s) => `<div class="blk open" style="left:${pct(s.minutes)};width:calc(${((s.durationMinutes / W) * 100).toFixed(2)}% - 3px)" data-act="bookslot" data-doc="${esc(d)}" data-date="${today}" data-time="${s.time}" data-dur="${s.durationMinutes}" title="Open slot ${s.time}">+</div>`).join("")}
      ${ap.map((a) => { const m = zoned(a.startAt, z()).minutes; return `<div class="blk ${stCls(a.status)}" style="left:${pct(m)};width:calc(${(((a.durationMinutes || 30) / W) * 100).toFixed(2)}% - 3px)" title="${hm(m)} ${esc(a.pname)} · ${esc(a.reason)} · ${a.status}" data-act="openp" data-id="${a.pid}">${esc(a.pname.split(" ")[0])}<small>${hm(m)}</small></div>`; }).join("")}</div></div>`;
  }).join("") || `<div class="empty">No sessions or appointments today. Open slots in Appointments.</div>`;
  const hours = []; for (let h = S0 / 60; h <= S1 / 60; h++) hours.push(`<span style="left:${pct(h * 60)}">${String(h).padStart(2, "0")}:00</span>`);
  const days = [...Array(14)].map((_, i) => { const d = addDaysIso(today, i - 13); const v = payments.filter((x) => dayKey(x.at) === d).reduce((a, x) => a + x.amount, 0); return { d, v }; });
  const mx = Math.max(1, ...days.map((x) => x.v));
  const nextUp = todays.filter((a) => a.startAt >= now - 30 * 60000 && !D.TERMINAL_APPOINTMENTS.includes(a.status)).sort((a, b) => a.startAt - b.startAt).slice(0, 7);
  const k = (lbl, val, ic, col, soft, delta) => `<div class="card kpi"><div class="lbl"><span class="ico" style="background:${soft};color:${col}">${I(ic)}</span>${lbl}</div><div class="val num">${val}</div><div class="delta" style="color:${col}">${delta}</div></div>`;
  const attention = [
    ...requested.map((a) => `<div class="list-item"><span style="width:34px;height:34px;border-radius:10px;display:grid;place-items:center;background:var(--amber-soft);color:var(--amber)">${I("phone")}</span><div class="grow"><b>Patient app booking needs a decision</b><small>${esc(a.pname)} asked for ${fmtMs(a.startAt, z(), true)} with ${esc(a.doctor)}; that time was taken</small></div><button class="btn sm" data-act="openp" data-id="${a.pid}">Review</button></div>`),
    ...conflicts.map((p) => `<div class="list-item"><span style="width:34px;height:34px;border-radius:10px;display:grid;place-items:center;background:var(--red-soft);color:var(--red)">${I("alert")}</span><div class="grow"><b>Sync conflict on ${esc(p.name)}</b><small>Changed on another device. Your draft is retained.</small></div><button class="btn sm" data-act="openp" data-id="${p.id}">Resolve</button></div>`),
    ...drafts.slice(0, 4).map(({ p, e }) => `<div class="list-item"><span style="width:34px;height:34px;border-radius:10px;display:grid;place-items:center;background:var(--violet-soft);color:var(--violet)">${I("file")}</span><div class="grow"><b>Encounter draft not finalized</b><small>${esc(p.name)} · ${esc(e.complaint)} · ${esc(e.doctor)}</small></div><button class="btn sm" data-act="openenc" data-id="${p.id}">Open</button></div>`),
    limit && count / limit > 0.8 ? `<div class="list-item"><span style="width:34px;height:34px;border-radius:10px;display:grid;place-items:center;background:var(--brand-softer);color:var(--brand)">${I("users")}</span><div class="grow"><b>Plan is ${Math.round((count / limit) * 100)}% full</b><small>${count.toLocaleString()} of ${limit.toLocaleString()} patients. Upgrade in the Android app or archive inactive patients.</small></div><a class="btn sm" href="#/settings">View</a></div>` : "",
  ].filter(Boolean);
  return `<div class="page">
  <div class="page-head"><div><p class="muted small" style="margin:0 0 2px;font-weight:600">${greeting()}, ${esc((S.user?.name || "").split(" ").slice(0, 2).join(" "))}</p><h1 class="h1">Here is ${esc(S.config.name)} today</h1></div>
    <div class="r"><a class="btn" href="#/schedule">${I("cal")}Open schedule</a>${can("manageAppointments") ? `<button class="btn primary" data-act="book">${I("plus")}Book appointment</button>` : ""}</div></div>
  <div class="kpis">
    ${k("Today's appointments", active.length, "cal", "var(--brand)", "var(--brand-softer)", `${active.filter((a) => a.status === "Completed").length} completed · ${todays.filter((a) => a.status === "Requested").length} requested`)}
    ${can("billing") || can("reports") ? k("Received today", money(receivedToday, c()), "wallet", "var(--green)", "var(--green-soft)", "Payments less refunds") : ""}
    ${can("billing") || can("reports") ? k("Outstanding balance", money(due, c()), "receipt", "var(--amber)", "var(--amber-soft)", `${S.patients.filter((p) => D.patientDue(p) > 0).length} patients with dues`) : ""}
    ${k("Open treatment plans", openPlans, "clip", "var(--violet)", "var(--violet-soft)", "Planned, accepted, in progress")}
    ${k("Active patients", count.toLocaleString(), "users", "var(--accent)", "var(--accent-soft)", limit ? `Plan covers ${limit.toLocaleString()}` : "No plan limit")}
  </div>
  <div class="dash">
    <div class="card wide"><div class="card-h"><h3>Today at a glance</h3><span class="sub">Each doctor's day. Dashed blocks are open slots: click to book.</span></div>
      <div class="card-b lanes"><div class="lanes-inner"><div class="lane-head"><div></div><div class="hours">${hours.join("")}</div></div>
        <div style="position:relative">${lanes}${nowMin >= S0 && nowMin <= S1 ? `<div style="position:absolute;left:150px;right:0;top:0;bottom:0;pointer-events:none"><div class="now-line" style="left:${pct(nowMin)}"></div></div>` : ""}</div></div></div></div>
    <div class="card"><div class="card-h"><h3>Needs attention</h3>${attention.length ? `<span class="badge red">${attention.length}</span>` : ""}</div><div class="card-b">${attention.join("") || `<div class="empty">Nothing needs attention. 🎉</div>`}</div></div>
    <div class="card"><div class="card-h"><h3>Up next</h3><div class="r"><a class="btn ghost sm" href="#/schedule">All ${I("chev")}</a></div></div>
      <div class="card-b">${nextUp.map((a) => `<div class="list-item" style="cursor:pointer" data-act="openp" data-id="${a.pid}"><div class="mono" style="width:48px;font-weight:600">${zoned(a.startAt, z()).time}</div><div class="avatar" style="background:${colorOf(a.doctor)}">${initials(a.pname)}</div><div class="grow"><b>${esc(a.pname)}</b><small>${esc(a.reason)} · ${esc(a.doctor)}</small></div><span class="badge ${stCls(a.status)}">${a.status}</span></div>`).join("") || `<div class="empty">No more visits today</div>`}</div></div>
    ${can("billing") || can("reports") ? `<div class="card"><div class="card-h"><h3>Payments received</h3><span class="sub">last 14 days</span><div class="r"><span class="badge green">${money(days.reduce((a, x) => a + x.v, 0), c())}</span></div></div>
      <div class="card-b"><div class="bars">${days.map((x, i) => `<div class="b" title="${fmtIso(x.d)}: ${money(x.v, c())}"><i class="${i === 13 ? "today" : ""}" style="height:${((x.v / mx) * 100).toFixed(1)}%"></i><span>${+x.d.slice(8)}</span></div>`).join("")}</div><p class="small muted" style="margin:8px 0 0">Based on payments received, not invoices issued.</p></div></div>` : ""}
    <div class="card"><div class="card-h"><h3>Quick actions</h3></div><div class="card-b"><div class="qa">
      ${can("managePatients") ? `<button data-act="register"><span class="ico" style="background:#0F766E">${I("plus")}</span>Register patient</button>` : ""}
      ${can("manageAppointments") ? `<button data-act="book"><span class="ico" style="background:#2563EB">${I("cal")}</span>Book appointment</button>` : ""}
      ${can("manageSlots") ? `<button data-act="openslots" data-date="${today}"><span class="ico" style="background:#0891B2">${I("clock")}</span>Open slots</button>` : ""}
      <button data-act="cmdk"><span class="ico" style="background:#7C3AED">${I("search")}</span>Find patient</button>
      ${can("billing") ? `<button data-act="newinvoice"><span class="ico" style="background:#B45309">${I("receipt")}</span>Create invoice</button>` : ""}
      <button data-act="sync"><span class="ico" style="background:#334155">${I("sync")}</span>Sync now</button></div></div></div>
  </div></div>`;
}
const greeting = () => { const h = zoned(Date.now(), z()).minutes / 60; return h < 12 ? "Good morning" : h < 17 ? "Good afternoon" : "Good evening"; };

// ============================================================================ schedule
function miniCal() {
  const today = todayIn(z());
  const [y, m] = A.month.split("-").map(Number);
  const first = `${y}-${String(m).padStart(2, "0")}-01`;
  const start = addDaysIso(first, -(dowOf(first) - 1));
  const cells = [...Array(42)].map((_, i) => {
    const d = addDaysIso(start, i);
    const sl = D.slotsFor(S.config, S.patients, d, [], 0);
    const dot = sl.length ? (sl.some((s) => !s.patientId) ? "<i></i>" : '<i class="full"></i>') : "";
    return `<button class="${+d.slice(5, 7) !== m ? "out" : ""} ${d === today ? "today" : ""} ${d === A.day ? "on" : ""}" data-act="day" data-d="${d}">${+d.slice(8)}${dot}</button>`;
  }).join("");
  return `<div class="card mini-cal"><div class="between" style="margin-bottom:8px"><b>${fmtMonth(y, m)}</b><div class="row" style="gap:4px"><button class="icon-btn" style="width:30px;height:30px" data-act="month" data-n="-1">${I("chevl")}</button><button class="icon-btn" style="width:30px;height:30px" data-act="month" data-n="1">${I("chev")}</button></div></div>
  <div class="grid">${["Mo", "Tu", "We", "Th", "Fr", "Sa", "Su"].map((d) => `<span class="dow">${d}</span>`).join("")}${cells}</div>
  <div class="legend" style="margin-top:8px"><span><i style="background:var(--green);border-radius:50%;width:8px;height:8px"></i>Free slots</span><span><i style="background:var(--line-2);border-radius:50%;width:8px;height:8px"></i>Fully booked</span></div></div>`;
}
function pageSchedule() {
  const day = A.day, now = Date.now();
  const appts = allAppointments().filter((a) => zoned(a.startAt, z()).date === day);
  const slots = D.slotsFor(S.config, S.patients, day, [], now);
  const sessions = D.sessionsOn(S.config, day);
  const doctors = [...new Set([...sessions.map((s) => s.doctor), ...appts.map((a) => a.doctor)])];
  const sel = appts.find((a) => a.id === A.selAppt);
  const mins = [...slots.map((s) => s.minutes), ...appts.map((a) => zoned(a.startAt, z()).minutes)];
  const H0 = Math.min(9 * 60, ...mins.map((m) => Math.floor(m / 60) * 60)), H1 = Math.max(21 * 60, ...appts.map((a) => zoned(a.startAt, z()).minutes + (a.durationMinutes || 30)), ...slots.map((s) => s.minutes + s.durationMinutes));
  const end = Math.ceil(H1 / 60) * 60, hh = 56, px = (m) => ((m - H0) / 30) * hh;
  const nowZ = zoned(now, z());
  const cols = doctors.map((d) => {
    const mine = appts.filter((a) => a.doctor === d);
    const ses = sessions.filter((s) => s.doctor === d).sort((a, b) => minutesOf(a.start) - minutesOf(b.start));
    let offs = "", cur = H0;
    for (const s of ses) { const a = minutesOf(s.start); if (a > cur) offs += `<div class="off" style="top:${px(cur)}px;height:${px(a) - px(cur)}px"></div>`; cur = Math.max(cur, minutesOf(s.end)); }
    offs += `<div class="off" style="top:${px(cur)}px;height:${px(end) - px(cur)}px"></div>`;
    const open = slots.filter((s) => s.doctor === d && !s.patientId);
    return `<div class="col" style="height:${px(end)}px">${offs}
      ${open.map((s) => `<div class="ev slot" style="top:${px(s.minutes) + 3}px;height:${(s.durationMinutes / 30) * hh - 6}px" ${s.available && can("manageAppointments") ? `data-act="bookslot" data-doc="${esc(d)}" data-date="${day}" data-time="${s.time}" data-dur="${s.durationMinutes}"` : ""}>${I("plus")}${s.time} ${s.available ? "free" : "past"}</div>`).join("")}
      ${mine.map((a) => { const m = zoned(a.startAt, z()).minutes; return `<div class="ev ${stCls(a.status)} ${A.selAppt === a.id ? "sel" : ""}" style="top:${px(m) + 3}px;height:${Math.max(26, ((a.durationMinutes || 30) / 30) * hh - 6)}px;${a.status === "Requested" ? "left:50%;" : ""}" data-act="selappt" data-id="${a.id}">${hm(m)} ${esc(a.pname)}<small>${esc(a.reason)}${a.bookedBy === "patient" ? " · app" : ""}</small></div>`; }).join("")}
      ${day === nowZ.date && nowZ.minutes >= H0 && nowZ.minutes <= end ? `<div class="now-line" style="left:0;right:0;width:auto;height:2px;top:${px(nowZ.minutes)}px;bottom:auto"></div>` : ""}</div>`;
  }).join("");
  const gutter = [...Array((end - H0) / 60 + 1)].map((_, i) => `<span style="top:${px(H0 + i * 60)}px;${i === 0 ? "transform:none;top:4px" : ""}">${String(H0 / 60 + i).padStart(2, "0")}:00</span>`).join("");
  const count = (st) => appts.filter((a) => a.status === st).length;
  const detail = sel ? (() => {
    const p = patient(sel.pid);
    return `<div class="card"><div class="card-h"><h3>Appointment</h3><div class="r"><button class="icon-btn" style="width:30px;height:30px" data-act="selappt" data-id="">${I("x")}</button></div></div><div class="card-b">
      <div class="row"><div class="avatar lg">${initials(p.name)}</div><div><b style="font-size:16px">${esc(p.name)}</b><div class="small muted num">${esc(p.phone || "")}${p.portal?.loginId ? " · " + esc(p.portal.loginId) : ""}</div></div></div>
      <dl class="kv" style="margin-top:14px"><dt>When</dt><dd>${fmtMs(sel.startAt, z(), true)} · ${sel.durationMinutes || 30} min</dd><dt>Doctor</dt><dd>${esc(sel.doctor)}</dd><dt>Reason</dt><dd>${esc(sel.reason)}</dd>${sel.room ? `<dt>Room</dt><dd>${esc(sel.room)}</dd>` : ""}<dt>Booked via</dt><dd>${sel.bookedBy === "patient" ? "Patient app" : "Clinic"}</dd><dt>Status</dt><dd><span class="badge ${stCls(sel.status)}">${sel.status}</span></dd></dl>
      ${sel.status === "Requested" ? `<div class="notice" style="margin-top:12px">${I("alert")}<span>Booked in the patient app, but the time was taken meanwhile. Reschedule then confirm, or cancel and call the patient.</span></div>` : ""}
      ${can("manageAppointments") ? `<div class="chips" style="margin-top:14px">${["Confirmed", "Checked in", "In progress", "Completed", "Cancelled", "No show"].map((s) => `<button class="chip ${sel.status === s ? "on" : ""}" data-act="status" data-pid="${p.id}" data-id="${sel.id}" data-s="${s}">${s}</button>`).join("")}</div>` : ""}
      <div class="row" style="margin-top:14px;flex-wrap:wrap"><button class="btn primary sm" data-act="openp" data-id="${p.id}">Open patient</button>${can("manageAppointments") ? `<button class="btn sm" data-act="resched" data-pid="${p.id}" data-id="${sel.id}">Reschedule / edit</button>` : ""}</div></div></div>`;
  })() : `<div class="card"><div class="card-h"><h3>${fmtIso(day, true)}</h3></div><div class="card-b">
      <div class="counters" style="grid-template-columns:repeat(2,1fr)"><div class="counter"><b class="num">${appts.filter((a) => a.status !== "Cancelled").length}</b><span>Booked</span></div><div class="counter"><b class="num" style="color:var(--green)">${count("Completed")}</b><span>Completed</span></div><div class="counter"><b class="num" style="color:var(--amber)">${count("Requested")}</b><span>Requested</span></div><div class="counter"><b class="num" style="color:var(--red)">${count("No show")}</b><span>No show</span></div></div>
      <hr class="sep"><div class="rail-title">Doctor hours</div>${doctors.map((d) => { const ss = sessions.filter((s) => s.doctor === d); return `<div class="list-item"><div class="avatar">${initials(d)}</div><div class="grow"><b>${esc(d)}</b><small>${ss.map((s) => `${s.start}–${s.end}`).join(", ") || "No session"}${ss.some((s) => (s.weekdays || []).length) ? " · weekly" : ""}</small></div>${ss.length && can("manageSlots") ? `<button class="btn ghost sm" data-act="closeday" data-ids="${ss.map((s) => s.id).join(",")}">Close day</button>` : ""}</div>`; }).join("") || `<p class="small muted">No sessions on this day.</p>`}
      ${can("manageSlots") ? `<button class="btn block" style="margin-top:10px" data-act="openslots" data-date="${day}">${I("clock")}Open slots on ${fmtIso(day)}</button><button class="btn ghost block" style="margin-top:6px" data-act="availability">All availability</button>` : ""}</div></div>`;
  const range = { Day: [day, day], Week: [addDaysIso(day, -(dowOf(day) - 1)), addDaysIso(day, 7 - dowOf(day))], Month: [day.slice(0, 8) + "01", day.slice(0, 8) + "31"], All: ["0000", "9999"] }[A.listRange || "Day"];
  const listAppts = allAppointments().filter((a) => { const d = zoned(a.startAt, z()).date; return d >= range[0] && d <= range[1]; }).sort((a, b) => a.startAt - b.startAt);
  const listView = `<div class="card"><div class="card-h"><div class="seg">${["Day", "Week", "Month", "All"].map((r) => `<button class="${(A.listRange || "Day") === r ? "on" : ""}" data-act="listrange" data-r="${r}">${r}</button>`).join("")}</div><span class="sub">${listAppts.length} appointments</span></div><div class="table-wrap" style="margin-top:8px"><table class="t"><thead><tr><th>When</th><th>Patient</th><th>Doctor</th><th>Reason</th><th>Source</th><th>Status</th></tr></thead><tbody>${listAppts.slice(0, 500).map((a) => `<tr class="click ${A.selAppt === a.id ? "sel" : ""}" data-act="selappt2" data-id="${a.id}" data-d="${zoned(a.startAt, z()).date}"><td class="mono">${fmtMs(a.startAt, z(), true)}</td><td><b>${esc(a.pname)}</b></td><td>${esc(a.doctor)}</td><td>${esc(a.reason)}</td><td>${a.bookedBy === "patient" ? "Patient app" : "Clinic"}</td><td><span class="badge ${stCls(a.status)}">${a.status}</span></td></tr>`).join("") || `<tr><td colspan="6" class="empty">No appointments match this calendar period.</td></tr>`}</tbody></table></div></div>`;
  return `<div class="page"><div class="page-head"><div><h1 class="h1">${fmtIso(day, true)}</h1><p class="muted small" style="margin:2px 0 0">${esc(z())} · ${S.config.bufferMinutes ?? 5} min buffer</p></div>
   <div class="r"><div class="row" style="gap:4px"><button class="btn" data-act="dayshift" data-n="-1" aria-label="Previous day">${I("chevl")}</button><button class="btn" data-act="day" data-d="${todayIn(z())}">Today</button><button class="btn" data-act="dayshift" data-n="1" aria-label="Next day">${I("chev")}</button></div>
   <div class="seg">${["Calendar", "List"].map((v) => `<button class="${A.schedView === v ? "on" : ""}" data-act="schedview" data-v="${v}">${v}</button>`).join("")}</div>
   <button class="btn" data-act="schedpdf" title="Save the shown day (or list period) as PDF">${I("download")}PDF</button>
   ${can("manageSlots") ? `<label class="row small" style="font-weight:700;padding:0 6px">Online booking by patients <label class="switch"><input type="checkbox" data-act="booking" ${S.config.patientBooking !== false ? "checked" : ""}><i></i></label></label><button class="btn" data-act="openslots" data-date="${day}">${I("clock")}Open slots</button>` : ""}
   ${can("manageAppointments") ? `<button class="btn primary" data-act="book" data-date="${day}">${I("plus")}Book appointment</button>` : ""}</div></div>
  <div class="sched"><div class="side-col" style="display:grid;gap:16px">${miniCal()}${detail}</div>
   ${A.schedView === "List" ? listView : doctors.length ? `<div class="card dayview"><div class="dv" style="grid-template-columns:64px repeat(${doctors.length},minmax(200px,1fr));--hh:${hh}px">
     <div class="colh" style="border-left:0"></div>${doctors.map((d) => { const st = (S.config.staff || []).find((s) => s.name === d); return `<div class="colh"><div class="avatar">${initials(d)}</div><div><b>${esc(d)}</b><small>${esc(st?.registration || D.roleTitle(st?.role || "dentist"))}</small></div></div>`; }).join("")}
     <div class="gutter" style="height:${px(end)}px">${gutter}</div>${cols}</div></div>` : `<div class="card"><div class="empty">No doctor hours on this day.${can("manageSlots") ? `<br><button class="btn primary" style="margin-top:10px" data-act="openslots" data-date="${day}">${I("clock")}Open slots</button>` : ""}</div></div>`}
  </div></div>`;
}

// ============================================================================ billing
function pageBilling() {
  const all = S.patients.flatMap((p) => (p.invoices || []).map((v) => ({ p, v }))).sort((a, b) => (b.v.at || 0) - (a.v.at || 0));
  const list = all.filter((x) => A.invFilter === "All" || D.invoiceState(x.v) === A.invFilter);
  const sel = all.find((x) => x.v.id === A.selInv) || list[0];
  const live = all.filter((x) => x.v.status !== "Voided");
  const billed = live.reduce((a, x) => a + D.invoiceTotal(x.v), 0), rec = live.reduce((a, x) => a + D.paid(x.v) - D.refunded(x.v), 0);
  return `<div class="page"><div class="page-head"><div><h1 class="h1">Billing</h1><p class="muted small" style="margin:2px 0 0">Billed amounts and money received are shown separately</p></div><div class="r"><button class="btn" data-act="invcsv">${I("download")}Export CSV</button>${can("billing") ? `<button class="btn primary" data-act="newinvoice">${I("plus")}Create invoice</button>` : ""}</div></div>
  <div class="kpis"><div class="card kpi"><div class="lbl">Billed</div><div class="val num">${money(billed, c())}</div></div><div class="card kpi"><div class="lbl">Received (net)</div><div class="val num" style="color:var(--green)">${money(rec, c())}</div></div><div class="card kpi"><div class="lbl">Outstanding</div><div class="val num" style="color:var(--amber)">${money(billed - rec, c())}</div></div><div class="card kpi"><div class="lbl">Invoices</div><div class="val num">${all.length}</div></div></div>
  <div class="split"><div class="card"><div class="card-h"><div class="chips">${["All", "Unpaid", "Partially paid", "Paid", "Voided"].map((f) => `<button class="chip ${A.invFilter === f ? "on" : ""}" data-act="invfilter" data-f="${f}">${f}</button>`).join("")}</div></div>
   <div class="table-wrap" style="margin-top:10px"><table class="t"><thead><tr><th>Date</th><th>Patient</th><th class="hide-sm">Items</th><th style="text-align:right">Total</th><th style="text-align:right">Received</th><th>Status</th></tr></thead><tbody>
   ${list.slice(0, 600).map(({ p, v }) => `<tr class="click ${sel && sel.v === v ? "sel" : ""}" data-act="selinv" data-id="${v.id}"><td>${fmtMs(v.at, z())}</td><td><b>${esc(p.name)}</b></td><td class="hide-sm muted">${esc(v.description || "")}</td><td class="num" style="text-align:right">${money(D.invoiceTotal(v), v.currency)}</td><td class="num" style="text-align:right">${money(D.paid(v) - D.refunded(v), v.currency)}</td><td><span class="badge ${({ Paid: "green", "Partially paid": "amber", Unpaid: "red", Voided: "" })[D.invoiceState(v)]}">${D.invoiceState(v)}</span></td></tr>`).join("") || `<tr><td colspan="6" class="empty">No invoices.</td></tr>`}
   </tbody></table></div></div>
   <div class="sticky" id="invside">${sel ? `<div class="small muted" style="margin:0 0 8px;font-weight:700">${esc(sel.p.name)} · <a href="#/patients/${sel.p.id}">open patient</a></div>` + invoiceCard(sel.p, sel.v) : ""}</div></div></div>`;
}

// ============================================================================ reports
function pageReports() {
  const [a, b] = D.periodRange(A.period, A.from, A.to, S.config);
  const inP = (ms) => ms >= a && ms < b;
  const invs = S.patients.flatMap((p) => (p.invoices || []).map((v) => ({ p, v })));
  const billed = invs.filter((x) => x.v.status !== "Voided" && inP(x.v.at)).reduce((s, x) => s + D.invoiceTotal(x.v), 0);
  const pays = invs.flatMap((x) => (x.v.payments || []).map((pp) => ({ ...pp, p: x.p, v: x.v })));
  const refs = invs.flatMap((x) => x.v.refunds || []);
  const received = pays.filter((x) => inP(x.at)).reduce((s, x) => s + x.amount, 0), refunded = refs.filter((x) => inP(x.at)).reduce((s, x) => s + x.amount, 0);
  const outstanding = S.patients.reduce((s, p) => s + D.patientDue(p), 0);
  const appts = allAppointments().filter((x) => inP(x.startAt));
  const done = S.patients.flatMap((p) => (p.treatments || []).filter((t) => t.status === "Completed" && inP(t.performedAt || t.at)).map((t) => ({ ...t, p })));
  const byMethod = D.METHODS.map((m) => [m, pays.filter((x) => inP(x.at) && x.method === m).reduce((s, x) => s + x.amount, 0)]).filter((x) => x[1]);
  const mm = Math.max(1, ...byMethod.map((x) => x[1]));
  const byProc = {};
  for (const t of done) { const f = D.treatmentFee(t.p, t); (byProc[t.procedure] ||= [0, 0])[0]++; byProc[t.procedure][1] += f ? f.total : 0; }
  const procs = Object.entries(byProc).sort((x, y) => y[1][0] - x[1][0]);
  const pm = Math.max(1, ...procs.map((x) => x[1][0]));
  const docs = [...new Set(appts.map((x) => x.doctor))].map((d) => [d, appts.filter((x) => x.doctor === d && x.status === "Completed").length, appts.filter((x) => x.doctor === d).length, done.filter((t) => t.doctor === d).length]);
  const balances = S.patients.map((p) => [p, D.patientDue(p)]).filter((x) => x[1] > 0).sort((x, y) => y[1] - x[1]).slice(0, 8);
  const newPatients = S.patients.filter((p) => (p.history || []).some((h) => h.text === "Patient registered" && inP(h.at))).length;
  const span = Number.isFinite(a) ? Math.min(62, Math.round((b - a) / 86400000)) : 30;
  const startIso = Number.isFinite(a) ? zoned(a, z()).date : addDaysIso(todayIn(z()), -29);
  const days = [...Array(span)].map((_, i) => { const d = addDaysIso(startIso, i); return [d, pays.filter((x) => zoned(x.at, z()).date === d).reduce((s, x) => s + x.amount, 0)]; });
  const dm = Math.max(1, ...days.map((x) => x[1]));
  const feeT = S.patients.flatMap((p) => (p.treatments || []).map((t) => ({ t, p, f: D.treatmentFee(p, t) }))).filter((x) => x.f && x.t.id === D.treatmentRef(x.t));
  A._csv = () => [["Date", "Patient", "Invoice", "Total", "Received", "Refunded", "Balance", "Status"], ...invs.filter((x) => inP(x.v.at)).map(({ p, v }) => [zoned(v.at, z()).date, p.name, v.description, D.invoiceTotal(v) / 100, D.paid(v) / 100, D.refunded(v) / 100, Math.max(0, D.balance(v)) / 100, D.invoiceState(v)])];
  return `<div class="page"><div class="page-head"><div><h1 class="h1">Reports</h1><p class="muted small" style="margin:2px 0 0">${esc(z())} · figures reconcile to source payments</p></div>
   <div class="r"><div class="seg">${["Day", "Week", "Month", "Custom", "All"].map((x) => `<button class="${A.period === x ? "on" : ""}" data-act="period" data-p="${x}">${x}</button>`).join("")}</div>
   ${A.period === "Custom" ? `<input class="input" style="width:auto" type="date" data-act="from" value="${A.from}"><input class="input" style="width:auto" type="date" data-act="to" value="${A.to}">` : ""}
   <button class="btn primary" data-act="reppdf">${I("download")}Financial report PDF</button><button class="btn" data-act="apptpdf">${I("cal")}Appointments PDF</button><button class="btn" data-act="repcsv">${I("share")}CSV</button></div></div>
  <div class="kpis">${[["Billed in period", billed, ""], ["Payments received", received, "var(--green)"], ["Refunds paid out", refunded, "var(--red)"], ["Net cash", received - refunded, "var(--brand)"], ["Outstanding (all dates)", outstanding, "var(--amber)"]].map(([l, v, col]) => `<div class="card kpi"><div class="lbl">${l}</div><div class="val num" style="color:${col || "var(--ink)"}">${money(v, c())}</div></div>`).join("")}</div>
  <div class="dash">
   <div class="card wide"><div class="card-h"><h3>Payments received per day</h3><span class="sub">cash basis</span></div><div class="card-b"><div class="bars" style="height:220px">${days.map(([d, v], i) => `<div class="b" title="${fmtIso(d)}: ${money(v, c())}"><i style="height:${(v / dm) * 100}%"></i><span class="hide-sm">${i % Math.ceil(days.length / 12) === 0 ? +d.slice(8) : ""}</span></div>`).join("")}</div></div></div>
   <div class="card"><div class="card-h"><h3>By payment method</h3></div><div class="card-b">${byMethod.map(([m, v]) => `<div style="margin-bottom:12px"><div class="between small"><b>${m}</b><span class="num">${money(v, c())} · ${Math.round((v / Math.max(1, received)) * 100)}%</span></div><div class="pay" style="padding:0;border:0;background:none"><div class="track"><i style="width:${(v / mm) * 100}%;background:${{ Cash: "#0F766E", bKash: "#E2136E", Nagad: "#F7941D", Card: "#2563EB", Bank: "#64748B" }[m]}"></i></div></div></div>`).join("") || `<p class="muted small">No payments in this period.</p>`}</div></div>
   <div class="card wide"><div class="card-h"><h3>Completed procedures</h3><span class="sub">${done.length} in period</span></div><div class="table-wrap"><table class="t"><thead><tr><th>Procedure</th><th>Count</th><th>Fees</th><th style="width:40%"></th></tr></thead><tbody>${procs.map(([n, [cnt, fee]]) => `<tr><td><b>${esc(n)}</b></td><td class="num">${cnt}</td><td class="num">${money(fee, c())}</td><td><div class="pay" style="padding:0;border:0;background:none;margin:0"><div class="track"><i style="width:${(cnt / pm) * 100}%;background:var(--brand)"></i></div></div></td></tr>`).join("") || `<tr><td colspan="4" class="empty">No completed procedures in this period.</td></tr>`}</tbody></table></div></div>
   <div class="card"><div class="card-h"><h3>Largest balances due</h3></div><div class="card-b">${balances.map(([p, d]) => `<div class="list-item" style="cursor:pointer" data-act="openp" data-id="${p.id}"><div class="avatar">${initials(p.name)}</div><div class="grow"><b>${esc(p.name)}</b><small>${esc(p.phone || "")}</small></div><b class="num" style="color:var(--amber)">${money(d, c())}</b></div>`).join("") || `<p class="muted small">No balances due.</p>`}</div></div>
   <div class="card"><div class="card-h"><h3>Clinician workload</h3></div><div class="card-b">${docs.map(([d, comp, all, pr]) => `<div class="list-item"><div class="avatar">${initials(d)}</div><div class="grow"><b>${esc(d)}</b><small>${all} appointments · ${comp} completed</small></div><span class="badge teal">${pr} procedures</span></div>`).join("") || `<p class="muted small">No appointments in this period.</p>`}</div></div>
   <div class="card"><div class="card-h"><h3>Appointment states</h3><span class="sub">${appts.length} in period · ${newPatients} new patients</span></div><div class="card-b"><div class="counters" style="grid-template-columns:repeat(2,1fr)">${D.APPOINTMENT_STATES.map((s) => [s, appts.filter((x) => x.status === s).length]).filter((x) => x[1]).map(([l, v]) => `<div class="counter"><b class="num">${v}</b><span>${l}</span></div>`).join("") || `<p class="muted small">No appointments.</p>`}</div></div></div>
   <div class="card wide"><div class="card-h"><h3>Treatment payments</h3><span class="sub">every treatment with a fee (all dates)</span></div><div class="table-wrap" style="max-height:360px"><table class="t"><thead><tr><th>Patient</th><th>Treatment</th><th style="text-align:right">Fee</th><th style="text-align:right">Paid</th><th style="text-align:right">Due</th></tr></thead><tbody>${feeT.sort((x, y) => y.f.due - x.f.due).slice(0, 200).map(({ t, p, f }) => `<tr class="click" data-act="openp" data-id="${p.id}"><td><b>${esc(p.name)}</b></td><td>${esc(t.procedure)} · ${esc(D.toothLabel(t.tooth, S.notation))}</td><td class="num" style="text-align:right">${money(f.total, c())}</td><td class="num" style="text-align:right">${money(f.paid, c())}</td><td class="num" style="text-align:right;color:${f.due ? "var(--amber)" : "var(--green)"}">${f.due ? money(f.due, c()) : "Paid"}</td></tr>`).join("")}</tbody></table></div></div>
  </div></div>`;
}
const csv = (rows) => rows.map((r) => r.map((v) => { const s = String(v ?? ""); return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s; }).join(",")).join("\n");

// ============================================================================ team
function pageTeam() {
  const staff = S.config.staff || [];
  const manage = can("manageTeam");
  const s = staff.find((x) => x.id === A.selStaff) || (A.selStaff === "new" ? { id: "", role: "dentist", active: true } : staff[0]);
  const role = A.permDraft?.role || (s ? (["administrator", "owner"].includes(s.role) ? "admin" : s.role) : "dentist");
  const joined = (email) => Object.values(S.config.members || {}).some((m) => m.email === String(email || "").toLowerCase() && m.active !== false);
  return `<div class="page"><div class="page-head"><div><h1 class="h1">Team & roles</h1><p class="muted small" style="margin:2px 0 0">Staff join your clinic when they sign in with the Google email you add here</p></div><div class="r">${manage ? `<button class="btn primary" data-act="newstaff">${I("plus")}Add staff member</button>` : ""}</div></div>
  ${manage ? "" : `<div class="notice" style="margin-bottom:16px">${I("lock")}<span>Only the owner and people with "Clinic settings, team & roles" can change the team.</span></div>`}
  <div class="split"><div class="card"><div class="table-wrap"><table class="t matrix"><thead><tr><th>Member</th><th>Role</th>${D.ACCESS.map((a) => `<th class="c hide-sm" title="${a[1]}" style="writing-mode:vertical-rl;transform:rotate(180deg);height:150px;font-size:10.5px;letter-spacing:.03em;text-transform:none;padding:8px 6px">${a[1]}</th>`).join("")}<th>Status</th></tr></thead><tbody>
   ${staff.map((m) => `<tr class="click ${s && m.id === s.id ? "sel" : ""}" data-act="selstaff" data-id="${m.id}"><td><div class="row"><div class="avatar">${initials(m.name)}</div><div><b>${esc(m.name || "Unnamed")}</b><div class="small muted">${esc(m.email || "No sign-in email")}</div></div></div></td><td><span class="badge">${D.roleTitle(m.role)}</span></td>
     ${D.ACCESS.map(([k]) => { const on = D.allowed(m.role, m.permissions, k); return `<td class="c hide-sm"><span class="tick ${on ? "" : "no"}">${I(on ? "check" : "x")}</span></td>`; }).join("")}
     <td>${m.active === false ? '<span class="badge">Inactive</span>' : joined(m.email) ? '<span class="badge green">Joined</span>' : '<span class="badge amber">Invited</span>'}</td></tr>`).join("") || `<tr><td colspan="12" class="empty">No staff yet. Add a doctor or receptionist to give them access.</td></tr>`}
   </tbody></table></div></div>
   ${s ? `<form class="card sticky" id="staffform" onsubmit="return false"><div class="card-h"><h3>${esc(s.name || "Add staff member")}</h3><div class="r">${s.id ? (s.active === false ? '<span class="badge">Inactive</span>' : '<span class="badge green">Active</span>') : ""}</div></div><div class="card-b">
    <div class="grid-form">${field("Full name", input("name", s.name || "", manage ? "" : "disabled"))}${field("Google account email", input("email", s.email || "", (manage ? "" : "disabled ") + 'type="email"'), "They sign in with this Google account")}${field("Phone", input("phone", s.phone || "", manage ? "" : "disabled"))}${field("Registration", input("registration", s.registration || "", manage ? "" : "disabled"))}</div>
    <div class="field" style="margin-top:14px"><label>Role (fills in access)</label><div class="chips">${D.ROLES.map((r) => `<button type="button" class="chip ${role === r ? "on" : ""}" data-act="role" data-r="${r}" ${manage ? "" : "disabled"}>${D.roleTitle(r)}</button>`).join("")}</div><span class="hint">${esc(D.roleSummary(role))}</span></div>
    <hr class="sep"><div class="rail-title">Access</div>
    ${D.ACCESS.map(([k, t, d]) => `<div class="set-row" style="padding:8px 0"><div><b class="small">${t}</b><small>${d}</small></div><label class="switch"><input type="checkbox" name="perm_${k}" ${D.allowed(role, A.permDraft?.role === role ? A.permDraft.perms : s.permissions, k) ? "checked" : ""} ${manage ? "" : "disabled"}><i></i></label></div>`).join("")}
    <div class="set-row"><div><b>Active</b><small>Turn off to remove access; earlier work stays</small></div><label class="switch"><input type="checkbox" name="active" ${s.active !== false ? "checked" : ""} ${manage ? "" : "disabled"}><i></i></label></div>
    ${manage ? `<div class="row" style="margin-top:12px"><button class="btn primary" data-act="savestaff" data-role="${role}">${s.id ? "Save changes" : "Add to team"}</button>${s.id ? `<button class="btn danger" data-act="removestaff">Remove from team</button>` : ""}</div>` : ""}</div></form>` : ""}
  </div></div>`;
}

// ============================================================================ settings
function pageSettings() {
  const cfg = S.config, manage = can("manageTeam");
  const dis = manage ? "" : "disabled";
  const sub = S.subscription || {};
  const limit = St.patientLimit(), count = St.activePatientCount();
  return `<div class="page"><div class="page-head"><div><h1 class="h1">Clinic settings</h1><p class="muted small" style="margin:2px 0 0">Signed in as ${esc(S.user?.email || "")} · ${esc(D.roleTitle(St.currentRole()))}${S.backend?.demo ? " · demo" : " · Firebase project " + esc(S.backend?.projectId || "")}</p></div></div>
  <div class="set-grid">
   <form class="card" id="clinicform" onsubmit="return false"><div class="card-h"><h3>Clinic management</h3></div><div class="card-b"><div class="grid-form">
     ${field("Clinic name", input("name", cfg.name || "", dis))}${field("Time zone", input("timeZone", cfg.timeZone || "Asia/Dhaka", dis + ' list="dl-tz"') + `<datalist id="dl-tz">${["Asia/Dhaka", "Asia/Kolkata", "Asia/Karachi", "Asia/Dubai", "Asia/Singapore", "Europe/London", "America/New_York", "UTC"].map((t) => `<option>${t}</option>`).join("")}</datalist>`)}
     ${field("Currency", input("currency", cfg.currency || "BDT", dis), "3-letter code, e.g. BDT")}${field("Tax percent", input("tax", String((cfg.taxBps || 0) / 100), dis))}
     ${field("Appointment buffer (min)", input("buffer", cfg.bufferMinutes ?? 5, dis + ' type="number" min="0" max="120"'))}</div>
     ${manage ? `<button class="btn primary" style="margin-top:14px" data-act="saveclinic">Save clinic settings</button>` : `<p class="small muted">Only an admin can change clinic settings.</p>`}</div></form>
   <div class="card"><div class="card-h"><h3>Chart & display</h3></div><div class="card-b">
     <div class="set-row"><div><b>Tooth numbering</b><small>Records are always stored in FDI</small></div><div class="seg">${[["palmer", "Palmer"], ["fdi", "FDI"], ["universal", "Universal"]].map(([v, l]) => `<button class="${S.notation === v ? "on" : ""}" data-act="notation" data-v="${v}">${l}</button>`).join("")}</div></div>
     <div class="set-row"><div><b>Tooth tap sound</b><small>Soft click when selecting a tooth in 3D</small></div><label class="switch"><input type="checkbox" data-act="sounds" ${S.sounds ? "checked" : ""}><i></i></label></div>
     <div class="set-row"><div><b>Theme</b><small>Follows your system unless chosen</small></div><div class="seg"><button class="${!isDark() ? "on" : ""}" data-act="settheme" data-v="light">Light</button><button class="${isDark() ? "on" : ""}" data-act="settheme" data-v="dark">Dark</button></div></div>
     <div class="set-row"><div><b>Keyboard</b><small><kbd>Ctrl K</kbd> search · <kbd>Esc</kbd> close or leave full-screen 3D</small></div></div></div></div>
   <form class="card" id="remform" onsubmit="return false"><div class="card-h"><h3>Reminders</h3></div><div class="card-b">
     <div class="set-row"><div><b>Enable clinic reminders</b><small>Scheduled on each device; no SMS or email is sent</small></div><label class="switch"><input type="checkbox" name="remindersEnabled" ${cfg.remindersEnabled ? "checked" : ""} ${dis}><i></i></label></div>
     <div class="grid-form" style="margin-top:8px">${field("Hours before appointment", input("reminderHours", cfg.reminderHours || 24, dis + ' type="number" min="1" max="168"'))}${field("Quiet hours start", input("quietStart", cfg.quietStart || "21:00", dis + ' type="time"'))}${field("Quiet hours end", input("quietEnd", cfg.quietEnd || "08:00", dis + ' type="time"'))}</div>
     ${manage ? `<button class="btn primary" style="margin-top:12px" data-act="saverem">Save reminders</button>` : ""}
     <div class="set-row"><div><b>Browser notifications</b><small>${"Notification" in window ? `Upcoming appointments on this PC while Dental Care is open (${Notification.permission})` : "Not supported in this browser"}</small></div><button class="btn sm" data-act="notify">Allow</button></div></div></form>
   <div class="card"><div class="card-h"><h3>Subscription</h3><div class="r"><span class="badge ${St.subscriptionActive() ? "teal" : "amber"}">${S.complimentary || St.isSuperAdmin() ? "Complimentary" : St.subscriptionActive() ? "Dental Care Pro" : "Not subscribed"}</span></div></div><div class="card-b">
     ${S.complimentary || St.isSuperAdmin() ? `<p class="muted">This clinic is free (super admin).</p>` : St.subscriptionActive() ? `<div class="small muted">Active${sub.expiresAt ? " until " + new Date(sub.expiresAt).toLocaleDateString() : ""} · tier ${St.planTier()}</div>` : `<p class="muted small">Not active. The owner or an admin subscribes in the Android app (Settings › Subscription).</p>`}
     ${limit ? `<div class="plan" style="margin-top:14px;background:var(--surface-3)"><div class="between small"><b>${count.toLocaleString()} of ${limit.toLocaleString()} active patients</b><span class="num">${Math.round((count / limit) * 100)}%</span></div><div class="bar"><i style="width:${Math.min(100, (count / limit) * 100)}%"></i></div><div class="small muted">Each further 1,000 patients adds US$${D.PLAN.offerUsd} (normally US$${D.PLAN.regularUsd}). Archived patients don't count. Upgrades are bought in the Android app.</div></div>` : ""}</div></div>
   <div class="card"><div class="card-h"><h3>Data & sync</h3></div><div class="card-b">
     <div class="set-row"><div><b>Sync now</b><small>Fetch others' changes and patient app bookings</small></div><button class="btn sm" data-act="sync">${I("sync")}Sync</button></div>
     <div class="set-row"><div><b>Restore cloud records</b><small>Reload all clinic records into this browser</small></div><button class="btn sm" data-act="restore">Restore</button></div>
     <div class="set-row"><div><b>Conflict drafts</b><small>${(S.retained || []).length} retained draft(s)</small></div>${(S.retained || []).length ? `<button class="btn sm" data-act="exportdrafts">Export drafts</button>` : ""}</div>
     <div class="set-row"><div><b>Import patient JSON as a new record</b><small>Exported files contain sensitive data</small></div>${can("managePatients") ? `<label class="btn sm">Import<input type="file" accept="application/json,.json" data-act="import" hidden></label>` : ""}</div>
     <div class="set-row"><div><b>Sign out</b><small>${esc(S.user?.email || "")}</small></div><button class="btn sm" data-act="signout">${I("out")}Sign out</button></div></div></div>
   <div class="card"><div class="card-h"><h3>Services & pricing</h3><div class="r">${manage ? `<button class="btn sm" data-act="service">${I("plus")}Add service</button>` : ""}</div></div><div class="table-wrap"><table class="t"><tbody>${(cfg.services || []).map((s) => `<tr><td><b>${esc(s.name)}</b>${s.active === false ? ' <span class="badge">Inactive</span>' : ""}<div class="small muted">${esc(s.code || "")} · ${s.durationMinutes || 30} min</div></td><td class="num" style="text-align:right">${money(s.fee, c())}</td><td style="width:150px">${manage ? `<button class="btn ghost sm" data-act="service" data-id="${s.id}">Edit</button><button class="btn ghost sm" data-act="svctoggle" data-id="${s.id}">${s.active === false ? "Activate" : "Deactivate"}</button>` : ""}</td></tr>`).join("") || `<tr><td class="empty">No services yet. Services fill in fees when recording treatment and invoices.</td></tr>`}</tbody></table></div></div>
  </div></div>`;
}

// ============================================================================ help
function pageHelp() {
  const order = ["start", "menu", "patients", "three-d", "lens", "multi", "record", "edit-treatment", "treatment-payments", "chart", "encounters", "treatments", "history", "slots", "booking", "reminders", "team", "patient-logins", "billing", "reports", "sync", "subscription"];
  const guides = GUIDES.slice().sort((x, y) => order.indexOf(x[0]) - order.indexOf(y[0]));
  const q = A.helpQ.trim().toLowerCase();
  const list = guides.filter((g) => (A.helpTopic === "All topics" || g[1] === A.helpTopic) && (!q || [g[1], g[2], g[3], ...g[4], g[5] || ""].join(" ").toLowerCase().includes(q)));
  const g = guides.find((x) => x[0] === A.helpId) || list[0] || guides[0];
  const idx = guides.indexOf(g);
  return `<div class="page"><div class="page-head"><div><h1 class="h1">Make each visit easier</h1><p class="muted small" style="margin:2px 0 0">Step-by-step guides for every part of Dental Care web</p></div></div>
  <div class="help"><div class="card"><div class="card-b" style="padding-bottom:10px"><input class="input" id="hq" placeholder="Try 3D, lens, slots, roles, refund, or sync" value="${esc(A.helpQ)}">
   <div class="chips" style="margin-top:10px">${["All topics", ...new Set(guides.map((x) => x[1]))].map((t) => `<button class="chip ${A.helpTopic === t ? "on" : ""}" data-act="helptopic" data-t="${esc(t)}">${esc(t)}</button>`).join("")}</div>
   <div class="small muted" style="margin-top:10px">${list.length} ${list.length === 1 ? "guide" : "guides"}</div></div>
   <div class="list">${list.map((x) => `<div class="gitem ${x === g ? "on" : ""}" data-act="helpid" data-id="${x[0]}"><div class="tp">${esc(x[1])}</div><b>${esc(x[2])}</b><small>${esc(x[3])}</small></div>`).join("") || `<div class="empty">No matching guide. Try a shorter search.</div>`}</div></div>
   <div class="card"><div class="card-b" style="padding:28px clamp(18px,3vw,44px)"><div class="gitem" style="padding:0;border:0;background:none;box-shadow:none;cursor:default"><div class="tp">${esc(g[1])}</div></div><h2 style="margin:6px 0 6px;font-size:clamp(20px,1.8vw,28px);letter-spacing:-.02em">${esc(g[2])}</h2><p class="muted" style="margin:0;font-size:15px">${esc(g[3])}</p>
    <ol class="steps">${g[4].map((s) => `<li>${esc(s)}</li>`).join("")}</ol>${g[5] ? `<div class="notice teal">${I("help")}<span>${esc(g[5])}</span></div>` : ""}
    <div class="between" style="margin-top:24px"><button class="btn" ${idx <= 0 ? "disabled" : ""} data-act="helpid" data-id="${(guides[idx - 1] || g)[0]}">${I("chevl")}Previous</button><button class="btn" ${idx >= guides.length - 1 ? "disabled" : ""} data-act="helpid" data-id="${(guides[idx + 1] || g)[0]}">Next ${I("chev")}</button></div></div></div></div></div>`;
}

// ============================================================================ sign in
function pageSignin() {
  return `${S.backend?.demo ? `<div class="demo-bar">${I("spark")}Demo mode: Firebase isn't configured in js/config.js yet. Sign in to explore a sample clinic.</div>` : ""}
  <div class="signin"><div class="hero"><div class="row" style="gap:12px">${LOGO.replace('class="logo"', 'class="logo" style="width:44px;height:44px"')}<b style="font-size:18px">Dental Care</b></div>
   <div><h1>Your whole clinic, on the big screen.</h1><p>Patients, the 3D dental chart, appointments, treatment payments and reports in one place. The same records as the Android app, live from your clinic's Firebase.</p></div>
   <div class="feat"><div><b>3D chart at a glance</b>Lenses, info tags and hover details</div><div><b>Doctor columns</b>Every doctor's day in one calendar</div><div><b>Payments by visit</b>Fee, paid and due for each treatment</div><div><b>Roles and access</b>Simple switches per staff member</div></div></div>
  <div class="form"><div class="box"><h2 style="margin:0 0 6px;font-size:26px;letter-spacing:-.02em">Sign in</h2><p class="muted" style="margin:0 0 20px">Choose who you are.</p>
   <div class="seg" style="width:100%;margin-bottom:20px"><button style="flex:1" class="on">Clinic team</button><button style="flex:1" data-act="toportal">Patient</button></div>
   ${S.user && S.sessionError ? `<div class="notice red" style="margin-bottom:14px">${I("alert")}<span>${esc(S.sessionError)}</span></div>` : ""}
   <button class="gbtn" data-act="google"><svg width="20" height="20" viewBox="0 0 48 48" aria-hidden="true"><path fill="#FFC107" d="M43.6 20.5H42V20H24v8h11.3C33.7 32.7 29.2 36 24 36c-6.6 0-12-5.4-12-12s5.4-12 12-12c3.1 0 5.8 1.2 7.9 3.1l5.7-5.7C34 6.1 29.3 4 24 4 12.9 4 4 12.9 4 24s8.9 20 20 20 20-8.9 20-20c0-1.3-.1-2.4-.4-3.5z"/><path fill="#FF3D00" d="m6.3 14.7 6.6 4.8C14.7 15.1 19 12 24 12c3.1 0 5.8 1.2 7.9 3.1l5.7-5.7C34 6.1 29.3 4 24 4 16.3 4 9.7 8.3 6.3 14.7z"/><path fill="#4CAF50" d="M24 44c5.2 0 9.9-2 13.4-5.2l-6.2-5.2C29.2 35.1 26.7 36 24 36c-5.2 0-9.6-3.3-11.3-8l-6.5 5C9.5 39.6 16.2 44 24 44z"/><path fill="#1976D2" d="M43.6 20.5H42V20H24v8h11.3c-.8 2.2-2.2 4.2-4.1 5.6l6.2 5.2C37 39.2 44 34 44 24c0-1.3-.1-2.4-.4-3.5z"/></svg>${S.backend?.demo ? "Open the demo clinic" : "Continue with Google"}</button>
   ${!S.user && S.sessionError ? `<div class="err">${esc(S.sessionError)}</div>` : ""}
   <p class="small muted" style="margin-top:16px">The first account to sign in creates the clinic and becomes its owner. Staff join automatically with the access set in Team & roles.</p>
   <hr class="sep" style="margin:28px 0 14px"><a href="#/help" class="small" style="font-weight:700">How to use Dental Care</a></div></div></div>`;
}

// ============================================================================ command palette
let cmdIdx = 0;
function openCmd() {
  const sc = $("#scrim");
  sc.innerHTML = `<div class="cmdk"><input id="cq" placeholder="Search patients, phone, ID, or type a page name…" autocomplete="off"><div class="res" id="cres"></div></div>`;
  sc.classList.add("show");
  sc.onmousedown = (e) => { if (e.target === sc) closeModal(); };
  const q = $("#cq");
  q.oninput = () => { cmdIdx = 0; cmdRender(); };
  q.onkeydown = (e) => {
    const items = $$("#cres [data-go]");
    if (e.key === "ArrowDown") { cmdIdx = Math.min(items.length - 1, cmdIdx + 1); cmdRender(); e.preventDefault(); }
    if (e.key === "ArrowUp") { cmdIdx = Math.max(0, cmdIdx - 1); cmdRender(); e.preventDefault(); }
    if (e.key === "Enter" && items[cmdIdx]) { closeModal(); go(items[cmdIdx].dataset.go); }
  };
  $("#cres").onclick = (e) => { const r = e.target.closest("[data-go]"); if (r) { closeModal(); go(r.dataset.go); } };
  cmdRender(); setTimeout(() => q.focus(), 20);
}
function cmdRender() {
  const v = ($("#cq")?.value || "").trim().toLowerCase();
  const ps = S.patients.filter((p) => !v || [p.name, p.phone, p.id, p.portal?.loginId || ""].join(" ").toLowerCase().includes(v)).slice(0, 8);
  const pages = [["home", "Home"], ["schedule", "Appointments"], ["billing", "Billing"], ["reports", "Reports"], ["team", "Team & roles"], ["settings", "Clinic settings"], ["help", "How to use"], ["portal", "Patient app"]].filter((x) => !v || x[1].toLowerCase().includes(v));
  let i = 0;
  const row = (g, html) => `<div data-go="${g}" class="${i++ === cmdIdx ? "on" : ""}">${html}</div>`;
  $("#cres").innerHTML = (ps.length ? `<h6>Patients</h6>` + ps.map((p) => row("patients/" + p.id, `<div class="avatar">${initials(p.name)}</div><div style="flex:1"><b>${esc(p.name)}</b><div class="small muted num">${esc(p.phone || "")}${p.portal?.loginId ? " · " + esc(p.portal.loginId) : ""}</div></div>${p.allergies ? `<span class="badge red">${esc(p.allergies.split(",")[0])}</span>` : ""}`)).join("") : "")
    + (pages.length ? `<h6>Go to</h6>` + pages.map((x) => row(x[0], `${I("chev")}<b>${x[1]}</b>`)).join("") : "") || `<div class="empty">Nothing found</div>`;
}

// ============================================================================ render
function parse() {
  const h = location.hash.replace(/^#\/?/, "") || "home";
  const [r, arg] = h.split("/");
  A.route = ["home", "patients", "schedule", "billing", "reports", "team", "settings", "help", "portal", "signin"].includes(r) ? r : "home";
  A.arg = arg || "";
}
function pageFor() {
  if (A.route === "patients") return renderPatients(A.arg);
  return ({ home: pageHome, schedule: pageSchedule, billing: pageBilling, reports: pageReports, team: pageTeam, settings: pageSettings, help: pageHelp })[A.route]?.() ?? pageHome();
}
let lastRoute = "";
export function render() {
  parse();
  const root = $("#root");
  if (!S.ready) { root.innerHTML = `<div class="boot"><div>${LOGO}Loading Dental Care…</div></div>`; return; }
  if (A.route === "portal") { Viewer.unmount(); root.innerHTML = renderPortal(); afterPortal(); lastRoute = "portal"; return; }
  if (!S.user || (S.user && !S.clinicId)) {
    Viewer.unmount();
    if (A.route === "help") { root.innerHTML = `<div style="padding:16px"><a class="btn" href="#/home">${I("chevl")}Back to sign in</a></div>` + pageHelp(); return; }
    root.innerHTML = pageSignin(); return;
  }
  if (S.loadingClinic) { root.innerHTML = `<div class="boot"><div>${LOGO}Opening ${esc(S.config.name || "your clinic")}…</div></div>`; return; }
  if (!A.day) { A.day = todayIn(z()); A.month = A.day.slice(0, 7); }
  const main = $("#main");
  const keep = main && lastRoute === A.route + A.arg ? { m: main.scrollTop, r: $("#prail")?.scrollTop, l: $(".plist .items")?.scrollTop, c: $("#pcenter")?.scrollTop } : null;
  root.innerHTML = shell(pageFor());
  if (keep) { $("#main").scrollTop = keep.m; if ($("#prail")) $("#prail").scrollTop = keep.r || 0; if ($(".plist .items")) $(".plist .items").scrollTop = keep.l || 0; if ($("#pcenter")) $("#pcenter").scrollTop = keep.c || 0; }
  lastRoute = A.route + A.arg;
  if (A.route === "patients") afterRenderPatients(); else Viewer.unmount();
  const hq = $("#hq");
  if (hq) hq.oninput = () => { A.helpQ = hq.value; render(); const n = $("#hq"); n.focus(); n.setSelectionRange(n.value.length, n.value.length); };
  showMessage();
}
let msgTimer;
function showMessage() {
  const m = S.message;
  let el = $("#msg");
  if (!m) { el?.remove(); return; }
  if (!el) { el = document.createElement("div"); el.id = "msg"; document.body.appendChild(el); }
  el.className = "msg " + (m.kind === "error" ? "error" : "");
  el.innerHTML = `${I(m.kind === "error" ? "alert" : "check")}<span>${esc(m.text)}</span><button aria-label="Dismiss" data-act="dismiss">Dismiss</button>`;
  clearTimeout(msgTimer);
  msgTimer = setTimeout(() => { if (S.message === m) { S.message = null; $("#msg")?.remove(); } }, m.kind === "error" ? 9000 : 3500);
}

// ============================================================================ actions
async function appAction(act, el, e) {
  const ds = el.dataset;
  switch (act) {
    case "dismiss": S.message = null; $("#msg")?.remove(); return;
    case "drawer": $("#app")?.classList.toggle("drawer"); return;
    case "cmdk": openCmd(); return;
    case "sync": sync(); return;
    case "theme": A.theme = isDark() ? "light" : "dark"; document.documentElement.dataset.theme = A.theme; try { localStorage.setItem("dc-theme", A.theme); } catch { /* ignore */ } break;
    case "settheme": A.theme = ds.v; document.documentElement.dataset.theme = A.theme; try { localStorage.setItem("dc-theme", A.theme); } catch { /* ignore */ } break;
    case "signout": e.preventDefault(); confirmBox("Sign out", "You'll need to sign in with Google again to open the clinic.", "Sign out", async () => { await St.signOut(); go("home"); }, false); return;
    case "google": await St.signIn(); return;
    case "toportal": go("portal"); return;
    case "demoreset": confirmBox("Reset demo", "This clears the demo clinic in this browser and starts again.", "Reset", () => S.backend.reset()); return;
    case "register": registerDialog(); return;
    case "book": bookDialog({ date: ds.date || "" }); return;
    case "bookslot": bookDialog({ doctor: ds.doc, date: ds.date, time: ds.time, duration: +ds.dur, slot: true }); return;
    case "openslots": openSlotsDialog(ds.date || A.day); return;
    case "availability": availabilityDialog(); return;
    case "closeday": confirmBox("Close this day", `No slots will be offered on ${fmtIso(A.day)} for this doctor. Weekly repeats on other days stay.`, "Close day", () => closeDayForDoctor(ds.ids.split(","), A.day)); return;
    case "newinvoice": invoiceDialog(); return;
    case "openp": go("patients/" + ds.id); return;
    case "openenc": P.tab = "encounters"; go("patients/" + ds.id); return;
    case "day": A.day = ds.d; A.month = ds.d.slice(0, 7); A.selAppt = null; break;
    case "dayshift": A.day = addDaysIso(A.day, +ds.n); A.month = A.day.slice(0, 7); A.selAppt = null; break;
    case "month": { const [y, m] = A.month.split("-").map(Number); const d = new Date(Date.UTC(y, m - 1 + +ds.n, 1)); A.month = `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, "0")}`; break; }
    case "schedview": A.schedView = ds.v; break;
    case "listrange": A.listRange = ds.r; break;
    case "selappt": A.selAppt = ds.id || null; break;
    case "selappt2": A.selAppt = ds.id; A.day = ds.d; A.month = ds.d.slice(0, 7); break;
    case "status": await setAppointmentStatus(ds.pid, ds.id, ds.s); return;
    case "resched": bookDialog({ pid: ds.pid, appointment: patient(ds.pid).appointments.find((a) => a.id === ds.id) }); return;
    case "invfilter": A.invFilter = ds.f; break;
    case "selinv": A.selInv = ds.id; break;
    case "invpdf": case "rcptpdf": case "receive": case "refund": case "void": case "shareinv": case "receipt": {
      const owner = S.patients.find((p) => (p.invoices || []).some((v) => v.id === ds.inv));
      if (!owner) return;
      P.pid = owner.id;
      return patientAction(act, el, e);
    }
    case "invcsv": { const rows = [["Date", "Patient", "Description", "Total", "Received", "Balance", "Status"], ...S.patients.flatMap((p) => (p.invoices || []).map((v) => [zoned(v.at, z()).date, p.name, v.description, D.invoiceTotal(v) / 100, (D.paid(v) - D.refunded(v)) / 100, Math.max(0, D.balance(v)) / 100, D.invoiceState(v)]))]; download("invoices.csv", csv(rows), "text/csv"); return; }
    case "period": A.period = ds.p; break;
    case "reppdf": { const [a, b] = D.periodRange(A.period, A.from, A.to, S.config); pdf(() => Reports.financialPdf(Reports.rangeLabel(A.period, a, b), a, b)); return; }
    case "apptpdf": { const [a, b] = D.periodRange(A.period, A.from, A.to, S.config); pdf(() => Reports.appointmentsPdf(Reports.rangeLabel(A.period, a, b), a, b)); return; }
    case "schedpdf": {
      const r = A.schedView === "List" ? (A.listRange || "Day") : "Day";
      const d0 = A.day, start = r === "Week" ? addDaysIso(d0, -(dowOf(d0) - 1)) : r === "Month" ? d0.slice(0, 8) + "01" : d0;
      const end = r === "Week" ? addDaysIso(start, 7) : addDaysIso(d0, 1);
      const endM = r === "Month" ? (() => { const [y, m] = start.split("-").map(Number); return m === 12 ? `${y + 1}-01-01` : `${y}-${String(m + 1).padStart(2, "0")}-01`; })() : end;
      const a = r === "All" ? -Infinity : zonedMs(start, "00:00", z()), b = r === "All" ? Infinity : zonedMs(endM, "00:00", z());
      pdf(() => Reports.appointmentsPdf(Reports.rangeLabel(r, a, b), a, b)); return;
    }
    case "repcsv": download(`financial-report-${A.period.toLowerCase()}.csv`, csv(A._csv()), "text/csv"); return;
    case "selstaff": A.selStaff = ds.id; A.permDraft = null; break;
    case "newstaff": A.selStaff = "new"; A.permDraft = null; break;
    case "role": { const f = $("#staffform"); A.permDraft = { role: ds.r, perms: D.preset(ds.r) }; const v = values(f); render(); const nf = $("#staffform"); ["name", "email", "phone", "registration"].forEach((k) => { if (nf[k]) nf[k].value = v[k]; }); return; }
    case "savestaff": return saveStaff(ds.role);
    case "removestaff": { const s = (S.config.staff || []).find((x) => x.id === A.selStaff) || S.config.staff[0]; confirmBox("Remove from team", `${esc(s.name)} will lose access. Their earlier work stays in the records.`, "Remove", async () => { const next = clone(S.config); next.staff = next.staff.filter((x) => x.id !== s.id); A.selStaff = ""; return saveConfig(next, "Removed from team"); }); return; }
    case "saveclinic": {
      const v = values($("#clinicform"));
      try { new Intl.DateTimeFormat("en", { timeZone: v.timeZone }); } catch { return say("Unknown time zone", "error"); }
      if (!/^[A-Z]{3}$/i.test(v.currency)) return say("Use a 3-letter currency code", "error");
      const next = { ...clone(S.config), name: v.name.trim() || "Dental Care", timeZone: v.timeZone, currency: v.currency.toUpperCase(), taxBps: Math.round(parseFloat(v.tax || "0") * 100), bufferMinutes: Math.max(0, Math.min(120, +v.buffer || 0)) };
      await saveConfig(next); return;
    }
    case "saverem": { const v = values($("#remform")); await saveConfig({ ...clone(S.config), remindersEnabled: !!v.remindersEnabled, reminderHours: Math.max(1, Math.min(168, +v.reminderHours || 24)), quietStart: v.quietStart, quietEnd: v.quietEnd }, "Reminders saved"); return; }
    case "notify": if ("Notification" in window) { await Notification.requestPermission(); render(); } return;
    case "notation": St.setPref("dc-notation", ds.v); Viewer.notation = ds.v; Viewer.call("setNotation", ds.v); break;
    case "restore": St.restoreAll(); return;
    case "exportdrafts": download("conflict-drafts.json", JSON.stringify(S.retained, null, 2)); return;
    case "service": serviceDialog(ds.id); return;
    case "svctoggle": { const next = clone(S.config); const s = next.services.find((x) => x.id === ds.id); s.active = s.active === false; await saveConfig(next, "Service updated"); return; }
    case "helptopic": A.helpTopic = ds.t; break;
    case "helpid": A.helpId = ds.id; $("#main") && ($("#main").scrollTop = 0); break;
    default: return;
  }
  render();
}
async function saveStaff(role) {
  const f = $("#staffform"), v = values(f);
  if (!v.name.trim()) return say("Enter the staff member's name", "error");
  if (v.email && !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(v.email.trim())) return say("Enter a valid Google email", "error");
  const next = clone(S.config);
  const list = (next.staff ||= []);
  if (v.email && list.some((x) => x.id !== A.selStaff && String(x.email).toLowerCase() === v.email.trim().toLowerCase())) return say("Someone in the team already uses this email", "error");
  let s = list.find((x) => x.id === A.selStaff);
  if (!s && A.selStaff !== "new") s = list[0];
  const isNew = !s;
  if (isNew) { s = { id: uid() }; list.push(s); }
  const perms = Object.fromEntries(D.ACCESS.map(([k]) => [k, !!v["perm_" + k]]));
  Object.assign(s, { name: v.name.trim(), email: v.email.trim().toLowerCase(), phone: v.phone.trim(), registration: v.registration.trim(), role, active: !!v.active, permissions: perms });
  const err = await saveConfig(next, isNew ? "Added to team · they join when they sign in with Google" : "Saved and shared with the clinic");
  if (!err) { A.selStaff = s.id; A.permDraft = null; render(); }
}
function serviceDialog(id) {
  const s = (S.config.services || []).find((x) => x.id === id);
  modal({ title: s ? "Edit service" : "Add service", body: `<div class="grid-form">${field("Name", input("name", s?.name || ""))}${field("Code", input("code", s?.code || ""))}${field(`Default fee (${c()})`, input("fee", s ? minorToText(s.fee, c()) : "", 'inputmode="decimal"'))}${field("Duration (minutes)", input("duration", s?.durationMinutes || 30, 'type="number" min="5" max="480"'))}</div>`,
    onSave: async (v) => {
      if (!v.name.trim()) return "Name is required";
      const next = clone(S.config); const list = (next.services ||= []);
      const entry = { id: s?.id || uid(), name: v.name.trim(), code: v.code.trim(), fee: parseMinor(v.fee || "0", c()), durationMinutes: Math.max(5, Math.min(480, +v.duration || 30)), active: s ? s.active !== false : true };
      if (s) list[list.findIndex((x) => x.id === s.id)] = entry; else list.push(entry);
      return saveConfig(next, "Service saved");
    } });
}

document.addEventListener("click", (e) => {
  const el = e.target.closest("[data-act]");
  if (!el || el.closest(".v3d") || el.matches("input[type=checkbox],input[type=file],input[type=date]")) return;
  if (el.tagName === "A" && el.getAttribute("href") && el.getAttribute("href") !== "#") return;
  const act = el.dataset.act;
  if (A.route === "portal") return portalAction(act, el, e);
  if (A.route === "patients" && !["dismiss", "drawer", "cmdk", "sync", "theme", "signout", "register", "demoreset"].includes(act)) return patientAction(act, el, e);
  appAction(act, el, e);
});
document.addEventListener("change", (e) => {
  const el = e.target.closest("[data-act]");
  if (!el || !el.matches("input")) return;
  const act = el.dataset.act;
  if (A.route === "portal") return portalAction(act, el, e);
  if (A.route === "patients") return patientAction(act, el, e);
  if (act === "booking") saveConfig({ ...clone(S.config), patientBooking: el.checked }, el.checked ? "Online booking turned on" : "Online booking turned off", true);
  if (act === "sounds") { St.setPref("dc-sounds", el.checked); Viewer.state.sounds = el.checked; Viewer.call("setSounds", el.checked); }
  if (act === "from") { A.from = el.value; render(); }
  if (act === "to") { A.to = el.value; render(); }
  if (act === "import") importJson(el.files?.[0]);
});
async function importJson(file) {
  if (!file) return;
  try {
    const raw = await file.text();
    if (raw.length > 650000) throw new Error("Imported document is too large");
    const p = JSON.parse(raw);
    if (p.schemaVersion !== 3 || !p.name) throw new Error("Unsupported patient export");
    const original = p.id;
    ["operationId", "cloudOwner", "audit", "receipts", "revision", "portal", "clinicId", "remoteVersion"].forEach((k) => delete p[k]);
    Object.assign(p, { id: uid(), remoteVersion: 0, importedFrom: original, name: p.name + " (imported)", images: [] });
    const err = await St.save(p, "Patient imported from export; verify identity and clinical data");
    if (!err) go("patients/" + p.id);
  } catch (err) { say(err.message, "error"); }
}
addEventListener("keydown", (e) => {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "k" && S.user && A.route !== "portal") { e.preventDefault(); openCmd(); }
  if (e.key === "Escape") { closeModal(); $("#app")?.classList.remove("drawer"); }
});
addEventListener("hashchange", () => { render(); const m = $("#main"); if (m) m.scrollTop = 0; });

// In-browser reminders while the app is open (no server, like the Android on-device reminders).
setInterval(() => {
  if (!S.user || !S.config.remindersEnabled || !("Notification" in window) || Notification.permission !== "granted") return;
  const now = Date.now(), soon = now + 30 * 60000;
  let seen = {};
  try { seen = JSON.parse(localStorage.getItem("dc-notified") || "{}"); } catch { /* ignore */ }
  for (const a of allAppointments()) {
    if (a.startAt > now && a.startAt <= soon && ["Scheduled", "Confirmed"].includes(a.status) && !seen[a.id]) {
      new Notification("Upcoming appointment", { body: `${zoned(a.startAt, z()).time} · ${a.pname} with ${a.doctor}`, tag: a.id });
      seen[a.id] = now;
    }
  }
  try { localStorage.setItem("dc-notified", JSON.stringify(seen)); } catch { /* ignore */ }
}, 60000);

St.onChange(render);
