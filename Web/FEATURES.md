# Dental Care Web: features

## 1. Android features in the web app

| Area | On the web |
|---|---|
| Sign-in | Google sign-in for the clinic team; first account creates the clinic (owner); invited staff join by email. Patient app sign-in with Patient ID + password (no Firebase account) |
| Roles & access | Admin, Dentist, Assistant, Receptionist, Cashier, Auditor + 9 access switches, enforced in the UI and by the same Firestore rules |
| Patients | Register (duplicate hint), search by name / phone / ID / app ID, filters (Today, Dues, Alerts), edit profile, medical alerts, clinical story, reminder consent, archive / restore with reason |
| **3D chart (Godot)** | Same Godot model as Android: rotate, pan, zoom, views (Front/Upper/Lower/Left/Right), arch filter, tooth numbers, roots (X-ray look), tap sound, auto-turn, surface pad |
| Record treatment | One or several teeth, procedure, status, surfaces, doctor, date, notes; completed care writes performed procedures and chart outcomes (restorations, extraction, implant, root canal) |
| Edit treatment | Any field, change log kept, linked performed care follows |
| Treatment payments | Total fee + payment per visit (Cash, bKash, Nagad, Card, Bank), Fee · Paid · Due, receipts, fee changes logged |
| Chart tab (2D) | Permanent / primary / mixed, assessment, presence, replacement, surface findings and restorations, resolve findings, planned procedure |
| Encounters | Draft, edit, finalize, amend with reason |
| Treatments tab | Workflow states (Accepted, In progress, Completed, Deferred, Declined, Referred, Cancelled), performed procedures with amendments |
| History | Every change with author; compare chart; **view earlier chart in 3D** |
| Images | Attach (lossless PNG/WebP ≤ 512 KB, SHA-256 names), view |
| Appointments | Doctor-column day calendar, month calendar with free/full dots, list (Day/Week/Month/All), book / reschedule / status, conflicts and buffer checks |
| Slots | Open slots (presets, length, repeat weekly up to 1 year), close a day, all availability (pause / remove), online booking switch |
| Patient app requests | Bookings and cancellations from the patient app are applied on Sync; taken times become "Requested" |
| Patient app logins | Create, change ID / password, turn off; shown once, share or copy |
| Billing | Invoices with lines, discount, tax; receive payment; refund; void; share invoice / receipt; clinic-wide invoice list and CSV |
| Reports | Day / Week / Month / Custom / All: billed, received, refunds, net, outstanding, payments per day, by method, procedures, balances due, clinician workload, appointment states, treatment payments, CSV |
| Settings | Clinic (name, time zone, currency, tax, buffer), services & fees, reminders, tooth numbering (Palmer / FDI / Universal), tap sound, theme, subscription status and plan usage, restore cloud records, conflict drafts, import / export JSON, care summary PDF (print) |
| Sync & conflicts | Revision-checked transactions; conflict review keeps your draft; Firestore offline cache |
| Patient app (web) | 3D teeth (read only), treatment history with Fee · Paid · Due, upcoming appointments, cancel (2 h rule), book free slots (3 bookings max), billing summary |
| **PDF reports** | Patient record (profile, 2D dental chart, tooth findings, treatments with Fee · Paid · Due, encounters, appointments, billing, history), financial report, appointments list (day / week / month), invoice and payment receipt. Same layout on Android (save via the file picker) and web (download) |
| **Clinic branding** | Owner / admin set the clinic name, logo, address, phone, Google Maps location and brand colour (Settings › Clinic branding). The app then shows the clinic everywhere: launch screen, sidebar / toolbar, sign-in, browser tab and icon, patient app (with a **Find us** map, directions and call button) and every PDF (logo + address header, faint logo and name watermark). Same settings on Android and web |
| **Languages** | 17 languages: English (default), Bangla, Hindi, Arabic, Chinese (Simplified), Spanish, French, Portuguese (Brazil), German, Russian, Japanese, Korean, Indonesian, Turkish, Italian, Vietnamese, Thai. Chosen per device in Settings, on the sign-in page and in the patient app. One shared dictionary (`Web/i18n/*.json` = `app/src/main/assets/i18n/*.json`) |
| **Currency** | 33 common currencies with local names (BDT, INR, USD, EUR, GBP, JPY, KRW, SAR, AED…); invoices keep the currency they were issued in |
| How to use | All Android guides, adapted for the web, plus 3D lens and multi-select guides |

## 2. Made for the big screen

- **3D at a glance**: hover any tooth for a card with findings, restorations, open care, last
  treatment and Fee · Paid · Due, without clicking.
- **Lenses**: colour the whole model by Clinical status, Treatment plan, **Balance due** or
  **Last treated** (glowing collar ring + tint), with a legend.
- **Info tags** floating over teeth ("+ Crown · due 3k", "caries").
- **At a glance panel** beside the model: counts, balance due, next visit, alerts, quadrant
  totals and a clickable **Needs attention** list.
- **Full-screen 3D** (Esc to leave) with all panels; **multi-select** teeth for one recording.
- Three-column patient workspace (list · record with 3D · tooth history), compact icon sidebar on
  the patient page, doctor-column calendar, Ctrl K search, dark mode, phone layout.

## 3. Additional features that can be added

**Clinical**
1. Periodontal charting (pocket depths, bleeding, mobility, furcation) with a 3D gum-depth lens.
2. X-ray / image viewer with zoom, measurements and annotations, linked to a tooth on the 3D model.
3. Treatment plan proposals: group planned items into options with totals, print / share for
   patient consent and e-signature.
4. Prescriptions (drug templates, dose, print with clinic letterhead) and allergy checks against
   the recorded medical alerts.
5. Consent forms and medical history questionnaires the patient fills in the patient app.
6. Before / after comparison slider on the 3D chart (timeline scrubber over history).
7. Orthodontic tracking (brackets, wires, appointments per stage) and lab orders (crowns,
   dentures) with due dates and lab status.

**Front desk & patients**
8. SMS / WhatsApp reminders and confirmations (Bangladesh SMS gateway), recall campaigns
   (6-month check-up), birthday messages; delivery status per message.
9. Waiting room / queue board for a TV screen (check-in order, chair, doctor).
10. Online booking page on the clinic's website without a patient login (new patients).
11. Family accounts (parent sees children's charts), patient documents and invoices download.

**Money & management**
12. Online payments (bKash / Nagad / card gateway) from invoices and the patient app.
13. Installment plans and payment reminders for overdue balances.
14. Expenses, supplier purchases and inventory (materials, stock alerts), daily cash close.
15. Doctor commission / revenue share reports, chair utilisation heat map, export to Excel.
16. Multi-branch clinics with branch switcher and consolidated reports.

**Platform**
17. Installable web app (PWA) with offline drafts on the PC, like the Android encrypted cache.
18. Audit log viewer and data export for compliance; automatic Firestore backups.
19. Native-language digits (Bangla, Hindi, Arabic numerals), translated guides and per-clinic custom wording; printable letterhead and receipt designs.
20. AI assistant (optional): draft encounter notes from dictation, summarise a patient's history
    and suggest follow-ups (always reviewed by the dentist, never a diagnosis).
