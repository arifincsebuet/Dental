import { initializeTestEnvironment, assertSucceeds, assertFails } from "@firebase/rules-unit-testing";
import { doc, getDoc, setDoc, updateDoc, deleteDoc, collection, query, where, getDocs, serverTimestamp, arrayUnion, arrayRemove, runTransaction } from "firebase/firestore";
import { readFileSync } from "node:fs";

// Firestore security-rules tests against the local emulator (no cloud project is touched).
//   cd firebase/tests && npm install && npm test

const env = await initializeTestEnvironment({ projectId: "dental-test", firestore: { rules: readFileSync(new URL("../firestore.rules", import.meta.url), "utf8"), host: "127.0.0.1", port: 8080 } });
let pass = 0, fail = 0;
async function t(name, p) { try { await p; pass++; console.log("ok  ", name); } catch (e) { fail++; console.log("FAIL", name, "-", e.message.split("\n")[0]); } }

const OWNER = "owner1", C = OWNER;
const owner = env.authenticatedContext(OWNER, { email: "boss@clinic.com", email_verified: true }).firestore();
const recep = env.authenticatedContext("recep1", { email: "desk@clinic.com", email_verified: true }).firestore();
const dent = env.authenticatedContext("dent1", { email: "doc@clinic.com", email_verified: true }).firestore();
const cash = env.authenticatedContext("cash1", { email: "cash@clinic.com", email_verified: true }).firestore();
const stranger = env.authenticatedContext("x1", { email: "x@evil.com", email_verified: true }).firestore();
const anon = env.unauthenticatedContext().firestore(); // the patient app: no Firebase sign-in
const superDb = env.authenticatedContext("sup1", { email: "dev.spala@gmail.com", email_verified: true }).firestore();

const config = { name: "Smile", timeZone: "Asia/Dhaka", members: { [OWNER]: { role: "owner", active: true } }, staff: [] };
const invites = {
  "desk@clinic.com": { role: "receptionist" },
  "doc@clinic.com": { role: "dentist", permissions: { recordTreatment: true, billing: false, patientLogins: false } },
  "cash@clinic.com": "cashier", // older string format
};
await t("owner creates clinic rev1", setDoc(doc(owner, `workspaces/${C}`), { config, revision: 1, invites, inviteEmails: Object.keys(invites) }));
await t("owner cannot create a clinic for others", assertFails(setDoc(doc(owner, `workspaces/other`), { config, revision: 1 })));
await t("create with subscription denied", assertFails(setDoc(doc(stranger, `workspaces/x1`), { config, revision: 1, subscription: { active: true } })));
await t("non-super complimentary denied", assertFails(setDoc(doc(stranger, `workspaces/x1`), { config, revision: 1, complimentary: true })));
await t("super admin complimentary ok", assertSucceeds(setDoc(doc(superDb, `workspaces/sup1`), { config: { members: {} }, revision: 1, complimentary: true })));
await t("owner writes public info", assertSucceeds(setDoc(doc(owner, `workspaces/${C}/public/info`), { name: "Smile", availability: [], busy: [] }, { merge: true })));
await t("stranger cannot read clinic", assertFails(getDoc(doc(stranger, `workspaces/${C}`))));
await t("patient app (no sign-in) reads public info", assertSucceeds(getDoc(doc(anon, `workspaces/${C}/public/info`))));
await t("no sign-in cannot write public info", assertFails(setDoc(doc(anon, `workspaces/${C}/public/info`), { name: "hacked" }, { merge: true })));

// invited staff join
await t("invite query", assertSucceeds(getDocs(query(collection(recep, "workspaces"), where("inviteEmails", "array-contains", "desk@clinic.com")))));
await t("stranger invite query denied", assertFails(getDocs(query(collection(stranger, "workspaces"), where("inviteEmails", "array-contains", "desk@clinic.com")))));
await t("join with wrong role denied", assertFails(updateDoc(doc(recep, `workspaces/${C}`), { "config.members.recep1": { role: "admin", active: true, email: "desk@clinic.com" } })));
await t("join other uid denied", assertFails(updateDoc(doc(recep, `workspaces/${C}`), { "config.members.someone": { role: "receptionist", active: true, email: "desk@clinic.com" } })));
await t("receptionist joins (transaction)", assertSucceeds(runTransaction(recep, async tx => { const s = await tx.get(doc(recep, `workspaces/${C}`)); tx.update(doc(recep, `workspaces/${C}`), "config.members.recep1", { role: s.data().invites["desk@clinic.com"].role, active: true, email: "desk@clinic.com" }); })));
await t("dentist joins without invited permissions denied", assertFails(updateDoc(doc(dent, `workspaces/${C}`), { "config.members.dent1": { role: "dentist", active: true, email: "doc@clinic.com" } })));
await t("dentist joins with invited permissions", assertSucceeds(updateDoc(doc(dent, `workspaces/${C}`), { "config.members.dent1": { role: "dentist", active: true, email: "doc@clinic.com", permissions: { recordTreatment: true, billing: false, patientLogins: false } } })));
await t("cashier joins (older string invite)", assertSucceeds(updateDoc(doc(cash, `workspaces/${C}`), { "config.members.cash1": { role: "cashier", active: true, email: "cash@clinic.com" } })));
await t("member reads clinic", assertSucceeds(getDoc(doc(recep, `workspaces/${C}`))));
await t("receptionist cannot edit clinic name", assertFails(updateDoc(doc(recep, `workspaces/${C}`), { revision: 2, "config.name": "X" })));
await t("receptionist opens slots (manageSlots)", assertSucceeds(updateDoc(doc(recep, `workspaces/${C}`), { revision: 2, "config.availability": [{ id: "a", doctor: "Dr A" }], updatedBy: "recep1" })));
await t("cashier cannot open slots", assertFails(updateDoc(doc(cash, `workspaces/${C}`), { revision: 3, "config.availability": [] })));
await t("receptionist cannot change roles via slots path", assertFails(updateDoc(doc(recep, `workspaces/${C}`), { revision: 3, "config.availability": [], "config.members.recep1.role": "admin" })));
await t("owner settings stale revision denied", assertFails(updateDoc(doc(owner, `workspaces/${C}`), { revision: 5, "config.name": "X" })));
await t("owner settings rev+1 ok", assertSucceeds(updateDoc(doc(owner, `workspaces/${C}`), { revision: 3, "config.name": "Smile 2" })));
await t("owner subscription ok", assertSucceeds(updateDoc(doc(owner, `workspaces/${C}`), { subscription: { active: true, expiresAt: 1 } })));
await t("dentist subscription denied", assertFails(updateDoc(doc(dent, `workspaces/${C}`), { subscription: { active: true } })));
await t("owner complimentary denied", assertFails(updateDoc(doc(owner, `workspaces/${C}`), { revision: 4, complimentary: true })));
await t("dentist cannot manage team", assertFails(updateDoc(doc(dent, `workspaces/${C}`), { revision: 4, "config.members.dent1.role": "admin" })));
await t("owner grants dentist manageTeam", assertSucceeds(updateDoc(doc(owner, `workspaces/${C}`), { revision: 4, "config.members.dent1.permissions.manageTeam": true })));
await t("dentist with manageTeam edits settings", assertSucceeds(updateDoc(doc(dent, `workspaces/${C}`), { revision: 5, "config.name": "Smile 3" })));
await t("owner revokes manageTeam", assertSucceeds(updateDoc(doc(owner, `workspaces/${C}`), { revision: 6, "config.members.dent1.permissions.manageTeam": false })));

// patients
const P = `workspaces/${C}/patients/p1`;
const base = { id: "p1", name: "Ann", revision: 1, syncedAt: serverTimestamp(), appointments: [], history: [], story: "", invoices: [] };
await t("receptionist creates patient", assertSucceeds(setDoc(doc(recep, P), base)));
await t("cashier cannot create patient (no managePatients)", assertFails(setDoc(doc(cash, `workspaces/${C}/patients/p5`), { ...base, id: "p5" })));
await t("create rev2 denied", assertFails(setDoc(doc(recep, `workspaces/${C}/patients/p9`), { ...base, id: "p9", revision: 2 })));
await t("create wrong id denied", assertFails(setDoc(doc(owner, `workspaces/${C}/patients/p7`), { ...base, id: "pX" })));
await t("stranger cannot read patient", assertFails(getDoc(doc(stranger, P))));
await t("no sign-in cannot read patient", assertFails(getDoc(doc(anon, P))));
await t("staff list patients since", assertSucceeds(getDocs(query(collection(recep, `workspaces/${C}/patients`), where("syncedAt", ">", new Date(0))))));
await t("receptionist edits clinical denied", assertFails(setDoc(doc(recep, P), { ...base, revision: 2, story: "x" })));
await t("receptionist edits appointments ok", assertSucceeds(setDoc(doc(recep, P), { ...base, revision: 2, appointments: [{ id: "a1" }] })));
await t("stale revision denied", assertFails(setDoc(doc(dent, P), { ...base, revision: 2, story: "y" })));
await t("dentist edits clinical ok", assertSucceeds(setDoc(doc(dent, P), { ...base, revision: 3, appointments: [{ id: "a1" }], story: "y" })));
await t("dentist edits invoices denied (billing off)", assertFails(setDoc(doc(dent, P), { ...base, revision: 4, appointments: [{ id: "a1" }], story: "y", invoices: [{ id: "i" }] })));
await t("cashier edits invoices ok", assertSucceeds(setDoc(doc(cash, P), { ...base, revision: 4, appointments: [{ id: "a1" }], story: "y", invoices: [{ id: "i" }] })));
await t("owner cannot delete patient", assertFails(deleteDoc(doc(owner, P))));
await t("dentist sets portal denied (patientLogins off)", assertFails(updateDoc(doc(dent, P), { portal: { loginId: "ANN1", key: "k1" }, revision: 5, syncedAt: serverTimestamp() })));
await t("receptionist sets portal ok", assertSucceeds(updateDoc(doc(recep, P), { portal: { loginId: "ANN1", key: "k1" }, revision: 5, syncedAt: serverTimestamp() })));

// patient app portal (no Firebase sign-in)
const K = "k1";
const portal = { clinicId: C, patientId: "p1", loginId: "ANN1", record: { name: "Ann", appointments: [] }, requests: [], pending: false };
await t("dentist cannot create portal (patientLogins off)", assertFails(setDoc(doc(dent, `portals/${K}`), portal)));
await t("stranger cannot create portal for clinic", assertFails(setDoc(doc(stranger, `portals/${K}`), portal)));
await t("receptionist creates portal", assertSucceeds(setDoc(doc(recep, `portals/${K}`), portal)));
await t("patient app reads portal by key", assertSucceeds(getDoc(doc(anon, `portals/${K}`))));
await t("patient app cannot list portals", assertFails(getDocs(collection(anon, "portals"))));
await t("staff list pending portals of own clinic", assertSucceeds(getDocs(query(collection(recep, "portals"), where("clinicId", "==", C), where("pending", "==", true)))));
await t("stranger list portals of clinic denied", assertFails(getDocs(query(collection(stranger, "portals"), where("clinicId", "==", C)))));
await t("patient app books (transaction)", assertSucceeds(runTransaction(anon, async tx => {
  const pub = await tx.get(doc(anon, `workspaces/${C}/public/info`)); const s = await tx.get(doc(anon, `portals/${K}`)); const d = s.data();
  const appt = { id: "a2", status: "Scheduled", startAt: 1 };
  tx.update(doc(anon, `portals/${K}`), { requests: [...d.requests, { id: "r1", type: "book", appointment: appt }], "record.appointments": [...d.record.appointments, appt], pending: true, updatedAt: 1 });
  tx.update(doc(anon, `workspaces/${C}/public/info`), { busy: arrayUnion({ s: 1, e: 2, d: "dr a" }), lastPortal: K });
})));
await t("busy change without portal request denied", assertFails(updateDoc(doc(anon, `workspaces/${C}/public/info`), { busy: arrayUnion({ s: 5, e: 6, d: "x" }), lastPortal: K })));
await t("patient app replaces record name denied", assertFails(getDoc(doc(anon, `portals/${K}`)).then(s => { const d = s.data(); return updateDoc(doc(anon, `portals/${K}`), { requests: [...d.requests, { id: "r9" }], "record.name": "Hacker", pending: true }); })));
await t("patient app wipes requests denied", assertFails(updateDoc(doc(anon, `portals/${K}`), { requests: [{ id: "x" }], pending: true })));
await t("patient app changes clinicId denied", assertFails(updateDoc(doc(anon, `portals/${K}`), { clinicId: "other" })));
await t("patient app cancels (transaction)", assertSucceeds(runTransaction(anon, async tx => {
  const pub = await tx.get(doc(anon, `workspaces/${C}/public/info`)); const s = await tx.get(doc(anon, `portals/${K}`)); const d = s.data();
  tx.update(doc(anon, `portals/${K}`), { requests: [...d.requests, { id: "r2", type: "cancel", appointmentId: "a2" }], "record.appointments": d.record.appointments.map(a => ({ ...a, status: "Cancelled" })), pending: true, updatedAt: 2 });
  tx.update(doc(anon, `workspaces/${C}/public/info`), { busy: arrayRemove({ s: 1, e: 2, d: "dr a" }), lastPortal: K });
})));
await t("patient app cannot delete portal", assertFails(deleteDoc(doc(anon, `portals/${K}`))));
await t("staff refreshes portal and clears requests", assertSucceeds(updateDoc(doc(recep, `portals/${K}`), { requests: [], pending: false, record: { name: "Ann", appointments: [] } })));
await t("staff cannot move portal to another patient", assertFails(updateDoc(doc(recep, `portals/${K}`), { patientId: "p2" })));
await t("stranger cannot delete portal", assertFails(deleteDoc(doc(stranger, `portals/${K}`))));
await t("receptionist deletes portal (reset)", assertSucceeds(deleteDoc(doc(recep, `portals/${K}`))));

// owner deactivates receptionist → access lost
await t("owner deactivates member", assertSucceeds(updateDoc(doc(owner, `workspaces/${C}`), { revision: 7, "config.members.recep1.active": false, invites: { "doc@clinic.com": invites["doc@clinic.com"] }, inviteEmails: ["doc@clinic.com"] })));
await t("deactivated cannot read patient", assertFails(getDoc(doc(recep, P))));
await t("deactivated cannot rejoin", assertFails(updateDoc(doc(recep, `workspaces/${C}`), { "config.members.recep1": { role: "receptionist", active: true, email: "desk@clinic.com" } })));

console.log(`\n${pass} passed, ${fail} failed`);
await env.cleanup();
process.exit(fail ? 1 : 0);
