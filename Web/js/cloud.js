// Cloud access, a port of ClinicCloud.kt. Same Firestore documents and rules as the Android app:
//   workspaces/{clinicId}, workspaces/{clinicId}/public/info, workspaces/{clinicId}/patients/{id}, portals/{key}
// When js/config.js has no Firebase settings the app runs on a local demo backend instead.
import { clone, uid, zoned } from "./util.js";
import { STAFF_ROLE, ACCESS, roleDefault, patientView, slotsFor, defaults } from "./domain.js";

const SDK = "https://www.gstatic.com/firebasejs/11.0.2/";
const MAX_BYTES = 750000;
const LOCAL_FIELDS = ["dirty", "operationId", "remoteVersion", "cloudOwner", "retainedAt", "syncedAt", "patientDevices"];

import { createDentalCloud, Conflict, sha256Hex } from "../sdk/dental-cloud.js";
export { Conflict };

export function staffInvites(config) {
  const invites = {};
  for (const person of config.staff || []) {
    const email = String(person.email || "").trim().toLowerCase();
    if (!email || !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email) || person.active === false) continue;
    const role = STAFF_ROLE[person.role] || "dentist";
    const invite = { role };
    if (person.permissions) invite.permissions = Object.fromEntries(ACCESS.map(([k]) => [k, k in person.permissions ? person.permissions[k] === true : roleDefault(role, k)]));
    invites[email] = invite;
  }
  return invites;
}
function applyStaffInvites(config, clinicId, invites) {
  const members = (config.members ||= {});
  for (const id of Object.keys(members)) {
    const m = members[id];
    if (id === clinicId || !m.email) continue;
    const inv = invites[m.email];
    m.active = !!inv;
    if (inv) { m.role = inv.role; if (inv.permissions) m.permissions = inv.permissions; else delete m.permissions; }
  }
}
export const publicInfo = (c) => ({ name: c.name || "Dental Care", timeZone: c.timeZone || "Asia/Dhaka", currency: c.currency || "BDT",
  availability: clone(c.availability || []), patientBooking: c.patientBooking !== false, remindersEnabled: !!c.remindersEnabled, reminderHours: c.reminderHours || 24,
  logo: c.logo || "", address: c.address || "", clinicPhone: c.clinicPhone || "", mapUrl: c.mapUrl || "", brandColor: c.brandColor || "" });
const clinicView = (c) => ({ name: c.name || "Dental Care", timeZone: c.timeZone || "Asia/Dhaka", currency: c.currency || "BDT" });
const stripLocal = (p) => { const d = clone(p); LOCAL_FIELDS.forEach((k) => delete d[k]); return d; };

/** Shared booking rules for the patient app (ClinicCloud.bookSlot / cancelMyAppointment). */
function buildBooking(portal, clinic, startAt, doctor, reason) {
  if (clinic.patientBooking === false) throw new Error("Online booking is turned off for this clinic");
  const now = Date.now();
  const date = zoned(startAt, clinic.timeZone || "Asia/Dhaka").date;
  const slot = slotsFor(clinic, [], date, clinic.busy || [], now).find((s) => s.startAt === startAt && s.doctor.toLowerCase() === doctor.trim().toLowerCase());
  if (!slot) throw new Error("That slot is not offered");
  if (!slot.available) throw new Error("That slot was just taken. Please choose another time.");
  const record = portal.record || {};
  const appointments = clone(record.appointments || []);
  const upcoming = appointments.filter((a) => ["Scheduled", "Confirmed", "Requested"].includes(a.status) && a.startAt > now).length;
  if (upcoming >= 3) throw new Error("You already have 3 upcoming appointments. Contact the clinic to book more.");
  const appointment = { id: "apt-" + uid().replace(/-/g, "").slice(0, 20), date: `${slot.date} ${slot.time}`, startAt: slot.startAt, durationMinutes: slot.durationMinutes,
    doctor: slot.doctor, room: slot.room, reason: (reason.trim() || "Check-up").slice(0, 500), status: "Scheduled", bookedBy: "patient", bookedAt: now, slot: true };
  appointments.push(appointment);
  const requests = [...(portal.requests || []), { id: "req-" + uid(), type: "book", at: now, appointment }];
  return { appointments, requests, busy: { s: slot.startAt, e: slot.startAt + slot.durationMinutes * 60000, d: slot.doctor.toLowerCase() } };
}
function buildCancel(portal, appointmentId) {
  const now = Date.now();
  const appointments = clone(portal.record?.appointments || []);
  const a = appointments.find((x) => x.id === appointmentId && ["Scheduled", "Confirmed", "Requested"].includes(x.status));
  if (!a) throw new Error("Appointment not found");
  if (a.startAt - now < 2 * 3600000) throw new Error("Appointments can be cancelled in the app up to 2 hours before. Please call the clinic.");
  Object.assign(a, { status: "Cancelled", cancelledBy: "patient", cancelledAt: now });
  const requests = [...(portal.requests || []), { id: "req-" + uid(), type: "cancel", at: now, appointmentId }];
  return { appointments, requests, busy: { s: a.startAt, e: a.startAt + (a.durationMinutes || 30) * 60000, d: String(a.doctor).trim().toLowerCase() } };
}

// =============================================================================================
// Firebase
// =============================================================================================
export async function firebaseBackend(cfg) {
  const [appM, authM, fsM, stM] = await Promise.all(["firebase-app.js", "firebase-auth.js", "firebase-firestore.js", "firebase-storage.js"].map((f) => import(SDK + f)));
  const app = appM.initializeApp(cfg.firebase);
  if (cfg.appCheckSiteKey) {
    const ac = await import(SDK + "firebase-app-check.js");
    if (cfg.appCheckDebug) self.FIREBASE_APPCHECK_DEBUG_TOKEN = true;
    ac.initializeAppCheck(app, { provider: new ac.ReCaptchaEnterpriseProvider(cfg.appCheckSiteKey), isTokenAutoRefreshEnabled: true });
  }
  const auth = authM.getAuth(app);
  let db;
  try {
    db = fsM.initializeFirestore(app, { ignoreUndefinedProperties: true, localCache: fsM.persistentLocalCache({ tabManager: fsM.persistentMultipleTabManager() }) });
  } catch { db = fsM.getFirestore(app); }
  const storage = stM.getStorage(app);
  // Patient documents and clinical images go through the shared Dental Cloud SDK (sdk/).
  const sdk = createDentalCloud({ db, storage, fs: fsM, st: stM, options: { conflictMessage: (p) => `${p.name} was changed on another device or by the patient. Use Review conflict to keep your draft and load the cloud version.` } });
  const { doc, collection, getDoc, getDocs, query, where, limit, runTransaction, serverTimestamp, setDoc, updateDoc, deleteField, arrayUnion, arrayRemove, Timestamp } = fsM;

  const fromValue = (v) => {
    if (v instanceof Timestamp) return v.toMillis();
    if (Array.isArray(v)) return v.map(fromValue);
    if (v && typeof v === "object") { const o = {}; for (const [k, x] of Object.entries(v)) o[k] = fromValue(x); return o; }
    return v;
  };
  const data = (snap) => fromValue(snap.data() || {});
  const clinicRef = (id) => doc(db, "workspaces", id);
  const publicRef = (id) => doc(db, "workspaces", id, "public", "info");
  const patientRef = (c, p) => doc(db, "workspaces", c, "patients", p);
  const portalRef = (k) => doc(db, "portals", k);

  return {
    demo: false,
    projectId: cfg.firebase.projectId,
    onAuth: (cb) => authM.onAuthStateChanged(auth, (u) => cb(u ? { uid: u.uid, email: (u.email || "").toLowerCase(), name: u.displayName || u.email, photo: u.photoURL, verified: u.emailVerified } : null)),
    async signInGoogle() {
      const provider = new authM.GoogleAuthProvider();
      provider.setCustomParameters({ prompt: "select_account" });
      await authM.signInWithPopup(auth, provider);
    },
    signOut: () => authM.signOut(auth),

    async resolveWorkspace(user) {
      const own = await getDoc(clinicRef(user.uid));
      if (own.exists()) return { clinicId: user.uid, role: "owner" };
      if (user.email) {
        const q = await getDocs(query(collection(db, "workspaces"), where("inviteEmails", "array-contains", user.email), limit(1)));
        const invited = q.docs[0];
        if (invited) {
          const role = await runTransaction(db, async (tx) => {
            const snap = await tx.get(invited.ref);
            const invite = snap.get("invites")?.[user.email];
            if (!invite) throw new Error("Your clinic invitation is no longer active");
            const granted = typeof invite === "string" ? invite : invite.role;
            if (!granted) throw new Error("Your clinic invitation is invalid");
            const entry = { role: granted, active: true, email: user.email };
            if (invite.permissions) entry.permissions = invite.permissions;
            const member = snap.get("config")?.members?.[user.uid];
            if (JSON.stringify(member) !== JSON.stringify(entry)) tx.update(invited.ref, new fsM.FieldPath("config", "members", user.uid), entry);
            return granted;
          });
          return { clinicId: invited.id, role };
        }
      }
      return { clinicId: user.uid, role: "owner" };
    },
    async getClinic(clinicId) {
      const snap = await getDoc(clinicRef(clinicId));
      if (!snap.exists()) return null;
      const d = data(snap);
      return { config: d.config || defaults(), revision: d.revision || 0, subscription: d.subscription || {}, complimentary: d.complimentary === true };
    },
    async commitClinic(clinicId, me, config, expectedRevision, superAdminOwner, slotsOnly = false) {
      return runTransaction(db, async (tx) => {
        const ref = clinicRef(clinicId);
        const snap = await tx.get(ref);
        const serverRevision = snap.exists() ? snap.get("revision") || 0 : 0;
        const subscription = snap.exists() ? fromValue(snap.get("subscription") || {}) : {};
        if (slotsOnly) {
          const server = fromValue(snap.get("config"));
          if (!server) throw new Error("The clinic settings are not set up yet. Ask your admin.");
          server.availability = clone(config.availability || []);
          if ("patientBooking" in config) server.patientBooking = config.patientBooking !== false;
          tx.update(ref, { "config.availability": server.availability, "config.patientBooking": server.patientBooking !== false, revision: serverRevision + 1, updatedAt: Date.now(), updatedBy: me });
          tx.set(publicRef(clinicId), publicInfo(server), { merge: true });
          return { config: server, revision: serverRevision + 1, subscription, complimentary: snap.get("complimentary") === true };
        }
        if (serverRevision !== expectedRevision) throw new Conflict("Clinic settings changed on another device. Reload the clinic settings, review, then save again.");
        const next = clone(config);
        ["dirty", "revision", "operationId"].forEach((k) => delete next[k]);
        const serverMembers = snap.exists() ? fromValue(snap.get("config")?.members || {}) : {};
        next.members ||= {};
        for (const [id, m] of Object.entries(serverMembers)) if (!next.members[id] && m.email) next.members[id] = m;
        if (clinicId === me && !next.members[me]) next.members[me] = { role: "owner", active: true };
        const invites = staffInvites(next);
        applyStaffInvites(next, clinicId, invites);
        const revision = serverRevision + 1;
        const payload = { config: next, revision, updatedAt: Date.now(), updatedBy: me, invites, inviteEmails: Object.keys(invites).slice(0, 200) };
        const complimentary = snap.get?.("complimentary") === true || superAdminOwner;
        if (superAdminOwner && snap.get?.("complimentary") !== true) payload.complimentary = true;
        if (snap.exists()) tx.update(ref, payload); else tx.set(ref, payload);
        tx.set(publicRef(clinicId), publicInfo(next), { merge: true });
        return { config: next, revision, subscription, complimentary };
      });
    },
    commitPatient: (clinicId, me, patient) => sdk.records.commit(clinicId, me, patient),
    listPatients: (clinicId, since) => sdk.records.listSince(clinicId, since),
    getPatient: (clinicId, id) => sdk.records.get(clinicId, id),
    publishBusy: (clinicId, busy) => setDoc(publicRef(clinicId), { busy }, { merge: true }),

    async createPatientLogin(clinicId, me, patient, config, id, key) {
      const now = Date.now();
      return runTransaction(db, async (tx) => {
        const ref = patientRef(clinicId, patient.id);
        const snap = await tx.get(ref);
        if (!snap.exists()) throw new Error("Save this patient to the cloud first, then create the login");
        const existing = await tx.get(portalRef(key));
        if (existing.exists()) throw new Error("This Patient ID and password are already in use. Choose a different Patient ID.");
        const old = fromValue(snap.get("portal") || null);
        const record = patientView(data(snap));
        tx.set(portalRef(key), { clinicId, patientId: patient.id, loginId: id, record, clinic: clinicView(config), requests: [], pending: false, createdAt: now, createdBy: me, updatedAt: now });
        if (old?.key && old.key !== key) tx.delete(portalRef(old.key));
        const portal = { loginId: id, key, createdAt: old?.createdAt || now, createdBy: old?.createdBy || me, updatedAt: now, updatedBy: me, handled: old?.handled || [] };
        const revision = (snap.get("revision") || 0) + 1;
        tx.update(ref, { portal, revision, updatedBy: me, syncedAt: serverTimestamp() });
        return { portal, revision };
      });
    },
    async removePatientLogin(clinicId, me, patientId) {
      return runTransaction(db, async (tx) => {
        const ref = patientRef(clinicId, patientId);
        const snap = await tx.get(ref);
        const old = snap.get("portal");
        if (!old) throw new Error("This patient has no app login");
        if (old.key) tx.delete(portalRef(old.key));
        const revision = (snap.get("revision") || 0) + 1;
        tx.update(ref, { portal: deleteField(), revision, updatedBy: me, syncedAt: serverTimestamp() });
        return revision;
      });
    },
    async pendingPortals(clinicId) {
      const s = await getDocs(query(collection(db, "portals"), where("clinicId", "==", clinicId), where("pending", "==", true)));
      return s.docs.map((d) => [d.id, data(d)]);
    },
    async refreshPortal(key, patient, config, handled) {
      await runTransaction(db, async (tx) => {
        const snap = await tx.get(portalRef(key));
        if (!snap.exists()) return;
        const remaining = (snap.get("requests") || []).filter((r) => !handled.has(r.id));
        tx.update(portalRef(key), { record: patientView(patient), clinic: clinicView(config), requests: remaining, pending: remaining.length > 0, loginId: patient.portal?.loginId || snap.get("loginId"), updatedAt: Date.now() });
      });
    },
    async loadPortal(key) {
      const snap = await getDoc(portalRef(key));
      if (!snap.exists()) return null;
      const d = data(snap);
      const pub = await getDoc(publicRef(d.clinicId));
      const clinic = pub.exists() ? data(pub) : {};
      for (const [k, v] of Object.entries(d.clinic || {})) if (!(k in clinic)) clinic[k] = v;
      clinic.clinicId = d.clinicId;
      return { loginId: d.loginId, patient: d.record || {}, clinic, pending: (d.requests || []).length };
    },
    async bookSlot(key, startAt, doctor, reason) {
      await runTransaction(db, async (tx) => {
        const ps = await tx.get(portalRef(key));
        if (!ps.exists()) throw new Error("Your login has changed. Sign in again.");
        const portal = data(ps);
        const pub = await tx.get(publicRef(portal.clinicId));
        if (!pub.exists()) throw new Error("The clinic has not opened online booking yet");
        const b = buildBooking(portal, data(pub), startAt, doctor, reason);
        tx.update(portalRef(key), { requests: b.requests, "record.appointments": b.appointments, pending: true, updatedAt: Date.now() });
        tx.update(publicRef(portal.clinicId), { busy: arrayUnion(b.busy), lastPortal: key });
      });
    },
    async cancelMyAppointment(key, appointmentId) {
      await runTransaction(db, async (tx) => {
        const ps = await tx.get(portalRef(key));
        if (!ps.exists()) throw new Error("Your login has changed. Sign in again.");
        const portal = data(ps);
        const pub = await tx.get(publicRef(portal.clinicId));
        const c = buildCancel(portal, appointmentId);
        tx.update(portalRef(key), { requests: c.requests, "record.appointments": c.appointments, pending: true, updatedAt: Date.now() });
        const busy = pub.exists() ? pub.get("busy") || [] : [];
        if (busy.some((m) => m.s === c.busy.s && m.e === c.busy.e && m.d === c.busy.d)) tx.update(publicRef(portal.clinicId), { busy: arrayRemove(c.busy), lastPortal: key });
      });
    },
    uploadImage: (clinicId, patientId, encoded) => sdk.images.upload(clinicId, patientId, encoded),
    imageUrl: (path) => sdk.images.url(path),
  };
}

// =============================================================================================
// Demo backend: same interface, data kept in this browser (localStorage). No Firebase needed.
// =============================================================================================
export function demoBackend(seed) {
  const KEY = "dc-demo-v2";
  let db;
  try { db = JSON.parse(localStorage.getItem(KEY) || "null"); } catch { db = null; }
  if (!db) db = seed();
  const persist = () => { try { localStorage.setItem(KEY, JSON.stringify(db)); } catch { /* storage full or blocked */ } };
  persist();
  let user = null, listener = () => {};
  try { user = JSON.parse(sessionStorage.getItem("dc-demo-user") || "null"); } catch { user = null; }
  const setUser = (u) => { user = u; try { sessionStorage.setItem("dc-demo-user", JSON.stringify(u)); } catch { /* ignore */ } listener(u); };
  const tick = () => (db.clock = Math.max(Date.now(), (db.clock || 0) + 1));
  return {
    demo: true,
    projectId: "demo",
    reset() { localStorage.removeItem(KEY); location.reload(); },
    onAuth(cb) { listener = cb; setTimeout(() => cb(user), 0); return () => {}; },
    async signInGoogle() { setUser({ uid: db.ownerId, email: "owner@demo.dentalcare", name: "Dr. Arif Rahman", verified: true }); },
    async signOut() { setUser(null); },
    async resolveWorkspace() { return { clinicId: db.ownerId, role: "owner" }; },
    async getClinic() { return clone(db.clinic); },
    async commitClinic(clinicId, me, config, expectedRevision, sa, slotsOnly) {
      if (!slotsOnly && db.clinic.revision !== expectedRevision) throw new Conflict("Clinic settings changed on another device.");
      const next = slotsOnly ? { ...db.clinic.config, availability: clone(config.availability), patientBooking: config.patientBooking !== false } : clone(config);
      db.clinic = { ...db.clinic, config: next, revision: db.clinic.revision + 1 };
      db.public = { ...db.public, ...publicInfo(next) };
      persist();
      return clone(db.clinic);
    },
    async commitPatient(clinicId, me, patient) {
      const server = db.patients[patient.id];
      if ((server?.revision || 0) !== (patient.remoteVersion || 0)) throw new Conflict(`${patient.name} was changed on another device.`);
      const d = stripLocal(patient);
      delete d.portal;
      if (server?.portal) d.portal = clone(server.portal);
      Object.assign(d, { revision: (server?.revision || 0) + 1, clinicId, updatedBy: me, syncedAt: tick() });
      db.patients[patient.id] = d;
      persist();
      const out = clone(d); delete out.syncedAt; return out;
    },
    async listPatients(clinicId, since) {
      let newest = 0;
      const records = Object.values(db.patients).filter((p) => !since || p.syncedAt > since).map((p) => { newest = Math.max(newest, p.syncedAt || 0); const d = clone(p); delete d.syncedAt; return d; });
      return { records, newest };
    },
    async getPatient(c, id) { const d = clone(db.patients[id]); if (d) delete d.syncedAt; return d; },
    async publishBusy(c, busy) { db.public.busy = busy; persist(); },
    async createPatientLogin(clinicId, me, patient, config, id, key) {
      const server = db.patients[patient.id];
      if (!server) throw new Error("Save this patient first, then create the login");
      if (db.portals[key]) throw new Error("This Patient ID and password are already in use. Choose a different Patient ID.");
      const old = server.portal;
      if (old?.key && old.key !== key) delete db.portals[old.key];
      db.portals[key] = { clinicId, patientId: patient.id, loginId: id, record: patientView(server), clinic: clinicView(config), requests: [], pending: false, updatedAt: Date.now() };
      const now = Date.now();
      server.portal = { loginId: id, key, createdAt: old?.createdAt || now, createdBy: me, updatedAt: now, updatedBy: me, handled: old?.handled || [] };
      server.revision++; server.syncedAt = tick();
      persist();
      return { portal: clone(server.portal), revision: server.revision };
    },
    async removePatientLogin(clinicId, me, patientId) {
      const server = db.patients[patientId];
      if (!server?.portal) throw new Error("This patient has no app login");
      delete db.portals[server.portal.key];
      delete server.portal; server.revision++; server.syncedAt = tick();
      persist();
      return server.revision;
    },
    async pendingPortals() { return Object.entries(db.portals).filter(([, p]) => p.pending).map(([k, p]) => [k, clone(p)]); },
    async refreshPortal(key, patient, config, handled) {
      const p = db.portals[key]; if (!p) return;
      p.requests = (p.requests || []).filter((r) => !handled.has(r.id));
      Object.assign(p, { record: patientView(patient), clinic: clinicView(config), pending: p.requests.length > 0, updatedAt: Date.now() });
      persist();
    },
    async loadPortal(key) {
      const p = db.portals[key]; if (!p) return null;
      return { loginId: p.loginId, patient: clone(p.record), clinic: { ...clone(db.public), ...p.clinic, clinicId: p.clinicId }, pending: (p.requests || []).length };
    },
    async bookSlot(key, startAt, doctor, reason) {
      const p = db.portals[key]; if (!p) throw new Error("Your login has changed. Sign in again.");
      const b = buildBooking(p, db.public, startAt, doctor, reason);
      p.requests = b.requests; p.record.appointments = b.appointments; p.pending = true;
      (db.public.busy ||= []).push(b.busy);
      persist();
    },
    async cancelMyAppointment(key, id) {
      const p = db.portals[key]; if (!p) throw new Error("Your login has changed. Sign in again.");
      const c = buildCancel(p, id);
      p.requests = c.requests; p.record.appointments = c.appointments; p.pending = true;
      db.public.busy = (db.public.busy || []).filter((m) => !(m.s === c.busy.s && m.e === c.busy.e && m.d === c.busy.d));
      persist();
    },
    async uploadImage(clinicId, patientId, encoded) {
      const { bytes, ext } = encoded, hash = await sha256Hex(bytes);
      const path = `workspaces/${clinicId}/patients/${patientId}/${hash}.${ext}`;
      const reader = await new Promise((res) => { const r = new FileReader(); r.onload = () => res(r.result); r.readAsDataURL(new Blob([bytes], { type: ext === "png" ? "image/png" : "image/webp" })); });
      (db.images ||= {})[path] = reader;
      persist();
      return { path, reference: `gs://demo/${path}`, sha256: hash, bytes: bytes.length, width: encoded.width, height: encoded.height, format: ext, resized: encoded.resized, at: Date.now() };
    },
    async imageUrl(path) { return db.images?.[path] || ""; },
  };
}
