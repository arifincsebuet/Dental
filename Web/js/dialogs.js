// Modal dialogs and the forms shared by several pages.
import { $, $$, esc, uid, clone, arr, zonedMs, zoned, todayIn, addDaysIso, dowOf, hm, minutesOf, money, parseMinor, minorToText, fmtIso } from "./util.js";
import * as D from "./domain.js";
import { S, save, saveConfig, can, patient, say } from "./store.js";
import { I } from "./icons.js";

let onCloseCb = null;
export function modal({ title, body, saveLabel = "Save", onSave, wide = false, onOpen, cancelLabel = "Cancel", danger = false, noFooter = false }) {
  const sc = $("#scrim");
  sc.innerHTML = `<form class="modal ${wide ? "wide" : ""}" role="dialog" aria-modal="true" aria-label="${esc(title)}" novalidate>
    <div class="modal-h"><h3>${title}</h3><button type="button" class="icon-btn" data-close style="margin-left:auto;width:34px;height:34px" aria-label="Close">${I("x")}</button></div>
    <div class="modal-b">${body}<div class="err" id="merr"></div></div>
    ${noFooter ? "" : `<div class="modal-f"><button type="button" class="btn" data-close>${cancelLabel}</button>${onSave ? `<button type="submit" class="btn ${danger ? "danger" : "primary"}" id="msave">${saveLabel}</button>` : ""}</div>`}</form>`;
  sc.classList.add("show");
  const form = sc.querySelector("form");
  $$("[data-close]", sc).forEach((b) => (b.onclick = closeModal));
  sc.onmousedown = (e) => { if (e.target === sc) closeModal(); };
  form.onsubmit = async (e) => {
    e.preventDefault();
    if (!onSave) return closeModal();
    const btn = $("#msave"); btn.disabled = true;
    $("#merr").textContent = "";
    try {
      const r = await onSave(values(form), form);
      if (typeof r === "string" && r) { $("#merr").textContent = r; btn.disabled = false; return; }
      if (r === false) { btn.disabled = false; return; }
      closeModal();
    } catch (err) { $("#merr").textContent = err.message || String(err); btn.disabled = false; }
  };
  onOpen?.(form);
  setTimeout(() => form.querySelector("input:not([type=hidden]):not([type=checkbox]),select,textarea")?.focus(), 30);
  return form;
}
export function closeModal() { const sc = $("#scrim"); sc.classList.remove("show"); sc.innerHTML = ""; onCloseCb?.(); onCloseCb = null; }
export function values(form) {
  const out = {};
  for (const el of form.elements) {
    if (!el.name) continue;
    if (el.type === "checkbox") out[el.name] = el.checked;
    else if (el.type === "radio") { if (el.checked) out[el.name] = el.value; }
    else out[el.name] = el.value;
  }
  $$("[data-chips]", form).forEach((g) => (out[g.dataset.chips] = $$(".chip.on,button.on", g).map((b) => b.dataset.v)));
  $$("[data-pick]", form).forEach((g) => (out[g.dataset.pick] = $(".on", g)?.dataset.v ?? ""));
  return out;
}
/** Chip groups: data-chips="name" (multi) or data-pick="name" (single). */
export function chips(name, options, selected = [], single = false) {
  const sel = Array.isArray(selected) ? selected : [selected];
  return `<div class="chips" ${single ? "data-pick" : "data-chips"}="${name}">${options.map((o) => {
    const [v, l] = Array.isArray(o) ? o : [o, o];
    return `<button type="button" class="chip ${sel.includes(v) ? "on" : ""}" data-v="${esc(v)}" onclick="${single ? "[...this.parentNode.children].forEach(b=>b.classList.remove('on'));this.classList.add('on')" : "this.classList.toggle('on')"}">${esc(l)}</button>`;
  }).join("")}</div>`;
}
export const field = (label, input, hint = "", span = false) => `<div class="field" ${span ? 'style="grid-column:1/-1"' : ""}><label>${label}</label>${input}${hint ? `<span class="hint">${hint}</span>` : ""}</div>`;
export const input = (name, value = "", attrs = "") => `<input class="input" name="${name}" value="${esc(value)}" ${attrs}>`;
export const textarea = (name, value = "", attrs = "") => `<textarea class="input" name="${name}" ${attrs}>${esc(value)}</textarea>`;
export const select = (name, options, sel = "", attrs = "") => `<select class="input" name="${name}" ${attrs}>${options.map((o) => { const [v, l] = Array.isArray(o) ? o : [o, o]; return `<option value="${esc(v)}" ${v === sel ? "selected" : ""}>${esc(l)}</option>`; }).join("")}</select>`;
export const check = (name, label, on = false) => `<label class="row" style="font-weight:600;cursor:pointer"><input type="checkbox" name="${name}" ${on ? "checked" : ""}> ${label}</label>`;
export function confirmBox(title, text, label, fn, danger = true) { modal({ title, body: `<p style="margin:0">${text}</p>`, saveLabel: label, danger, onSave: fn }); }
export function patientOptions() { return `<datalist id="dl-patients">${S.patients.filter((p) => !p.archived).map((p) => `<option value="${esc(p.name)} · ${esc(p.phone || "")}${p.portal?.loginId ? " · " + esc(p.portal.loginId) : ""}" data-id="${p.id}"></option>`).join("")}</datalist>`; }
export function findPatientByText(text) {
  const t = String(text || "").trim().toLowerCase();
  if (!t) return null;
  return S.patients.find((p) => `${p.name} · ${p.phone || ""}${p.portal?.loginId ? " · " + p.portal.loginId : ""}`.toLowerCase() === t) ||
    S.patients.find((p) => p.name.toLowerCase() === t || (p.phone && p.phone === text.trim()) || p.portal?.loginId?.toLowerCase() === t || p.id.toLowerCase() === t);
}

// ------------------------------------------------------------------ appointments
export function bookDialog({ pid = "", doctor = "", date = "", time = "", duration = 30, slot = false, appointment = null } = {}) {
  if (!can("manageAppointments")) return say("You don't have access to book and change appointments.", "error");
  const config = S.config, z = D.tz(config);
  const a = appointment;
  const p = pid ? patient(pid) : null;
  const docs = [...new Set([...D.clinicians(config), ...(config.availability || []).map((s) => s.doctor)])];
  const services = (config.services || []).filter((s) => s.active !== false);
  const [d0, t0] = a ? String(a.date || "").split(" ") : [date || todayIn(z), time || "18:00"];
  modal({
    title: a ? "Reschedule / edit appointment" : "Book appointment", wide: true,
    body: `<div class="grid-form">
      ${field("Patient", `<input class="input" name="patient" list="dl-patients" value="${esc(p ? `${p.name} · ${p.phone || ""}${p.portal?.loginId ? " · " + p.portal.loginId : ""}` : "")}" placeholder="Name, phone or patient app ID" ${a ? "readonly" : ""}>${patientOptions()}`, "", true)}
      ${field("Doctor", select("doctor", docs.length ? docs : [""], a?.doctor || doctor || docs[0]))}
      ${field("Date", input("date", d0, 'type="date"'))}
      ${field("Time", input("time", t0, 'type="time"'))}
      ${field("Duration (minutes)", input("duration", a?.durationMinutes || duration, 'type="number" min="5" max="480"'))}
      ${field("Reason / service", `<input class="input" name="reason" list="dl-svc" value="${esc(a?.reason || "")}" placeholder="e.g. Check-up"><datalist id="dl-svc">${services.map((s) => `<option>${esc(s.name)}</option>`).join("")}</datalist>`)}
      ${field("Room / chair (optional)", input("room", a?.room || ""))}
      <div class="field" style="grid-column:1/-1">${check("reminder", "Schedule appointment reminder (needs patient consent)", a ? a.reminderEnabled : true)}</div></div>`,
    saveLabel: a ? "Save appointment" : "Book",
    onSave: async (v) => {
      const target = a ? patient(pid) : findPatientByText(v.patient);
      if (!target) return "Choose a patient from the list (name, phone or patient app ID).";
      if (!v.reason.trim()) return "Enter the reason or service.";
      const copy = clone(target);
      const list = arr(copy, "appointments");
      const startAt = zonedMs(v.date, v.time, z);
      const item = a ? list.find((x) => x.id === a.id) : { id: uid(), status: "Scheduled", createdAt: Date.now(), bookedBy: "staff" };
      const prev = a ? `${a.date}` : "";
      Object.assign(item, { date: `${v.date} ${v.time}`, startAt, durationMinutes: Number(v.duration) || 30, doctor: v.doctor, room: v.room.trim(), reason: v.reason.trim(),
        reminderEnabled: !!v.reminder && copy.communicationConsent === true && config.remindersEnabled === true, reminderHours: config.reminderHours || 24,
        consent: copy.communicationConsent === true, updatedAt: Date.now(), slot: !!slot || !!item.slot });
      if (a && prev !== item.date) arr(item, "reschedules").push({ id: uid(), from: prev, to: item.date, at: Date.now() });
      if (!a) list.push(item);
      return save(copy, a ? `Appointment updated · ${item.date} · ${item.doctor}` : `Appointment booked · ${item.date} · ${item.doctor} · ${item.reason}`);
    },
  });
}
export async function setAppointmentStatus(pid, aid, status) {
  const copy = clone(patient(pid));
  const a = copy.appointments.find((x) => x.id === aid);
  const from = a.status;
  if (status === from) return null;
  if (!(D.APPOINTMENT_NEXT[from] || []).includes(status)) { say(`An appointment that is ${from} can't be changed to ${status}`, "error"); return null; }
  Object.assign(a, { status, statusAt: Date.now() });
  arr(a, "statusLog").push({ id: uid(), from, to: status, at: Date.now() });
  return save(copy, `Appointment ${a.date}: ${from} → ${status}`);
}

/** Open slots for a doctor on a day (or weekly), like the Android "Open slots" sheet. */
export function openSlotsDialog(date) {
  if (!can("manageSlots")) return say("You don't have access to open and close appointment slots.", "error");
  const docs = D.clinicians(S.config);
  if (!docs.length) return say("Add a dentist in Team & roles first.", "error");
  const presets = { morning: ["09:00", "13:00"], afternoon: ["14:00", "17:00"], evening: ["17:00", "21:00"], full: ["09:00", "21:00"] };
  const form = modal({
    title: `Open slots · ${fmtIso(date, true)}`, wide: true,
    body: `<div class="grid-form">
      ${field("Doctor", select("doctor", docs))}
      ${field("Appointment length", select("len", ["10", "15", "20", "30", "45", "60"].map((x) => [x, x + " min"]), "30"))}
      <div class="field" style="grid-column:1/-1"><label>Hours</label>${chips("preset", [["morning", "Morning 09–13"], ["afternoon", "Afternoon 14–17"], ["evening", "Evening 17–21"], ["full", "Full day"], ["exact", "Exact times"]], "evening", true)}</div>
      ${field("From", input("start", "17:00", 'type="time"'))}${field("To", input("end", "21:00", 'type="time"'))}
      ${field("Room / chair (optional)", input("room", ""))}
      ${field("Repeat", select("repeat", [["0", "This day only"], ["4", "Every week · 4 weeks"], ["12", "Every week · 3 months"], ["26", "Every week · 6 months"], ["52", "Every week · 1 year"]]))}
      <div class="field" style="grid-column:1/-1"><div class="notice teal" id="preview"></div></div></div>`,
    saveLabel: "Open slots",
    onOpen: (f) => {
      const upd = () => {
        const v = values(f);
        if (presets[v.preset]) { f.start.value = presets[v.preset][0]; f.end.value = presets[v.preset][1]; }
        const step = +v.len, a = minutesOf(f.start.value), b = minutesOf(f.end.value);
        const times = []; for (let m = a; m + step <= b; m += step) times.push(hm(m));
        $("#preview", f).innerHTML = `${I("check")}<span>${times.length} slots: ${times.slice(0, 10).join(", ")}${times.length > 10 ? "…" : ""}</span>`;
        $("#msave", f).textContent = `Open ${times.length} slots`;
      };
      f.addEventListener("click", (e) => { if (e.target.closest("[data-pick]")) setTimeout(upd); });
      f.addEventListener("input", upd); upd();
    },
    onSave: async (v) => {
      if (minutesOf(v.end) <= minutesOf(v.start)) return "End time must be after the start time";
      const weeks = +v.repeat;
      const session = { id: uid(), doctor: v.doctor, startDate: date, endDate: weeks ? addDaysIso(date, weeks * 7 - 1) : date, weekdays: weeks ? [dowOf(date)] : [],
        start: v.start, end: v.end, slotMinutes: +v.len, active: true };
      if (v.room.trim()) session.room = v.room.trim();
      const next = clone(S.config); arr(next, "availability").push(session);
      return saveConfig(next, "Slots opened · shared with staff and the patient app", true);
    },
  });
  return form;
}
export async function closeDayForDoctor(sessionIds, date) {
  const next = clone(S.config);
  for (const s of next.availability || []) if (sessionIds.includes(s.id)) {
    if (s.startDate === s.endDate) s.active = false; else (s.exceptDates ||= []).includes(date) || s.exceptDates.push(date);
  }
  return saveConfig(next, `Closed ${fmtIso(date)}`, true);
}
export function availabilityDialog() {
  const list = S.config.availability || [];
  const days = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
  modal({
    title: "All availability", wide: true, noFooter: true,
    body: list.length ? `<div class="table-wrap"><table class="t"><thead><tr><th>Doctor</th><th>Dates</th><th>Days</th><th>Hours</th><th>Slot</th><th></th></tr></thead><tbody>${list.map((s) => `<tr>
      <td><b>${esc(s.doctor)}</b>${s.room ? `<div class="small muted">${esc(s.room)}</div>` : ""}</td><td>${fmtIso(s.startDate)} – ${fmtIso(s.endDate)}${(s.exceptDates || []).length ? `<div class="small muted">${s.exceptDates.length} closed day(s)</div>` : ""}</td>
      <td>${(s.weekdays || []).length ? s.weekdays.map((d) => days[d - 1]).join(", ") : "Every day"}</td><td class="mono">${s.start}–${s.end}</td><td>${s.slotMinutes} min</td>
      <td><div class="row"><button type="button" class="btn sm" data-toggle="${s.id}">${s.active === false ? "Resume" : "Pause"}</button><button type="button" class="btn sm danger" data-del="${s.id}">Remove</button></div></td></tr>`).join("")}</tbody></table></div>`
      : `<div class="empty">No availability yet. Pick a day in Appointments and choose Open slots.</div>`,
    onOpen: (f) => f.addEventListener("click", async (e) => {
      const t = e.target.closest("[data-toggle],[data-del]"); if (!t) return;
      const next = clone(S.config);
      if (t.dataset.toggle) { const s = next.availability.find((x) => x.id === t.dataset.toggle); s.active = s.active === false; }
      else next.availability = next.availability.filter((x) => x.id !== t.dataset.del);
      await saveConfig(next, "Availability updated", true);
      availabilityDialog();
    }),
  });
}

// ------------------------------------------------------------------ billing
export function invoiceDialog(pid = "") {
  if (!can("billing")) return say("You don't have access to invoices and payments.", "error");
  const c = D.cur(S.config), p = pid ? patient(pid) : null;
  const services = (S.config.services || []).filter((s) => s.active !== false);
  const lines = [];
  const form = modal({
    title: `New invoice · ${c}`, wide: true,
    body: `<div class="grid-form">
      ${field("Patient", `<input class="input" name="patient" list="dl-patients" value="${esc(p ? `${p.name} · ${p.phone || ""}${p.portal?.loginId ? " · " + p.portal.loginId : ""}` : "")}">${patientOptions()}`, "", true)}
      ${field("Line description", `<input class="input" name="desc" list="dl-svc2" placeholder="Pick a service or type"><datalist id="dl-svc2">${services.map((s) => `<option>${esc(s.name)}</option>`).join("")}</datalist>`)}
      ${field(`Unit price (${c})`, input("price", "", 'inputmode="decimal"'))}${field("Quantity", input("qty", "1", 'type="number" min="1" max="999"'))}
      <div class="field"><label>&nbsp;</label><button type="button" class="btn" id="addline">${I("plus")}Add line</button></div>
      <div class="field" style="grid-column:1/-1"><table style="width:100%" class="inv-lines" id="lines"></table></div>
      ${field(`Discount amount (${c})`, input("discount", "0", 'inputmode="decimal"'))}${field("Tax percent", input("tax", String((S.config.taxBps || 0) / 100), 'inputmode="decimal"'))}
      <div class="field" style="grid-column:1/-1"><div class="total-row big"><span>Total</span><span class="num" id="itotal"></span></div></div></div>`,
    saveLabel: "Issue invoice",
    onOpen: (f) => {
      const draw = () => {
        $("#lines", f).innerHTML = lines.map((l, i) => `<tr><td>${esc(l.description)} × ${l.quantity}</td><td class="num" style="text-align:right">${money(l.unitPrice * l.quantity, c)}</td><td style="width:40px"><button type="button" class="btn ghost sm" data-rm="${i}">${I("x")}</button></td></tr>`).join("") || `<tr><td class="muted small">No lines yet</td></tr>`;
        try { const inv = { lines, discount: parseMinor(f.discount.value || "0", c), taxBps: Math.round(parseFloat(f.tax.value || "0") * 100) }; $("#itotal", f).textContent = money(D.invoiceTotal(inv), c); } catch { $("#itotal", f).textContent = "-"; }
      };
      f.desc.addEventListener("change", () => { const s = services.find((x) => x.name === f.desc.value); if (s) f.price.value = minorToText(s.fee, c); });
      $("#addline", f).onclick = () => {
        try {
          if (!f.desc.value.trim()) throw new Error("Enter a line description");
          const s = services.find((x) => x.name === f.desc.value);
          lines.push({ id: uid(), ...(s ? { serviceId: s.id } : {}), description: f.desc.value.trim(), quantity: Math.max(1, Math.min(999, +f.qty.value || 1)), unitPrice: parseMinor(f.price.value, c) });
          f.desc.value = ""; f.price.value = ""; f.qty.value = "1"; $("#merr").textContent = ""; draw();
        } catch (e) { $("#merr").textContent = e.message; }
      };
      f.addEventListener("click", (e) => { const b = e.target.closest("[data-rm]"); if (b) { lines.splice(+b.dataset.rm, 1); draw(); } });
      f.addEventListener("input", draw); draw();
    },
    onSave: async (v) => {
      const target = findPatientByText(v.patient);
      if (!target) return "Choose a patient";
      if (!lines.length) return "Add at least one line";
      const discount = parseMinor(v.discount || "0", c), taxBps = Math.round(parseFloat(v.tax || "0") * 100);
      const sub = lines.reduce((a, l) => a + l.unitPrice * l.quantity, 0);
      if (discount > sub) return "Discount cannot exceed the subtotal";
      if (taxBps < 0 || taxBps > 10000) return "Tax must be between 0 and 100 percent";
      const invoice = { id: uid(), description: lines.map((l) => l.description).join(", "), currency: c, lines, discount, taxBps, at: Date.now(), status: "Issued", payments: [], refunds: [] };
      invoice.amount = D.invoiceTotal(invoice);
      const copy = clone(target); arr(copy, "invoices").push(invoice);
      return save(copy, `Invoice issued · ${money(invoice.amount, c)}`);
    },
  });
  return form;
}
export function receivePaymentDialog(pid, invId) {
  const p = patient(pid), inv = p.invoices.find((v) => v.id === invId), c = inv.currency || D.cur(S.config);
  modal({
    title: `Receive payment · ${esc(p.name)}`,
    body: `<div class="grid-form">${field(`Amount (${c})`, input("amount", minorToText(D.balance(inv), c), 'inputmode="decimal"'), `Balance ${money(D.balance(inv), c)}`)}
      <div class="field" style="grid-column:1/-1"><label>Method</label>${chips("method", D.METHODS, "Cash", true)}</div>${field("Reference (optional)", input("reference", ""), "", true)}</div>`,
    saveLabel: "Record payment",
    onSave: async (v) => {
      const amount = parseMinor(v.amount, c);
      if (amount <= 0) return "Enter the amount";
      if (amount > D.balance(inv)) return "That is more than the balance";
      const copy = clone(p); copy.invoices.find((x) => x.id === invId).payments.push({ id: uid(), amount, method: v.method || "Cash", reference: v.reference.trim(), at: Date.now() });
      return save(copy, `Payment received · ${money(amount, c)} (${v.method || "Cash"})`);
    },
  });
}
export function refundDialog(pid, invId) {
  const p = patient(pid), inv = p.invoices.find((v) => v.id === invId), c = inv.currency || D.cur(S.config);
  const payments = (inv.payments || []).filter((x) => x.amount - (inv.refunds || []).filter((r) => r.paymentId === x.id).reduce((a, r) => a + r.amount, 0) > 0);
  if (!payments.length) return say("No payment on this invoice can be refunded.", "error");
  modal({
    title: "Refund a payment",
    body: `<div class="grid-form">${field("Payment", select("paymentId", payments.map((x) => [x.id, `${money(x.amount, c)} · ${x.method} · ${new Date(x.at).toLocaleDateString()}`])), "", true)}
      ${field(`Amount (${c})`, input("amount", ""))}<div class="field"><label>Method</label>${chips("method", D.METHODS, "Cash", true)}</div>${field("Reason", input("reason", ""), "", true)}</div>`,
    saveLabel: "Refund",
    onSave: async (v) => {
      const pay = payments.find((x) => x.id === v.paymentId); const amount = parseMinor(v.amount, c);
      const left = pay.amount - (inv.refunds || []).filter((r) => r.paymentId === pay.id).reduce((a, r) => a + r.amount, 0);
      if (amount <= 0 || amount > left) return `Enter up to ${money(left, c)}`;
      if (!v.reason.trim()) return "A reason is required";
      const copy = clone(p); arr(copy.invoices.find((x) => x.id === invId), "refunds").push({ id: uid(), paymentId: pay.id, amount, method: v.method || "Cash", reason: v.reason.trim(), at: Date.now() });
      return save(copy, `Refund ${money(amount, c)}: ${v.reason.trim()}`);
    },
  });
}
export function voidDialog(pid, invId) {
  const p = patient(pid), inv = p.invoices.find((v) => v.id === invId);
  if (D.paid(inv) !== D.refunded(inv)) return say("Refund the payments on this invoice before voiding it.", "error");
  modal({ title: "Void invoice", body: field("Reason", input("reason", "")), saveLabel: "Void invoice", danger: true,
    onSave: async (v) => { if (!v.reason.trim()) return "A reason is required"; const copy = clone(p); Object.assign(copy.invoices.find((x) => x.id === invId), { status: "Voided", voidReason: v.reason.trim(), voidedAt: Date.now() }); return save(copy, `Invoice voided: ${v.reason.trim()}`); } });
}
export function receiptText(p, inv, pay) {
  const c = inv.currency || D.cur(S.config);
  return `${S.config.name || "Dental Care"}\nPAYMENT RECEIPT\nPatient: ${p.name}\nFor: ${inv.description || ""}\nDate: ${new Date(pay.at).toLocaleString()}\nReceived: ${money(pay.amount, c)} (${pay.method})\nInvoice total: ${money(D.invoiceTotal(inv), c)} · Paid: ${money(D.paid(inv) - D.refunded(inv), c)} · Due: ${money(Math.max(0, D.balance(inv)), c)}`;
}
export async function shareText(title, text) {
  if (navigator.share) { try { await navigator.share({ title, text }); return; } catch { /* cancelled */ } }
  try { await navigator.clipboard.writeText(text); say("Copied to the clipboard"); } catch { modal({ title, body: `<pre style="white-space:pre-wrap;margin:0">${esc(text)}</pre>`, noFooter: true }); }
}
