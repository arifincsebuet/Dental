# PRD implementation audit

Audit date: 30 October 2026. Baseline: [Android PRD v1.3](Docs/dental_clinic_management_system_prd_v1.2.md), plus the subsequent user requirements for Google login disabled initially, one Firestore document per patient, cloud images referenced in that document, lossless images within 512 KiB, and Settings → How to use.

## Result

**The application does not yet satisfy every requirement in the updated PRD.** Major workflows exist in source, but several P0 requirements remain partial. Source review is not evidence that the Android app builds, that Godot works on target hardware, or that Firebase rules/functions pass integration tests. Earlier broad implementation summaries should be read with this qualification.

> **Later change:** the Cloud Functions referenced below were removed. The app now uses Firestore directly, secured by `firebase/firestore.rules` and App Check; see [BACKEND_SETUP.md](BACKEND_SETUP.md). Server-side validation mentioned here now runs in the app.

The single-document patient layout and disabled Google login are explicit user decisions, not missing features. Native Firestore maps, embedded patient history, and private Storage references implement that revised direction. A bounded single document cannot hold unlimited history; capacity errors/export planning remain necessary.

## Coverage by requirement

| PRD area | Source coverage | Evidence and remaining requirements |
|---|---|---|
| 5.1 Authentication / users | Partial; Google intentionally disabled | `ClinicStore.google/signOut`, backend `roleFor/has/updateClinic`, config membership UI. Anonymous cloud authentication and optional Google linking exist. Email invitation/account recovery UX is absent; membership uses exact UIDs. Provider activation and permission/revocation integration tests remain. |
| 5.2 Patients | Partial | Registration, name/phone/ID search, DOB, allergies, story, communication consent, archive, history, import/export exist in `MainActivity`, `ClinicStore`. Duplicate detection is exact name+phone, without a merge/reconciliation workflow. Structured medication/medical-history/emergency-contact/profile fields are not fully exposed. |
| 5.3 Appointments | Partial | `SchedulingScreen`, `ClinicalRules.validateAppointment`, backend `scheduling`: day/week/month/all filters, duration/room/clinician overlap with buffers, reschedule and status actions. Requested state, structured service linkage, working hours/holidays/leave enforcement, richer patient/doctor/status search and linked follow-up workflow remain. |
| 5.4 Encounters/history | Partial | `ClinicalWorkspace`: encounter drafts, finalization, amendments, treatment transitions, performed care, prior values and attachment handling. Trusted encounter validation and source authorship were repaired in this audit. Findings are snapshot codes rather than authoritative finding IDs/status/encounter-linked source records. Charges/invoice lines are not consistently linked to performed procedures. |
| 5.5 Tooth JSON and identity | Substantial source coverage; partial | FDI primary/permanent/mixed maps, independent assessment/presence/replacement, multiple surface findings/restorations, explicit unknowns, validation, history and size caps. Multi-tooth/arch treatment targets and finding active/resolved/entered-in-error source lifecycle are absent. Shared anatomy rules are duplicated in code rather than delivered as one catalogue/asset manifest. |
| 5.5 Godot and fallback | Substantial source coverage; runtime unverified | `chart.gd`, `Dental3DActivity`, `NativeDentalChart`: custom crowns/cusps/fissures, gingiva/roots/replacements, loss markers, overlays, orbit/pinch/pan/reset/focus/arches, native selection and surface bridge. Read-only history/explanation and session/revision checks exist. Native 2D is manually available; automatic recovery from engine/GPU/native crashes is not implemented. Procedural anatomy is educational, not clinically validated. |
| 5.5 Offline / synchronization | Partial | Encrypted atomic storage, device-saved versus cloud-synchronized indication, dirty records, persisted operation IDs, callable commits, revision conflicts, retained drafts and bounded retry exist. Text typed before Save is held with `remember` and is not durable across process death/rotation. A second draft while a command is pending is not supported. |
| 5.5 / 3 online-only finalization | Gap | Encounter Finalize, treatment Completed, issued invoices, payments and confirmed appointments can be represented as final domain states in local pending records before a server receipt. Pending labels distinguish synchronization, but do not meet the PRD's explicit online-only finalization policy. An explicit draft/accepted-state implementation is still required. |
| 5.6 Staff | Partial | Staff profiles, roles, professional registration, active flag and free-text hours exist. Structured availability/leave and scheduling enforcement remain. Cloud account membership is distinct from staff directory entries. |
| 5.7 Services/pricing | Partial | Service name/code/default fee/duration/active state, currency/tax and retained invoice prices exist. Category/description fields and complete procedure-to-invoice linkage remain. |
| 5.8 Billing/payments | Substantial source coverage; partial | Line items, quantities, fixed discount/tax, minor-unit calculations, partial receipts, payment-linked refunds, reasoned voids and immutable issued terms exist. Server validation exists. Online-finalization separation, procedure linkage, role/device/integration verification and more complete invoice state presentation remain. No payment gateway is claimed. |
| 5.9 Reports | Partial | Day/week/month/custom filters, separate currencies, billed/received/refunded/net/outstanding amounts, appointment states, doctor booked minutes and treatment states, CSV sharing. Returning-patient metrics, utilization against available hours, service revenue, discount totals and report PDF export remain. All reporting uses the local record set, which may be stale/incomplete until refreshed. |
| 5.10 Automation (P1) | Partial | Local and scheduled private staff appointment reminders, opt-in consent, quiet hours and delivery outcome source states. Post-treatment follow-ups, no-show/overdue alerts, daily summaries, provider templates and direct patient messaging channels are absent. Legacy schedule rows may require re-save/sync; backend returns `reminderRebuildRequired`. |
| 5.11 Search/audit/export/settings | Partial | Clinic settings, role-scoped callable reads, embedded server clinical/financial/config audit, PDF care summary, JSON export/import, CSV sharing. Export actions do not yet generate required trusted audit records; formal retention/deletion policies and a managed backup/restore procedure are not implemented. |
| 6–7 Navigation/workflows | Partial | Home, Patients, Schedule, Reports, Settings and patient detail tabs exist. Core workflows can be followed in source. Durable unsaved-form recovery, consistent server-accepted final states, keyboard/large-text/device UX and automatic renderer fallback need further work. |
| 8–9 Architecture/data | Partial / deviation | User-approved embedded patient data is implemented; permission checks and callable server commits exist. Client models are `JSONObject` and encrypted files, rather than typed Kotlin DTOs, Room and explicit version migrations. Source times use epoch milliseconds and chart RFC 3339, without the PRD's native Firestore Timestamp adapters. |
| 10–15 Quality/release | Not demonstrated | No target phone/tablet GPU/lifecycle/accessibility/performance results, meaningful automated contract/concurrency/security test suite, migration/restore exercise, shared-device lock/cache-expiry workflow, dental review, or release/recovery evidence is present. Nine new backend source regressions cover the audit fixes, but template Android tests and the limited backend suite do not establish these wider requirements. Definition of Done is not met. |
| New: Settings → How to use | Implemented in source | `HowToUseScreen.kt`: offline operational help, 18 searchable/topic-filtered expandable guides, accessible headings, system/top-bar back navigation and the clinic palette. Available before cloud configuration or Google login. |

## Corrections made during this audit

- Added Settings → How to use with real navigation labels and operational guidance; it makes no cloud calls.
- Added patient ID to local patient search.
- Preserved typed patient-editor input when a synchronous save is rejected for a stale record, pending submission, role, validation, capacity, or busy state. Native forms, encounter/clinical dialogs, billing dialogs, invoice issue, appointment editing and tooth assessment check save rejection before closing. This does not implement durable unsaved drafts or cover disk failures after asynchronous acceptance.
- Recording tooth loss clears previous current natural-tooth facts while retaining the prior chart in history. New surface facts on a lost slot require an explicit replacement.
- Enforced the 32 KiB current-chart budget in client validation and authoritative server commits.
- Protected unsynchronized clinic settings from accidental workspace closure/switching.
- Preserved server-owned `recordedBy` / `recordedAt` for existing source records and nested events.
- Added trusted validation of new encounters and amendments; Android-only validation is insufficient for a callable API.
- Recomputed reminder jobs when clinic reminder configuration changes, using bounded schedule metadata rather than scanning patient documents. Legacy entries with unknown metadata are flagged for explicit migration instead of assuming consent.

## Validation performed and limits

- Reviewed the source against all functional sections and the acceptance/release sections above, with independent clinical/3D and backend reviews.
- `node --test functions/test/audit-regressions.test.js`: **9 passed, 0 failed**, covering preserved authorship, nested receipt provenance, trusted encounter validation, valid tooth surfaces and consent-aware reminder rebuilding. The harness stubs Firebase registration and calls pure server helpers; it does not contact cloud services or test transactions/rules.
- `node --check functions/index.js` passes after backend changes. This is JavaScript syntax validation, not a Firebase integration test.
- Guide wiring/content, unique help article IDs, XML/JSON parsing and local documentation links were checked with local source tooling.
- Android compilation, emulator/device runs, Godot rendering, Firebase deployment/access-rule tests and lifecycle/performance checks were not performed. The required Gradle 9.5 distribution is not unpacked locally; `local.properties` also references a different Windows SDK location. A prior download attempt was blocked by network restrictions. No runtime success is claimed.

## Priority for reaching PRD completion

1. Persist unsaved inputs as durable drafts and separate local proposed finalization from backend-accepted clinical/financial/appointment states.
2. Add authoritative finding source records and multi-target treatment scope inside the patient document, with versioned typed contracts and migration fixtures.
3. Complete working-hours/leave/service scheduling rules, profile fields, missing report metrics and export audit.
4. Implement reliable automatic 2D recovery, device cache/lock policy and tested retention/backup/restore procedures.
5. Resolve build dependencies, run meaningful security/concurrency/contract and phone/tablet lifecycle/accessibility checks, measure PRD targets, and obtain clinical review before release.

Patient portal, external accounting, insurance, imaging/PACS, AI diagnosis and specialist periodontal/root chart extensions remain outside the Android MVP; they are not counted as missing MVP features here.
