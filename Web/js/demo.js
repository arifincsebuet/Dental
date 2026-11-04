// Demo clinic in the same document format as Firestore (used only when Firebase is not configured).
import { uid, zonedMs, todayIn, addDaysIso, dowOf, hm } from "./util.js";
import { publicInfo } from "./cloud.js";
import { QUADRANT_ORDER, busyTimes, normalizeChart } from "./domain.js";

const TZ = "Asia/Dhaka";
let s = 11;
const rnd = () => (s = (s * 16807) % 2147483647) / 2147483647;
const pick = (a) => a[Math.floor(rnd() * a.length)];
const tk = (n) => n * 100; // BDT has 2 decimal digits; amounts are stored in poisha

export function demoSeed() {
  s = 11;
  const ownerId = "demo-owner";
  const today = todayIn(TZ);
  const at = (days, time = "11:00") => zonedMs(addDaysIso(today, days), time, TZ);
  const staff = [
    { id: uid(), name: "Dr. Arif Rahman", email: "owner@demo.dentalcare", phone: "01711-204455", registration: "BMDC 4471", role: "admin", active: true },
    { id: uid(), name: "Dr. Nusrat Jahan", email: "nusrat@demo.dentalcare", phone: "01819-332211", registration: "BMDC 6120", role: "dentist", active: true },
    { id: uid(), name: "Dr. Tanvir Ahmed", email: "tanvir@demo.dentalcare", phone: "01712-998877", registration: "BMDC 5893", role: "dentist", active: true },
    { id: uid(), name: "Dr. Farzana Akter", email: "farzana@demo.dentalcare", phone: "01911-441100", registration: "BMDC 7004", role: "dentist", active: true },
    { id: uid(), name: "Sumaiya Khatun", email: "sumaiya@demo.dentalcare", phone: "01552-110022", role: "assistant", active: true },
    { id: uid(), name: "Rafiqul Islam", email: "rafiq@demo.dentalcare", phone: "01673-552200", role: "receptionist", active: true },
    { id: uid(), name: "Kamal Hossain", email: "kamal@demo.dentalcare", phone: "01844-776655", role: "cashier", active: true },
  ];
  const doctors = ["Dr. Nusrat Jahan", "Dr. Tanvir Ahmed", "Dr. Farzana Akter"];
  const services = [["Consultation", 500, 15], ["Scaling & polishing", 2500, 30], ["Filling (composite)", 2000, 30], ["Root canal", 5000, 60], ["Extraction", 1500, 30],
    ["Crown", 8000, 45], ["Implant", 60000, 90], ["Teeth whitening", 12000, 60], ["Orthodontic", 80000, 30], ["Dental X-ray (IOPA)", 300, 10]]
    .map(([name, fee, d], i) => ({ id: uid(), name, code: "S" + (i + 1), fee: tk(fee), durationMinutes: d, active: true }));
  const start = addDaysIso(today, -60), end = addDaysIso(today, 300);
  const weekdays = [1, 2, 3, 4, 6, 7]; // Friday closed
  const availability = [
    { id: uid(), doctor: doctors[0], startDate: start, endDate: end, weekdays, start: "09:00", end: "13:00", slotMinutes: 30, active: true, room: "Chair 1" },
    { id: uid(), doctor: doctors[0], startDate: start, endDate: end, weekdays, start: "16:00", end: "20:00", slotMinutes: 30, active: true, room: "Chair 1" },
    { id: uid(), doctor: doctors[1], startDate: start, endDate: end, weekdays, start: "10:00", end: "14:00", slotMinutes: 30, active: true, room: "Chair 2" },
    { id: uid(), doctor: doctors[1], startDate: start, endDate: end, weekdays, start: "17:00", end: "21:00", slotMinutes: 30, active: true, room: "Chair 2" },
    { id: uid(), doctor: doctors[2], startDate: start, endDate: end, weekdays, start: "15:00", end: "20:00", slotMinutes: 30, active: true, room: "Chair 3" },
  ];
  const config = { name: "Smile Point Dental", address: "House 12, Road 7, Dhanmondi, Dhaka 1205", clinicPhone: "01711-204455", mapUrl: "23.7465,90.3760", currency: "BDT", timeZone: TZ, taxBps: 0, bufferMinutes: 5, reminderHours: 24, remindersEnabled: true,
    quietStart: "21:00", quietEnd: "08:00", staff, services, members: { [ownerId]: { role: "owner", active: true } }, availability, patientBooking: true };

  const names = ["Rahim Uddin", "Fatema Begum", "Sajid Hossain", "Nadia Islam", "Karim Chowdhury", "Ayesha Siddiqua", "Mehedi Hasan", "Tahmina Akter", "Imran Kabir",
    "Shirin Sultana", "Arman Hossain", "Lubna Rahman", "Jahid Hasan", "Ruma Akhter", "Tariq Aziz", "Sabina Yasmin", "Faisal Mahmud", "Rokeya Khanam"];
  const female = new Set(["Fatema Begum", "Nadia Islam", "Ayesha Siddiqua", "Tahmina Akter", "Shirin Sultana", "Lubna Rahman", "Ruma Akhter", "Sabina Yasmin", "Rokeya Khanam"]);
  const alerts = ["Penicillin allergy", "Diabetic", "On blood thinners", "Hypertension", "Asthma", "Latex allergy"];
  const reasons = ["Check-up", "Scaling", "Filling", "RCT visit", "Extraction", "Braces adjustment", "Crown fitting", "Pain, lower left", "Whitening", "Follow-up"];
  const procs = [["Filling (composite)", 2000], ["Root canal", 5000], ["Crown", 8000], ["Extraction", 1500], ["Scaling & polishing", 2500]];
  const patients = {};
  names.forEach((name, i) => {
    const id = "P" + (1001 + i);
    const teeth = {};
    QUADRANT_ORDER.forEach((k) => { if (rnd() < 0.85) teeth[k] = { assessment: "assessed", presence: "present" }; });
    const age = name === "Arman Hossain" ? 9 : 18 + Math.floor(rnd() * 50);
    const p = { id, schemaVersion: 3, name, phone: `01${7 + Math.floor(rnd() * 3)}${10 + Math.floor(rnd() * 89)}-${100000 + Math.floor(rnd() * 899999)}`,
      dob: `${new Date().getFullYear() - age}-${String(1 + Math.floor(rnd() * 12)).padStart(2, "0")}-${String(1 + Math.floor(rnd() * 27)).padStart(2, "0")}`,
      gender: female.has(name) ? "Female" : "Male", address: pick(["Mirpur 10, Dhaka", "Kazipara, Mirpur", "Dhanmondi 27, Dhaka", "Uttara Sector 7", "Mohammadpur, Dhaka", "Banani, Dhaka"]),
      allergies: rnd() < 0.4 ? pick(alerts) : "", story: "", communicationConsent: rnd() < 0.7, archived: false,
      chart: { schemaVersion: 2, catalogVersion: "fdi-v1", dentition: "permanent", teeth }, treatments: [], performedProcedures: [], encounters: [], appointments: [], invoices: [], images: [], history: [],
      revision: 1, clinicId: ownerId, updatedAt: Date.now() };
    p.history.push({ id: uid(), at: at(-120 - i), text: "Patient registered" });
    const n = 1 + Math.floor(rnd() * 3);
    for (let k = 0; k < n; k++) {
      const tooth = String(pick(QUADRANT_ORDER)); const [proc, fee] = pick(procs);
      const status = pick(["Completed", "Completed", "Planned", "In progress"]);
      const when = at(-Math.floor(rnd() * 150));
      addTreatment(p, tooth, proc, status, pick(doctors), when, fee, status === "Planned" ? [] : [[Math.round(fee * pick([1, 1, 0.4, 0.6]) / 100) * 100, pick(["Cash", "bKash", "Nagad", "Card"]), when]]);
    }
    patients[id] = p;
  });
  // Rahim Uddin: a full record.
  const r = patients.P1001;
  Object.assign(r, { dob: "1984-03-12", phone: "01711-482916", address: "Kazipara, Mirpur, Dhaka", allergies: "Penicillin allergy, Diabetic", communicationConsent: true,
    story: "Presented in September with intermittent pain lower left. Irreversible pulpitis 36, RCT in progress. Generalised mild gingivitis, scaling done. Plan: crown 36 after RCT, filling 25, review 17 caries.",
    treatments: [], performedProcedures: [], invoices: [] });
  QUADRANT_ORDER.forEach((k) => (r.chart.teeth[k] = { assessment: "assessed", presence: "present" }));
  Object.assign(r.chart.teeth, {
    18: { assessment: "assessed", presence: "missing" }, 28: { assessment: "assessed", presence: "missing" }, 48: { assessment: "assessed", presence: "extracted", findings: [] },
    17: { assessment: "assessed", presence: "present", surfaces: { O: { assessment: "assessed", findings: ["caries"], restorations: [] } } },
    38: { assessment: "assessed", presence: "present", surfaces: { O: { assessment: "assessed", findings: ["caries"], restorations: [] } } },
    45: { assessment: "assessed", presence: "present", surfaces: { D: { assessment: "assessed", findings: ["caries"], restorations: [] } } },
    11: { assessment: "assessed", presence: "present", surfaces: { M: { assessment: "assessed", findings: [], restorations: ["composite"] } } },
  });
  addTreatment(r, "48", "Extraction", "Completed", doctors[1], at(-700), 4000, [[4000, "Cash", at(-700)]], "Impacted, uneventful healing.");
  addTreatment(r, "14", "Crown", "Completed", doctors[1], at(-500), 8000, [[8000, "Bank", at(-500)]]);
  addTreatment(r, "11", "Filling (composite)", "Completed", doctors[0], at(-260), 2000, [[2000, "Nagad", at(-260)]], "", ["M"]);
  addTreatment(r, "46", "Filling (composite)", "Completed", doctors[0], at(-50), 2000, [[2000, "Cash", at(-50)]], "", ["O"]);
  addTreatment(r, "26", "Filling (composite)", "Completed", doctors[0], at(-50), 2500, [[2500, "Card", at(-50)]], "", ["O", "D"]);
  addTreatment(r, "36", "Root canal", "In progress", doctors[1], at(-15), 5000, [[1000, "bKash", at(-29)], [1000, "Cash", at(-15)]], "Visit 2 of 4. Working length 21 mm MB, ML, D.");
  addTreatment(r, "36", "Crown", "Planned", doctors[1], at(20), 15000, []);
  addTreatment(r, "25", "Filling (composite)", "Planned", doctors[0], at(6), 2000, [], "", ["D"]);
  r.encounters = [
    { id: uid(), at: at(-50), doctor: doctors[0], complaint: "Routine check-up", examination: "Caries 46 O, 26 OD; mild gingivitis", diagnosis: "Dental caries; gingivitis", plan: "Restorations, scaling", status: "Finalized", amendments: [] },
    { id: uid(), at: at(-29), doctor: doctors[1], complaint: "Throbbing pain lower left, 4 days", examination: "36 tender to percussion, deep caries", diagnosis: "Irreversible pulpitis 36; caries 17, 38", plan: "RCT 36 then crown; restore 25", status: "Finalized",
      amendments: [{ id: uid(), at: at(-28), reason: "Missed finding", text: "Added caries 38 O to diagnosis." }] },
    { id: uid(), at: at(-15), doctor: doctors[1], complaint: "Follow-up RCT 36", examination: "Canals located", diagnosis: "Irreversible pulpitis 36", plan: "Continue RCT, obturation next visit", status: "Finalized", amendments: [] },
    { id: uid(), at: at(0, "09:30"), doctor: doctors[1], complaint: "RCT visit 3", examination: "", diagnosis: "", plan: "", status: "Draft", amendments: [] },
  ];
  // Appointments for the past 30 and next 14 days.
  const ids = Object.keys(patients);
  for (let d = -30; d <= 14; d++) {
    const day = addDaysIso(today, d);
    if (dowOf(day) === 5) continue;
    for (const sess of availability) {
      for (let m = toMin(sess.start); m < toMin(sess.end); m += 30) {
        if (rnd() > (d < 0 ? 0.5 : d === 0 ? 0.62 : 0.4)) continue;
        const dur = rnd() < 0.2 ? 60 : 30;
        if (m + dur > toMin(sess.end)) continue;
        const p = patients[pick(ids)];
        const startAt = zonedMs(day, hm(m), TZ);
        const past = startAt + dur * 60000 < Date.now();
        const status = past ? pick(["Completed", "Completed", "Completed", "No show", "Cancelled"]) : startAt < Date.now() ? "In progress" : pick(["Scheduled", "Confirmed", "Confirmed"]);
        p.appointments.push({ id: uid(), date: `${day} ${hm(m)}`, startAt, durationMinutes: dur, doctor: sess.doctor, room: sess.room, reason: pick(reasons), status,
          bookedBy: rnd() < 0.2 ? "patient" : "staff", slot: true, consent: p.communicationConsent, reminderEnabled: p.communicationConsent, createdAt: startAt - 86400000 * 3 });
        m += dur - 30;
      }
    }
  }
  const rahimAt = zonedMs(addDaysIso(today, dowOf(today) === 5 ? 1 : 0), "20:00", TZ);
  r.appointments.push({ id: uid(), date: `${addDaysIso(today, dowOf(today) === 5 ? 1 : 0)} 20:00`, startAt: rahimAt, durationMinutes: 60, doctor: doctors[1], room: "Chair 2",
    reason: "RCT visit 3, tooth 36", status: "Confirmed", bookedBy: "staff", slot: false, consent: true, reminderEnabled: true });
  Object.values(patients).forEach((p) => { normalizeChart(p); p.syncedAt = Date.now(); });
  const pub = publicInfo(config);
  pub.busy = busyTimes(Object.values(patients), config);
  return { ownerId, clinic: { config, revision: 3, subscription: { active: true, expiresAt: Date.now() + 25 * 86400000, tier: 1, productId: "dental_care_pro" }, complimentary: false },
    public: pub, patients, portals: {}, clock: Date.now() };
}
function toMin(t) { const [h, m] = t.split(":").map(Number); return h * 60 + m; }
function addTreatment(p, tooth, procedure, status, doctor, atMs, feeTk, payments, notes = "", surfaces = []) {
  const id = uid();
  const surface = surfaces.length ? surfaces.join("") : "Whole tooth";
  const t = { id, groupId: id, tooth, surface, procedure, notes, status, at: atMs, doctor, events: [{ id: uid(), to: status, at: atMs, doctor }] };
  const slot = (p.chart.teeth[tooth] ||= { assessment: "assessed", presence: "present" });
  if (status === "Completed") {
    t.performedAt = atMs;
    p.performedProcedures.push({ id: uid(), treatmentId: id, tooth, surface, procedure, doctor, outcome: notes || procedure + " completed", at: atMs });
    if (procedure === "Extraction") { slot.presence = "extracted"; delete slot.surfaces; }
    if (procedure === "Root canal") slot.findings = ["endodontically_treated"];
    const restoration = { "Filling (composite)": "composite", Crown: "crown" }[procedure];
    if (restoration && slot.presence === "present") for (const sf of surfaces.length ? surfaces : [+tooth[1] <= 3 ? "I" : "O"]) {
      const o = ((slot.surfaces ||= {})[sf] ||= { assessment: "assessed", findings: [], restorations: [] });
      if (!o.restorations.includes(restoration)) o.restorations.push(restoration);
    }
  }
  p.treatments.push(t);
  if (status !== "Planned" || payments.length) {
    const desc = `${procedure} · FDI ${tooth}`;
    p.invoices.push({ id: uid(), treatmentRef: id, treatmentId: id, description: desc, currency: "BDT", lines: [{ id: uid(), description: desc, quantity: 1, unitPrice: tk(feeTk) }],
      discount: 0, taxBps: 0, amount: tk(feeTk), at: atMs, status: "Issued", refunds: [],
      payments: payments.map(([a, m, w]) => ({ id: uid(), amount: tk(a), method: m, reference: "", at: w, treatmentRef: id })) });
  }
  p.history.push({ id: uid(), at: atMs, text: `Treatment recorded · ${procedure} · ${status} · FDI ${tooth}` });
}
