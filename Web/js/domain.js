// Domain rules shared with the Android app (ClinicalRules, Access, ToothNotation, TreatmentHistory,
// TreatmentPayments, AppointmentSlots). Data shapes are identical so both apps work on one clinic.
import { uid, arr, obj, clone, zonedMs, zoned, todayIn, addDaysIso, dowOf, minutesOf, hm, money, sha256Hex } from "./util.js";

// ---------------------------------------------------------------- access
export const ACCESS = [
  ["viewClinical", "View charts & treatment history", "3D chart, findings, treatments, clinical notes"],
  ["recordTreatment", "Record & edit treatment", "Add or correct treatments and chart status"],
  ["managePatients", "Register & edit patients", "New patients, profile details"],
  ["manageAppointments", "Book & change appointments", "Book slots for patients, reschedule, cancel"],
  ["manageSlots", "Open & close appointment slots", "Doctors' available times for booking"],
  ["billing", "Invoices & payments", "Create invoices, take payments, refunds"],
  ["patientLogins", "Patient app logins", "Create Patient IDs and passwords"],
  ["reports", "Reports", "Clinic reports and exports"],
  ["manageTeam", "Clinic settings, team & roles", "Add staff, change roles and access (admin)"],
];
export const ROLES = ["admin", "dentist", "assistant", "receptionist", "cashier", "auditor"];
const DEFAULTS = {
  viewClinical: ["admin", "administrator", "dentist", "assistant", "auditor"],
  recordTreatment: ["admin", "administrator", "dentist"],
  managePatients: ["admin", "administrator", "dentist", "assistant", "receptionist"],
  manageAppointments: ["admin", "administrator", "dentist", "assistant", "receptionist"],
  manageSlots: ["admin", "administrator", "dentist", "receptionist"],
  billing: ["admin", "administrator", "cashier"],
  patientLogins: ["admin", "administrator", "dentist", "receptionist"],
  reports: ["admin", "administrator", "dentist", "cashier", "auditor"],
  manageTeam: ["admin", "administrator"],
};
export const roleTitle = (r) => ({ admin: "Admin", administrator: "Admin", owner: "Owner" }[r] || (r ? r[0].toUpperCase() + r.slice(1) : ""));
export const roleSummary = (r) => ({
  admin: "Everything, including team and settings", dentist: "Clinical care, patients, appointments, slots, patient logins",
  assistant: "Views charts, registers patients, books appointments", receptionist: "Front desk: patients, appointments, slots, patient logins",
  cashier: "Billing and reports", auditor: "Read-only charts and reports",
}[r] || "");
export const roleDefault = (role, key) => role === "owner" || (DEFAULTS[key] || []).includes(role);
export const preset = (role) => Object.fromEntries(ACCESS.map(([k]) => [k, roleDefault(role, k)]));
export function allowed(role, permissions, key) {
  if (permissions && Object.prototype.hasOwnProperty.call(permissions, key)) return permissions[key] === true;
  return roleDefault(role, key) || (key === "recordTreatment" && role === "assistant" && permissions?.clinicalWrite === true) ||
    (key === "viewClinical" && role === "assistant" && permissions?.clinicalRead === true);
}
export const STAFF_ROLE = { owner: "admin", admin: "admin", administrator: "admin", dentist: "dentist", assistant: "assistant", receptionist: "receptionist", cashier: "cashier", auditor: "auditor" };
export const SUPER_ADMINS = ["dev.spala@gmail.com", "spalatechnologies@gmail.com"];

// ---------------------------------------------------------------- clinic rules
export const APPOINTMENT_STATES = ["Requested", "Scheduled", "Confirmed", "Checked in", "In progress", "Completed", "Cancelled", "No show"];
export const TERMINAL_APPOINTMENTS = ["Completed", "Cancelled", "No show"];
/** Allowed appointment status changes (same as the Android app, ClinicFeatures.SchedulingScreen). */
export const APPOINTMENT_NEXT = {
  Requested: ["Confirmed", "Cancelled"], Scheduled: ["Confirmed", "Checked in", "Cancelled", "No show"],
  Confirmed: ["Checked in", "Cancelled", "No show"], "Checked in": ["In progress", "Cancelled"], "In progress": ["Completed", "Cancelled"],
};
export const appointmentChoices = (status) => [status, ...(APPOINTMENT_NEXT[status] || [])];
export const ACTIVE_APPOINTMENTS = ["Scheduled", "Confirmed", "Checked in", "In progress", "Requested"];
export const TREATMENT_STATES = ["Planned", "Accepted", "In progress", "Completed", "Deferred", "Declined", "Cancelled", "Referred"];
export const OPEN_TREATMENT = ["Planned", "Accepted", "In progress", "Deferred", "Referred"];
export const METHODS = ["Cash", "bKash", "Nagad", "Card", "Bank"];
export const FINDINGS = ["caries", "fracture", "wear", "endodontically_treated", "periapical_finding", "other"];
export const RESTORATIONS = ["composite", "amalgam", "glass_ionomer", "sealant", "crown", "inlay", "onlay", "other"];
export const PRESENCE = ["unknown", "present", "unerupted", "missing", "extracted", "exfoliated", "congenitally_absent"];
export const LOST = ["missing", "extracted", "exfoliated", "congenitally_absent"];
export const REPLACEMENTS = ["none", "implant", "pontic", "removable_prosthesis"];
export const PROCEDURES = [
  ["Filling (composite)", "composite"], ["Filling (amalgam)", "amalgam"], ["Glass ionomer", "glass_ionomer"], ["Root canal"], ["Crown", "crown"],
  ["Extraction"], ["Implant"], ["Scaling & polishing"], ["Sealant", "sealant"], ["Inlay", "inlay"], ["Onlay", "onlay"], ["Bridge"], ["Veneer"], ["Orthodontic"],
];
export const NEXT_STATES = {
  Planned: ["Accepted", "Deferred", "Declined", "Referred", "Cancelled"], Accepted: ["In progress", "Deferred", "Cancelled"],
  "In progress": ["Completed", "Deferred", "Referred"], Deferred: ["Accepted", "Cancelled"], Referred: ["Accepted", "Cancelled"],
};
export function defaults() {
  return { name: "Dental Care", currency: "BDT", timeZone: "Asia/Dhaka", taxBps: 0, bufferMinutes: 5, reminderHours: 24, remindersEnabled: false,
    quietStart: "21:00", quietEnd: "08:00", staff: [], services: [], members: {}, availability: [], patientBooking: true };
}
export const tz = (config) => config?.timeZone || "Asia/Dhaka";
export const cur = (config) => config?.currency || "BDT";

export function invoiceTotal(inv) {
  if (!Array.isArray(inv.lines)) return Number(inv.amount) || 0;
  let sub = 0;
  for (const l of inv.lines) sub += (Number(l.unitPrice) || 0) * (Number(l.quantity) || 0);
  const net = sub - (Number(inv.discount) || 0);
  const tax = Math.round((net * (Number(inv.taxBps) || 0)) / 10000);
  return net + tax;
}
export const paid = (inv) => (inv.payments || []).reduce((a, p) => a + (Number(p.amount) || 0), 0);
export const refunded = (inv) => (inv.refunds || []).reduce((a, p) => a + (Number(p.amount) || 0), 0);
export const balance = (inv) => (inv.status === "Voided" ? 0 : invoiceTotal(inv) - (paid(inv) - refunded(inv)));
export const invoiceState = (inv) => (inv.status === "Voided" ? "Voided" : balance(inv) <= 0 ? "Paid" : paid(inv) - refunded(inv) > 0 ? "Partially paid" : "Unpaid");
export const patientDue = (p) => (p.invoices || []).reduce((a, v) => a + Math.max(0, balance(v)), 0);

export function appointmentMillis(a, config) {
  if (typeof a.startAt === "number") return a.startAt;
  const m = /^(\d{4}-\d{2}-\d{2}) (\d{2}:\d{2})$/.exec(a.date || "");
  if (!m) throw new Error("Use date/time YYYY-MM-DD HH:mm");
  return zonedMs(m[1], m[2], tz(config));
}
export function validateAppointment(candidate, patients, config) {
  const duration = Number(candidate.durationMinutes || 30);
  if (duration < 5 || duration > 480) throw new Error("Duration must be 5–480 minutes");
  if (!String(candidate.doctor || "").trim()) throw new Error("A clinician is required");
  const start = appointmentMillis(candidate, config);
  const buffer = Math.min(120, Math.max(0, Number(config.bufferMinutes ?? 5))) * 60000;
  const end = start + duration * 60000;
  const room = String(candidate.room || "").trim().toLowerCase();
  for (const p of patients) for (const o of p.appointments || []) {
    if (o.id === candidate.id || TERMINAL_APPOINTMENTS.includes(o.status)) continue;
    const sameDoctor = String(o.doctor || "").trim().toLowerCase() === String(candidate.doctor).trim().toLowerCase();
    const sameRoom = room && String(o.room || "").trim().toLowerCase() === room;
    if (!sameDoctor && !sameRoom) continue;
    let os; try { os = appointmentMillis(o, config); } catch { continue; }
    const oe = os + Number(o.durationMinutes || 30) * 60000;
    const gap = candidate.slot && o.slot ? 0 : buffer;
    if (start < oe + gap && os < end + gap) throw new Error(sameDoctor ? `${candidate.doctor} is already booked then (${p.name}, including buffer)` : "Room is already booked during this time");
  }
}
export function periodRange(period, start, end, config) {
  const z = tz(config), today = todayIn(z);
  let a, b;
  if (period === "Day") { a = today; b = addDaysIso(today, 1); }
  else if (period === "Week") { a = addDaysIso(today, -(dowOf(today) - 1)); b = addDaysIso(a, 7); }
  else if (period === "Month") { a = today.slice(0, 8) + "01"; const [y, m] = a.split("-").map(Number); b = m === 12 ? `${y + 1}-01-01` : `${y}-${String(m + 1).padStart(2, "0")}-01`; }
  else if (period === "Custom") { if (!start || !end) return [-Infinity, Infinity]; a = start; b = addDaysIso(end, 1); }
  else return [-Infinity, Infinity];
  return [zonedMs(a, "00:00", z), zonedMs(b, "00:00", z)];
}

// ---------------------------------------------------------------- teeth & notation
export const QUADRANT_ORDER = [18, 17, 16, 15, 14, 13, 12, 11, 21, 22, 23, 24, 25, 26, 27, 28, 38, 37, 36, 35, 34, 33, 32, 31, 41, 42, 43, 44, 45, 46, 47, 48];
export const PRIMARY_ORDER = [55, 54, 53, 52, 51, 61, 62, 63, 64, 65, 75, 74, 73, 72, 71, 81, 82, 83, 84, 85];
export function toothShort(key, notation) {
  const q = +String(key)[0], p = +String(key)[1];
  if (notation === "fdi") return String(key);
  if (notation === "universal") {
    const codes = { 1: 9 - p, 2: 8 + p, 3: 25 - p, 4: 24 + p };
    if (codes[q]) return String(codes[q]);
    const idx = { 5: 5 - p, 6: 4 + p, 7: 15 - p, 8: 14 + p };
    return "ABCDEFGHIJKLMNOPQRST"[idx[q]];
  }
  return q <= 4 ? String(p) : "ABCDE"[p - 1];
}
export function toothLabel(key, notation) {
  if (notation === "fdi") return String(key);
  if (notation === "universal") return "#" + toothShort(key, notation);
  return ({ 1: "UR", 2: "UL", 3: "LL", 4: "LR", 5: "UR", 6: "UL", 7: "LL", 8: "LR" }[+String(key)[0]]) + toothShort(key, notation);
}
export function toothName(key) {
  const q = +String(key)[0], p = +String(key)[1], primary = q >= 5;
  const arch = [1, 2, 5, 6].includes(q) ? "Upper" : "Lower", side = [1, 4, 5, 8].includes(q) ? "right" : "left";
  const names = primary ? ["central incisor", "lateral incisor", "canine", "first molar", "second molar"] : ["central incisor", "lateral incisor", "canine", "first premolar", "second premolar", "first molar", "second molar", "third molar"];
  return `${arch} ${side} ${primary ? "primary " : ""}${names[p - 1] || ""}`.trim();
}
export const quadrantOf = (key) => ({ 1: "UR", 2: "UL", 3: "LL", 4: "LR", 5: "UR", 6: "UL", 7: "LL", 8: "LR" }[+String(key)[0]]);
export const isAnterior = (key) => +String(key)[1] <= 3;
export const topSurface = (key) => (isAnterior(key) ? "I" : "O");
export function surfaceName(key, s) {
  const ant = isAnterior(key), upper = [1, 2, 5, 6].includes(+String(key)[0]);
  return { M: "Mesial", D: "Distal", F: ant ? "Labial" : "Buccal", L: upper ? "Palatal" : "Lingual", I: "Incisal", O: "Occlusal" }[s] || s;
}
export function keysForDentition(d) {
  if (d === "primary") return PRIMARY_ORDER;
  if (d === "mixed") return [...QUADRANT_ORDER, ...PRIMARY_ORDER];
  return QUADRANT_ORDER;
}
export const pretty = (code) => String(code || "").replace(/_/g, " ");
export const emptyChart = () => ({ schemaVersion: 2, catalogVersion: "fdi-v1", dentition: "permanent", teeth: {} });
const keysOf = (item) => String(item.tooth || "").split(/[,\s]+/).filter((k) => /^([1-4][1-8]|[5-8][1-5])$/.test(k));

/** Rendering-only chart: adds treated flags for completed care (same as Android displayChart). */
export function displayChart(p) {
  const chart = clone(p.chart || emptyChart());
  const teeth = obj(chart, "teeth");
  const treated = new Set();
  (p.performedProcedures || []).filter((x) => !x.voided).forEach((x) => keysOf(x).forEach((k) => treated.add(k)));
  (p.treatments || []).filter((t) => t.status === "Completed").forEach((t) => keysOf(t).forEach((k) => treated.add(k)));
  const d = chart.dentition || "permanent";
  for (const k of treated) {
    const ok = d === "permanent" ? k[0] <= "4" : d === "primary" ? k[0] >= "5" : true;
    if (ok) (teeth[k] ||= { assessment: "not_assessed", presence: "unknown" }).treated = true;
  }
  return chart;
}
export function toothFacts(p, key) {
  const t = p.chart?.teeth?.[key] || {};
  const findings = [...(t.findings || []).map(pretty)], restorations = [];
  for (const [s, o] of Object.entries(t.surfaces || {})) {
    (o.findings || []).forEach((f) => findings.push(`${pretty(f)} (${s})`));
    (o.restorations || []).forEach((r) => restorations.push(`${pretty(r)} (${s})`));
  }
  const items = (p.treatments || []).filter((x) => keysOf(x).includes(String(key))).sort((a, b) => (b.at || 0) - (a.at || 0));
  const open = items.filter((x) => OPEN_TREATMENT.includes(x.status));
  const done = items.filter((x) => x.status === "Completed");
  let fee = 0, got = 0;
  const refs = new Set(items.map(treatmentRef));
  for (const r of refs) { const inv = treatmentInvoice(p, r); if (inv) { fee += invoiceTotal(inv); got += paid(inv) - refunded(inv); } }
  return {
    key, presence: t.presence || "unknown", assessment: t.assessment || "not_assessed", replacement: t.replacement || "none",
    lost: LOST.includes(t.presence) && (!t.replacement || t.replacement === "none"), findings, restorations, items, open, done,
    fee, paid: got, due: Math.max(0, fee - got), lastAt: done[0]?.performedAt || done[0]?.at || 0, surfaces: t.surfaces || {},
  };
}
export function chartCounts(p) {
  const chart = displayChart(p);
  let treated = 0, planned = 0, findings = 0, missing = 0, assessed = 0;
  for (const [k, t] of Object.entries(chart.teeth || {})) {
    if (LOST.includes(t.presence)) missing++;
    if (t.treated) treated++;
    if ((t.treatmentStates || []).length || (p.treatments || []).some((x) => x.tooth === k && OPEN_TREATMENT.includes(x.status))) planned++;
    if ((t.findings || []).length || Object.values(t.surfaces || {}).some((o) => (o.findings || []).length)) findings++;
    if (t.assessment === "assessed") assessed++;
  }
  return { treated, planned, findings, missing, assessed };
}

/** Recompute chart treatmentStates from treatments (ClinicStore.save does this before every save). */
export function normalizeChart(p) {
  if (!p.chart) return;
  const c = p.chart;
  c.schemaVersion = 2; c.catalogVersion = "fdi-v1"; c.numberingSystem = "FDI";
  c.chartId = p.id; c.patientId = p.id;
  const teeth = obj(c, "teeth");
  for (const k of Object.keys(teeth)) teeth[k].treatmentStates = [];
  const map = { Planned: "planned", Accepted: "accepted", "In progress": "in_progress", Deferred: "deferred", Referred: "referred" };
  for (const t of p.treatments || []) {
    const s = map[t.status], tooth = teeth[t.tooth];
    if (s && tooth && !tooth.treatmentStates.includes(s)) tooth.treatmentStates.push(s);
  }
}

// ---------------------------------------------------------------- record / edit treatment
function ensureSlot(p, key) {
  const teeth = obj(obj(p, "chart"), "teeth");
  if (!p.chart.schemaVersion) Object.assign(p.chart, { schemaVersion: 2, catalogVersion: "fdi-v1", dentition: p.chart.dentition || "permanent" });
  const d = p.chart.dentition || "permanent";
  if (d === "permanent" && key[0] >= "5") p.chart.dentition = "mixed";
  if (d === "primary" && key[0] <= "4") p.chart.dentition = "mixed";
  const slot = teeth[key] || (teeth[key] = { assessment: "assessed", presence: "present" });
  if (slot.assessment === "not_assessed") slot.assessment = "assessed";
  if (!slot.presence || slot.presence === "unknown") slot.presence = "present";
  return slot;
}
function applyOutcome(p, key, name, surfaces) {
  const tooth = obj(obj(obj(p, "chart"), "teeth"), key);
  tooth.assessment = "assessed";
  const option = PROCEDURES.find((o) => o[0].toLowerCase() === name.toLowerCase());
  if (/^extraction$/i.test(name)) { tooth.presence = "extracted"; delete tooth.surfaces; tooth.findings = []; return; }
  if (/^implant$/i.test(name)) { if (LOST.includes(tooth.presence)) tooth.replacement = "implant"; return; }
  if (!tooth.presence || tooth.presence === "unknown") tooth.presence = "present";
  if (LOST.includes(tooth.presence)) return;
  if (/^root canal$/i.test(name)) { const f = arr(tooth, "findings"); if (!f.includes("endodontically_treated")) f.push("endodontically_treated"); }
  const restoration = option?.[1];
  if (!restoration) return;
  for (const s of surfaces.length ? surfaces : [topSurface(key)]) {
    const o = obj(obj(tooth, "surfaces"), s);
    o.assessment = "assessed"; arr(o, "findings");
    const r = arr(o, "restorations"); if (!r.includes(restoration)) r.push(restoration);
  }
}
/** Adds one treatment per tooth (shared groupId). Returns the history text. */
export function recordTreatment(p, { teeth, procedure, status, surfaces, doctor, at, notes }, notation) {
  const name = procedure.trim();
  if (!name) throw new Error("Choose or type a procedure");
  if (!teeth.length) throw new Error("Select at least one tooth");
  if (status === "Completed" && !doctor.trim()) throw new Error("Record the doctor who performed the treatment");
  const group = uid();
  for (const key of teeth) {
    ensureSlot(p, key);
    const top = topSurface(key);
    const codes = [...new Set(surfaces.map((s) => (s === "Top" ? top : s)))];
    const surfaceText = codes.length ? codes.join("") : "Whole tooth";
    const id = uid();
    const item = { id, groupId: group, tooth: key, surface: surfaceText, procedure: name, notes: notes.trim(), status, at, doctor: doctor.trim(),
      events: [{ id: uid(), to: status, at: Date.now(), doctor: doctor.trim() }] };
    if (status === "Completed") {
      item.performedAt = at;
      arr(p, "performedProcedures").push({ id: uid(), treatmentId: id, tooth: key, surface: surfaceText, procedure: name, doctor: doctor.trim(), outcome: notes.trim() || `${name} completed`, at });
      applyOutcome(p, key, name, codes);
    }
    arr(p, "treatments").push(item);
  }
  return `Treatment recorded · ${name} · ${status} · ${teeth.map((k) => `${toothLabel(k, notation)} (FDI ${k})`).join(", ")}`;
}
export function editTreatment(p, id, { procedure, status, surfaces, doctor, at, notes }, notation) {
  const item = (p.treatments || []).find((t) => t.id === id);
  if (!item) throw new Error("This treatment no longer exists. Reopen the patient.");
  const key = item.tooth, top = topSurface(key), name = procedure.trim();
  const codes = [...new Set(surfaces.map((s) => (s === "Top" ? top : s)))];
  const surfaceText = codes.length ? codes.join("") : "Whole tooth";
  if (status === "Completed" && !doctor.trim()) throw new Error("Record the doctor who performed the treatment");
  const wasCompleted = item.status === "Completed";
  const previous = {};
  for (const f of ["procedure", "status", "surface", "doctor", "at", "performedAt", "notes"]) if (f in item) previous[f] = item[f];
  Object.assign(item, { procedure: name, status, surface: surfaceText, doctor: doctor.trim(), at, notes: notes.trim() });
  if (status === "Completed") item.performedAt = at; else delete item.performedAt;
  arr(item, "events").push({ id: uid(), to: status, at: Date.now(), doctor: doctor.trim(), edit: true, previous });
  const performed = arr(p, "performedProcedures"), linked = performed.filter((x) => x.treatmentId === id);
  if (status === "Completed") {
    let current = linked[0];
    if (!current) { current = { id: uid(), treatmentId: id, tooth: key }; performed.push(current); }
    Object.assign(current, { surface: surfaceText, procedure: name, doctor: doctor.trim(), outcome: notes.trim() || `${name} completed`, at });
    delete current.voided;
    linked.slice(1).forEach((x) => (x.voided = true));
    if (!wasCompleted || String(previous.procedure).toLowerCase() !== name.toLowerCase()) applyOutcome(p, key, name, codes);
  } else linked.forEach((x) => (x.voided = true));
  const changes = [
    previous.procedure !== name && `procedure ${previous.procedure} → ${name}`, previous.status !== status && `status ${previous.status} → ${status}`,
    previous.surface !== surfaceText && `surface ${previous.surface} → ${surfaceText}`, previous.doctor !== doctor.trim() && `doctor → ${doctor.trim()}`,
    previous.at !== at && "date changed", (previous.notes || "") !== notes.trim() && "notes updated",
  ].filter(Boolean);
  return `Treatment edited · ${toothLabel(key, notation)} (FDI ${key}) · ${changes.join("; ") || "no changes"}`;
}
/** Treatments tab workflow transition (ClinicalWorkspace). */
export function moveTreatment(p, id, next, values) {
  const t = p.treatments.find((x) => x.id === id);
  const from = t.status;
  t.status = next;
  arr(t, "events").push({ ...values, id: uid(), from, to: next, at: Date.now() });
  if (next === "Completed") {
    t.performedAt = Date.now(); t.doctor = values.doctor;
    arr(p, "performedProcedures").push({ ...values, id: uid(), treatmentId: t.id, tooth: t.tooth, surface: t.surface, procedure: t.procedure, at: Date.now() });
    const restoration = values.restoration || "none";
    if (restoration !== "none") {
      const keys = String(t.surface || "").split("").filter((s) => "MDFLIO".includes(s));
      if (!keys.length || t.surface === "Whole tooth") throw new Error("Record a specific surface before adding a restoration outcome");
      const tooth = obj(obj(obj(p, "chart"), "teeth"), t.tooth);
      for (const k of keys) { const o = obj(obj(tooth, "surfaces"), k); o.assessment = "assessed"; arr(o, "findings"); const r = arr(o, "restorations"); if (!r.includes(restoration)) r.push(restoration); }
    }
  }
  return `Treatment ${t.procedure}: ${from} → ${next}`;
}
/** Chart status editor (MainActivity tooth editor). */
export function applyChartStatus(p, key, { assessment, presence, replacement, surface, finding, restoration, clearFindings, procedure, note }) {
  const lost = LOST.includes(presence);
  if (lost && ["none", "not recorded"].includes(replacement) && (finding !== "none" || restoration !== "none"))
    throw new Error("Record tooth loss separately. Surface findings or restorations require a present tooth or a recorded replacement.");
  const teeth = obj(obj(p, "chart"), "teeth");
  const before = teeth[key];
  const tooth = teeth[key] || {};
  if (lost && (before?.presence !== presence || ["none", "not recorded"].includes(replacement))) { delete tooth.surfaces; tooth.findings = []; }
  tooth.assessment = assessment; tooth.presence = presence;
  if (replacement !== "not recorded") tooth.replacement = replacement;
  const blank = () => ({ assessment: "assessed", findings: [], restorations: [] });
  if (clearFindings) {
    if (surface === "Whole tooth") tooth.findings = [];
    else obj(tooth, "surfaces")[surface] = { assessment: "assessed", findings: [], restorations: tooth.surfaces?.[surface]?.restorations || [] };
  }
  if (restoration !== "none" && surface !== "Whole tooth") {
    const o = obj(tooth, "surfaces")[surface] || blank();
    if (!o.restorations.includes(restoration)) o.restorations.push(restoration);
    tooth.surfaces[surface] = o;
  }
  if (finding !== "none") {
    if (surface === "Whole tooth") { const f = arr(tooth, "findings"); if (!f.includes(finding)) f.push(finding); }
    else { const o = obj(tooth, "surfaces")[surface] || blank(); arr(o, "findings"); if (!o.findings.includes(finding)) o.findings.push(finding); tooth.surfaces[surface] = o; }
  }
  if (tooth.surfaces && !Object.keys(tooth.surfaces).length) delete tooth.surfaces;
  teeth[key] = tooth;
  if (procedure.trim()) { tooth.treatmentStates = ["planned"]; arr(p, "treatments").push({ id: uid(), tooth: key, surface, procedure: procedure.trim(), notes: note, status: "Planned", at: Date.now() }); }
  return `Tooth ${key}: ${assessment}; ${presence}; replacement ${replacement}; ${surface}; finding ${finding}; restoration ${restoration}; findings resolved=${clearFindings}${procedure.trim() ? "; planned " + procedure.trim() : ""}${note ? "; " + note : ""}`;
}

// ---------------------------------------------------------------- treatment fees paid over visits
export const treatmentRef = (t) => t.groupId || t.id;
export const treatmentInvoice = (p, ref) => [...(p.invoices || [])].reverse().find((v) => v.treatmentRef === ref && v.status !== "Voided");
export function treatmentFee(p, t) {
  const inv = treatmentInvoice(p, treatmentRef(t));
  if (!inv) return null;
  const total = invoiceTotal(inv), got = paid(inv) - refunded(inv);
  return { total, paid: got, due: Math.max(0, total - got), invoice: inv };
}
export function saveTreatmentPayment(p, t, { fee, amount, method, note, at }, config, notation) {
  const currency = cur(config), ref = treatmentRef(t);
  const group = (p.treatments || []).filter((x) => treatmentRef(x) === ref);
  const teeth = [...new Set(group.map((x) => x.tooth))];
  const existing = treatmentInvoice(p, ref);
  const already = existing ? paid(existing) - refunded(existing) : 0;
  if (!(fee > 0)) throw new Error("The fee must be more than zero");
  if (fee < already) throw new Error(`The fee can't be less than the ${money(already, currency)} already paid`);
  if (amount != null) {
    if (!(amount > 0)) throw new Error("Enter the amount paid");
    if (already + amount > fee) throw new Error(`That is more than the ${money(fee - already, currency)} still due. Raise the total fee first.`);
  }
  const description = `${t.procedure} · ` + teeth.map((k) => `${toothLabel(k, notation)} (FDI ${k})`).join(", ");
  const actions = [];
  let target = existing;
  if (!target) {
    target = { id: uid(), treatmentRef: ref, treatmentId: t.id, description, currency, lines: [{ id: uid(), description, quantity: 1, unitPrice: fee }],
      discount: 0, taxBps: 0, amount: fee, at: Date.now(), status: "Issued", payments: [], refunds: [] };
    arr(p, "invoices").push(target);
    actions.push(`fee ${money(fee, currency)}`);
  } else if (invoiceTotal(existing) !== fee) {
    const before = invoiceTotal(existing);
    if (existing.lines?.[0]) { existing.lines[0].unitPrice = fee; existing.lines[0].quantity = 1; }
    existing.amount = fee;
    arr(existing, "feeChanges").push({ id: uid(), from: before, to: fee, at: Date.now() });
    actions.push(`fee changed ${money(before, currency)} → ${money(fee, currency)}`);
  }
  if (amount != null) {
    arr(target, "payments").push({ id: uid(), amount, method, reference: note.trim(), at, treatmentRef: ref });
    actions.push(`payment ${money(amount, currency)} (${method})`);
  }
  if (!actions.length) throw new Error("Nothing to save. Enter an amount paid, or change the fee.");
  return `Treatment payment · ${t.procedure} · ${actions.join("; ")}`;
}
export function serviceFee(config, procedure) {
  const s = (config.services || []).find((x) => x.active !== false && String(x.name).toLowerCase() === String(procedure).toLowerCase());
  return s ? Number(s.fee) || 0 : null;
}

// ---------------------------------------------------------------- appointment slots
export function sessionsOn(config, date) {
  return (config.availability || []).filter((s) => {
    if (s.active === false || !s.startDate || !s.endDate) return false;
    const wd = s.weekdays || [], closed = s.exceptDates || [];
    return date >= s.startDate && date <= s.endDate && (!wd.length || wd.includes(dowOf(date))) && !closed.includes(date);
  });
}
/** Slots for a day: from sessions, minus active appointments (staff) or anonymous busy times (patients). */
export function slotsFor(config, patients, date, busy = [], now = Date.now()) {
  const zone = tz(config);
  const bookings = {};
  const add = (d, b) => (bookings[d] ||= []).push(b);
  for (const p of patients || []) {
    if (p.archived) continue;
    for (const a of p.appointments || []) {
      if (!ACTIVE_APPOINTMENTS.includes(a.status)) continue;
      let s; try { s = appointmentMillis(a, config); } catch { continue; }
      add(String(a.doctor || "").trim().toLowerCase(), { s, e: s + Number(a.durationMinutes || 30) * 60000, patientId: p.id, patientName: p.name, status: a.status, id: a.id });
    }
  }
  for (const b of busy || []) add(String(b.d || "").trim().toLowerCase(), { s: Number(b.s), e: Number(b.e), status: "Scheduled" });
  const out = [];
  for (const session of sessionsOn(config, date)) {
    const step = Math.min(240, Math.max(5, Number(session.slotMinutes || 30)));
    const from = minutesOf(session.start), to = minutesOf(session.end);
    const doctor = String(session.doctor || "").trim();
    for (let m = from; m + step <= to; m += step) {
      const startAt = zonedMs(date, hm(m), zone), endAt = startAt + step * 60000;
      const booked = (bookings[doctor.toLowerCase()] || []).find((b) => b.s < endAt && startAt < b.e);
      out.push({ sessionId: session.id, doctor, room: session.room || "", date, time: hm(m), minutes: m, startAt, durationMinutes: step,
        available: !booked && startAt > now, patientId: booked?.patientId, patientName: booked?.patientName, appointmentStatus: booked?.status, appointmentId: booked?.id });
    }
  }
  return out.sort((a, b) => a.startAt - b.startAt || a.doctor.localeCompare(b.doctor));
}
export function busyTimes(patients, config, now = Date.now()) {
  const out = [];
  for (const p of patients) {
    if (p.archived) continue;
    for (const a of p.appointments || []) {
      if (!ACTIVE_APPOINTMENTS.includes(a.status)) continue;
      let s; try { s = appointmentMillis(a, config); } catch { continue; }
      if (s < now - 86400000) continue;
      out.push({ s, e: s + Number(a.durationMinutes || 30) * 60000, d: String(a.doctor || "").trim().toLowerCase() });
    }
  }
  return out.sort((a, b) => a.s - b.s || a.d.localeCompare(b.d)).slice(0, 2000);
}
export function clinicians(config) {
  const names = (config.staff || []).filter((s) => s.active !== false && ["dentist", "admin", "administrator", "owner"].includes(s.role) && s.name).map((s) => s.name.trim());
  return [...new Set(names)];
}

// ---------------------------------------------------------------- patient app logins
export const LOGIN_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
export const PASSWORD_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";
export function randomText(alphabet, n) { const b = crypto.getRandomValues(new Uint32Array(n)); return [...b].map((x) => alphabet[x % alphabet.length]).join(""); }
export const normalizeLoginId = (v) => String(v || "").trim().toUpperCase();
export const validLoginId = (v) => /^[A-Za-z0-9]{4,12}$/.test(String(v || "").trim());
export const passwordProblem = (v) => (String(v).length < 6 ? "Use at least 6 characters" : /\s/.test(v) ? "Spaces are not allowed" : null);
export const portalKey = (loginId, password) => sha256Hex(`dental-care-portal:v1:${normalizeLoginId(loginId)}:${password}`);
export function patientView(p) {
  const keep = ["id", "name", "dob", "phone", "chart", "treatments", "performedProcedures", "appointments", "invoices", "updatedAt"];
  const out = {};
  for (const k of keep) if (k in p) out[k] = clone(p[k]);
  (out.treatments || []).forEach((t) => { delete t.events; delete t.amendments; });
  out.appointments ||= [];
  return out;
}
/** Applies patient-app booking / cancel requests to the staff copy of the patient (ClinicCloud.applyPortalRequests). */
export function applyPortalRequests(p, requests, config, patients) {
  const portal = obj(p, "portal");
  const done = new Set(portal.handled || []);
  const applied = new Set();
  const appointments = arr(p, "appointments"), now = Date.now();
  for (const r of requests) {
    if (!r.id) continue;
    if (done.has(r.id)) { applied.add(r.id); continue; }
    if (r.type === "book" && r.appointment && !appointments.some((a) => a.id === r.appointment.id)) {
      const a = r.appointment, start = Number(a.startAt), end = start + Number(a.durationMinutes || 30) * 60000;
      const clash = patients.filter((o) => o.id !== p.id).some((o) => (o.appointments || []).some((x) => {
        if (!["Scheduled", "Confirmed", "Checked in", "In progress"].includes(x.status) || String(x.doctor).toLowerCase() !== String(a.doctor).toLowerCase()) return false;
        let s; try { s = appointmentMillis(x, config); } catch { return false; }
        return s < end && start < s + Number(x.durationMinutes || 30) * 60000;
      }));
      const consent = p.communicationConsent === true;
      appointments.push({ ...clone(a), status: clash ? "Requested" : "Scheduled", consent, reminderEnabled: consent && config.remindersEnabled === true });
      arr(p, "history").push({ id: uid(), at: now, text: `Patient app: booked ${a.date} with ${a.doctor}` + (clash ? " (time no longer free; please confirm)" : "") });
    }
    if (r.type === "cancel") {
      const a = appointments.find((x) => x.id === r.appointmentId);
      if (a && ["Scheduled", "Confirmed", "Requested"].includes(a.status)) {
        Object.assign(a, { status: "Cancelled", cancelledBy: "patient", cancelledAt: r.at || now });
        arr(p, "history").push({ id: uid(), at: now, text: `Patient app: cancelled ${a.date}` });
      }
    }
    done.add(r.id); applied.add(r.id);
  }
  portal.handled = [...done].slice(-100);
  return applied;
}
export const PLAN = { perTier: 1000, maxTier: 10, offerUsd: 5, regularUsd: 10 };
export const requiredTier = (n) => Math.min(PLAN.maxTier, Math.max(1, Math.ceil(n / PLAN.perTier)));
