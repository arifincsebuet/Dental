"use strict";

// One patient document, one clinic configuration document. No event subcollections.
const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");
const { getStorage } = require("firebase-admin/storage");
const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { onSchedule } = require("firebase-functions/v2/scheduler");
const { setGlobalOptions } = require("firebase-functions/v2");
const { createHash } = require("node:crypto");
initializeApp();
setGlobalOptions({ region: process.env.FUNCTION_REGION || "us-central1", maxInstances: 4 });
const db = getFirestore();
const callOptions = { enforceAppCheck: process.env.ENFORCE_APP_CHECK === "true", timeoutSeconds: 60 };
const MAX_BYTES = 750000;
const MAX_HISTORY = 3000;
const MAX_RECEIPTS = 128;
const ROLES = ["owner", "admin", "administrator", "dentist", "assistant", "receptionist", "cashier", "auditor"];
const PROFILE = ["name", "phone", "dob", "allergies", "address", "gender", "emergencyContact", "consent", "communicationConsent", "reminderConsent", "medicalHistory", "medications"];
const CLINICAL = ["story", "chart", "treatments", "encounters", "performedProcedures", "notes", "medicalAlerts", "images", "allergies", "medicalHistory", "medications"];
const ROOT_FIELDS = new Set(["id", "schemaVersion", ...PROFILE, ...CLINICAL, "appointments", "invoices", "history", "amendmentReason", "archived", "archiveReference", "importedFrom"]);
const INITIAL_CLINICAL = new Set(CLINICAL);

function fail(message, code = "invalid-argument", details) { throw new HttpsError(code, message, details); }
function assert(condition, message, code, details) { if (!condition) fail(message, code, details); }
function identifier(value, name) { assert(typeof value === "string" && /^[A-Za-z0-9_-]{1,128}$/.test(value), `Invalid ${name}`); return value; }
function object(value, name) { assert(value && typeof value === "object" && !Array.isArray(value), `${name} must be an object`); return value; }
function keysOnly(value, allowed, name) { for (const key of Object.keys(value)) assert(allowed.includes(key), `Unknown ${name} field ${key}`); }
function array(value, name, max = 1000) { assert(Array.isArray(value) && value.length <= max, `${name} must be a bounded array`); return value; }
function text(value, name, max = 20000) { assert(typeof value === "string" && value.length <= max, `Invalid ${name}`); return value; }
function money(value, name) { assert(Number.isSafeInteger(value) && value >= 0, `${name} must be nonnegative integer minor units`); return value; }
function stable(value) {
  if (value === null || typeof value !== "object") return JSON.stringify(value);
  if (Array.isArray(value)) return `[${value.map(stable).join(",")}]`;
  return `{${Object.keys(value).sort().map(key => `${JSON.stringify(key)}:${stable(value[key])}`).join(",")}}`;
}
function same(a, b) { return stable(a === undefined ? null : a) === stable(b === undefined ? null : b); }
function fingerprint(value) { return createHash("sha256").update(stable(value)).digest("hex"); }
function clone(value) { return JSON.parse(JSON.stringify(value)); }
function previousChanges(before, after) {
  if (same(before, after)) return null;
  if (Array.isArray(before) && Array.isArray(after) && before.every(item => item && typeof item === "object" && item.id)) {
    return { changedOrRemoved: before.filter(item => !same(item, after.find(next => next.id === item.id))), addedIds: after.filter(item => !before.some(old => old.id === item.id)).map(item => item.id) };
  }
  if (before && after && typeof before === "object" && typeof after === "object" && !Array.isArray(before) && !Array.isArray(after)) {
    const delta = {};
    for (const key of new Set([...Object.keys(before), ...Object.keys(after)])) if (!same(before[key], after[key])) delta[key] = previousChanges(before[key], after[key]);
    return delta;
  }
  return before === undefined ? null : before;
}
function sizeGuard(value) { assert(Buffer.byteLength(JSON.stringify(value), "utf8") <= MAX_BYTES, "Patient/clinic document is full. Export and arrange archival before more changes.", "resource-exhausted"); }
function actor(request) { assert(request.auth, "Authentication is required", "unauthenticated"); return request.auth.uid; }
function roleFor(clinicId, uid, clinic) {
  if (clinicId === uid) return { role: "owner", active: true };
  const member = clinic && clinic.config && clinic.config.members && clinic.config.members[uid];
  assert(member && member.active !== false && ROLES.includes(member.role), "You are not an active clinic member", "permission-denied");
  return { ...member, role: member.role === "administrator" ? "admin" : member.role };
}
function has(member, category) {
  if (["owner", "admin"].includes(member.role)) return true;
  if (member.role === "auditor") return false;
  if (category === "profile") return ["dentist", "assistant", "receptionist"].includes(member.role);
  if (category === "clinical") return member.role === "dentist" || (member.role === "assistant" && member.permissions && member.permissions.clinicalWrite === true);
  if (category === "billing") return member.role === "cashier";
  if (category === "appointments") return ["dentist", "assistant", "receptionist"].includes(member.role);
  return false;
}
function defaultConfig(uid) {
  return { name: "Dental Care", timeZone: "Asia/Dhaka", currency: "BDT", members: { [uid]: { role: "owner", active: true } }, staff: [], services: [], settings: { remindersEnabled: false, reminderLeadMinutes: 1440 } };
}
function visibleClinic(clinicId, clinic, uid) {
  const summary = {};
  for (const job of clinic && clinic.reminderJobs || []) summary[job.status] = (summary[job.status] || 0) + 1;
  return { clinicId, revision: clinic ? clinic.revision || 0 : 0, config: clinic ? clinic.config : defaultConfig(uid), reminderSummary: summary,
    reminderRebuildRequired: Boolean(clinic && clinic.reminderRebuildRequired) };
}
function normalized(value) { return String(value || "").toLowerCase().replace(/[ -]+/g, "_"); }
function substantiveChart(value) { if (!value) return value; const next = clone(value); for (const key of ["clinicId", "patientId", "chartId", "updatedAt", "updatedBy", "chartVersion"]) delete next[key]; return next; }
function without(value, keys) { const next = clone(value); for (const key of keys) delete next[key]; return next; }
function clinicSetting(clinic, key, fallback) { return clinic.config[key] ?? clinic.config.settings?.[key] ?? fallback; }
function readsClinical(member) { return ["owner", "admin", "dentist", "auditor"].includes(member.role) || (member.role === "assistant" && member.permissions && (member.permissions.clinicalRead === true || member.permissions.clinicalWrite === true)); }
function projectPatient(patient, member) {
  if (readsClinical(member)) return patient;
  const allowed = ["id", "schemaVersion", "name", "phone", "dob", "address", "gender", "emergencyContact", "communicationConsent", "revision", "updatedAt", "updatedBy", "clinicId", "archived"];
  if (["receptionist", "assistant"].includes(member.role)) allowed.push("appointments");
  if (["cashier", "receptionist"].includes(member.role)) allowed.push("invoices");
  const result = {};
  for (const key of allowed) if (patient[key] !== undefined) result[key] = patient[key];
  result.history = [];
  result.redacted = true;
  return result;
}

function validTooth(key) { return /^[1-4][1-8]$|^[5-8][1-5]$/.test(key); }
function validateChart(chart, patientId) {
  object(chart, "chart");
  assert(chart.schemaVersion === 2 && chart.catalogVersion === "fdi-v1", "Unsupported chart schema/catalogue");
  assert(["permanent", "primary", "mixed"].includes(chart.dentition), "Invalid chart dentition");
  const teeth = object(chart.teeth, "chart.teeth");
  assert(Object.keys(teeth).length <= 52, "Too many tooth slots");
  const findingCodes = ["caries", "fracture", "wear", "endodontically_treated", "periapical_finding", "other"];
  const restorations = ["composite", "amalgam", "glass_ionomer", "sealant", "crown", "inlay", "onlay", "other"];
  const assessments = ["not_assessed", "assessed", "unable_to_assess"];
  const presence = ["unknown", "present", "unerupted", "missing", "extracted", "exfoliated", "congenitally_absent"];
  function codes(values, allowed, name) { array(values, name, allowed.length); assert(new Set(values).size === values.length && values.every(code => allowed.includes(code)), `Invalid ${name}`); }
  for (const [key, tooth] of Object.entries(teeth)) {
    assert(validTooth(key), `Invalid tooth slot ${key}`);
    assert(chart.dentition === "mixed" || (chart.dentition === "permanent" ? Number(key[0]) <= 4 : Number(key[0]) >= 5), "Recorded tooth does not belong to this chart inventory; use mixed dentition to retain both sets");
    object(tooth, `tooth ${key}`);
    assert(assessments.includes(tooth.assessment) && presence.includes(tooth.presence), `Invalid tooth state ${key}`);
    if (tooth.findings !== undefined) codes(tooth.findings, findingCodes, "findings");
    if (tooth.replacement !== undefined) assert(["none", "implant", "pontic", "removable_prosthesis"].includes(tooth.replacement), "Invalid replacement");
    if (tooth.surfaces !== undefined) {
      const surfaces = object(tooth.surfaces, "surfaces");
      const allowed = ["M", "D", "F", "L", Number(key[1]) <= 3 ? "I" : "O"];
      assert(Object.keys(surfaces).length <= 5, "Too many surfaces");
      for (const [surface, observation] of Object.entries(surfaces)) {
        assert(allowed.includes(surface), `Invalid surface ${surface} for tooth ${key}`);
        object(observation, "surface observation");
        assert(assessments.includes(observation.assessment), "Invalid surface assessment");
        codes(observation.findings, findingCodes, "surface findings");
        codes(observation.restorations, restorations, "surface restorations");
      }
    }
  }
  chart.patientId = patientId;
  chart.chartId = patientId;
  chart.numberingSystem = "FDI";
}

function appendOnly(oldItems, items, name) {
  const byId = new Map(items.map(item => [item.id, item]));
  for (const previous of oldItems) assert(byId.has(previous.id) && same(previous, byId.get(previous.id)), `${name} cannot be removed or overwritten; append a correction instead`, "failed-precondition");
}
function uniqueIds(items, name) { const ids = items.map(item => identifier(item.id, `${name}.id`)); assert(new Set(ids).size === ids.length, `Duplicate ${name} IDs`); }
function stampNewEntries(previous, entries, uid, now) {
  const old = new Map((previous || []).map(item => [item.id, item]));
  for (const item of entries || []) {
    const prior = old.get(item.id);
    if (!prior) { item.recordedBy = uid; item.recordedAt = now; }
    else {
      // Existing provenance belongs to the server, even on otherwise editable drafts.
      for (const field of ["recordedBy", "recordedAt"]) {
        if (Object.prototype.hasOwnProperty.call(prior, field)) item[field] = prior[field];
        else delete item[field];
      }
    }
    for (const field of ["events", "amendments", "payments", "refunds"]) if (Array.isArray(item[field])) stampNewEntries(prior && prior[field], item[field], uid, now);
  }
}
function validateInvoices(invoices, previous) {
  array(invoices, "invoices", 500); uniqueIds(invoices, "invoice");
  const oldById = new Map((previous || []).map(invoice => [invoice.id, invoice]));
  for (const old of previous || []) assert(invoices.some(invoice => invoice.id === old.id), "Invoices cannot be deleted", "failed-precondition");
  for (const invoice of invoices) {
    object(invoice, "invoice");
    assert(/^[A-Z]{3}$/.test(invoice.currency), "Invalid invoice currency");
    const amount = money(invoice.amount, "invoice amount");
    if (invoice.lines !== undefined) {
      const lines = array(invoice.lines, "invoice lines", 100); uniqueIds(lines, "invoice line");
      assert(lines.length > 0, "Invoice must contain at least one line");
      let subtotal = 0n;
      for (const line of lines) {
        assert(Number.isInteger(line.quantity) && line.quantity >= 1 && line.quantity <= 999, "Line quantity must be 1–999");
        money(line.unitPrice, "historical unit price"); text(line.description, "line description", 1000);
        subtotal += BigInt(line.quantity) * BigInt(line.unitPrice);
      }
      const discount = money(invoice.discount || 0, "discount");
      const taxBps = invoice.taxBps || 0;
      assert(Number.isInteger(taxBps) && taxBps >= 0 && taxBps <= 10000 && BigInt(discount) <= subtotal, "Invalid discount or tax rate");
      const taxable = subtotal - BigInt(discount);
      const total = taxable + (taxable * BigInt(taxBps) + 5000n) / 10000n;
      assert(total <= BigInt(Number.MAX_SAFE_INTEGER) && BigInt(amount) === total, "Invoice total does not match its lines/discount/tax");
    }
    const payments = array(invoice.payments || [], "payments", 1000); uniqueIds(payments, "payment");
    for (const payment of payments) { money(payment.amount, "payment"); assert(payment.amount > 0, "Payment must be positive"); text(payment.method, "payment method", 128); }
    const refunds = array(invoice.refunds || [], "refunds", 1000); uniqueIds(refunds, "refund");
    for (const refund of refunds) { money(refund.amount, "refund"); assert(refund.amount > 0, "Refund must be positive"); text(refund.reason, "refund reason", 2000); assert(refund.reason.trim(), "Refund reason is required"); assert(payments.some(payment => payment.id === refund.paymentId), "Refund must reference its original payment"); }
    const paid = payments.reduce((sum, payment) => sum + payment.amount, 0);
    const refunded = refunds.reduce((sum, refund) => sum + refund.amount, 0);
    assert(Number.isSafeInteger(paid) && Number.isSafeInteger(refunded) && paid - refunded <= amount && refunded <= paid, "Payment/refund totals exceed invoice/receipts");
    for (const payment of payments) assert(refunds.filter(refund => refund.paymentId === payment.id).reduce((sum, refund) => sum + refund.amount, 0) <= payment.amount, "Refund exceeds the original payment");
    const old = oldById.get(invoice.id);
    if (old) {
      assert(invoice.amount === old.amount && invoice.currency === old.currency, "Issued invoice amount/currency cannot be rewritten; void and issue a correction", "failed-precondition");
      for (const field of ["description", "lines", "discount", "taxBps"]) assert(same(old[field], invoice[field]), `Issued invoice ${field} cannot be rewritten`, "failed-precondition");
      appendOnly(old.payments || [], payments, "Payments"); appendOnly(old.refunds || [], refunds, "Refunds");
      if (["void", "voided"].includes(normalized(old.status))) assert(same(old, invoice), "Voided invoices are immutable", "failed-precondition");
    }
    if (["void", "voided"].includes(normalized(invoice.status))) assert(paid === refunded && typeof invoice.voidReason === "string" && invoice.voidReason.trim(), "Void requires a reason and all receipts refunded");
  }
}

function validateClinical(patient, previous, member) {
  validateChart(patient.chart, patient.id);
  for (const key of Object.keys(previous && previous.chart && previous.chart.teeth || {})) assert(patient.chart.teeth[key], "Recorded tooth slots cannot be deleted; record tooth loss or use an explicit correction", "failed-precondition");
  const treatments = array(patient.treatments || [], "treatments", 1000); uniqueIds(treatments, "treatment");
  const statuses = ["planned", "accepted", "in_progress", "completed", "deferred", "declined", "cancelled", "referred"];
  const transitions = {
    planned: ["planned", "accepted", "in_progress", "completed", "deferred", "declined", "cancelled", "referred"],
    accepted: ["accepted", "in_progress", "completed", "deferred", "declined", "cancelled", "referred"],
    in_progress: ["in_progress", "completed", "deferred", "cancelled", "referred"],
    deferred: ["deferred", "planned", "accepted", "in_progress", "cancelled", "referred"],
    referred: ["referred", "planned", "accepted", "in_progress", "completed", "cancelled"],
    declined: ["declined"], cancelled: ["cancelled"], completed: ["completed"]
  };
  const oldItems = new Map((previous && previous.treatments || []).map(item => [item.id, item]));
  for (const old of oldItems.values()) assert(treatments.some(item => item.id === old.id), "Treatment history cannot be deleted", "failed-precondition");
  for (const item of treatments) {
    assert(validTooth(item.tooth), "Treatment tooth reference is invalid");
    assert(patient.chart.teeth[item.tooth], "Treatment target must exist in the recorded chart");
    assert(statuses.includes(normalized(item.status)), "Invalid treatment state");
    text(item.procedure, "procedure", 500);
    const old = oldItems.get(item.id);
    if (old) {
      assert(transitions[normalized(old.status)] && transitions[normalized(old.status)].includes(normalized(item.status)), "Invalid treatment transition", "failed-precondition");
      appendOnly(old.events || [], item.events || [], "Treatment events");
      if (normalized(old.status) === "completed" && !same(without(old, ["events", "amendments", "amendmentReason"]), without(item, ["events", "amendments", "amendmentReason"]))) {
        assert(has(member, "clinical") && typeof item.amendmentReason === "string" && item.amendmentReason.trim(), "Completed procedures require an explicit amendment reason", "failed-precondition");
      }
    }
  }
  if (patient.encounters !== undefined) {
    const encounters = array(patient.encounters, "encounters", 1000); uniqueIds(encounters, "encounter");
    for (const encounter of encounters) {
      assert(["draft", "finalized"].includes(normalized(encounter.status)), "Invalid encounter status");
      assert(text(encounter.doctor, "encounter clinician", 200).trim(), "Encounter clinician is required");
      assert(text(encounter.complaint, "encounter complaint", 20000).trim(), "Encounter complaint is required");
      for (const field of ["examination", "diagnosis", "plan"]) if (encounter[field] !== undefined) text(encounter[field], `encounter ${field}`, 50000);
      if (encounter.at !== undefined) assert(Number.isSafeInteger(encounter.at) && encounter.at >= 0, "Invalid encounter observation time");
      const amendments = array(encounter.amendments || [], "encounter amendments", 1000); uniqueIds(amendments, "encounter amendment");
      for (const amendment of amendments) {
        assert(text(amendment.reason, "amendment reason", 2000).trim(), "Amendment reason is required");
        assert(text(amendment.text, "amendment correction", 50000).trim(), "Amendment correction is required");
      }
    }
    for (const old of previous && previous.encounters || []) {
      const next = encounters.find(item => item.id === old.id);
      assert(next, "Encounter history cannot be deleted", "failed-precondition");
      appendOnly(old.amendments || [], next.amendments || [], "Encounter amendments");
      if (normalized(old.status) === "finalized") assert(same(without(old, ["amendments"]), without(next, ["amendments"])), "Finalized encounter base fields cannot be rewritten; append an amendment", "failed-precondition");
      for (const amendment of next.amendments || []) assert(typeof amendment.reason === "string" && amendment.reason.trim(), "Amendment reason is required");
    }
  }
  if (patient.performedProcedures !== undefined) {
    const performed = array(patient.performedProcedures, "performed procedures", 1000); uniqueIds(performed, "performed procedure");
    for (const procedure of performed) { assert(validTooth(procedure.tooth), "Performed-procedure tooth is invalid"); assert(treatments.some(item => item.id === procedure.treatmentId && item.tooth === procedure.tooth && normalized(item.status) === "completed"), "Performed procedure must reference a completed treatment for the same tooth"); }
    for (const old of previous && previous.performedProcedures || []) {
      const next = performed.find(item => item.id === old.id);
      assert(next && same(without(old, ["amendments"]), without(next, ["amendments"])), "Performed care cannot be removed or rewritten; append an amendment", "failed-precondition");
      appendOnly(old.amendments || [], next.amendments || [], "Performed-procedure amendments");
      for (const amendment of next.amendments || []) assert(typeof amendment.reason === "string" && amendment.reason.trim(), "Procedure amendment reason is required");
    }
  }
  // Backend-owned chart workflow projection; completed care is not a whole-tooth state.
  for (const tooth of Object.values(patient.chart.teeth)) tooth.treatmentStates = [];
  for (const item of treatments) {
    const state = normalized(item.status);
    if (["planned", "accepted", "in_progress", "deferred", "referred"].includes(state)) {
      const tooth = patient.chart.teeth[item.tooth];
      if (tooth && !tooth.treatmentStates.includes(state)) tooth.treatmentStates.push(state);
    }
  }
}

function localTime(value, timeZone) {
  if (Number.isSafeInteger(value)) return value;
  assert(typeof value === "string", "Appointment start time is required");
  if (/T.*(?:Z|[+-]\d\d:\d\d)$/.test(value)) { const parsed = Date.parse(value); assert(Number.isFinite(parsed), "Invalid appointment time"); return parsed; }
  const match = /^(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2})$/.exec(value);
  assert(match, "Appointment date must be YYYY-MM-DD HH:mm or an ISO instant");
  const fields = match.slice(1).map(Number);
  const utc = Date.UTC(fields[0], fields[1] - 1, fields[2], fields[3], fields[4]);
  const formatter = new Intl.DateTimeFormat("sv-SE", { timeZone, year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hourCycle: "h23" });
  let guess = utc;
  for (let attempt = 0; attempt < 3; attempt++) {
    const formatted = formatter.format(new Date(guess));
    const parts = formatted.match(/\d+/g).map(Number);
    const rendered = Date.UTC(parts[0], parts[1] - 1, parts[2], parts[3], parts[4]);
    guess += utc - rendered;
  }
  assert(formatter.format(new Date(guess)) === value, "Invalid or ambiguous local appointment time; use an ISO instant");
  return guess;
}
function scheduling(patient, clinicId, clinic, now) {
  const appointments = array(patient.appointments || [], "appointments", 1000); uniqueIds(appointments, "appointment");
  const zone = clinic.config.timeZone || "UTC";
  const oldIndex = (clinic.scheduleIndex || []).filter(item => item.patientId !== patient.id && item.endAt > now - 86400000);
  const additions = [];
  for (const appointment of appointments) {
    const status = normalized(appointment.status);
    assert(["requested", "scheduled", "confirmed", "checked_in", "in_progress", "completed", "cancelled", "no_show"].includes(status), "Invalid appointment status");
    if (patient.archived === true) continue;
    if (!["scheduled", "confirmed", "checked_in", "in_progress"].includes(status)) continue;
    const startAt = localTime(appointment.startAt || appointment.startsAt || appointment.date, zone);
    const duration = appointment.durationMinutes === undefined ? 30 : appointment.durationMinutes;
    assert(Number.isInteger(duration) && duration >= 5 && duration <= 480, "Appointment duration must be 5–480 minutes");
    const endAt = startAt + duration * 60000;
    const clinician = String(appointment.doctorId || appointment.clinicianId || appointment.doctor || "").trim();
    assert(clinician, "Appointment clinician is required");
    const room = String(appointment.roomId || appointment.room || "").trim();
    appointment.startAt = startAt; appointment.endAt = endAt; appointment.durationMinutes = duration;
    if (endAt <= now - 86400000) continue;
    const revoked = patient.communicationConsent === false || patient.reminderConsent === false || patient.consent && patient.consent.reminders === false;
    const reminderConsent = !revoked && (appointment.consent === true || patient.communicationConsent === true || patient.reminderConsent === true || patient.consent && patient.consent.reminders === true);
    if (appointment.reminderHours !== undefined) assert(Number.isInteger(appointment.reminderHours) && appointment.reminderHours >= 1 && appointment.reminderHours <= 168, "Reminder hours must be 1–168");
    const candidate = { patientId: patient.id, appointmentId: appointment.id, clinician, room, startAt, endAt,
      reminderEnabled: appointment.reminderEnabled === true, reminderConsent: Boolean(reminderConsent), reminderHours: appointment.reminderHours ?? null };
    const buffer = Number(clinicSetting(clinic, "bufferMinutes", 0) || 0) * 60000;
    const conflict = [...oldIndex, ...additions].find(item => item.startAt < endAt + buffer && startAt < item.endAt + buffer && (item.clinician.toLowerCase() === clinician.toLowerCase() || (room && item.room.toLowerCase() === room.toLowerCase())));
    assert(!conflict, "Clinician or room overlaps another appointment", "already-exists", conflict ? { appointmentId: conflict.appointmentId, startAt: conflict.startAt } : undefined);
    additions.push(candidate);
  }
  const index = [...oldIndex, ...additions];
  assert(index.length <= 2000, "Active clinic schedule index is full; archive completed appointments before booking more", "resource-exhausted");
  return index;
}

exports.getClinic = onCall(callOptions, async request => {
  const uid = actor(request); const clinicId = identifier(request.data.clinicId || uid, "clinic ID");
  const snapshot = await db.doc(`workspaces/${clinicId}`).get();
  const clinic = snapshot.exists ? snapshot.data() : null;
  roleFor(clinicId, uid, clinic);
  return visibleClinic(clinicId, clinic, uid);
});

exports.getPatient = onCall(callOptions, async request => {
  const uid = actor(request); const clinicId = identifier(request.data.clinicId || uid, "clinic ID"); const patientId = identifier(request.data.patientId, "patient ID");
  const clinic = await db.doc(`workspaces/${clinicId}`).get(); const member = roleFor(clinicId, uid, clinic.exists ? clinic.data() : null);
  const snapshot = await db.doc(`workspaces/${clinicId}/patients/${patientId}`).get();
  assert(snapshot.exists, "Patient not found", "not-found");
  return { patient: projectPatient(snapshot.data(), member) };
});

exports.listPatients = onCall(callOptions, async request => {
  const uid = actor(request); const clinicId = identifier(request.data.clinicId || uid, "clinic ID");
  const pageSize = request.data.pageSize === undefined ? 50 : request.data.pageSize;
  assert(Number.isInteger(pageSize) && pageSize >= 1 && pageSize <= 100, "Page size must be 1–100");
  const clinic = await db.doc(`workspaces/${clinicId}`).get(); const member = roleFor(clinicId, uid, clinic.exists ? clinic.data() : null);
  let query = db.collection(`workspaces/${clinicId}/patients`).orderBy(require("firebase-admin/firestore").FieldPath.documentId()).limit(pageSize);
  if (request.data.afterId) query = query.startAfter(identifier(request.data.afterId, "patient cursor"));
  const snapshots = await query.get();
  return { patients: snapshots.docs.map(snapshot => projectPatient(snapshot.data(), member)), nextCursor: snapshots.size === pageSize ? snapshots.docs[snapshots.size - 1].id : null };
});

exports.updateClinic = onCall(callOptions, async request => {
  const uid = actor(request); const data = object(request.data, "request");
  const clinicId = identifier(data.clinicId || uid, "clinic ID"); const operationId = identifier(data.operationId, "operation ID");
  assert(Number.isSafeInteger(data.expectedRevision) && data.expectedRevision >= 0, "Invalid expected revision");
  const config = clone(object(data.config, "clinic config"));
  keysOnly(config, ["name", "timeZone", "currency", "members", "staff", "services", "settings", "taxBps", "bufferMinutes", "reminderHours", "remindersEnabled", "quietStart", "quietEnd", "phone", "address", "numberingSystem"], "clinic config");
  text(config.name, "clinic name", 200); assert(config.name.trim(), "Clinic name is required");
  assert(typeof config.currency === "string" && /^[A-Z]{3}$/.test(config.currency), "Invalid clinic currency");
  try { new Intl.DateTimeFormat("en", { timeZone: config.timeZone }).format(); } catch (_) { fail("Invalid clinic time zone"); }
  const members = object(config.members, "members"); assert(Object.keys(members).length <= 100, "Maximum 100 clinic members");
  for (const [memberId, member] of Object.entries(members)) { identifier(memberId, "member UID"); object(member, "member"); assert(ROLES.includes(member.role), "Invalid member role"); }
  assert(members[clinicId] && members[clinicId].active !== false && members[clinicId].role === "owner", "Clinic owner's exact UID must retain owner access");
  array(config.staff || [], "staff", 200); array(config.services || [], "services", 500);
  object(config.settings || (config.settings = {}), "settings");
  const lead = config.settings.reminderLeadMinutes === undefined ? (config.reminderHours === undefined ? 1440 : config.reminderHours * 60) : config.settings.reminderLeadMinutes;
  assert(Number.isInteger(lead) && lead >= 5 && lead <= 43200, "Reminder lead must be 5–43200 minutes");
  config.settings.reminderLeadMinutes = lead;
  const buffer = config.bufferMinutes ?? 0;
  assert(Number.isInteger(buffer) && buffer >= 0 && buffer <= 120, "Buffer must be 0–120 minutes");
  for (const field of ["quietStart", "quietEnd"]) if (config[field] !== undefined) assert(/^([01]\d|2[0-3]):[0-5]\d$/.test(config[field]), "Quiet hours must be HH:mm");
  const hash = fingerprint({ uid, clinicId, config, expectedRevision: data.expectedRevision });
  const ref = db.doc(`workspaces/${clinicId}`);
  return db.runTransaction(async tx => {
    const snapshot = await tx.get(ref); const old = snapshot.exists ? snapshot.data() : null;
    const member = roleFor(clinicId, uid, old);
    assert(["owner", "admin"].includes(member.role), "Only clinic administrators can change configuration", "permission-denied");
    const receipt = (old && old.receipts || []).find(item => item.id === operationId);
    if (receipt) { assert(receipt.hash === hash && receipt.actor === uid, "Operation ID cannot be reused with different content", "already-exists"); return visibleClinic(clinicId, old, uid); }
    assert((old && old.revision || 0) === data.expectedRevision, "Clinic configuration changed; reload and review before retrying", "aborted");
    if (member.role !== "owner") assert(same(old.config.members, config.members), "Only the owner can change membership", "permission-denied");
    const now = Date.now(); const revision = data.expectedRevision + 1;
    const next = { ...(old || {}), config, revision, updatedAt: now, updatedBy: uid,
      remindersEnabled: (config.remindersEnabled ?? config.settings.remindersEnabled) === true,
      nextReminderAt: old && old.nextReminderAt || Number.MAX_SAFE_INTEGER,
      receipts: [...(old && old.receipts || []).slice(-(MAX_RECEIPTS - 1)), { id: operationId, actor: uid, hash, revision, at: now }],
      audit: [...(old && old.audit || []), { id: operationId, actor: uid, at: now, action: "clinic_configuration_updated", revision }]
    };
    // Rebuild from the bounded schedule index; configuration changes need no patient scan.
    next.reminderJobs = rebuildClinicReminders(next, now);
    next.nextReminderAt = nextReminder(next.reminderJobs);
    next.reminderRebuildRequired = (next.scheduleIndex || []).some(item => item.startAt > now && !hasReminderMetadata(item));
    sizeGuard(next); tx.set(ref, next);
    return visibleClinic(clinicId, next, uid);
  });
});

exports.commitPatient = onCall(callOptions, async request => {
  const uid = actor(request); const data = object(request.data, "request");
  const clinicId = identifier(data.clinicId || uid, "clinic ID"); const patientId = identifier(data.patientId, "patient ID"); const operationId = identifier(data.operationId, "operation ID");
  assert(Number.isSafeInteger(data.expectedRevision) && data.expectedRevision >= 0, "Invalid expected revision");
  const input = clone(object(data.patient, "patient"));
  keysOnly(input, [...ROOT_FIELDS, "dirty", "remoteVersion", "cloudOwner", "revision", "updatedAt", "updatedBy", "clinicId", "operationId", "audit", "receipts", "redacted"], "patient");
  const patient = {};
  for (const key of Object.keys(input)) if (ROOT_FIELDS.has(key)) patient[key] = input[key];
  assert(patient.id === patientId && patient.schemaVersion === 3, "Patient identity or schema mismatch");
  if (patient.importedFrom !== undefined) identifier(patient.importedFrom, "import source patient ID");
  text(patient.name, "patient name", 300); assert(patient.name.trim(), "Patient name is required");
  for (const key of ["phone", "dob", "allergies", "story"]) if (patient[key] !== undefined) text(patient[key], key, key === "story" ? 100000 : 20000);
  array(patient.history || [], "history", MAX_HISTORY);
  uniqueIds(patient.history || [], "history");
  const hash = fingerprint({ uid, clinicId, patientId, patient, expectedRevision: data.expectedRevision });
  const patientRef = db.doc(`workspaces/${clinicId}/patients/${patientId}`); const clinicRef = db.doc(`workspaces/${clinicId}`);
  return db.runTransaction(async tx => {
    const [patientSnapshot, clinicSnapshot] = await Promise.all([tx.get(patientRef), tx.get(clinicRef)]);
    const old = patientSnapshot.exists ? patientSnapshot.data() : null;
    const clinic = clinicSnapshot.exists ? clinicSnapshot.data() : { config: defaultConfig(clinicId), revision: 0 };
    const member = roleFor(clinicId, uid, clinic);
    const receipt = (old && old.receipts || []).find(item => item.id === operationId);
    if (receipt) {
      assert(receipt.hash === hash && receipt.actor === uid, "Operation ID cannot be reused with different content", "already-exists");
      return { revision: old.revision, operationRevision: receipt.revision, updatedAt: old.updatedAt, patient: projectPatient(old, member) };
    }
    assert(member.role !== "auditor", "Auditors have read-only access", "permission-denied");
    assert((old && old.revision || 0) === data.expectedRevision, "Patient changed on another device. Reload and review your local draft.", "aborted", { currentRevision: old && old.revision || 0 });
    // Omitted role-hidden fields are preserved, never converted to empty clinical facts.
    if (old) for (const key of ROOT_FIELDS) if (patient[key] === undefined && old[key] !== undefined) patient[key] = clone(old[key]);
    if (!old && patient.chart === undefined) patient.chart = { schemaVersion: 2, catalogVersion: "fdi-v1", numberingSystem: "FDI", dentition: "permanent", teeth: {} };
    const changed = [...new Set([...PROFILE, ...CLINICAL, "appointments", "invoices", "archived", "archiveReference"])].filter(key => key === "chart" ? !same(substantiveChart(old && old.chart), substantiveChart(patient.chart)) : !same(old && old[key], patient[key]));
    for (const key of changed) {
      // Empty standard clinical defaults are permitted on receptionist registration.
      const emptyClinical = !old && INITIAL_CLINICAL.has(key) && ((Array.isArray(patient[key]) && patient[key].length === 0) || patient[key] === "" || (key === "chart" && Object.keys(patient.chart && patient.chart.teeth || {}).length === 0));
      if (emptyClinical) continue;
      const category = key === "invoices" ? "billing" : key === "appointments" ? "appointments" : CLINICAL.includes(key) ? "clinical" : "profile";
      assert(has(member, category), `Your role cannot change ${key}`, "permission-denied");
    }
    validateClinical(patient, old, member);
    validateInvoices(patient.invoices || [], old && old.invoices || []);
    const now = Date.now(); const revision = data.expectedRevision + 1;
    for (const field of ["treatments", "encounters", "performedProcedures", "appointments", "invoices"]) stampNewEntries(old && old[field], patient[field], uid, now);
    // Preserve server-owned history metadata and require existing source events intact.
    const history = old && old.history || [];
    const priorIds = new Set(history.map(item => item.id));
    const incomingById = new Map((patient.history || []).map(item => [item.id, item]));
    for (const event of readsClinical(member) ? history : []) {
      const incoming = incomingById.get(event.id);
      assert(incoming && ["text", "previousValues", "at"].every(key => same(incoming[key], event[key])), "Recorded history cannot be deleted or rewritten", "failed-precondition");
    }
    const added = (patient.history || []).filter(item => !priorIds.has(item.id)).map(item => ({ ...item, actor: uid, recordedAt: now, source: "android", revision }));
    patient.history = [...history, ...added];
    const deltas = {};
    for (const key of changed) deltas[key] = { before: previousChanges(old && old[key], patient[key]), afterHash: fingerprint(patient[key] === undefined ? null : patient[key]) };
    const audit = [...(old && old.audit || []), { id: operationId, actor: uid, at: now, revision, action: old ? "patient_updated" : "patient_created", changedFields: changed, previousValues: deltas }];
    assert(audit.length <= MAX_HISTORY, "Audit history is full; arrange archive before more changes", "resource-exhausted");
    patient.audit = audit;
    patient.receipts = [...(old && old.receipts || []).slice(-(MAX_RECEIPTS - 1)), { id: operationId, actor: uid, hash, revision, at: now }];
    patient.revision = revision; patient.operationId = operationId; patient.clinicId = clinicId; patient.updatedAt = now; patient.updatedBy = uid;
    patient.chart.clinicId = clinicId; patient.chart.updatedBy = uid; patient.chart.updatedAt = new Date(now).toISOString(); patient.chart.chartVersion = (old && old.chart && old.chart.chartVersion || 0) + (changed.some(key => CLINICAL.includes(key)) ? 1 : 0);
    assert(Buffer.byteLength(JSON.stringify(patient.chart), "utf8") <= 32 * 1024, "Current chart exceeds the 32 KiB budget", "resource-exhausted");
    const scheduleIndex = scheduling(patient, clinicId, clinic, now);
    const images = array(patient.images || [], "images", 500); uniqueIds(images.map(image => ({ id: image.path ? image.path.split("/").pop().replace(".png", "") : "" })), "image hash");
    const oldImages = old && old.images || [];
    appendOnly(oldImages, images, "Images");
    const prefix = `workspaces/${clinicId}/patients/${patientId}/`;
    for (const image of images) {
      assert(typeof image.path === "string" && image.path.startsWith(prefix) && /^[a-f0-9]{64}\.png$/.test(image.path.slice(prefix.length)), "Image path does not belong to this clinic/patient");
      assert(Number.isInteger(image.bytes) && image.bytes > 0 && image.bytes <= 512 * 1024, "Image exceeds 512 KiB");
      if (!oldImages.some(previous => previous.path === image.path)) {
        const file = getStorage().bucket().file(image.path);
        let metadata;
        try { [metadata] = await file.getMetadata(); } catch (_) { fail("Attach the image in Firebase Storage before saving its reference", "failed-precondition"); }
        assert(metadata.contentType === "image/png" && Number(metadata.size) === image.bytes && Number(metadata.size) <= 512 * 1024, "Stored image metadata is invalid");
        image.reference = `gs://${file.bucket.name}/${image.path}`;
      }
    }
    const jobs = reminderJobs(patient, clinic, scheduleIndex, now);
    const nextClinic = { ...clinic, scheduleIndex, reminderJobs: jobs, remindersEnabled: clinicSetting(clinic, "remindersEnabled", false) === true,
      nextReminderAt: nextReminder(jobs), reminderRebuildRequired: scheduleIndex.some(item => item.startAt > now && !hasReminderMetadata(item)) };
    sizeGuard(patient); sizeGuard(nextClinic);
    tx.set(patientRef, patient);
    if (!clinicSnapshot.exists || ["scheduleIndex", "reminderJobs", "remindersEnabled", "nextReminderAt", "reminderRebuildRequired"].some(key => !same(clinic[key], nextClinic[key]))) tx.set(clinicRef, nextClinic);
    return { revision, updatedAt: now, patient: projectPatient(patient, member) };
  });
});

function nextReminder(jobs) { return Math.min(Number.MAX_SAFE_INTEGER, ...jobs.filter(job => job.status === "pending").map(job => job.dueAt)); }
function hasReminderMetadata(item) {
  return typeof item.reminderEnabled === "boolean" && typeof item.reminderConsent === "boolean" &&
    (item.reminderHours === null || (Number.isInteger(item.reminderHours) && item.reminderHours >= 1 && item.reminderHours <= 168));
}
function rebuildClinicReminders(clinic, now) {
  const index = clinic.scheduleIndex || [];
  const enabled = clinicSetting(clinic, "remindersEnabled", false) === true;
  const legacy = new Set(index.filter(item => !hasReminderMetadata(item)).map(item => `${item.patientId}:${item.appointmentId}`));
  // Old index rows cannot prove consent/overrides. Preserve already-authorized pending
  // jobs, expose the migration flag, and enrich those rows on the next patient commit.
  const jobs = (clinic.reminderJobs || []).filter(job => job.dueAt > now - 30 * 86400000 &&
    (job.status !== "pending" || (enabled && legacy.has(`${job.patientId}:${job.appointmentId}`))));
  if (enabled) for (const item of index) {
    if (item.startAt <= now || !hasReminderMetadata(item) || !item.reminderEnabled || !item.reminderConsent) continue;
    const id = fingerprint({ patient: item.patientId, appointment: item.appointmentId, start: item.startAt }).slice(0, 32);
    if (jobs.some(job => job.id === id)) continue;
    const hours = item.reminderHours ?? clinicSetting(clinic, "reminderHours", 24);
    assert(Number.isInteger(hours) && hours >= 1 && hours <= 168, "Reminder hours must be 1–168");
    jobs.push({ id, patientId: item.patientId, appointmentId: item.appointmentId, dueAt: Math.max(now, item.startAt - hours * 3600000), status: "pending" });
  }
  assert(jobs.length <= 256, "Reminder queue is full; reduce booking horizon or arrange queue retention", "resource-exhausted");
  return jobs;
}
function quietUntil(clinic, now) {
  const start = clinicSetting(clinic, "quietStart", "22:00"); const end = clinicSetting(clinic, "quietEnd", "08:00");
  if (start === end) return 0;
  const zone = clinic.config.timeZone || "UTC";
  const formatter = new Intl.DateTimeFormat("sv-SE", { timeZone: zone, year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hourCycle: "h23" });
  const value = formatter.format(new Date(now)); const clock = value.slice(11, 16);
  const quiet = start < end ? clock >= start && clock < end : clock >= start || clock < end;
  if (!quiet) return 0;
  let day = value.slice(0, 10);
  if (start > end && clock >= start) day = new Date(Date.parse(`${day}T12:00:00Z`) + 86400000).toISOString().slice(0, 10);
  return localTime(`${day} ${end}`, zone);
}
function reminderJobs(patient, clinic, index, now) {
  const existing = clinic.reminderJobs || [];
  const jobs = existing.filter(job => job.patientId !== patient.id || job.status !== "pending").filter(job => job.dueAt > now - 30 * 86400000);
  if (clinicSetting(clinic, "remindersEnabled", false) === true) {
    for (const item of index.filter(entry => entry.patientId === patient.id && entry.startAt > now)) {
      const appointment = (patient.appointments || []).find(entry => entry.id === item.appointmentId);
      const revoked = patient.communicationConsent === false || patient.reminderConsent === false || patient.consent && patient.consent.reminders === false;
      if (!appointment || revoked || appointment.reminderEnabled !== true || !(appointment.consent === true || patient.communicationConsent === true || patient.reminderConsent === true || patient.consent && patient.consent.reminders === true)) continue;
      const leadHours = appointment.reminderHours ?? clinicSetting(clinic, "reminderHours", 24);
      assert(Number.isInteger(leadHours) && leadHours >= 1 && leadHours <= 168, "Reminder hours must be 1–168");
      const lead = leadHours * 3600000;
      const id = fingerprint({ patient: patient.id, appointment: item.appointmentId, start: item.startAt }).slice(0, 32);
      if (!jobs.some(job => job.id === id)) jobs.push({ id, patientId: patient.id, appointmentId: item.appointmentId, dueAt: Math.max(now, item.startAt - lead), status: "pending" });
    }
  }
  assert(jobs.length <= 256, "Reminder queue is full; remove old completed jobs or reduce booking horizon", "resource-exhausted");
  return jobs;
}

exports.registerDevice = onCall(callOptions, async request => {
  const uid = actor(request); const clinicId = identifier(request.data.clinicId || uid, "clinic ID");
  const token = text(request.data.token, "notification token", 4096); assert(token.length >= 20, "Invalid notification token");
  const ref = db.doc(`workspaces/${clinicId}`);
  await db.runTransaction(async tx => {
    const snap = await tx.get(ref); const clinic = snap.exists ? snap.data() : { config: defaultConfig(uid), revision: 0 };
    roleFor(clinicId, uid, clinic);
    const devices = { ...(clinic.devices || {}) };
    const list = (devices[uid] || []).filter(item => item.token !== token && item.at > Date.now() - 90 * 86400000);
    if (request.data.enabled === true) list.push({ token, at: Date.now() });
    devices[uid] = list.slice(-8);
    const next = { ...clinic, devices }; sizeGuard(next); tx.set(ref, next);
  });
  return { enabled: request.data.enabled === true };
});

/** One due-clinic query, no per-patient polling and no notification documents. */
exports.dispatchClinicReminders = onSchedule({ schedule: "every 15 minutes", timeZone: "UTC", timeoutSeconds: 120 }, async () => {
  const due = await db.collection("workspaces").where("remindersEnabled", "==", true).where("nextReminderAt", "<=", Date.now()).limit(50).get();
  for (const snapshot of due.docs) {
    const claim = await db.runTransaction(async tx => {
      const fresh = await tx.get(snapshot.ref); const clinic = fresh.data(); const now = Date.now();
      if (!clinic || clinic.remindersEnabled !== true) return null;
      const jobs = (clinic.reminderJobs || []).map(job => ({ ...job }));
      const resume = quietUntil(clinic, now);
      if (resume > now) {
        for (const job of jobs) if (job.status === "pending" && job.dueAt <= now) job.dueAt = resume;
        tx.update(snapshot.ref, { reminderJobs: jobs, nextReminderAt: nextReminder(jobs) });
        return null;
      }
      const claimed = jobs.filter(job => job.status === "pending" && job.dueAt <= now).slice(0, 30);
      for (const job of claimed) { job.status = "claimed"; job.claimedAt = now; }
      if (!claimed.length) return null;
      tx.update(snapshot.ref, { reminderJobs: jobs, nextReminderAt: nextReminder(jobs) });
      const tokens = [];
      for (const [uid, devices] of Object.entries(clinic.devices || {})) {
        const member = uid === snapshot.id ? { active: true } : clinic.config.members && clinic.config.members[uid];
        if (!member || member.active === false) continue;
        for (const device of devices) if (device.at > now - 90 * 86400000) tokens.push(device.token);
      }
      return { ids: claimed.map(job => job.id), tokens: [...new Set(tokens)].slice(0, 500) };
    });
    if (!claim) continue;
    let status = "no_registered_device"; let delivered = 0;
    if (claim.tokens.length) {
      try {
        const result = await getMessaging().sendEachForMulticast({ tokens: claim.tokens, notification: { title: "Dental Care", body: "A clinic appointment needs attention. Open the app to review." }, android: { notification: { channelId: "clinic-reminders", tag: `clinic-${snapshot.id}` } }, data: { clinicId: snapshot.id, kind: "appointment_reminder" } });
        delivered = result.successCount; status = result.failureCount ? "delivery_partial_or_failed" : "sent";
      } catch (_) { status = "delivery_failed"; }
    }
    await db.runTransaction(async tx => {
      const fresh = await tx.get(snapshot.ref); if (!fresh.exists) return;
      const clinic = fresh.data(); const jobs = clinic.reminderJobs || [];
      for (const job of jobs) if (claim.ids.includes(job.id) && job.status === "claimed") { job.status = status; job.finishedAt = Date.now(); job.deliveredDevices = delivered; }
      tx.update(snapshot.ref, { reminderJobs: jobs, nextReminderAt: nextReminder(jobs) });
    });
  }
});
