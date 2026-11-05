// Application state and operations (a port of ClinicStore.kt). The web app writes each change to
// Firestore straight away (one transaction per patient with a revision check); Firestore's own
// IndexedDB cache keeps the last loaded records available offline.
import { clone, uid, arr, sha256Hex } from "./util.js";
import * as D from "./domain.js";
import { firebaseBackend, demoBackend, Conflict } from "./cloud.js";
import { encodeLossless } from "../sdk/dental-cloud.js";
import { demoSeed } from "./demo.js";

const listeners = new Set();
export const S = {
  ready: false, backend: null, cfg: {}, user: null, clinicId: "", memberRole: "", config: D.defaults(), revision: 0,
  subscription: {}, complimentary: false, patients: [], lastPull: 0, busyKey: "", conflicts: {}, busy: false,
  message: null, sessionError: "", loadingClinic: false,
  notation: pref("dc-notation", "palmer"), sounds: pref("dc-sounds", "true") === "true",
};
function pref(k, d) { try { return localStorage.getItem(k) ?? d; } catch { return d; } }
export function setPref(k, v) { S[{ "dc-notation": "notation", "dc-sounds": "sounds" }[k] || k] = v; try { localStorage.setItem(k, String(v)); } catch { /* ignore */ } }
export const onChange = (fn) => listeners.add(fn);
export const emit = () => listeners.forEach((fn) => fn());
export function say(text, kind = "ok") { S.message = { text, kind, at: Date.now() }; emit(); }

export const isSuperAdmin = () => !!S.user && D.SUPER_ADMINS.includes(S.user.email);
export const isOwner = () => !!S.user && S.user.uid === S.clinicId;
export function currentRole() {
  if (isOwner()) return "owner";
  const m = S.config.members?.[S.user?.uid];
  if (!m || m.active === false) return "unknown";
  return m.role || S.memberRole || "unknown";
}
export function can(perm) {
  if (!S.user) return false;
  if (isOwner()) return true;
  const m = S.config.members?.[S.user.uid];
  if (!m || m.active === false) return false;
  return D.allowed(m.role, m.permissions, perm);
}
export const subscriptionActive = () => isSuperAdmin() || S.complimentary || S.backend?.demo || (S.subscription?.active === true && Number(S.subscription.expiresAt) > Date.now());
export const activePatientCount = () => S.patients.filter((p) => !p.archived).length;
export const planTier = () => Math.max(1, Number(S.subscription?.tier) || 1);
export const patientLimit = () => (isSuperAdmin() || S.complimentary || !subscriptionActive() ? null : planTier() * D.PLAN.perTier);
export const patient = (id) => S.patients.find((p) => p.id === id);

export async function init(cfg) {
  S.cfg = cfg;
  try {
    S.backend = cfg.firebase?.apiKey && !cfg.forceDemo ? await firebaseBackend(cfg) : demoBackend(demoSeed);
  } catch (e) {
    S.sessionError = "Could not start Firebase: " + (e.message || e);
    S.backend = demoBackend(demoSeed);
  }
  S.backend.onAuth(async (user) => {
    S.user = user;
    if (!user) { Object.assign(S, { clinicId: "", patients: [], ready: true, config: D.defaults(), lastPull: 0 }); emit(); return; }
    try { await openWorkspace(); } catch (e) { S.sessionError = friendly(e); }
    S.ready = true; emit();
  });
}

async function openWorkspace() {
  S.loadingClinic = true; emit();
  const ws = await S.backend.resolveWorkspace(S.user);
  S.clinicId = ws.clinicId; S.memberRole = ws.role;
  let clinic = await S.backend.getClinic(S.clinicId);
  if (!clinic && ws.role === "owner") {
    const config = D.defaults();
    config.staff = [{ id: uid(), name: S.user.name || "Clinic owner", email: S.user.email, role: "admin", active: true }];
    clinic = await S.backend.commitClinic(S.clinicId, S.user.uid, config, 0, isSuperAdmin());
  }
  applyClinic(clinic);
  const { records, newest } = await S.backend.listPatients(S.clinicId, null);
  S.patients = records.map(withRemote);
  S.lastPull = newest;
  S.busyKey = JSON.stringify(D.busyTimes(S.patients, S.config));
  S.loadingClinic = false;
  if (S.backend.demo) await seedDemoPortal();
}
function applyClinic(c) {
  if (!c) return;
  S.config = { ...D.defaults(), ...c.config };
  S.revision = c.revision || 0; S.subscription = c.subscription || {}; S.complimentary = !!c.complimentary;
}
const withRemote = (p) => ({ ...p, remoteVersion: p.revision || 0 });

async function seedDemoPortal() {
  const r = patient("P1001");
  if (!r || r.portal) return;
  const key = await D.portalKey("RAHIM01", "smile123");
  try { const res = await S.backend.createPatientLogin(S.clinicId, S.user.uid, r, S.config, "RAHIM01", key); Object.assign(r, { portal: res.portal, revision: res.revision, remoteVersion: res.revision }); } catch { /* already there */ }
}

export function friendly(e) {
  const m = e?.message || String(e);
  if (e?.code === "permission-denied" || /permission/i.test(m)) return "The clinic's security rules refused this change. Check your access in Team & roles, or App Check settings.";
  if (e?.code === "unavailable" || /offline|network/i.test(m)) return "You're offline. Reconnect, then try again.";
  if (e?.code === "auth/popup-closed-by-user") return "Sign-in was cancelled.";
  if (e?.code === "auth/unauthorized-domain") return "This web address isn't authorised for Google sign-in. Add it in Firebase › Authentication › Settings › Authorised domains.";
  return m;
}

// ------------------------------------------------------------------- patients
const CLINICAL = ["allergies", "medicalHistory", "medications", "story", "chart", "treatments", "encounters", "performedProcedures", "images", "notes", "medicalAlerts"];
const same = (a, b) => JSON.stringify(a ?? null) === JSON.stringify(b ?? null);

/** Validates, records history and commits one patient. Returns null or the reason it was refused. */
export async function save(next, action = "", { quiet = false } = {}) {
  const existing = patient(next.id);
  const role = currentRole();
  if (["auditor", "unknown"].includes(role)) return refuse("This workspace is read-only for your account");
  const changed = (f) => {
    const v = next[f];
    const emptyInitial = !existing && (v == null || v === "" || (Array.isArray(v) && !v.length) || (f === "chart" && !Object.keys(v?.teeth || {}).length));
    return !emptyInitial && !same(v, existing?.[f]);
  };
  const denied = !existing && !can("managePatients") ? "register patients"
    : existing && CLINICAL.some(changed) && !can("recordTreatment") ? "record and edit treatment"
      : changed("invoices") && !can("billing") ? "invoices and payments"
        : changed("appointments") && !can("manageAppointments") ? "book and change appointments"
          : existing && ["name", "phone", "dob", "archived", "communicationConsent"].some(changed) && !can("managePatients") ? "register and edit patients" : null;
  if (denied) return refuse(`You don't have access to ${denied}. Ask your admin to turn it on in Team & roles.`);
  if (S.conflicts[next.id]) return refuse("This patient has a conflict. Review the conflict before editing.");
  const copy = clone(next);
  if (copy.chart) {
    const oldChart = existing?.chart;
    copy.chart.chartVersion = oldChart?.chartVersion || 0;
    D.normalizeChart(copy);
    const clinicalChanged = !same(oldChart?.teeth, copy.chart.teeth) || oldChart?.dentition !== copy.chart.dentition ||
      ["story", "treatments", "encounters", "performedProcedures", "images", "allergies"].some((f) => !same(existing?.[f], copy[f]));
    if (clinicalChanged) copy.chart.chartVersion = (oldChart?.chartVersion || 0) + 1;
    copy.chart.updatedAt = new Date().toISOString();
    copy.chart.clinicId = S.clinicId; copy.chart.updatedBy = S.user.uid;
  }
  try { validate(copy, existing); } catch (e) { return refuse(e.message); }
  copy.updatedAt = Date.now();
  if (action) {
    const before = {};
    if (existing) for (const f of ["name", "phone", "dob", "allergies", "story", "chart", "treatments", "appointments", "invoices", "encounters", "performedProcedures", "communicationConsent"])
      if ((f in copy || f in existing) && !same(existing[f], copy[f])) before[f] = clone(existing[f] ?? null);
    arr(copy, "history").push({ id: uid(), at: Date.now(), text: action, actor: S.user.name || S.user.email, previousValues: before });
  }
  if (new TextEncoder().encode(JSON.stringify(copy)).length > 650000) return refuse("Patient record is approaching its document limit. Export the record before adding more history.");
  S.busy = true; emit();
  try {
    const committed = await S.backend.commitPatient(S.clinicId, S.user.uid, copy);
    const clean = withRemote(committed);
    const i = S.patients.findIndex((p) => p.id === clean.id);
    if (i >= 0) S.patients[i] = clean; else S.patients.push(clean);
    if (!quiet) say(action ? "Saved · " + action.split(" · ")[0] : "Saved");
    afterPatientChange(clean, existing).catch(() => {});
    return null;
  } catch (e) {
    if (e instanceof Conflict) { S.conflicts[copy.id] = copy; say(e.message, "error"); return e.message; }
    return refuse(friendly(e));
  } finally { S.busy = false; emit(); }
}
function refuse(reason) { say(reason, "error"); return reason; }

function validate(p, previous) {
  if (!String(p.name || "").trim()) throw new Error("Patient name is required");
  if (p.dob && !/^\d{4}-\d{2}-\d{2}$/.test(p.dob)) throw new Error("Date of birth must be YYYY-MM-DD");
  if (p.dob && p.dob > new Date().toISOString().slice(0, 10)) throw new Error("Date of birth cannot be in the future");
  for (const f of ["treatments", "encounters", "invoices", "appointments", "history"])
    for (const o of previous?.[f] || []) if (!(p[f] || []).some((x) => x.id === o.id)) throw new Error(`${f} history cannot be deleted`);
  for (const k of Object.keys(previous?.chart?.teeth || {})) if (!p.chart?.teeth?.[k]) throw new Error(`Do not delete recorded tooth ${k}; record explicit presence instead`);
  for (const t of p.treatments || []) if (p.chart && !p.chart.teeth?.[t.tooth]) throw new Error("Treatment must target a recorded chart slot");
  for (const inv of p.invoices || []) {
    if (Array.isArray(inv.lines) && inv.amount !== D.invoiceTotal(inv)) throw new Error("Invoice total differs from its lines, discount, and tax");
    if (D.refunded(inv) > D.paid(inv) || D.paid(inv) - D.refunded(inv) > inv.amount) throw new Error("Payment or refund totals exceed the invoice");
  }
  const old = Object.fromEntries((previous?.appointments || []).map((a) => [a.id, a]));
  const others = S.patients.filter((x) => x.id !== p.id);
  for (const a of p.appointments || []) {
    if (!D.APPOINTMENT_STATES.includes(a.status)) throw new Error("Invalid appointment state");
    if (!D.TERMINAL_APPOINTMENTS.includes(a.status) && !same(a, old[a.id])) D.validateAppointment(a, [...others, p], S.config);
  }
}

async function afterPatientChange(p, before) {
  if (!same(p.appointments, before?.appointments)) await publishBusy();
  if (p.portal?.key) await S.backend.refreshPortal(p.portal.key, p, S.config, new Set());
}
async function publishBusy() {
  if (["auditor", "unknown"].includes(currentRole())) return;
  const busy = D.busyTimes(S.patients, S.config);
  const key = JSON.stringify(busy);
  if (key === S.busyKey) return;
  await S.backend.publishBusy(S.clinicId, busy);
  S.busyKey = key;
}

export async function createPatient({ name, phone, dob, allergies, gender, address, consent }) {
  if (!name.trim()) return refuse("Patient name is required");
  if (S.patients.some((p) => p.name.toLowerCase() === name.trim().toLowerCase() && p.phone === phone.trim())) return refuse("A patient with this name and phone already exists");
  const limit = patientLimit();
  if (limit && activePatientCount() >= limit) return refuse(`Your plan covers up to ${limit.toLocaleString()} patients. Upgrade in the Android app (Settings › Subscription), or archive patients who no longer visit.`);
  const id = uid();
  const record = { id, schemaVersion: 3, name: name.trim(), phone: phone.trim(), dob, gender, address: address.trim(), remoteVersion: 0, appointments: [], history: [],
    allergies: allergies.trim(), story: "", chart: D.emptyChart(), treatments: [], images: [], invoices: [], communicationConsent: !!consent };
  const err = await save(record, "Patient registered");
  return err ? null : id;
}

// ------------------------------------------------------------------- sync
export async function sync() {
  if (S.busy || !S.clinicId) return;
  S.busy = true; emit();
  try {
    await pull();
    const handled = {};
    try {
      const pending = await S.backend.pendingPortals(S.clinicId);
      for (const [key, portal] of pending) {
        const local = patient(portal.patientId);
        if (!local || local.portal?.key !== key || S.conflicts[local.id]) continue;
        const copy = clone(local);
        const ids = D.applyPortalRequests(copy, portal.requests || [], S.config, S.patients);
        if (!ids.size) continue;
        const committed = await S.backend.commitPatient(S.clinicId, S.user.uid, { ...copy, updatedAt: Date.now() });
        S.patients[S.patients.findIndex((x) => x.id === copy.id)] = withRemote(committed);
        handled[copy.id] = ids;
      }
    } catch (e) { say("Patient app requests: " + friendly(e), "error"); }
    await publishBusy().catch(() => {});
    for (const [pid, ids] of Object.entries(handled)) { const p = patient(pid); if (p?.portal?.key) await S.backend.refreshPortal(p.portal.key, p, S.config, ids).catch(() => {}); }
    const clinic = await S.backend.getClinic(S.clinicId);
    if (clinic) applyClinic(clinic);
    const n = Object.values(handled).reduce((a, s) => a + s.size, 0);
    say(n ? `Synced · ${n} patient app request${n > 1 ? "s" : ""} applied` : "Everything is up to date");
  } catch (e) { say(friendly(e), "error"); } finally { S.busy = false; emit(); }
}
async function pull() {
  const { records, newest } = await S.backend.listPatients(S.clinicId, S.lastPull || null);
  for (const r of records) {
    if (S.conflicts[r.id]) continue;
    const i = S.patients.findIndex((p) => p.id === r.id);
    if (i >= 0) S.patients[i] = withRemote(r); else S.patients.push(withRemote(r));
  }
  if (newest > S.lastPull) S.lastPull = newest;
}
export async function resolveConflict(id) {
  const remote = await S.backend.getPatient(S.clinicId, id);
  if (!remote) return refuse("This patient is not in the cloud yet");
  const draft = S.conflicts[id];
  (S.retained ||= []).push({ ...draft, retainedAt: Date.now() });
  delete S.conflicts[id];
  const i = S.patients.findIndex((p) => p.id === id);
  S.patients[i] = withRemote(remote);
  say("Cloud record loaded. Your draft is kept under Settings › Conflict drafts for export. Reapply reviewed changes.");
}
export async function restoreAll() {
  S.busy = true; emit();
  try { S.lastPull = 0; S.patients = []; await pull(); say("Cloud records restored"); } catch (e) { say(friendly(e), "error"); } finally { S.busy = false; emit(); }
}

// ------------------------------------------------------------------- clinic settings
export async function saveConfig(next, success = "Clinic settings saved", slotsOnly = false) {
  const manage = can("manageTeam");
  if (!manage && !(slotsOnly && can("manageSlots"))) return refuse("Only an admin can change clinic settings.");
  S.busy = true; emit();
  try {
    const state = await S.backend.commitClinic(S.clinicId, S.user.uid, next, S.revision, isSuperAdmin() && isOwner(), slotsOnly && !manage);
    applyClinic(state);
    say(success);
    return null;
  } catch (e) {
    if (e instanceof Conflict) { const c = await S.backend.getClinic(S.clinicId); applyClinic(c); }
    return refuse(friendly(e));
  } finally { S.busy = false; emit(); }
}

// ------------------------------------------------------------------- patient app logins
export async function createPatientLogin(pid, loginId, password) {
  const id = D.normalizeLoginId(loginId);
  if (!D.validLoginId(id)) throw new Error("Patient ID: 4–12 letters or numbers");
  const problem = D.passwordProblem(password);
  if (problem) throw new Error("Password: " + problem);
  if (!can("patientLogins")) throw new Error("You don't have access to patient app logins.");
  const key = await D.portalKey(id, password);
  const res = await S.backend.createPatientLogin(S.clinicId, S.user.uid, patient(pid), S.config, id, key);
  const p = patient(pid);
  Object.assign(p, { portal: res.portal, revision: res.revision, remoteVersion: res.revision });
  emit();
  return id;
}
export async function removePatientLogin(pid) {
  const rev = await S.backend.removePatientLogin(S.clinicId, S.user.uid, pid);
  const p = patient(pid); delete p.portal; p.revision = rev; p.remoteVersion = rev; emit();
}

// ------------------------------------------------------------------- images (lossless, ≤ 512 KB)
export async function attachImage(pid, file) {
  // Lossless on this device (WebP verified pixel-exact, else PNG), <= 512 KB, then Storage; the
  // gs:// link and path are saved in the patient's own document (images list).
  const encoded = await encodeLossless(file);
  const ref = await S.backend.uploadImage(S.clinicId, pid, encoded);
  const current = patient(pid);
  if ((current.images || []).some((x) => x.path === ref.path)) return null;
  const { resized, ...link } = ref;
  const copy = clone(current);
  arr(copy, "images").push(link);
  return save(copy, `Clinical image attached (${Math.round(link.bytes / 1024)} KB, lossless ${link.format.toUpperCase()}${resized ? `, resized to ${link.width}×${link.height}` : ""})`);
}

export async function signIn() { try { S.sessionError = ""; await S.backend.signInGoogle(); } catch (e) { S.sessionError = friendly(e); emit(); } }
export async function signOut() { await S.backend.signOut(); }
