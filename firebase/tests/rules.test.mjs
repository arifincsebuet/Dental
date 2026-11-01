import { initializeTestEnvironment, assertSucceeds, assertFails } from "@firebase/rules-unit-testing";
import { doc, getDoc, setDoc, updateDoc, deleteDoc, collection, collectionGroup, query, where, getDocs, serverTimestamp, arrayUnion, arrayRemove, runTransaction, deleteField } from "firebase/firestore";
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
const stranger = env.authenticatedContext("x1", { email: "x@evil.com", email_verified: true }).firestore();
const patientDb = env.authenticatedContext("pat1", { email: "abcd2345@patient.dentalcare.local", email_verified: false }).firestore();
const otherPatient = env.authenticatedContext("pat2", { email: "zzzz2345@patient.dentalcare.local" }).firestore();
const superDb = env.authenticatedContext("sup1", { email: "dev.spala@gmail.com", email_verified: true }).firestore();

const config = { name: "Smile", timeZone: "Asia/Dhaka", members: { [OWNER]: { role: "owner", active: true } }, staff: [] };
await t("owner creates clinic rev1", setDoc(doc(owner, `workspaces/${C}`), { config, revision: 1, invites: { "desk@clinic.com": "receptionist", "doc@clinic.com": "dentist" }, inviteEmails: ["desk@clinic.com", "doc@clinic.com"] }));
await t("owner cannot create a 2nd clinic for others", assertFails(setDoc(doc(owner, `workspaces/other`), { config, revision: 1 })));
await t("create with subscription denied", assertFails(setDoc(doc(stranger, `workspaces/x1`), { config, revision: 1, subscription: { active: true } })));
await t("non-super complimentary denied", assertFails(setDoc(doc(stranger, `workspaces/x1`), { config, revision: 1, complimentary: true })));
await t("super admin complimentary ok", assertSucceeds(setDoc(doc(superDb, `workspaces/sup1`), { config: { members: {} }, revision: 1, complimentary: true })));
await t("owner writes public info", assertSucceeds(setDoc(doc(owner, `workspaces/${C}/public/info`), { name: "Smile", availability: [], busy: [] }, { merge: true })));
await t("stranger cannot read clinic", assertFails(getDoc(doc(stranger, `workspaces/${C}`))));
await t("stranger reads public info", assertSucceeds(getDoc(doc(stranger, `workspaces/${C}/public/info`))));
await t("stranger cannot write public info", assertFails(setDoc(doc(stranger, `workspaces/${C}/public/info`), { name: "hacked" }, { merge: true })));

// invited staff join
await t("invite query", assertSucceeds(getDocs(query(collection(recep, "workspaces"), where("inviteEmails", "array-contains", "desk@clinic.com")))));
await t("stranger invite query denied", assertFails(getDocs(query(collection(stranger, "workspaces"), where("inviteEmails", "array-contains", "desk@clinic.com")))));
await t("join with wrong role denied", assertFails(updateDoc(doc(recep, `workspaces/${C}`), { "config.members.recep1": { role: "admin", active: true, email: "desk@clinic.com" } })));
await t("join other uid denied", assertFails(updateDoc(doc(recep, `workspaces/${C}`), { "config.members.someone": { role: "receptionist", active: true, email: "desk@clinic.com" } })));
await t("receptionist joins (transaction)", assertSucceeds(runTransaction(recep, async tx => { const s = await tx.get(doc(recep, `workspaces/${C}`)); tx.update(doc(recep, `workspaces/${C}`), "config.members.recep1", { role: s.data().invites["desk@clinic.com"], active: true, email: "desk@clinic.com" }); })));
await t("dentist joins", assertSucceeds(updateDoc(doc(dent, `workspaces/${C}`), { "config.members.dent1": { role: "dentist", active: true, email: "doc@clinic.com" } })));
await t("member reads clinic", assertSucceeds(getDoc(doc(recep, `workspaces/${C}`))));
await t("receptionist cannot edit settings", assertFails(updateDoc(doc(recep, `workspaces/${C}`), { revision: 2, "config.name": "X" })));
await t("owner settings stale revision denied", assertFails(updateDoc(doc(owner, `workspaces/${C}`), { revision: 5, "config.name": "X" })));
await t("owner settings rev+1 ok", assertSucceeds(updateDoc(doc(owner, `workspaces/${C}`), { revision: 2, "config.name": "Smile 2" })));
await t("owner subscription ok", assertSucceeds(updateDoc(doc(owner, `workspaces/${C}`), { subscription: { active: true, expiresAt: 1 } })));
await t("dentist subscription denied", assertFails(updateDoc(doc(dent, `workspaces/${C}`), { subscription: { active: true } })));
await t("owner complimentary denied", assertFails(updateDoc(doc(owner, `workspaces/${C}`), { revision: 3, complimentary: true })));

// patients
const P = `workspaces/${C}/patients/p1`;
const base = { id: "p1", name: "Ann", revision: 1, syncedAt: serverTimestamp(), appointments: [], history: [], story: "", invoices: [] };
await t("receptionist creates patient", assertSucceeds(setDoc(doc(recep, P), base)));
await t("create rev2 denied", assertFails(setDoc(doc(recep, `workspaces/${C}/patients/p9`), { ...base, id: "p9", revision: 2 })));
await t("create with portal denied", assertFails(setDoc(doc(owner, `workspaces/${C}/patients/p8`), { ...base, id: "p8", portal: { uid: "pat1" } })));
await t("create wrong id denied", assertFails(setDoc(doc(owner, `workspaces/${C}/patients/p7`), { ...base, id: "pX" })));
await t("stranger cannot read patient", assertFails(getDoc(doc(stranger, P))));
await t("staff list patients since", assertSucceeds(getDocs(query(collection(recep, `workspaces/${C}/patients`), where("syncedAt", ">", new Date(0))))));
await t("receptionist edits clinical denied", assertFails(setDoc(doc(recep, P), { ...base, revision: 2, story: "x" })));
await t("receptionist edits appointments ok", assertSucceeds(setDoc(doc(recep, P), { ...base, revision: 2, appointments: [{ id: "a1" }] })));
await t("stale revision denied", assertFails(setDoc(doc(dent, P), { ...base, revision: 2, story: "y" })));
await t("dentist edits clinical ok", assertSucceeds(setDoc(doc(dent, P), { ...base, revision: 3, appointments: [{ id: "a1" }], story: "y" })));
await t("dentist edits invoices denied", assertFails(setDoc(doc(dent, P), { ...base, revision: 4, appointments: [{ id: "a1" }], story: "y", invoices: [{ id: "i" }] })));
await t("owner cannot delete patient", assertFails(deleteDoc(doc(owner, P))));
await t("receptionist sets portal denied", assertFails(updateDoc(doc(recep, P), { portal: { uid: "pat1", loginId: "ABCD2345", mustChange: true }, revision: 4, syncedAt: serverTimestamp() })));
await t("dentist sets portal ok", assertSucceeds(updateDoc(doc(dent, P), { portal: { uid: "pat1", loginId: "ABCD2345", mustChange: true }, revision: 4, syncedAt: serverTimestamp(), updatedBy: "dent1" })));

// patient app
await t("patient finds record (collection group)", assertSucceeds(getDocs(query(collectionGroup(patientDb, "patients"), where("portal.uid", "==", "pat1")))));
await t("patient reads own record", assertSucceeds(getDoc(doc(patientDb, P))));
await t("other patient cannot read", assertFails(getDoc(doc(otherPatient, P))));
await t("other patient query denied w/o filter", assertFails(getDocs(collectionGroup(otherPatient, "patients"))));
await t("patient cannot read clinic doc", assertFails(getDoc(doc(patientDb, `workspaces/${C}`))));
await t("patient reads public info", assertSucceeds(getDoc(doc(patientDb, `workspaces/${C}/public/info`))));
await t("patient clears mustChange", assertSucceeds(updateDoc(doc(patientDb, P), { "portal.mustChange": false })));
await t("patient cannot change portal uid", assertFails(updateDoc(doc(patientDb, P), { "portal.uid": "pat2" })));
await t("patient cannot edit clinical", assertFails(updateDoc(doc(patientDb, P), { story: "hack", revision: 5, syncedAt: serverTimestamp(), updatedBy: "pat1" })));
await t("patient books (transaction)", assertSucceeds(runTransaction(patientDb, async tx => {
  const pub = await tx.get(doc(patientDb, `workspaces/${C}/public/info`)); const s = await tx.get(doc(patientDb, P)); const d = s.data();
  tx.update(doc(patientDb, P), { appointments: [...d.appointments, { id: "a2", status: "Scheduled" }], history: [...d.history, { id: "h" }], revision: d.revision + 1, updatedAt: 1, updatedBy: "pat1", syncedAt: serverTimestamp() });
  tx.update(doc(patientDb, `workspaces/${C}/public/info`), "busy", arrayUnion({ s: 1, e: 2, d: "dr" }));
})));
await t("patient adds 2 appointments denied", assertFails(getDoc(doc(patientDb, P)).then(s => { const d = s.data(); return updateDoc(doc(patientDb, P), { appointments: [...d.appointments, { id: "x" }, { id: "y" }], history: [...d.history, { id: "h2" }], revision: d.revision + 1, updatedBy: "pat1", syncedAt: serverTimestamp() }); })));
await t("patient wipes history denied", assertFails(getDoc(doc(patientDb, P)).then(s => { const d = s.data(); return updateDoc(doc(patientDb, P), { appointments: d.appointments, history: [{ id: "only" }], revision: d.revision + 1, updatedBy: "pat1", syncedAt: serverTimestamp() }); })));
await t("patient cancels (status change)", assertSucceeds(getDoc(doc(patientDb, P)).then(s => { const d = s.data(); return updateDoc(doc(patientDb, P), { appointments: d.appointments.map(a => a.id === "a2" ? { ...a, status: "Cancelled" } : a), history: [...d.history, { id: "h3" }], revision: d.revision + 1, updatedAt: 2, updatedBy: "pat1", syncedAt: serverTimestamp() }); })));
await t("patient removes busy", assertSucceeds(updateDoc(doc(patientDb, `workspaces/${C}/public/info`), { busy: arrayRemove({ s: 1, e: 2, d: "dr" }) })));
await t("patient replaces busy denied", assertFails(updateDoc(doc(patientDb, `workspaces/${C}/public/info`), { busy: [{ s: 9, e: 9, d: "a" }, { s: 8, e: 8, d: "b" }] })));
await t("patient edits availability denied", assertFails(updateDoc(doc(patientDb, `workspaces/${C}/public/info`), { availability: [{}] })));
await t("patient account cannot use staff path", assertFails(getDocs(collection(patientDb, `workspaces/${C}/patients`))));
await t("other patient cannot update record", assertFails(updateDoc(doc(otherPatient, P), { "portal.mustChange": false })));

// owner deactivates receptionist → access lost
await t("owner deactivates member", assertSucceeds(updateDoc(doc(owner, `workspaces/${C}`), { revision: 3, "config.members.recep1.active": false, invites: { "doc@clinic.com": "dentist" }, inviteEmails: ["doc@clinic.com"] })));
await t("deactivated cannot read patient", assertFails(getDoc(doc(recep, P))));
await t("deactivated cannot rejoin", assertFails(updateDoc(doc(recep, `workspaces/${C}`), { "config.members.recep1": { role: "receptionist", active: true, email: "desk@clinic.com" } })));

console.log(`\n${pass} passed, ${fail} failed`);
await env.cleanup();
process.exit(fail ? 1 : 0);
