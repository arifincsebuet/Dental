"use strict";

// One patient document, one clinic configuration document. No event subcollections.
const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");
const { getStorage } = require("firebase-admin/storage");
const { getAuth } = require("firebase-admin/auth");
const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { onSchedule } = require("firebase-functions/v2/scheduler");
const { setGlobalOptions } = require("firebase-functions/v2");
const { createHash, randomInt } = require("node:crypto");
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
// Platform super admins (verified Google emails) use the app free of charge, and so do clinics they own.
const SUPER_ADMINS = (process.env.SUPER_ADMINS || "dev.spala@gmail.com,spalatechnologies@gmail.com").split(",").map(email => email.trim().toLowerCase()).filter(Boolean);
function isSuperAdmin(token) {
  return Boolean(token && typeof token.email === "string" && token.email_verified === true && SUPER_ADMINS.includes(token.email.toLowerCase()));
}
function visibleClinic(clinicId, clinic, uid, superAdmin = false) {
  const summary = {};
  for (const job of clinic && clinic.reminderJobs || []) summary[job.status] = (summary[job.status] || 0) + 1;
  return { clinicId, revision: clinic ? clinic.revision || 0 : 0, config: clinic ? clinic.config : defaultConfig(uid), reminderSummary: summary,
    subscription: publicSubscription(clinic, superAdmin),
    patientPushes: (clinic && clinic.patientPushJobs || []).slice(-30).map(job => ({ id: job.id, title: job.title, body: job.body, sendAt: job.sendAt,
      status: job.status, recipients: job.patientIds.length, delivered: job.delivered || 0, failed: job.failed || 0, createdBy: job.createdBy })),
    reminderRebuildRequired: Boolean(clinic && clinic.reminderRebuildRequired) };
}
function normalized(value) { return String(value || "").toLowerCase().replace(/[ -]+/g, "_"); }
function substantiveChart(value) { if (!value) return value; const next = clone(value); for (const key of ["clinicId", "patientId", "chartId", "updatedAt", "updatedBy", "chartVersion"]) delete next[key]; return next; }
function without(value, keys) { const next = clone(value); for (const key of keys) delete next[key]; return next; }
function clinicSetting(clinic, key, fallback) { return clinic.config[key] ?? clinic.config.settings?.[key] ?? fallback; }
function readsClinical(member) { return ["owner", "admin", "dentist", "auditor"].includes(member.role) || (member.role === "assistant" && member.permissions && (member.permissions.clinicalRead === true || member.permissions.clinicalWrite === true)); }
function projectPatient(patient, member) {
  // Push tokens never leave the server towards staff devices.
  if (patient && patient.patientDevices) { patient = { ...patient }; delete patient.patientDevices; }
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
      reminderEnabled: appointment.reminderEnabled === true, reminderConsent: Boolean(reminderConsent), reminderHours: appointment.reminderHours ?? null,
      slot: appointment.slot === true };
    const buffer = Number(clinicSetting(clinic, "bufferMinutes", 0) || 0) * 60000;
    // Slots already define their spacing, so the clinic buffer only applies when a free-form
    // appointment is involved (otherwise back-to-back slots could never both be booked).
    const conflict = [...oldIndex, ...additions].find(item => {
      const gap = item.slot === true && candidate.slot ? 0 : buffer;
      return item.startAt < endAt + gap && startAt < item.endAt + gap && (item.clinician.toLowerCase() === clinician.toLowerCase() || (room && item.room.toLowerCase() === room.toLowerCase()));
    });
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
  let clinic = snapshot.exists ? snapshot.data() : null;
  roleFor(clinicId, uid, clinic);
  const superAdmin = isSuperAdmin(request.auth.token);
  // A super admin's own clinic is complimentary for all of its staff (one write, once).
  if (superAdmin && clinicId === uid && clinic && clinic.complimentary !== true) {
    await snapshot.ref.update({ complimentary: true }); clinic = { ...clinic, complimentary: true };
  }
  // Lazy renewal check: only when the stored period has ended (no Pub/Sub, no polling).
  if (clinic && clinic.complimentary !== true && clinic.billing && clinic.billing.token && (!clinic.subscription || clinic.subscription.expiresAt < Date.now())) {
    try { clinic = { ...clinic, subscription: await refreshSubscription(snapshot.ref, clinic.billing, clinicId) }; } catch (_) { /* keep last known state */ }
  }
  return visibleClinic(clinicId, clinic, uid, superAdmin);
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
  const { FieldPath } = require("firebase-admin/firestore");
  // Incremental mode reads only documents changed since the caller's last pull (cost: changed docs only).
  if (request.data.updatedAfter !== undefined && request.data.updatedAfter !== null) {
    assert(Number.isSafeInteger(request.data.updatedAfter) && request.data.updatedAfter >= 0, "Invalid updatedAfter");
    let query = db.collection(`workspaces/${clinicId}/patients`).where("updatedAt", ">", request.data.updatedAfter)
      .orderBy("updatedAt").orderBy(FieldPath.documentId()).limit(pageSize);
    if (request.data.afterId) query = query.startAfter(request.data.afterAt, identifier(request.data.afterId, "patient cursor"));
    const snapshots = await query.get();
    const last = snapshots.docs[snapshots.size - 1];
    return { patients: snapshots.docs.map(snapshot => projectPatient(snapshot.data(), member)),
      nextCursor: snapshots.size === pageSize ? last.id : null, nextAt: snapshots.size === pageSize ? last.data().updatedAt : null, serverTime: Date.now() };
  }
  let query = db.collection(`workspaces/${clinicId}/patients`).orderBy(FieldPath.documentId()).limit(pageSize);
  if (request.data.afterId) query = query.startAfter(identifier(request.data.afterId, "patient cursor"));
  const snapshots = await query.get();
  return { patients: snapshots.docs.map(snapshot => projectPatient(snapshot.data(), member)), nextCursor: snapshots.size === pageSize ? snapshots.docs[snapshots.size - 1].id : null, serverTime: Date.now() };
});

exports.updateClinic = onCall(callOptions, async request => {
  const uid = actor(request); const data = object(request.data, "request");
  const clinicId = identifier(data.clinicId || uid, "clinic ID"); const operationId = identifier(data.operationId, "operation ID");
  assert(Number.isSafeInteger(data.expectedRevision) && data.expectedRevision >= 0, "Invalid expected revision");
  const config = clone(object(data.config, "clinic config"));
  keysOnly(config, ["name", "timeZone", "currency", "members", "staff", "services", "settings", "taxBps", "bufferMinutes", "reminderHours", "remindersEnabled", "quietStart", "quietEnd", "phone", "address", "numberingSystem", "availability", "patientBooking", "patientPushReminders"], "clinic config");
  validateAvailability(config.availability || []);
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
    // Staff who joined by Google email after this client last loaded settings are kept, not dropped.
    for (const [memberId, joined] of Object.entries(old && old.config && old.config.members || {})) if (!config.members[memberId] && joined.email) config.members[memberId] = joined;
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
    applyStaffInvites(next, clinicId);
    if (isSuperAdmin(request.auth.token) && clinicId === uid) next.complimentary = true;
    next.reminderJobs = rebuildClinicReminders(next, now);
    next.nextReminderAt = nextReminder(next.reminderJobs);
    next.reminderRebuildRequired = (next.scheduleIndex || []).some(item => item.startAt > now && !hasReminderMetadata(item));
    sizeGuard(next); tx.set(ref, next);
    return visibleClinic(clinicId, next, uid, isSuperAdmin(request.auth.token));
  });
});

exports.commitPatient = onCall(callOptions, async request => {
  const uid = actor(request); const data = object(request.data, "request");
  const clinicId = identifier(data.clinicId || uid, "clinic ID"); const patientId = identifier(data.patientId, "patient ID"); const operationId = identifier(data.operationId, "operation ID");
  assert(Number.isSafeInteger(data.expectedRevision) && data.expectedRevision >= 0, "Invalid expected revision");
  const input = clone(object(data.patient, "patient"));
  // `portal` (patient login metadata) is server-owned: accepted in the payload but always taken from the stored doc.
  keysOnly(input, [...ROOT_FIELDS, "dirty", "remoteVersion", "cloudOwner", "revision", "updatedAt", "updatedBy", "clinicId", "operationId", "audit", "receipts", "redacted", "portal", "patientDevices"], "patient");
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
    if (old && old.portal) patient.portal = old.portal;
    // Patient device tokens are written by the patient app directly (Firestore rules); keep them.
    if (old && old.patientDevices) patient.patientDevices = old.patientDevices;
    patient.revision = revision; patient.operationId = operationId; patient.clinicId = clinicId; patient.updatedAt = now; patient.updatedBy = uid;
    patient.chart.clinicId = clinicId; patient.chart.updatedBy = uid; patient.chart.updatedAt = new Date(now).toISOString(); patient.chart.chartVersion = (old && old.chart && old.chart.chartVersion || 0) + (changed.some(key => CLINICAL.includes(key)) ? 1 : 0);
    assert(Buffer.byteLength(JSON.stringify(patient.chart), "utf8") <= 32 * 1024, "Current chart exceeds the 32 KiB budget", "resource-exhausted");
    const scheduleIndex = scheduling(patient, clinicId, clinic, now);
    const images = array(patient.images || [], "images", 500); uniqueIds(images.map(image => ({ id: image.path ? image.path.split("/").pop().replace(/\.(png|webp)$/, "") : "" })), "image hash");
    const oldImages = old && old.images || [];
    appendOnly(oldImages, images, "Images");
    const prefix = `workspaces/${clinicId}/patients/${patientId}/`;
    for (const image of images) {
      assert(typeof image.path === "string" && image.path.startsWith(prefix) && /^[a-f0-9]{64}\.(png|webp)$/.test(image.path.slice(prefix.length)), "Image path does not belong to this clinic/patient");
      assert(Number.isInteger(image.bytes) && image.bytes > 0 && image.bytes <= 512 * 1024, "Image exceeds 512 KiB");
      if (!oldImages.some(previous => previous.path === image.path)) {
        const file = getStorage().bucket().file(image.path);
        let metadata;
        try { [metadata] = await file.getMetadata(); } catch (_) { fail("Attach the image in Firebase Storage before saving its reference", "failed-precondition"); }
        assert(["image/png", "image/webp"].includes(metadata.contentType) && Number(metadata.size) === image.bytes && Number(metadata.size) <= 512 * 1024, "Stored image metadata is invalid");
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
      return { ids: claimed.map(job => job.id), tokens: [...new Set(tokens)].slice(0, 500),
        patientJobs: clinic.config.patientPushReminders === true ? claimed.map(job => ({ patientId: job.patientId, appointmentId: job.appointmentId })) : [],
        zone: clinic.config.timeZone || "UTC", clinicName: clinic.config.name || "Dental Care" };
    });
    if (!claim) continue;
    let status = "no_registered_device"; let delivered = 0;
    if (claim.tokens.length) {
      try {
        const result = await getMessaging().sendEachForMulticast({ tokens: claim.tokens, notification: { title: "Dental Care", body: "A clinic appointment needs attention. Open the app to review." }, android: { notification: { channelId: "clinic-reminders", tag: `clinic-${snapshot.id}` } }, data: { clinicId: snapshot.id, kind: "appointment_reminder" } });
        delivered = result.successCount; status = result.failureCount ? "delivery_partial_or_failed" : "sent";
      } catch (_) { status = "delivery_failed"; }
    }
    // Patients' own phones (tokens stored in their documents), when the clinic enables it.
    if (claim.patientJobs.length) await sendAppointmentPushes(snapshot.id, claim.patientJobs, claim.zone, claim.clinicName).catch(() => null);
    await db.runTransaction(async tx => {
      const fresh = await tx.get(snapshot.ref); if (!fresh.exists) return;
      const clinic = fresh.data(); const jobs = clinic.reminderJobs || [];
      for (const job of jobs) if (claim.ids.includes(job.id) && job.status === "claimed") { job.status = status; job.finishedAt = Date.now(); job.deliveredDevices = delivered; }
      tx.update(snapshot.ref, { reminderJobs: jobs, nextReminderAt: nextReminder(jobs) });
    });
  }
});


// ---------------------------------------------------------------------------------------------
// Staff access by Google account email (no extra documents: invites live in the clinic doc)
// ---------------------------------------------------------------------------------------------

const STAFF_ROLE = { owner: "admin", admin: "admin", administrator: "admin", dentist: "dentist", assistant: "assistant", receptionist: "receptionist", cashier: "cashier", auditor: "auditor" };
function staffInvites(config) {
  const invites = {};
  for (const person of config.staff || []) {
    const email = typeof person.email === "string" ? person.email.trim().toLowerCase() : "";
    if (!email || !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email) || person.active === false) continue;
    invites[email] = STAFF_ROLE[person.role] || "dentist";
  }
  return invites;
}
/** Keeps `inviteEmails` (the only indexed lookup field) and member roles in step with the staff list. */
function applyStaffInvites(clinic, clinicId) {
  const invites = staffInvites(clinic.config);
  clinic.inviteEmails = Object.keys(invites).slice(0, 200);
  for (const [uid, member] of Object.entries(clinic.config.members || {})) {
    if (uid === clinicId || !member.email) continue;
    const role = invites[member.email];
    member.active = Boolean(role);
    if (role) member.role = role;
  }
}

/**
 * Called after Google sign-in. Owner of an existing clinic → that clinic. Invited email → joins the
 * inviting clinic once (one write). Otherwise the caller's own UID becomes a new personal clinic.
 */
exports.resolveWorkspace = onCall(callOptions, async request => {
  const uid = actor(request);
  assert(!request.auth.token.role || request.auth.token.role !== "patient", "Patient accounts cannot open the clinic workspace", "permission-denied");
  const own = await db.doc(`workspaces/${uid}`).get();
  if (own.exists) return { clinicId: uid, role: "owner" };
  const email = String(request.auth.token.email || "").toLowerCase();
  if (email && request.auth.token.email_verified !== false) {
    const invited = await db.collection("workspaces").where("inviteEmails", "array-contains", email).limit(1).get();
    if (!invited.empty) {
      const ref = invited.docs[0].ref;
      const role = await db.runTransaction(async tx => {
        const snap = await tx.get(ref); const clinic = snap.data();
        const granted = staffInvites(clinic.config)[email];
        assert(granted, "Your clinic invitation is no longer active", "permission-denied");
        const members = { ...(clinic.config.members || {}) };
        if (!members[uid] || members[uid].role !== granted || members[uid].active === false) {
          members[uid] = { role: granted, active: true, email, joinedAt: Date.now() };
          tx.update(ref, { "config.members": members, audit: [...(clinic.audit || []), { id: `join-${uid}-${Date.now()}`, actor: uid, at: Date.now(), action: "staff_joined", email, role: granted }] });
        }
        return granted;
      });
      return { clinicId: ref.id, role };
    }
  }
  return { clinicId: uid, role: "owner" };
});

// ---------------------------------------------------------------------------------------------
// Patient portal logins: Firebase Auth email/password accounts with custom claims.
// The patient enters a short Patient ID; the email is synthetic and never receives mail.
// No Firestore documents are created; login metadata is one `portal` field on the patient doc.
// ---------------------------------------------------------------------------------------------

const LOGIN_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
const PASSWORD_ALPHABET = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789";
function randomText(alphabet, length) { let out = ""; for (let i = 0; i < length; i++) out += alphabet[randomInt(alphabet.length)]; return out; }
function patientEmail(loginId) { return `${loginId.toLowerCase()}@patient.dentalcare.local`; }
async function portalAdmin(request) {
  const uid = actor(request);
  const clinicId = identifier(request.data.clinicId || uid, "clinic ID"); const patientId = identifier(request.data.patientId, "patient ID");
  const clinic = await db.doc(`workspaces/${clinicId}`).get();
  const member = roleFor(clinicId, uid, clinic.exists ? clinic.data() : null);
  assert(["owner", "admin", "dentist"].includes(member.role), "Only clinic administrators manage patient logins", "permission-denied");
  const ref = db.doc(`workspaces/${clinicId}/patients/${patientId}`);
  const snapshot = await ref.get();
  assert(snapshot.exists, "Synchronize this patient to the cloud before creating a login", "failed-precondition");
  return { uid, clinicId, patientId, ref, patient: snapshot.data() };
}

exports.createPatientLogin = onCall(callOptions, async request => {
  const { uid, clinicId, patientId, ref, patient } = await portalAdmin(request);
  assert(!patient.portal, "This patient already has a login. Use Reset password.", "already-exists");
  const password = randomText(PASSWORD_ALPHABET, 10);
  let loginId; let user;
  for (let attempt = 0; attempt < 5 && !user; attempt++) {
    loginId = randomText(LOGIN_ALPHABET, 8);
    try { user = await getAuth().createUser({ email: patientEmail(loginId), password, displayName: patient.name || "Patient", emailVerified: false }); }
    catch (error) { if (error.code !== "auth/email-already-exists") throw error; }
  }
  assert(user, "Could not allocate a Patient ID; try again", "unavailable");
  await getAuth().setCustomUserClaims(user.uid, { role: "patient", clinicId, patientId, mustChange: true });
  const portal = { loginId, uid: user.uid, createdAt: Date.now(), createdBy: uid, passwordResetAt: Date.now() };
  await ref.update({ portal });
  return { loginId, password, portal };
});

exports.resetPatientPassword = onCall(callOptions, async request => {
  const { uid, ref, patient, clinicId, patientId } = await portalAdmin(request);
  assert(patient.portal && patient.portal.uid, "Create a login for this patient first", "failed-precondition");
  const password = randomText(PASSWORD_ALPHABET, 10);
  await getAuth().updateUser(patient.portal.uid, { password, disabled: false });
  await getAuth().setCustomUserClaims(patient.portal.uid, { role: "patient", clinicId, patientId, mustChange: true });
  await getAuth().revokeRefreshTokens(patient.portal.uid);
  const portal = { ...patient.portal, passwordResetAt: Date.now(), resetBy: uid };
  await ref.update({ portal });
  return { loginId: portal.loginId, password, portal };
});

/** Patient finished changing the temporary password on the device; clear the forced-change flag. */
exports.completePasswordChange = onCall(callOptions, async request => {
  actor(request);
  const token = request.auth.token;
  assert(token.role === "patient", "Patient login required", "permission-denied");
  await getAuth().setCustomUserClaims(request.auth.uid, { role: "patient", clinicId: token.clinicId, patientId: token.patientId, mustChange: false });
  return { ok: true };
});

/** The signed-in patient's own record, read-only and without internal staff notes or audit. One read. */
exports.getMyRecord = onCall(callOptions, async request => {
  actor(request);
  const token = request.auth.token;
  assert(token.role === "patient" && token.clinicId && token.patientId, "Patient login required", "permission-denied");
  const [snapshot, clinic] = await Promise.all([
    db.doc(`workspaces/${identifier(token.clinicId, "clinic ID")}/patients/${identifier(token.patientId, "patient ID")}`).get(),
    db.doc(`workspaces/${token.clinicId}`).get(),
  ]);
  assert(snapshot.exists, "Your record is not available yet. Contact the clinic.", "not-found");
  const patient = snapshot.data();
  const keep = ["id", "name", "dob", "phone", "chart", "treatments", "performedProcedures", "appointments", "invoices", "updatedAt"];
  const record = {};
  for (const key of keep) if (patient[key] !== undefined) record[key] = clone(patient[key]);
  for (const item of record.treatments || []) { delete item.events; delete item.amendments; }
  return { patient: record, clinic: { name: clinic.exists ? clinic.data().config.name : "Dental Care", currency: clinic.exists ? clinic.data().config.currency : "BDT", timeZone: clinic.exists ? clinic.data().config.timeZone : "Asia/Dhaka" } };
});


// ---------------------------------------------------------------------------------------------
// Google Play subscription (clinic-level). Prices and offers live in Play Console; the server only
// verifies purchase tokens with the Play Developer API and stores the entitlement on the clinic
// document (no extra documents). Renewals are re-checked lazily when the stored period ends.
// ---------------------------------------------------------------------------------------------

const ANDROID_PACKAGE = process.env.ANDROID_PACKAGE || "com.spala.dental";
const SUBSCRIPTION_PRODUCTS = (process.env.SUBSCRIPTION_PRODUCTS || "dental_care_pro").split(",");
const ACTIVE_STATES = ["SUBSCRIPTION_STATE_ACTIVE", "SUBSCRIPTION_STATE_IN_GRACE_PERIOD"];

function publicSubscription(clinic, superAdmin = false) {
  if (superAdmin || (clinic && clinic.complimentary === true)) return { active: true, complimentary: true, expiresAt: 8640000000000000, state: "COMPLIMENTARY", productId: "", offerId: null, autoRenewing: false };
  const sub = clinic && clinic.subscription;
  if (!sub) return { active: false };
  return { active: sub.active === true && sub.expiresAt > Date.now(), expiresAt: sub.expiresAt, state: sub.state, productId: sub.productId, offerId: sub.offerId || null, autoRenewing: sub.autoRenewing === true };
}
function accountTag(clinicId) { return createHash("sha256").update(`dental-care:${clinicId}`).digest("hex"); }

let playClient;
async function play(path, method = "GET") {
  if (!playClient) {
    const { GoogleAuth } = require("google-auth-library");
    playClient = await new GoogleAuth({ scopes: ["https://www.googleapis.com/auth/androidpublisher"] }).getClient();
  }
  const response = await playClient.request({ url: `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${ANDROID_PACKAGE}/${path}`, method });
  return response.data;
}
function entitlement(data) {
  const items = data.lineItems || [];
  const expiresAt = Math.max(0, ...items.map(item => Date.parse(item.expiryTime) || 0));
  const item = items.find(entry => SUBSCRIPTION_PRODUCTS.includes(entry.productId)) || items[0] || {};
  return { active: ACTIVE_STATES.includes(data.subscriptionState) && expiresAt > Date.now(), state: data.subscriptionState || "UNKNOWN", expiresAt,
    productId: item.productId || "", offerId: item.offerDetails && item.offerDetails.offerId || null, basePlanId: item.offerDetails && item.offerDetails.basePlanId || null,
    autoRenewing: Boolean(item.autoRenewingPlan && item.autoRenewingPlan.autoRenewEnabled), verifiedAt: Date.now() };
}
async function refreshSubscription(ref, billing, clinicId) {
  const data = await play(`purchases/subscriptionsv2/tokens/${encodeURIComponent(billing.token)}`);
  const sub = entitlement(data);
  await ref.update({ subscription: { ...sub, linkedBy: billing.linkedBy } });
  return sub;
}

/** Owner/admin links a Play purchase to the clinic; the purchase must have been made for this clinic. */
exports.verifySubscription = onCall(callOptions, async request => {
  const uid = actor(request);
  const clinicId = identifier(request.data.clinicId || uid, "clinic ID");
  const token = text(request.data.purchaseToken, "purchase token", 4096); assert(token.length >= 20, "Invalid purchase token");
  const ref = db.doc(`workspaces/${clinicId}`);
  const snapshot = await ref.get();
  const member = roleFor(clinicId, uid, snapshot.exists ? snapshot.data() : null);
  assert(["owner", "admin"].includes(member.role), "Only the clinic owner or an administrator can manage the subscription", "permission-denied");
  let data;
  try { data = await play(`purchases/subscriptionsv2/tokens/${encodeURIComponent(token)}`); }
  catch (error) { fail("Google Play could not confirm this purchase. Try Restore purchase in a moment.", "failed-precondition"); }
  const external = data.externalAccountIdentifiers && data.externalAccountIdentifiers.obfuscatedExternalAccountId;
  assert(!external || external === accountTag(clinicId), "This purchase belongs to a different clinic", "permission-denied");
  const sub = entitlement(data);
  assert(SUBSCRIPTION_PRODUCTS.includes(sub.productId), "Unknown subscription product");
  if (data.acknowledgementState === "ACKNOWLEDGEMENT_STATE_PENDING" && sub.active) {
    // Unacknowledged purchases are refunded by Google after 3 days.
    await play(`purchases/subscriptions/${encodeURIComponent(sub.productId)}/tokens/${encodeURIComponent(token)}:acknowledge`, "POST");
  }
  const billing = { token, linkedBy: uid, linkedAt: Date.now(), tokenHash: fingerprint(token) };
  const subscription = { ...sub, linkedBy: uid };
  if (snapshot.exists) await ref.update({ billing, subscription });
  else await ref.set({ config: defaultConfig(clinicId), revision: 0, billing, subscription });
  return { subscription: publicSubscription({ subscription }) };
});


// ---------------------------------------------------------------------------------------------
// Appointment slots. Admins define availability sessions (doctor, dates/weekdays, hours, slot
// length) inside the clinic config; individual slots are computed, never stored. A slot is free
// when no active appointment of that doctor overlaps it (checked against the clinic scheduleIndex).
// ---------------------------------------------------------------------------------------------

const HHMM = /^([01]\d|2[0-3]):[0-5]\d$/;
const YMD = /^\d{4}-\d{2}-\d{2}$/;
function minutes(value) { const [h, m] = value.split(":").map(Number); return h * 60 + m; }
function validateAvailability(sessions) {
  array(sessions, "availability", 300); uniqueIds(sessions, "availability");
  for (const session of sessions) {
    keysOnly(session, ["id", "doctor", "room", "startDate", "endDate", "weekdays", "start", "end", "slotMinutes", "active", "note"], "availability session");
    assert(typeof session.doctor === "string" && session.doctor.trim() && session.doctor.length <= 200, "Availability needs a doctor");
    assert(YMD.test(session.startDate) && YMD.test(session.endDate) && session.endDate >= session.startDate, "Availability dates must be YYYY-MM-DD, end on or after start");
    assert(HHMM.test(session.start) && HHMM.test(session.end) && minutes(session.end) > minutes(session.start), "Availability hours must be HH:mm with end after start");
    assert(Number.isInteger(session.slotMinutes) && session.slotMinutes >= 5 && session.slotMinutes <= 240, "Slot length must be 5–240 minutes");
    assert(Array.isArray(session.weekdays || []) && (session.weekdays || []).every(day => Number.isInteger(day) && day >= 1 && day <= 7), "Weekdays must be 1 (Mon)–7 (Sun)");
    if (session.room !== undefined) text(session.room, "room", 200);
  }
}
function addDays(ymd, days) { const d = new Date(`${ymd}T12:00:00Z`); d.setUTCDate(d.getUTCDate() + days); return d.toISOString().slice(0, 10); }
function isoWeekday(ymd) { const day = new Date(`${ymd}T12:00:00Z`).getUTCDay(); return day === 0 ? 7 : day; }
/** All slots of one date with availability, using the clinic schedule index for "booked". */
function slotsFor(clinic, date, now) {
  const zone = clinic.config.timeZone || "UTC";
  const index = clinic.scheduleIndex || [];
  const out = [];
  for (const session of clinic.config.availability || []) {
    if (session.active === false || date < session.startDate || date > session.endDate) continue;
    if ((session.weekdays || []).length && !session.weekdays.includes(isoWeekday(date))) continue;
    for (let t = minutes(session.start); t + session.slotMinutes <= minutes(session.end); t += session.slotMinutes) {
      const time = `${String(Math.floor(t / 60)).padStart(2, "0")}:${String(t % 60).padStart(2, "0")}`;
      let startAt; try { startAt = localTime(`${date} ${time}`, zone); } catch (_) { continue; }
      const endAt = startAt + session.slotMinutes * 60000;
      const doctor = session.doctor.trim();
      const booked = index.some(item => item.clinician.toLowerCase() === doctor.toLowerCase() && item.startAt < endAt && startAt < item.endAt);
      out.push({ sessionId: session.id, doctor, room: session.room || "", date, time, startAt, endAt, durationMinutes: session.slotMinutes, available: !booked && startAt > now });
    }
  }
  return out.sort((a, b) => a.startAt - b.startAt || a.doctor.localeCompare(b.doctor));
}

async function slotCaller(request) {
  const uid = actor(request);
  const token = request.auth.token;
  if (token.role === "patient") return { uid, clinicId: identifier(token.clinicId, "clinic ID"), patientId: identifier(token.patientId, "patient ID"), patient: true };
  const clinicId = identifier(request.data.clinicId || uid, "clinic ID");
  return { uid, clinicId, patient: false };
}

/** Slots for up to 42 days from `from` (one clinic read; no names of other patients are returned). */
exports.getSlots = onCall(callOptions, async request => {
  const caller = await slotCaller(request);
  const from = String(request.data.from || ""); assert(YMD.test(from), "from must be YYYY-MM-DD");
  const days = request.data.days === undefined ? 31 : request.data.days;
  assert(Number.isInteger(days) && days >= 1 && days <= 42, "days must be 1–42");
  const snapshot = await db.doc(`workspaces/${caller.clinicId}`).get();
  const clinic = snapshot.exists ? snapshot.data() : { config: defaultConfig(caller.clinicId) };
  if (!caller.patient) roleFor(caller.clinicId, caller.uid, snapshot.exists ? clinic : null);
  const now = Date.now(); const slots = [];
  for (let i = 0; i < days; i++) slots.push(...slotsFor(clinic, addDays(from, i), now));
  return { slots, timeZone: clinic.config.timeZone || "UTC", patientBooking: clinic.config.patientBooking !== false };
});

/** Patient self-booking of a free slot: one transaction on the clinic and the patient's own document. */
exports.bookSlot = onCall(callOptions, async request => {
  const caller = await slotCaller(request);
  assert(caller.patient, "Staff book slots from the clinic app", "permission-denied");
  const startAt = request.data.startAt; assert(Number.isSafeInteger(startAt), "Choose a slot");
  const doctor = text(request.data.doctor, "doctor", 200).trim();
  const reason = text(request.data.reason || "Check-up", "reason", 500).trim() || "Check-up";
  const clinicRef = db.doc(`workspaces/${caller.clinicId}`);
  const patientRef = db.doc(`workspaces/${caller.clinicId}/patients/${caller.patientId}`);
  return db.runTransaction(async tx => {
    const [clinicSnap, patientSnap] = await Promise.all([tx.get(clinicRef), tx.get(patientRef)]);
    assert(clinicSnap.exists && patientSnap.exists, "Your record is not available", "not-found");
    const clinic = clinicSnap.data(); const patient = patientSnap.data(); const now = Date.now();
    assert(clinic.config.patientBooking !== false, "Online booking is turned off for this clinic", "failed-precondition");
    const date = new Intl.DateTimeFormat("sv-SE", { timeZone: clinic.config.timeZone || "UTC", year: "numeric", month: "2-digit", day: "2-digit" }).format(new Date(startAt));
    const slot = slotsFor(clinic, date, now).find(item => item.startAt === startAt && item.doctor.toLowerCase() === doctor.toLowerCase());
    assert(slot, "That slot is not offered", "not-found");
    assert(slot.available, "That slot was just taken. Please choose another time.", "already-exists");
    const upcoming = (patient.appointments || []).filter(a => ["scheduled", "confirmed"].includes(normalized(a.status)) && (a.startAt || 0) > now);
    assert(upcoming.length < 3, "You already have 3 upcoming appointments. Contact the clinic to book more.", "resource-exhausted");
    const appointment = { id: `apt-${createHash("sha256").update(`${caller.patientId}:${startAt}:${now}`).digest("hex").slice(0, 20)}`,
      date: `${slot.date} ${slot.time}`, startAt: slot.startAt, durationMinutes: slot.durationMinutes, doctor: slot.doctor, room: slot.room,
      reason, status: "Scheduled", bookedBy: "patient", bookedAt: now, slot: true, reminderEnabled: clinicSetting(clinic, "remindersEnabled", false) === true, consent: patient.communicationConsent === true };
    patient.appointments = [...(patient.appointments || []), appointment];
    return commitSelfService(tx, clinicRef, patientRef, clinic, patient, caller, now, `Appointment booked by patient: ${slot.date} ${slot.time} with ${slot.doctor}`, { appointment });
  });
});

/** Patient cancels one of their own upcoming appointments (at least 2 hours ahead). */
exports.cancelMyAppointment = onCall(callOptions, async request => {
  const caller = await slotCaller(request);
  assert(caller.patient, "Patient login required", "permission-denied");
  const appointmentId = identifier(request.data.appointmentId, "appointment ID");
  const clinicRef = db.doc(`workspaces/${caller.clinicId}`);
  const patientRef = db.doc(`workspaces/${caller.clinicId}/patients/${caller.patientId}`);
  return db.runTransaction(async tx => {
    const [clinicSnap, patientSnap] = await Promise.all([tx.get(clinicRef), tx.get(patientRef)]);
    assert(clinicSnap.exists && patientSnap.exists, "Your record is not available", "not-found");
    const clinic = clinicSnap.data(); const patient = patientSnap.data(); const now = Date.now();
    const appointment = (patient.appointments || []).find(item => item.id === appointmentId);
    assert(appointment && ["scheduled", "confirmed"].includes(normalized(appointment.status)), "Appointment not found", "not-found");
    assert((appointment.startAt || 0) - now >= 2 * 3600000, "Appointments can be cancelled in the app up to 2 hours before. Please call the clinic.", "failed-precondition");
    appointment.status = "Cancelled"; appointment.cancelledBy = "patient"; appointment.cancelledAt = now;
    return commitSelfService(tx, clinicRef, patientRef, clinic, patient, caller, now, `Appointment cancelled by patient: ${appointment.date}`, { appointment });
  });
});

/** Shared write for patient self-service: revision, history, audit, schedule index and reminders. */
function commitSelfService(tx, clinicRef, patientRef, clinic, patient, caller, now, action, result) {
  const revision = (patient.revision || 0) + 1;
  const scheduleIndex = scheduling(patient, caller.clinicId, clinic, now);
  const jobs = reminderJobs(patient, clinic, scheduleIndex, now);
  patient.history = [...(patient.history || []), { id: `self-${revision}-${now}`, at: now, text: action, actor: caller.uid, recordedAt: now, source: "patient-app", revision }];
  patient.audit = [...(patient.audit || []), { id: `self-${revision}-${now}`, actor: caller.uid, at: now, revision, action: "patient_self_service", changedFields: ["appointments"] }];
  patient.revision = revision; patient.updatedAt = now; patient.updatedBy = caller.uid;
  const nextClinic = { ...clinic, scheduleIndex, reminderJobs: jobs, nextReminderAt: nextReminder(jobs) };
  sizeGuard(patient); sizeGuard(nextClinic);
  tx.set(patientRef, patient); tx.set(clinicRef, nextClinic);
  return { ...result, revision };
}


// ---------------------------------------------------------------------------------------------
// Patient pushes. Tokens live in each patient document (patientDevices.{deviceId}, written by the
// patient app). Admins queue messages from the app; jobs live in the clinic document
// (patientPushJobs, bounded). "Send now" is delivered by the callable; scheduled ones by
// dispatchPatientPushes. Invalid tokens are removed after each send.
// ---------------------------------------------------------------------------------------------

const PUSH_JOB_LIMIT = 100;
function nextPatientPush(jobs) { return Math.min(Number.MAX_SAFE_INTEGER, ...jobs.filter(job => job.status === "scheduled").map(job => job.sendAt)); }

/** Sends one title/body to the given patients' devices. Returns {delivered, failed}. */
async function sendToPatients(clinicId, patientIds, title, body, extra = {}) {
  let delivered = 0; let failed = 0;
  for (let start = 0; start < patientIds.length; start += 100) {
    const refs = patientIds.slice(start, start + 100).map(id => db.doc(`workspaces/${clinicId}/patients/${id}`));
    const snapshots = refs.length ? await db.getAll(...refs) : [];
    const targets = []; // {ref, deviceId, token}
    for (const snapshot of snapshots) {
      if (!snapshot.exists) continue;
      for (const [deviceId, device] of Object.entries(snapshot.data().patientDevices || {})) {
        if (device && typeof device.token === "string" && device.token.length >= 20) targets.push({ ref: snapshot.ref, deviceId, token: device.token });
      }
    }
    for (let i = 0; i < targets.length; i += 500) {
      const batch = targets.slice(i, i + 500);
      const result = await getMessaging().sendEachForMulticast({
        tokens: batch.map(target => target.token),
        // Data message: the app shows it on its own "Appointment reminders" channel, hidden on the lock screen.
        data: { kind: "patient_reminder", title: String(title), body: String(body), clinicId, ...extra },
        android: { priority: "high" },
      });
      delivered += result.successCount; failed += result.failureCount;
      const stale = [];
      result.responses.forEach((response, index) => {
        const code = response.error && response.error.code;
        if (code === "messaging/registration-token-not-registered" || code === "messaging/invalid-registration-token") stale.push(batch[index]);
      });
      const { FieldPath, FieldValue } = require("firebase-admin/firestore");
      await Promise.all(stale.map(target => target.ref.update(new FieldPath("patientDevices", target.deviceId), FieldValue.delete()).catch(() => null)));
    }
  }
  return { delivered, failed };
}

/** Automatic appointment reminders to patients (called by dispatchClinicReminders). */
async function sendAppointmentPushes(clinicId, jobs, zone, clinicName) {
  const byPatient = new Map();
  for (const job of jobs) byPatient.set(job.patientId, [...(byPatient.get(job.patientId) || []), job.appointmentId]);
  const snapshots = await db.getAll(...[...byPatient.keys()].map(id => db.doc(`workspaces/${clinicId}/patients/${id}`)));
  const format = new Intl.DateTimeFormat("en-GB", { timeZone: zone, weekday: "short", day: "numeric", month: "short", hour: "2-digit", minute: "2-digit", hourCycle: "h23" });
  for (const snapshot of snapshots) {
    if (!snapshot.exists) continue;
    const patient = snapshot.data();
    for (const appointmentId of byPatient.get(snapshot.id) || []) {
      const appointment = (patient.appointments || []).find(item => item.id === appointmentId);
      if (!appointment || !["scheduled", "confirmed"].includes(normalized(appointment.status))) continue;
      const when = appointment.startAt ? format.format(new Date(appointment.startAt)) : appointment.date;
      await sendToPatients(clinicId, [snapshot.id], "Dental appointment reminder", [when, appointment.doctor, clinicName].filter(Boolean).join(" · "), { appointmentId });
    }
  }
}

function pushAdmin(request) {
  const uid = actor(request);
  const clinicId = identifier(request.data.clinicId || uid, "clinic ID");
  return { uid, clinicId };
}

/** Admin queues a notification to patients: now (delivered immediately) or at sendAt. */
exports.queuePatientPush = onCall(callOptions, async request => {
  const { uid, clinicId } = pushAdmin(request);
  const title = text(request.data.title, "title", 65).trim(); assert(title, "Enter a title");
  const body = text(request.data.body, "message", 240).trim(); assert(body, "Enter a message");
  const patientIds = [...new Set(array(request.data.patientIds, "patients", 500).map(id => identifier(id, "patient ID")))];
  assert(patientIds.length > 0, "Choose at least one patient");
  const now = Date.now();
  const sendAt = request.data.sendAt === undefined || request.data.sendAt === null ? now : request.data.sendAt;
  assert(Number.isSafeInteger(sendAt) && sendAt >= now - 60000 && sendAt <= now + 60 * 86400000, "Choose a time within the next 60 days");
  const ref = db.doc(`workspaces/${clinicId}`);
  const job = await db.runTransaction(async tx => {
    const snapshot = await tx.get(ref); const clinic = snapshot.exists ? snapshot.data() : null;
    const member = roleFor(clinicId, uid, clinic);
    assert(["owner", "admin"].includes(member.role) || isSuperAdmin(request.auth.token), "Only clinic administrators send patient notifications", "permission-denied");
    const immediate = sendAt <= now + 60000;
    const entry = { id: `push-${fingerprint({ uid, now, title, patientIds }).slice(0, 20)}`, title, body, patientIds, sendAt, createdBy: uid, createdAt: now,
      status: immediate ? "sending" : "scheduled" };
    const jobs = [...(clinic && clinic.patientPushJobs || []), entry].slice(-PUSH_JOB_LIMIT);
    const update = { patientPushJobs: jobs, nextPatientPushAt: nextPatientPush(jobs) };
    if (snapshot.exists) tx.update(ref, update); else tx.set(ref, { config: defaultConfig(clinicId), revision: 0, ...update });
    return entry;
  });
  if (job.status === "sending") {
    const result = await sendToPatients(clinicId, job.patientIds, job.title, job.body);
    await finishPushJob(ref, job.id, result);
    return { id: job.id, status: "sent", ...result };
  }
  return { id: job.id, status: "scheduled", sendAt: job.sendAt };
});

exports.cancelPatientPush = onCall(callOptions, async request => {
  const { uid, clinicId } = pushAdmin(request);
  const jobId = identifier(request.data.jobId, "notification ID");
  const ref = db.doc(`workspaces/${clinicId}`);
  return db.runTransaction(async tx => {
    const snapshot = await tx.get(ref); const clinic = snapshot.exists ? snapshot.data() : null;
    const member = roleFor(clinicId, uid, clinic);
    assert(["owner", "admin"].includes(member.role) || isSuperAdmin(request.auth.token), "Only clinic administrators manage patient notifications", "permission-denied");
    const jobs = (clinic.patientPushJobs || []).map(job => ({ ...job }));
    const job = jobs.find(item => item.id === jobId);
    assert(job && job.status === "scheduled", "Only scheduled notifications can be cancelled", "failed-precondition");
    job.status = "cancelled"; job.cancelledBy = uid; job.cancelledAt = Date.now();
    tx.update(ref, { patientPushJobs: jobs, nextPatientPushAt: nextPatientPush(jobs) });
    return { id: jobId, status: "cancelled" };
  });
});

async function finishPushJob(ref, jobId, result) {
  await db.runTransaction(async tx => {
    const snapshot = await tx.get(ref); if (!snapshot.exists) return;
    const jobs = (snapshot.data().patientPushJobs || []).map(job => ({ ...job }));
    const job = jobs.find(item => item.id === jobId); if (!job) return;
    job.status = result.delivered > 0 ? (result.failed ? "partly_sent" : "sent") : "no_devices";
    job.delivered = result.delivered; job.failed = result.failed; job.finishedAt = Date.now();
    tx.update(ref, { patientPushJobs: jobs, nextPatientPushAt: nextPatientPush(jobs) });
  });
}

/** Every 5 minutes: one query for clinics with a due patient notification; claim, send, record. */
exports.dispatchPatientPushes = onSchedule({ schedule: "every 5 minutes", timeZone: "UTC", timeoutSeconds: 300 }, async () => {
  const due = await db.collection("workspaces").where("nextPatientPushAt", "<=", Date.now()).limit(50).get();
  for (const snapshot of due.docs) {
    const claimed = await db.runTransaction(async tx => {
      const fresh = await tx.get(snapshot.ref); if (!fresh.exists) return [];
      const now = Date.now();
      const jobs = (fresh.data().patientPushJobs || []).map(job => ({ ...job }));
      const ready = jobs.filter(job => job.status === "scheduled" && job.sendAt <= now).slice(0, 10);
      for (const job of ready) { job.status = "sending"; job.claimedAt = now; }
      tx.update(snapshot.ref, { patientPushJobs: jobs, nextPatientPushAt: nextPatientPush(jobs) });
      return ready;
    });
    for (const job of claimed) {
      const result = await sendToPatients(snapshot.id, job.patientIds, job.title, job.body).catch(() => ({ delivered: 0, failed: job.patientIds.length }));
      await finishPushJob(snapshot.ref, job.id, result);
    }
  }
});
