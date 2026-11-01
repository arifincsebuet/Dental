"use strict";
// Exercises pure server validation without loading Firebase SDKs or contacting services.
// This does not emulate callable transactions, Firestore Rules, Android, or FCM delivery.
const test = require("node:test");
const assert = require("node:assert/strict");
const vm = require("node:vm");
const fs = require("node:fs");
const path = require("node:path");

const registered = {};
class HttpsError extends Error {
  constructor(code, message, details) { super(message); this.code = code; this.details = details; }
}
const modules = {
  "firebase-admin/app": { initializeApp() {} },
  "firebase-admin/firestore": { getFirestore: () => ({}) },
  "firebase-admin/messaging": { getMessaging: () => ({}) },
  "firebase-admin/storage": { getStorage: () => ({}) },
  "firebase-admin/auth": { getAuth: () => ({}) },
  "firebase-functions/v2/https": { HttpsError, onCall: (_, handler) => handler },
  "firebase-functions/v2/scheduler": { onSchedule: (_, handler) => handler },
  "firebase-functions/v2": { setGlobalOptions() {} },
  "node:crypto": require("node:crypto")
};
const source = fs.readFileSync(path.join(__dirname, "..", "index.js"), "utf8");
vm.runInNewContext(source + "\nmodule.exports.audit = {stampNewEntries, validateClinical, validateChart, rebuildClinicReminders, hasReminderMetadata, staffInvites, applyStaffInvites, patientEmail, randomText};", {
  exports: registered, module: { exports: registered }, Buffer, process: { env: {} },
  require(name) { assert.ok(modules[name], `Unexpected dependency ${name}`); return modules[name]; }
});
const { stampNewEntries, validateClinical, validateChart, rebuildClinicReminders } = registered.audit;
const now = 1_800_000_000_000;
const chart = () => ({ schemaVersion: 2, catalogVersion: "fdi-v1", dentition: "permanent", teeth: {} });
const patient = encounter => ({ id: "patient-1", chart: chart(), treatments: [], encounters: [encounter] });
const encounter = () => ({ id: "encounter-1", doctor: "Dentist", complaint: "Review", status: "Draft", at: now, amendments: [] });

test("editable existing records retain their server author and timestamp", () => {
  const old = [{ id: "record-1", recordedBy: "original", recordedAt: 123 }];
  const incoming = [{ id: "record-1", recordedBy: "forged", recordedAt: 999 }];
  stampNewEntries(old, incoming, "editor", now);
  assert.equal(incoming[0].recordedBy, "original"); assert.equal(incoming[0].recordedAt, 123);
});
test("legacy records cannot acquire forged prior authorship", () => {
  const incoming = [{ id: "legacy", recordedBy: "forged", recordedAt: 999 }];
  stampNewEntries([{ id: "legacy" }], incoming, "editor", now);
  assert.equal(incoming[0].recordedBy, undefined); assert.equal(incoming[0].recordedAt, undefined);
});
test("nested payments preserve authorship while a new event receives server metadata", () => {
  const previous = [{ id: "invoice", payments: [{ id: "payment", recordedBy: "cashier", recordedAt: 123 }] }];
  const incoming = [{ id: "invoice", payments: [{ id: "payment", recordedBy: "forged", recordedAt: 999 }, { id: "new-payment", recordedBy: "forged" }] }];
  stampNewEntries(previous, incoming, "current-cashier", now);
  assert.equal(incoming[0].payments[0].recordedBy, "cashier");
  assert.equal(incoming[0].payments[1].recordedBy, "current-cashier"); assert.equal(incoming[0].payments[1].recordedAt, now);
});
test("new encounters require supported state, clinician and complaint", () => {
  for (const change of [{ status: "Anything" }, { doctor: "" }, { complaint: "" }]) {
    assert.throws(() => validateClinical(patient({ ...encounter(), ...change }), null, { role: "dentist" }));
  }
  assert.doesNotThrow(() => validateClinical(patient(encounter()), null, { role: "dentist" }));
});
test("encounter amendments require both reason and correction", () => {
  const value = encounter(); value.amendments = [{ id: "amendment", reason: "Correction", text: "" }];
  assert.throws(() => validateClinical(patient(value), null, { role: "dentist" }), /correction/i);
});
test("primary canines use I while permanent molars use O", () => {
  const tooth = surface => ({ assessment: "assessed", presence: "present", surfaces: { [surface]: { assessment: "assessed", findings: [], restorations: [] } } });
  assert.doesNotThrow(() => validateChart({ ...chart(), dentition: "mixed", teeth: { "53": tooth("I"), "16": tooth("O") } }, "patient-1"));
  assert.throws(() => validateChart({ ...chart(), dentition: "mixed", teeth: { "53": tooth("O") } }, "patient-1"), /surface/i);
});
function clinic(enabled = true) {
  return { config: { remindersEnabled: enabled, reminderHours: 24 }, scheduleIndex: [{ patientId: "patient", appointmentId: "appointment", startAt: now + 48 * 3_600_000, reminderEnabled: true, reminderConsent: true, reminderHours: null }], reminderJobs: [] };
}
test("enabling reminders schedules already indexed consented appointments", () => {
  const value = clinic(); const jobs = rebuildClinicReminders(value, now);
  assert.equal(jobs.length, 1); assert.equal(jobs[0].dueAt, now + 24 * 3_600_000);
});
test("lead changes rebuild pending jobs without resending completed jobs", () => {
  const value = clinic(); value.reminderJobs = rebuildClinicReminders(value, now); value.config.reminderHours = 12;
  const rebuilt = rebuildClinicReminders(value, now);
  assert.equal(rebuilt.length, 1); assert.equal(rebuilt[0].dueAt, now + 36 * 3_600_000);
  value.reminderJobs[0].status = "sent";
  assert.equal(rebuildClinicReminders(value, now).length, 1);
});
test("disabled or non-consented reminders are never invented from unknown data", () => {
  assert.equal(rebuildClinicReminders(clinic(false), now).length, 0);
  const denied = clinic(); denied.scheduleIndex[0].reminderConsent = false;
  assert.equal(rebuildClinicReminders(denied, now).length, 0);
  const legacy = clinic(); delete legacy.scheduleIndex[0].reminderConsent;
  assert.equal(rebuildClinicReminders(legacy, now).length, 0);
});

test("staff invites follow the active staff list and deactivate removed members", () => {
  const { applyStaffInvites, staffInvites } = registered.audit;
  const clinic = { config: { staff: [
    { name: "Dr A", role: "dentist", email: " DrA@Example.com " },
    { name: "Rec", role: "receptionist", email: "rec@example.com", active: false },
    { name: "Bad", role: "dentist", email: "not-an-email" }],
    members: { owner1: { role: "owner", active: true }, u2: { role: "dentist", active: true, email: "gone@example.com" }, u3: { role: "assistant", active: true, email: "dra@example.com" } } } };
  assert.equal(JSON.stringify(staffInvites(clinic.config)), JSON.stringify({ "dra@example.com": "dentist" }));
  applyStaffInvites(clinic, "owner1");
  assert.equal(JSON.stringify(clinic.inviteEmails), JSON.stringify(["dra@example.com"]));
  assert.equal(clinic.config.members.u2.active, false);
  assert.equal(clinic.config.members.u3.role, "dentist");
  assert.equal(clinic.config.members.owner1.active, true);
});

test("patient login identifiers are short, unambiguous and map to a synthetic email", () => {
  const { patientEmail, randomText } = registered.audit;
  const id = randomText("ABCDEFGHJKMNPQRSTUVWXYZ23456789", 8);
  assert.match(id, /^[A-HJ-KM-NP-Z2-9]{8}$/);
  assert.equal(patientEmail("AB23CD45"), "ab23cd45@patient.dentalcare.local");
});
