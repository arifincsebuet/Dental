// Patient app (web): sign in with the Patient ID + password from the clinic. No Firebase account:
// the portal document key is SHA-256 of the ID and password (same as the Android patient app).
import { $, esc, money, zoned, todayIn, addDaysIso, dowOf, fmtIso, fmtMs, fmtMonth } from "./util.js";
import * as D from "./domain.js";
import { S, say, emit } from "./store.js";
import { Viewer } from "./viewer3d.js";
import { I, LOGO } from "./icons.js";
import { confirmBox } from "./dialogs.js";

const R = { key: "", rec: null, error: "", busy: false, day: "", month: "", doctor: "", slot: null, reason: "", tooth: "" };
try { R.key = sessionStorage.getItem("dc-portal") || ""; } catch { /* ignore */ }
const z = () => R.rec?.clinic?.timeZone || "Asia/Dhaka", cur = () => R.rec?.clinic?.currency || "BDT";

async function load() {
  R.busy = true; emit();
  try {
    R.rec = await S.backend.loadPortal(R.key);
    if (!R.rec) { R.error = "Patient ID or password is not correct, or your login was changed by the clinic."; R.key = ""; sessionStorage.removeItem("dc-portal"); }
  } catch (e) { R.error = e.message || String(e); }
  R.busy = false; emit();
}
if (R.key) setTimeout(load, 0);

export function renderPortal() {
  if (!R.rec) return signin();
  const p = R.rec.patient, clinic = R.rec.clinic;
  const now = Date.now();
  const appts = (p.appointments || []).filter((a) => !["Cancelled", "Completed", "No show"].includes(a.status) && a.startAt > now - 3600000).sort((a, b) => a.startAt - b.startAt);
  const fees = (p.invoices || []).filter((v) => v.status !== "Voided");
  const total = fees.reduce((a, v) => a + D.invoiceTotal(v), 0), got = fees.reduce((a, v) => a + D.paid(v) - D.refunded(v), 0);
  if (!R.day) { R.day = todayIn(z()); R.month = R.day.slice(0, 7); }
  const doctors = [...new Set((clinic.availability || []).filter((s) => s.active !== false).map((s) => s.doctor))];
  if (!R.doctor) R.doctor = doctors[0] || "";
  const slots = D.slotsFor(clinic, [], R.day, clinic.busy || [], now).filter((s) => s.doctor === R.doctor);
  const list = (p.treatments || []).filter((t) => !R.tooth || t.tooth === R.tooth).sort((a, b) => (b.at || 0) - (a.at || 0));
  return `<div style="min-height:100dvh;background:var(--bg)"><div class="portal-top">${LOGO.replace('class="logo"', 'class="logo" style="width:34px;height:34px"')}<div><b>${esc(clinic.name || "Dental Care")}</b><div class="small muted">Patient app</div></div>
    <div style="margin-left:auto" class="row"><span class="small hide-sm" style="font-weight:700">${esc(p.name)} · ${esc(R.rec.loginId)}</span><button class="btn sm" data-act="prefresh">${I("sync", R.busy ? "spin" : "")}Refresh my record</button><button class="btn sm" data-act="pout">Sign out</button></div></div>
  <div class="portal"><h1 class="h1" style="margin-bottom:4px">Hello, ${esc(p.name.split(" ")[0])}</h1><p class="muted" style="margin:0 0 18px">Your clinic's records, read only.${appts[0] ? ` Next visit: <b>${fmtMs(appts[0].startAt, z(), true)}</b> with ${esc(appts[0].doctor)}.` : ""}${R.rec.pending ? ` ${R.rec.pending} request(s) waiting for the clinic.` : ""}</p>
  <div class="portal-grid"><div style="display:grid;gap:16px;align-content:start">
   <div class="card"><div class="card-h"><h3>My teeth</h3><span class="sub">Drag to rotate · scroll to zoom · hover or click a tooth</span></div><div class="card-b"><div class="stage-slot short" id="stage-slot"></div>
    <div class="legend" style="margin-top:10px"><span><i style="background:#60a5fa"></i>Treated</span><span><i style="background:#14b8a6"></i>Planned care (+)</span><span><i style="background:#f59e0b"></i>Finding</span><span><i style="background:#64748b"></i>Missing (×)</span></div></div></div>
   <div class="card"><div class="card-h"><h3>Treatment history ${R.tooth ? "· " + esc(D.toothLabel(R.tooth, S.notation)) : ""}</h3>${R.tooth ? `<div class="r"><button class="btn ghost sm" data-act="pall">All teeth</button></div>` : ""}</div><div class="card-b"><div class="timeline">${list.map((t) => { const f = D.treatmentFee(p, t); return `<div class="tl ${t.status === "Planned" ? "planned" : ""}"><div class="between"><b>${esc(t.procedure)} <span class="muted" style="font-weight:600">· ${esc(D.toothLabel(t.tooth, S.notation))}</span></b><span class="badge">${esc(t.status)}</span></div><div class="m">${fmtMs(t.at, z())}${t.doctor ? " · " + esc(t.doctor) : ""}</div>${f ? `<div class="pay ${f.due ? "due" : ""}"><div class="nums num"><span>Fee ${money(f.total, cur())}</span><span style="color:var(--green)">Paid ${money(f.paid, cur())}</span><span style="color:${f.due ? "var(--amber)" : "var(--green)"}">${f.due ? "Due " + money(f.due, cur()) : "Fully paid"}</span></div><div class="track"><i style="width:${Math.min(100, (f.paid / f.total) * 100)}%"></i></div></div>` : ""}</div>`; }).join("") || `<div class="empty">No treatment recorded${R.tooth ? " for this tooth" : ""}.</div>`}</div></div></div></div>
   <div style="display:grid;gap:16px;align-content:start">
   <div class="card"><div class="card-h"><h3>My appointments</h3></div><div class="card-b">${appts.map((a) => `<div class="list-item"><div class="mono" style="width:92px;font-size:12px">${fmtMs(a.startAt, z(), true).replace(" · ", "<br>")}</div><div class="grow"><b>${esc(a.reason)}</b><small>${esc(a.doctor)}</small></div><span class="badge st-${a.status.replace(/ /g, "-")}">${esc(a.status)}</span>${["Scheduled", "Confirmed", "Requested"].includes(a.status) ? `<button class="btn ghost sm danger" data-act="pcancel" data-id="${a.id}">Cancel</button>` : ""}</div>`).join("") || `<p class="muted small" style="margin:0">No upcoming appointments.</p>`}
     <p class="small muted" style="margin:8px 0 0">Cancel up to 2 hours before. Later than that, please call the clinic.</p></div></div>
   <div class="card"><div class="card-h"><h3>Book appointment</h3></div><div class="card-b">${clinic.patientBooking === false ? `<p class="muted">Online booking is off. Please call the clinic to book.</p>` : `
     ${doctors.length > 1 ? `<div class="field" style="margin-bottom:12px"><label>Doctor</label><div class="chips">${doctors.map((d) => `<button class="chip ${R.doctor === d ? "on" : ""}" data-act="pdoc" data-d="${esc(d)}">${esc(d)}</button>`).join("")}</div></div>` : ""}
     ${miniCal(clinic)}
     <div class="rail-title" style="margin-top:12px">${fmtIso(R.day, true)}</div>
     <div class="slots">${slots.map((s) => `<button ${s.available ? "" : "disabled"} class="${R.slot === s.startAt ? "on" : ""}" data-act="pslot" data-s="${s.startAt}">${s.time}</button>`).join("") || `<span class="small muted">No free times this day.</span>`}</div>
     <div class="field" style="margin-top:12px"><label>Reason (optional)</label><input class="input" id="preason" value="${esc(R.reason)}" placeholder="e.g. tooth pain"></div>
     <button class="btn primary block" style="margin-top:12px" data-act="pbook" ${R.slot ? "" : "disabled"}>Book${R.slot ? " " + zoned(R.slot, z()).time : ""}</button><p class="small muted" style="margin:8px 0 0">You can hold up to 3 upcoming bookings.</p>`}</div></div>
   <div class="card"><div class="card-h"><h3>Billing</h3></div><div class="card-b"><div class="total-row"><span class="muted">Total fees</span><b class="num">${money(total, cur())}</b></div><div class="total-row"><span class="muted">Paid</span><b class="num" style="color:var(--green)">${money(got, cur())}</b></div><div class="total-row big"><span>Due</span><span class="num" style="color:var(--amber)">${money(Math.max(0, total - got), cur())}</span></div></div></div>
  </div></div></div></div>`;
}
function miniCal(clinic) {
  const [y, m] = R.month.split("-").map(Number);
  const first = `${R.month}-01`, start = addDaysIso(first, -(dowOf(first) - 1)), today = todayIn(z());
  const cells = [...Array(42)].map((_, i) => {
    const d = addDaysIso(start, i);
    const free = d >= today && D.slotsFor(clinic, [], d, clinic.busy || []).some((s) => s.available && s.doctor === R.doctor);
    return `<button class="${+d.slice(5, 7) !== m ? "out" : ""} ${d === today ? "today" : ""} ${d === R.day ? "on" : ""}" data-act="pday" data-d="${d}" ${d < today ? "disabled" : ""}>${+d.slice(8)}${free ? "<i></i>" : ""}</button>`;
  }).join("");
  return `<div class="mini-cal" style="padding:0"><div class="between" style="margin-bottom:8px"><b>${fmtMonth(y, m)}</b><div class="row" style="gap:4px"><button class="icon-btn" style="width:30px;height:30px" data-act="pmonth" data-n="-1">${I("chevl")}</button><button class="icon-btn" style="width:30px;height:30px" data-act="pmonth" data-n="1">${I("chev")}</button></div></div><div class="grid">${["Mo", "Tu", "We", "Th", "Fr", "Sa", "Su"].map((d) => `<span class="dow">${d}</span>`).join("")}${cells}</div></div>`;
}
function signin() {
  return `<div class="signin"><div class="hero"><div class="row" style="gap:12px">${LOGO.replace('class="logo"', 'class="logo" style="width:44px;height:44px"')}<b style="font-size:18px">Dental Care</b></div>
   <div><h1>Your dental care, on any screen.</h1><p>See your teeth in 3D, read your treatment history, check what is paid and due, and book appointments with your clinic.</p></div><div></div></div>
  <div class="form"><form class="box" id="plogin" onsubmit="return false"><h2 style="margin:0 0 6px;font-size:26px;letter-spacing:-.02em">Sign in</h2><p class="muted" style="margin:0 0 20px">Use the Patient ID and password your clinic gave you.</p>
   <div class="seg" style="width:100%;margin-bottom:20px"><button type="button" style="flex:1" data-act="pstaff">Clinic team</button><button type="button" style="flex:1" class="on">Patient</button></div>
   <div style="display:grid;gap:14px"><div class="field"><label>Patient ID</label><input class="input" name="id" autocomplete="username" placeholder="e.g. RAHIM01" ${S.backend?.demo ? 'value="RAHIM01"' : ""}></div>
   <div class="field"><label>Password</label><input class="input" name="pw" type="password" autocomplete="current-password" ${S.backend?.demo ? 'value="smile123"' : ""}></div>
   <button class="btn primary" style="height:46px;justify-content:center" data-act="plogin" ${R.busy ? "disabled" : ""}>${R.busy ? "Signing in…" : "Sign in"}</button>
   ${R.error ? `<div class="err">${esc(R.error)}</div>` : ""}<p class="small muted" style="margin:0">Forgot your password? Ask your clinic; they can set a new one.${S.backend?.demo ? " Demo: RAHIM01 / smile123." : ""}</p></div></form></div></div>`;
}
export function afterPortal() {
  const slot = $("#stage-slot");
  if (!slot || !R.rec) return Viewer.unmount();
  const p = R.rec.patient;
  const chart = { ...D.displayChart(p), readOnly: true };
  Viewer.notation = S.notation;
  const ok = Viewer.mount(slot, chart, {
    action: (act, arg) => {
      if (act === "view") Viewer.call("setView", arg);
      if (act === "zoom") Viewer.call("zoom", arg);
      if (act === "fs") Viewer.setFullscreen(!Viewer.fullscreen);
      ui();
    },
    ready: () => ui(), refreshOverlays: () => ui(), refreshUi: () => ui(),
    focus: (k) => { R.tooth = k; emit(); },
    tooltip: (k) => { const f = D.toothFacts(p, k); return `<div class="t"><span>${esc(D.toothLabel(k, S.notation))}</span>${esc(D.toothName(k))}</div><div class="row">${f.findings.map((x) => `<span class="pill a">${esc(x)}</span>`).join("")}${f.open.map((x) => `<span class="pill t">${esc(x.procedure)}</span>`).join("")}</div>${f.items[0] ? `<hr><b>${esc(f.items[0].procedure)}</b> · ${esc(f.items[0].status)}` : ""}${f.fee ? `<div class="money"><span>Fee ${money(f.fee, cur())}</span><span>${f.due ? "Due " + money(f.due, cur()) : "Paid"}</span></div>` : ""}`; },
  });
  if (!ok) slot.innerHTML = `<div class="stage-fallback"><div class="notice">${I("alert")}<span>Your browser can't show the 3D model (WebGL 2 is off).</span></div></div>`;
  function ui() {
    Viewer.renderUi(`<div class="v3d-top"><div class="glass v3d-seg">${["Front", "Upper", "Lower", "Left", "Right"].map((v) => `<button data-v3d="view" data-arg="${v}">${v}</button>`).join("")}</div><span class="spacer"></span><div class="glass v3d-seg"><button class="v3d-btn" data-v3d="zoom" data-arg="out">${I("minus")}</button><button class="v3d-btn" data-v3d="zoom" data-arg="in">${I("plus")}</button><button class="v3d-btn" data-v3d="fs">${I(Viewer.fullscreen ? "shrink" : "expand")}</button></div></div>`);
    Viewer.setLens({}); Viewer.setBadges({});
  }
  if (Viewer.ready) { ui(); Viewer.call("selectTooth", R.tooth || ""); }
}
export async function portalAction(act, el) {
  const ds = el.dataset;
  switch (act) {
    case "pstaff": location.hash = "#/home"; return;
    case "plogin": {
      const f = $("#plogin"); const id = D.normalizeLoginId(f.id.value), pw = f.pw.value;
      if (!D.validLoginId(id) || !pw) { R.error = "Enter your Patient ID and password"; emit(); return; }
      R.error = ""; R.key = await D.portalKey(id, pw);
      try { sessionStorage.setItem("dc-portal", R.key); } catch { /* ignore */ }
      await load(); return;
    }
    case "pout": R.key = ""; R.rec = null; try { sessionStorage.removeItem("dc-portal"); } catch { /* ignore */ } emit(); return;
    case "prefresh": await load(); say("Your record is up to date"); return;
    case "pall": R.tooth = ""; Viewer.call("clearSelection"); break;
    case "pdoc": R.doctor = ds.d; R.slot = null; break;
    case "pday": R.day = ds.d; R.slot = null; break;
    case "pmonth": { const [y, m] = R.month.split("-").map(Number); const d = new Date(Date.UTC(y, m - 1 + +ds.n, 1)); R.month = `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, "0")}`; break; }
    case "pslot": R.slot = +ds.s; R.reason = $("#preason")?.value || ""; break;
    case "pbook":
      try { await S.backend.bookSlot(R.key, R.slot, R.doctor, $("#preason")?.value || ""); R.slot = null; R.reason = ""; say("Booked. Your clinic receives it with their next sync."); await load(); } catch (e) { say(e.message, "error"); }
      return;
    case "pcancel": confirmBox("Cancel appointment", "Cancel this appointment?", "Cancel appointment", async () => { await S.backend.cancelMyAppointment(R.key, ds.id); say("Appointment cancelled"); await load(); }); return;
    default: return;
  }
  emit();
}
