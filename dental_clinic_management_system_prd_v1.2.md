# Product Requirements Document (PRD)
## Dental Clinic Management System

**Version:** 1.3  
**Updated:** 30 October 2026  
**Status:** Draft — Android scope and efficient dental-chart contract defined  
**Product:** Native Android clinic management application for phones and tablets, with an interactive 3D dental chart  
**Proposed technology:** Kotlin, Jetpack Compose, Room, WorkManager, Firebase services, and embedded Godot

### Revision decisions

- Android is the first-release platform. Web, desktop, and patient portal clients are later scope.
- Replace repeated tooth anatomy and labels with one versioned, shared tooth catalogue.
- Store a sparse, bounded current-state map keyed by canonical FDI strings. Store findings, procedures, amendments, and history separately.
- Support primary, permanent, and mixed dentition; unknown or unassessed data never means healthy.
- Support offline viewing and durable clinical drafts; only backend-confirmed changes become saved clinical records.
- Retain the existing PRD filename for continuity; the document version above identifies this revision.

## 1. Product Overview

The Dental Clinic Management System (DCMS) centralizes appointments, patient profiles, clinical records, tooth-level treatment plans, doctor and employee management, billing, payments, reports, and routine notifications.

A core feature is an interactive human-tooth model built with Godot 3D Engine and embedded in the Android application. Every patient has an individual dental chart. Doctors can select teeth, record findings, plan treatments, mark procedures as completed, and review the patient's history over time. Native Android screens own clinical editing; Godot provides selection and visualization. The chart helps explain care to patients but does not replace professional clinical judgment.

## 2. Problem and Goals

Clinics may manage patient records, dental findings, schedules, and finances using disconnected systems or paper files. This can cause duplicated work, missed follow-ups, incomplete treatment histories, and difficult reporting.

Goals:

- Keep patient and clinical history in one place.
- Schedule and track appointments.
- Maintain doctor and employee records.
- Record tooth-level findings, planned treatments, and completed procedures.
- Provide an interactive 3D dental chart and a 2D fallback.
- Manage invoices, payments, balances, and receipts.
- Generate daily, weekly, and monthly operational and financial reports.
- Automate reminders and routine tasks.
- Protect sensitive information through access controls and audit logs.

## 3. Scope

### In scope

- Native Android phone and tablet application, touch interaction, and lifecycle-safe state restoration.
- Offline access to explicitly cached records and durable clinical drafts, with visible synchronization status.
- Authentication and role-based permissions.
- Patient registration, profiles, medical alerts, and dental history.
- Appointment calendar and status tracking.
- Clinical encounters, notes, findings, and treatment plans.
- Godot-based 3D dental chart with patient-specific saved data.
- Doctor and employee management.
- Service catalogue, invoices, payments, receipts, and balances.
- Daily, weekly, and monthly reports.
- Notifications, audit logs, export, and clinic settings.

### Out of scope for MVP

- Web/desktop clients and the patient portal.
- Fully offline clinical finalization, appointment confirmation, payments, refunds, or invoice issuance; these require backend confirmation in MVP.
- Structured supernumerary-tooth, root/canal-level, and periodontal pocket-depth charting. Preserve relevant observations in encounter notes; do not assign invented FDI codes. These require a later contract extension.
- AI diagnosis or autonomous treatment recommendations.
- Insurance claims, full laboratory management, or imaging/PACS.
- Integration with external accounting systems unless separately approved.
- Patient self-diagnosis through the 3D model.

## 4. User Roles

| Role | Permissions |
|---|---|
| Clinic owner / administrator | Clinic configuration, users, permissions, and reports |
| Receptionist | Patient registration and appointment scheduling; limited billing access |
| Dentist / doctor | Clinical encounters, dental chart, findings, and treatment plans |
| Dental assistant | Clinical information and tasks explicitly permitted |
| Accountant / cashier | Invoices, payments, receipts, and financial reports |
| Patient portal user (post-MVP) | View own permitted appointments and shared treatment plan |
| Auditor (optional) | Read-only access to authorized records and audit history |

Permissions must be enforced by the backend, not only by hiding interface controls. Clinic data must be isolated between tenants if multiple clinics use the system.

## 5. Functional Requirements

Priority: **P0** = MVP essential, **P1** = next phase, **P2** = later enhancement.

### 5.1 Authentication and User Management — P0

- Secure sign-in, sign-out, and account recovery.
- Administrators can invite, disable, and assign roles to users.
- Enforce least-privilege permissions on the backend.
- Log important security and permission events.
- Support clinic-level data separation.

### 5.2 Patient Management — P0
Each profile should include a unique patient ID, name, date of birth or age, contact details, address if needed, emergency contact if collected, relevant medical conditions, allergies, medications, dental history, consent records, and communication preferences.

The system must:

- Search by patient ID, name, phone, or approved identifiers.
- Detect likely duplicate registrations and let authorized staff resolve them.
- Display appointments, encounters, dental-chart history, treatment plans, invoices, and payments.
- Restrict sensitive fields by role.
- Record corrections and updates in an audit trail.
- Collect only information necessary for care and clinic operations.

### 5.3 Appointment Management — P0

- Day, week, and month calendar views.
- Create, reschedule, confirm, cancel, and complete appointments.
- Link each appointment to a patient, doctor, service, date/time, and duration.
- Track requested, scheduled, confirmed, checked-in, in-progress, completed, cancelled, and no-show states.
- Warn about doctor or room conflicts.
- Configure working hours, holidays, duration, and buffer times.
- Search appointments by patient, doctor, date, and status.
- Preserve reschedule and cancellation history.
- Support follow-up appointments.

### 5.4 Clinical Encounters and History — P0

- Record encounter date, attending clinician, complaint, examination notes, findings, diagnosis, and plan.
- Link findings and procedures to individual teeth where relevant.
- Record performed procedures, clinician, date, notes, and charges.
- Maintain chronological visit and treatment history.
- Distinguish clinical findings, proposed treatments, and procedures actually performed.
- Allow authorized clinicians to finalize notes; later amendments must be traceable rather than silently overwriting history.
- Support approved attachments with file-type, size, and access restrictions.

### 5.5 Interactive Dental Chart — P0

#### Architecture decision: shared anatomy, compact patient state, separate history

Use three layers:

1. **Shared catalogue:** Bundle tooth anatomy, label keys, valid surfaces, and a separate renderer mapping once with the Android app. Version the clinical catalogue as `fdi-v1`. Do not repeat static data for every patient.
2. **Patient snapshot:** Store a sparse map of recorded tooth states keyed by canonical FDI strings. This gives direct tooth lookup without searching an array. Keep descriptive field names; numeric enum codes and bitmasks add complexity without an MVP need.
3. **Authoritative clinical records:** Store findings, treatment items, performed procedures, and immutable revisions separately. The snapshot is a backend-maintained current projection for fast rendering, not a replacement for clinical history.

JSON is the Android/API/export contract. Firestore stores native maps and typed values, not serialized JSON strings. Android decodes to typed Kotlin models; the same state drives the Compose 2D chart and Godot 3D view. Store meshes/textures in packaged assets or permitted asset storage, never in the patient chart.

#### Tooth identity, inventory, and mixed dentition

- Use canonical FDI strings as **anatomical slot keys**: permanent `11–18`, `21–28`, `31–38`, `41–48`; primary `51–55`, `61–65`, `71–75`, `81–85`.
- `"11"` is the stable slot identity within the patient/chart context. Do not repeat `toothId`, `toothNumber`, tooth name, quadrant, or node path in each patient entry. A full reference is `(clinicId, patientId, chartId, toothKey)`.
- `dentition` is `primary`, `permanent`, or `mixed`. Mixed allows the union of 52 standard slots; this does not imply 52 teeth are present. Primary `"51"` and permanent `"11"` are different slots and can coexist. Never rename one into the other or infer eruption/exfoliation from age.
- Retained primary teeth alongside permanent teeth require a mixed chart. Reject inventory changes that would drop existing recorded slots; changing a UI numbering preference never rewrites canonical keys.
- A slot is not a physical tooth or implant identity. Extraction and later replacement remain chronological records against that slot. Record prosthetic replacement separately from natural-tooth presence.
- Node names such as `Tooth_11` are rendering metadata. Asset updates must not change clinical identity. Missing asset mappings produce an explicit native fallback, never a highlight on another tooth.
- FDI two-digit notation follows [ISO 3950](https://www.iso.org/standard/68292.html); the [FDI primary dentition chart](https://www.fdiworlddental.org/sites/default/files/2021-09/NOHP_slides_day1.pdf) identifies primary canines at position 3. The catalogue still requires dental-lead approval. Supernumerary teeth require a future identity extension; reject unsupported keys rather than inventing standard FDI slots.

#### Shared catalogue and surfaces

Bundle one versioned entry per supported slot, for example:

```json
{
  "catalogVersion": "fdi-v1",
  "toothKey": "11",
  "dentition": "permanent",
  "arch": "maxillary",
  "side": "right",
  "type": "central_incisor",
  "labelKey": "tooth.permanent.upper_right.central_incisor",
  "surfaceKeys": ["M", "D", "F", "L", "I"]
}
```

An independent asset manifest maps `(toothKey, optional surfaceKey)` to Godot nodes/mesh regions and records `modelVersion`. Mesh paths and asset version do not belong in patient tooth state. Localized labels belong in Android resources.

| Key | Canonical meaning | Valid teeth |
|---|---|---|
| `M` | Mesial | All supported teeth |
| `D` | Distal | All supported teeth |
| `F` | Facial; UI labels as labial/buccal for that tooth | All supported teeth |
| `L` | Lingual; UI labels as palatal on upper arch | All supported teeth |
| `I` | Incisal | Incisors and canines |
| `O` | Occlusal | Premolars and molars |

Permanent and primary positions 1–3 (including canines) use `I`; permanent positions 4–8 and primary positions 4–5 use `O`. Reject `O` on `11` and `I` on `16`. These are application conventions requiring clinical approval, not an interoperability certification.

#### Current-chart JSON contract — schema version 2

This is a complete valid **sparse** snapshot. It does not claim that all other teeth were examined:

```json
{
  "schemaVersion": 2,
  "catalogVersion": "fdi-v1",
  "clinicId": "CLINIC-001",
  "patientId": "PAT-1001",
  "chartId": "CHART-1001",
  "dentition": "permanent",
  "numberingSystem": "FDI",
  "chartVersion": 7,
  "updatedAt": "2026-10-30T10:00:00Z",
  "updatedBy": "DOC-001",
  "teeth": {
    "11": {
      "assessment": "assessed",
      "presence": "present",
      "surfaces": {
        "F": {
          "assessment": "assessed",
          "findings": [],
          "restorations": [
            "composite"
          ]
        }
      }
    },
    "16": {
      "assessment": "assessed",
      "presence": "present",
      "surfaces": {
        "O": {
          "assessment": "assessed",
          "findings": [
            "caries",
            "fracture"
          ],
          "restorations": [
            "amalgam"
          ]
        }
      },
      "treatmentStates": [
        "planned"
      ]
    },
    "48": {
      "assessment": "assessed",
      "presence": "extracted"
    }
  }
}
```

Here `11` has a recorded facial composite restoration; `16` has occlusal caries and fracture alongside an existing amalgam restoration, with planned care; `48` was recorded as extracted.

| Field | Meaning |
|---|---|
| `schemaVersion` | Contract version; this revision introduces `2` |
| `catalogVersion` | Pins clinical key/code interpretation; initially `fdi-v1` |
| `clinicId`, `patientId`, `chartId` | Transport context; backend compares with authorized request/path context |
| `numberingSystem` | Canonical value `FDI` in v2 |
| `dentition` | Supported slot inventory, independent of actual presence |
| `chartVersion` | Backend revision counter; starts at 0, increments once per accepted clinical command |
| `updatedAt`, `updatedBy` | Server-owned commit metadata; UTC RFC 3339 string in JSON, native Timestamp in Firestore |
| `teeth` | Map with at most 32 permanent, 20 primary, or 52 mixed entries |
| `assessment` | Required on recorded tooth/surface entries: `not_assessed`, `assessed`, `unable_to_assess` |
| `presence` | Required per recorded tooth: `unknown`, `present`, `unerupted`, `missing`, `extracted`, `exfoliated`, `congenitally_absent` |
| `replacement` | Optional: `none`, `implant`, `pontic`, `removable_prosthesis`; omission means not recorded |
| `findings` | Optional unique tooth-wide code set; surface findings belong on their surface |
| `surfaces` | Optional map; each recorded surface requires assessment, findings array, restorations array |
| `treatmentStates` | Optional unique set of current open states, projected from separate treatment items |

Contract rules:

- An absent standard slot means **unassessed with unknown presence** in this chart. This is a rendering default, not a clinical observation. It never means healthy, missing, or extracted.
- Tooth assessment does not assess every surface. An omitted surface is unassessed. An omitted optional findings/treatment-state array is not recorded; explicit `[]` is a recorded empty set for that scope. Do not collapse these cases in adapters.
- An assessed surface with `findings: []` has no current coded finding recorded there; `restorations: []` records no restoration there. Neither certifies whole-tooth health or replaces an examination narrative.
- Multiple findings and restorations may coexist on a surface. Arrays are unique bounded sets of current codes; detail, provenance, and chronology remain in authoritative documents.
- Presence, findings, restorations, and treatment workflow are independent. Remove the v1 `overallStatus` field, which conflated them. Completed treatment becomes a performed-procedure record and, where relevant, a current restoration; the tooth is not permanently labeled completed.
- Missing/extracted/exfoliated slots must be explicit. Never delete a slot to encode tooth loss. Historical restorations remain in history after extraction, rather than being reported as current natural-tooth surface state.
- `treatmentStates` includes open item states: `planned`, `accepted`, `in_progress`, `deferred`, `referred`. Completed/declined/cancelled items remain in history. Multiple open states may belong to separate items; the summary is not a workflow source.
- No null values in v2 snapshots. Use explicit `unknown` or omit optional unrecorded fields. Do not use empty strings or numeric array offsets as clinical meaning.
- Never embed unrestricted notes, patient demographics, growing record-ID arrays, anatomy, or mesh paths in tooth snapshots. An `other` code requires an explanatory authoritative note.
- Use real UTC transport strings, such as `2026-10-30T10:00:00Z`. Text such as `"Firestore Timestamp"` is an invalid placeholder. Firestore adapters persist native Timestamp fields and server-controlled commit metadata.
- The companion [snapshot JSON Schema](contracts/dental-chart.schema.json) validates shape, inventories, surfaces, and initial code vocabulary. Trusted backend rules additionally enforce clinical compatibility, authorization, references, source/projection consistency, and size limits.

Initial provisional vocabulary, requiring dental-lead approval before production:

- Findings: `caries`, `fracture`, `wear`, `endodontically_treated`, `periapical_finding`, `other`.
- Restorations: `composite`, `amalgam`, `glass_ionomer`, `sealant`, `crown`, `inlay`, `onlay`, `other`.
- Treatment-item workflow: `planned`, `accepted`, `in_progress`, `completed`, `deferred`, `declined`, `cancelled`, `referred`.
- Procedure codes belong to a versioned clinic service catalogue. These are application codes, not external billing codes; vocabulary changes require catalogue/version review.

#### Authoritative clinical records

Example finding:

```json
{
  "findingId": "FND-001",
  "patientId": "PAT-1001",
  "chartId": "CHART-1001",
  "toothKey": "16",
  "surfaceKeys": ["O"],
  "code": "caries",
  "status": "active",
  "encounterId": "ENC-001",
  "observedAt": "2026-10-30T09:55:00Z",
  "recordedAt": "2026-10-30T10:00:00Z",
  "recordedBy": "DOC-001",
  "note": "Clinical examination finding"
}
```

Example treatment item:

```json
{
  "treatmentId": "TREAT-001",
  "patientId": "PAT-1001",
  "chartId": "CHART-1001",
  "planId": "PLAN-001",
  "targets": [{"toothKey": "16", "surfaceKeys": ["O"]}],
  "procedureCode": "composite_restoration",
  "procedureCatalogVersion": "clinic-services-v1",
  "status": "planned",
  "encounterId": "ENC-001",
  "plannedAt": "2026-10-30T10:00:00Z",
  "plannedBy": "DOC-001"
}
```

- IDs are readable examples; use nonsequential generated IDs in production. Clinic ownership comes from the authorized path and is checked on every reference.
- Findings use active/resolved/entered-in-error states with immutable changes. Resolution removes a finding from the current projection without erasing its original record.
- Treatment targets support at most 52 unique standard slots and multi-tooth procedures. Define an explicit scope variant for whole-mouth/arch procedures in the treatment contract; never invent a tooth key.
- Completion creates a separate performed-procedure record: actual targets, linked treatment if any, clinician, encounter, performed/recorded times, and outcome. A performed procedure does not automatically resolve every linked finding.
- Treatment transitions and amendments preserve previous/new values, actor, server time, and required reason. Finalized encounters require amendment rather than overwrite.
- Billing references procedures or an explicitly approved planned-charge policy; chart colors do not trigger charges. Client-supplied author/ownership fields are never trusted.

#### Firestore layout and efficiency

```text
clinics/{clinicId}/
  patients/{patientId}                    # profile + activeChartId
    dentalCharts/{chartId}                # compact current snapshot
      revisions/{revisionId}             # immutable accepted revisions
      commands/{operationId}             # receipt + request fingerprint
    dentalFindings/{findingId}
    treatmentPlans/{planId}
    treatmentRecords/{treatmentId}
      events/{eventId}                    # immutable transitions
    performedProcedures/{procedureId}
    encounters/{encounterId}
  auditEvents/{auditId}                   # backend-owned, restricted reads
```

- Maintain one activeChartId pointer per patient transactionally. A dentition transition updates the inventory while preserving identity/history; it does not require a new chart.
- Open a chart with one snapshot fetch, then bounded detail pages as needed. Do not fetch one document per tooth or download the entire clinical history to draw the chart.
- Exempt the `teeth` map and other non-query payloads from automatic indexing. Query source records using explicit fields and tested indexes; see [Firestore best practices](https://firebase.google.com/docs/firestore/best-practices) and [map index exemptions](https://firebase.google.com/docs/firestore/query-data/index-overview).
- For multi-tooth treatment lookup, derive bounded `targetToothKeys` from targets for array-contains queries. Candidate patient-scoped indexes: findings `(chartId, toothKey, status, observedAt)` and treatments `(chartId, targetToothKeys ARRAY_CONTAINS, status, plannedAt)`. Commit indexes for actual query patterns; paginate with cursors.
- Product budget: **32 KiB compact UTF-8 per snapshot**, excluding detail/history/assets. Supplied fixtures measure **624 bytes** for the three-slot example, **12,247 bytes** for a fully assessed permanent chart, and **7,745 bytes** for a fully assessed primary chart. These are fixture sizes, not promised compression ratios or Firestore encoded sizes.
- Firestore has a separate **1 MiB document limit**; JSON byte size is not its storage size. Bound commands, target counts, and arrays as well as snapshots; see [Firestore limits](https://firebase.google.com/docs/firestore/quotas).
- Reuse the installed catalogue/assets, retain readable JSON fields, and measure database reads, network payloads, and 3D asset costs separately. Custom binary packing is deferred until profiling justifies it.
- Each accepted clinical command atomically creates source changes, current projection, immutable full snapshot revision, command receipt, and audit event. Full revisions simplify reconstruction in MVP; UI selections/camera moves/local keystrokes create no server revisions. Paginate history and define retention/storage-cost policy.

#### Android offline drafts, save confirmation, and conflicts

1. Authenticate and resolve clinic/patient/chart. Load permitted cached data through the repository with last-confirmed time/revision. Uncached patients require connectivity. Keep the confirmed snapshot separate from the draft overlay.
2. Native Compose controls edit a durable Room draft. Submission freezes an immutable outbox command with operationId, UID/clinic/patient/chart context, expectedChartVersion, and payload. UI states are local_draft, pending, syncing, saved, conflict, or rejected; they are not clinical statuses.
3. Foreground submission or a network-constrained WorkManager retry calls the authenticated backend. Recheck permissions at submission time. Android clients cannot directly write finalized clinical records/projections.
4. In one transaction, the backend first checks the receipt: the same operation ID and fingerprint returns its original result; a different payload reusing that ID is rejected. Validate expected revision, ownership, enums, clinical compatibility, and transitions; append source changes/revision/audit, update the snapshot, increment chartVersion once, and store the receipt atomically. Generate stable IDs outside retryable transaction callbacks; external side effects use a post-commit job.
5. Show Saved only after a backend receipt; atomically acknowledge the command and update the confirmed Room cache. Retry the same ID after timeout or process death, including death after server commit but before local acknowledgment.
6. A revision mismatch writes no clinical changes. Retain the draft, load current confirmed data, and show differences for clinician resolution. Do not silently merge or replay against a new version. A reviewed changed command uses a new ID and expected revision.
7. At most one unresolved submitted command per chart. Further edits remain a separate local draft until its predecessor is acknowledged. Do not queue assumed future revisions. Claim outbox items atomically to prevent foreground/worker races; acknowledge only the submitted draft without erasing subsequent edits.
8. Retry transient network failures with backoff and the same ID; validation/permission failures require user action. Pause on expired authentication or changed user/clinic. A pending command never rebinds to a newly selected patient. Dependent submission pauses on conflict/rejection.

Room supplies durable local state; WorkManager schedules retry rather than an Activity timer, following [Android offline-first guidance](https://developer.android.com/topic/architecture/data-layer/offline-first). Firestore offline changes use last-write-wins behavior, so direct offline writes are not this product's finalized clinical-save mechanism; see [Firestore offline behavior](https://firebase.google.com/docs/firestore/manage-data/enable-offline). Backend revision checks use [Firestore transactions](https://firebase.google.com/docs/firestore/manage-data/transactions).

Example command envelope (intent, not a client-computed snapshot):

```json
{
  "schemaVersion": 2,
  "operationId": "d3749642-41aa-4c90-8599-5ba26d161bd7",
  "clinicId": "CLINIC-001",
  "patientId": "PAT-1001",
  "chartId": "CHART-1001",
  "expectedChartVersion": 7,
  "encounterId": "ENC-001",
  "type": "record_finding",
  "payload": {"toothKey": "16", "surfaceKeys": ["O"], "code": "caries"}
}
```

Publish a separate command schema, limits, transition rules, and success/conflict/rejection responses before endpoint implementation. The snapshot schema does not validate commands, catalogue entries, or detail records.

#### Godot Android integration and presentation

- Embed Godot's Android library/AAR using a host-managed Activity/Fragment and a versioned bridge. Compose owns clinical controls around the native view. Pin compatible editor/export/library versions after the Phase 0 spike.
- Official embedding guidance describes host lifecycle responsibilities, one engine instance per Android process, and configuration-change constraints. Verify rotation, resizing, background/resume, and recreation for the pinned release. The [Godot Android embedding documentation](https://docs.godotengine.org/en/latest/tutorials/platform/android/embedding_in_android_projects.html) is marked latest; do not assume all described APIs exist in a selected stable release.
- Keep at most one active engine per process. Use a dedicated chart host if needed; initialization/GPU/asset failures open native 2D. Web export inside a WebView is not the default Android approach.
- Bridge messages carry bridgeVersion, a random sessionToken for the current chart load, chartVersion, and minimal tooth visualization/selection state. The token correlates callbacks, not authorization. Never send names, clinical notes, financial data, auth tokens, or privileged credentials to Godot.
- Host commands: load_chart, apply_confirmed_state, apply_draft_preview, set_selection, clear_chart. Renderer events: ready, tooth_selected, optional surface_selected, render_error. Clear/reset acknowledgment precedes the next patient's payload. Ignore old-session/stale callbacks and clear prior selection/detail state on patient switch.
- Validate selections against the active catalogue/session. Godot never authorizes or commits care. Draft overlays visibly differ from confirmed state in both renderers.
- Support rotate, pinch zoom, pan, reset, arch visibility, clear selected-tooth labels, and optional mesh surface selection. Native surface selectors remain available for every supported tooth.
- Use clinician-approved overlays/badges, legend, and text/symbol cues for multiple facts. Unknown/unassessed, findings, restorations, planned care, and recorded tooth loss remain distinguishable without color alone.
- Native 2D chart/list provides the same essential tooth/surface read/edit/save workflow, TalkBack, and validation without initializing Godot. Installed assets work offline.

#### History and patient explanation

- Retain baseline, accepted revisions, finding resolutions, treatment decisions, performed care, tooth loss/replacement, and amendments. Compare selected revisions with authors and commit times.
- Separate observation/performed time from server recording time; a delayed offline draft must not pretend it was recorded earlier.
- Patient explanation mode is clinician-operated within Android and filtered before rendering; hide internal notes/unrelated records. An independently authenticated portal remains later scope.
- Authorized print/PDF/export includes patient identity, chart date/version, clinician, and legend. Never export a local draft as finalized care.

### 5.6 Doctor and Employee Management — P0

- Maintain staff profiles, roles, contact details, professional details, and active status.
- Assign doctors to appointments and encounters.
- Configure working hours, leave, and availability.
- Disable access when a user is deactivated.
- Restrict employment or payroll information to authorized roles. Payroll is not part of MVP unless approved.

### 5.7 Services and Pricing — P0

- Maintain service name, description, category, default fee, duration, and active status.
- Restrict price changes to authorized users.
- Preserve the actual unit price charged on historical invoices.
- Configure clinic currency and applicable tax settings explicitly.

### 5.8 Billing and Payments — P0

- Create invoices linked to patients and, where relevant, encounters or appointments.
- Support line items, discounts, taxes, and adjustments according to clinic policy.
- Record payment amount, date/time, method, reference, and receiving staff member.
- Support partial payments and outstanding balances.
- Generate receipts.
- Restrict voids, refunds, and corrections to authorized roles and require reasons.
- Distinguish invoice status from payment status. Finalize invoices/payments through idempotent backend commands; offline entry remains a draft.
- Never store payment-card credentials. Future online payments must use an approved provider flow.
- Base income reports on payments received, not merely invoices issued.

### 5.9 Dashboard and Reports — P0
Provide date-filtered reports for:

- Daily, weekly, and monthly appointments.
- Completed, cancelled, and no-show appointments.
- New and returning patients.
- Procedures and treatment-plan status.
- Payments received, invoices issued, outstanding balances, discounts, refunds, and voids.
- Doctor workload and appointment utilization.
- Service-level revenue.

Reports must:

- Use the clinic's configured time zone.
- Label whether a figure represents cash/payment received, amount billed, or amount outstanding.
- Respect role and clinic permissions.
- Allow permitted CSV/PDF export.
- Reconcile to source transactions.

### 5.10 Automation and Notifications — P1

- Appointment confirmations and reminders.
- Post-treatment follow-up reminders.
- Optional no-show and overdue-balance alerts.
- Daily summaries for authorized staff.
- Configurable templates, timing, channels, and enable/disable controls.
- Record delivery status and failures when the provider supports it.
- Respect consent, communication preferences, quiet hours, and applicable laws.
- Prevent duplicate messages with idempotent scheduled jobs.
- Treat SMS and messaging costs as provider-dependent, not automatically free.

### 5.11 Search, Audit, Export, and Settings — P0

- Search records only within the user's permissions.
- Log sensitive clinical changes, role changes, billing adjustments, exports, and other important actions.
- Include actor, timestamp, action, target record, and an appropriate change summary.
- Prevent ordinary users from editing audit records.
- Provide authorized export and documented retention policies.
- Configure clinic name/logo, time zone, currency, hours, numbering system, reminders, and permissions.
- Establish backup and recovery procedures.

## 6. Information Architecture

Suggested navigation:

1. Dashboard
2. Appointments
3. Patients
4. Dental Chart / Clinical Records
5. Doctors and Staff
6. Services
7. Billing and Payments
8. Reports
9. Notifications / Automation
10. Settings and Audit Log

UX principles:

- Keep common receptionist tasks short.
- Confirm patient identity before clinical or financial edits.
- Make appointment and payment states unambiguous.
- Confirm destructive or financially significant actions.
- Provide loading, empty, confirmed, local draft, pending, conflict, and rejected states with recovery actions.
- Support touch, TalkBack, large text, optional keyboard navigation, and readable contrast.
- Clearly identify unsaved clinical changes.

## 7. Core Workflows

### New patient and appointment

1. Search for an existing patient.
2. Create a profile if needed.
3. Book an appointment with a doctor and service.
4. Confirm the booking and schedule reminders if enabled.

### Consultation and chart update

1. Open the appointment and verify the patient.
2. Create/open the clinical encounter.
3. Review medical alerts and prior history.
4. Select teeth in the 3D chart or 2D fallback.
5. Record findings and proposed treatments.
6. Save a durable local draft and submit validated intent; show confirmed care only after the backend receipt.
7. Review the plan with the patient and record the decision as appropriate.

### Treatment completion

1. Open a planned treatment item.
2. Record the procedure performed and relevant notes.
3. Record clinician and completion date.
4. Submit completion online; atomically record performed care and immutable status history after backend validation.
5. Verify the corresponding invoice line.
6. Show the updated state in the chart and patient history.

### Payment and daily close

1. Create or open an invoice.
2. Record payment and method.
3. Recalculate balance and invoice status.
4. Generate a receipt.
5. Verify the payment appears in the correct reporting period.
6. Review daily payments, refunds, voids, invoices, and outstanding balances. Corrections must be audited.

## 8. Conceptual Data Model and Integrity Rules

| Entity / collection | Purpose and key data |
|---|---|
| Clinic / ClinicMembership | Settings; UID, role, permissions, active status |
| Patient / MedicalHistory | Profile, alerts, consent, activeChartId, authored history |
| Appointment / ClinicalEncounter | Patient, clinician, time, status, revision; encounter notes/finalization |
| ToothCatalogue / ModelManifest | Bundled anatomy, labels, surfaces; independent asset-to-slot mapping |
| DentalChart | Schema/catalogue versions, dentition, chartVersion, sparse teeth, server metadata |
| DentalChartRevision | Immutable full snapshot, resulting version, operation ID, actor/time/reason |
| DentalFinding | Tooth/surfaces, code, active/resolved/error state, encounter, observed/recorded times |
| TreatmentPlan / TreatmentItem | Bounded targets, procedure catalogue/code, decisions, workflow |
| TreatmentEvent / PerformedProcedure | Immutable transitions, performed care, actual targets, clinician/dates/outcome |
| ClinicalCommandReceipt | Operation ID, request fingerprint, actor/context, resulting revision/record IDs |
| LocalChartCache / LocalDraft / Outbox | Device-only UID/clinic-scoped confirmed projection, overlay, immutable command |
| Service | Versioned procedure code, label, default fee/duration, active status |
| Invoice / InvoiceLine | Number, currency, procedure link, historical prices, totals, status |
| Payment / Refund | Integer minor-unit amount, method, reference, status, recorder, required reason |
| NotificationJob / AuditEvent | Idempotent delivery; backend-owned actor/action/target/time/change records |

Data rules:

- Section 5.5 and the companion snapshot schema define one dental contract for Android, backend, Room adapters, and Godot projections. No screen-specific competing chart schema.
- FDI keys identify anatomical slots; the catalogue supplies labels/anatomy. Snapshots contain bounded current facts; authoritative records/events preserve clinical detail and chronology.
- Path/stored context must agree. Check clinic/patient/chart/encounter ownership and current membership on every command, including offline retries.
- JSON uses UTC RFC 3339; Firestore uses Timestamp adapters. Observation time, server commit time, and device-local draft time are distinct.
- Schema, catalogue, chart revision, renderer bridge, and asset versions have separate meanings; validate supported combinations. Publish separate command/detail schemas before implementation.
- Client writes to server-owned chart projections/revisions/receipts/audits are denied. Reads and attachments enforce tenant/role scope. Admin SDK handlers enforce authorization independently of Security Rules.
- Related clinical changes fit within one bounded backend transaction. Post-commit jobs handle idempotent notifications; never send external effects from retryable transaction callbacks.
- Appointment availability and finances also require server-side checks. Cached data cannot reserve an appointment or settle an invoice.
- Money uses integer minor units plus ISO currency, explicit rounding, preserved charged prices, and immutable financial adjustments.
- Define retention for revisions/source records/audits/receipts. Retain receipts for at least the supported command retry period; define a maximum pending-command age and reject expired unknown commands before deleting receipts.

## 9. Proposed Android Technical Architecture

| Layer | Proposed implementation and responsibility |
|---|---|
| Native UI | Kotlin + Jetpack Compose; adaptive phone/tablet screens and native 2D chart |
| UI state | ViewModel + StateFlow, lifecycle-aware collection, immutable patient-scoped state |
| Domain | Dental rules, command construction, conflict review, billing rules; independent of Godot |
| Repository | Coordinates local state and authenticated backend; no direct database access from composables |
| Local persistence | Room for bounded cache/drafts/outbox; DataStore for nonclinical preferences |
| Durable sync | WorkManager, network constraints, per-chart serialization, bounded retry and permission recheck |
| Serialization | kotlinx.serialization DTOs and explicit domain/Room/Firestore adapters |
| Identity/backend | Firebase Authentication; trusted Cloud Functions/Cloud Run command API |
| Remote storage | Firestore native fields and Cloud Storage with clinic-scoped access |
| Visualization | Embedded Godot Android library/assets; complete native 2D fallback |
| Reminders/reporting | Trusted server jobs and traceable queries, independent of Android background execution |

Separate UI, repositories, and data sources following [Android architecture recommendations](https://developer.android.com/topic/architecture/recommendations). Room is the UI's local source; backend-confirmed records remain the authority for accepted care. Keep drafts separate from the confirmed projection. Avoid a second independently managed Firestore disk cache for the same records; explicitly choose SDK memory-only caching if Android SDK reads are used alongside Room.

Android requirements:

- Decode `teeth` as `Map<String, ToothStateDto>`, never a positional array. Use typed enums/sealed states; never default missing assessment to assessed or missing presence to present.
- Inspect schema/catalogue version before decoding the body. Unsupported future versions require an update/read-only flow; do not drop unknown fields and resave. Semantic changes require a new version.
- Preserve omitted optional arrays versus explicit `[]`: nullable Kotlin transport properties represent absence, emptyList represents recorded empty, explicit JSON null is rejected, and serialization omits absent values. Surface arrays are required. Test these semantics through all storage adapters.
- Use application-scoped repositories and durable Room records. Never keep the only draft in an Activity/ViewModel, or put full clinical JSON/notes in navigation arguments, saved-state bundles, logs, or analytics.
- Room drafting/outbox insertion and receipt/cache acknowledgment are transactional. Foreground and worker submission share an atomic claim; acknowledgments cannot erase later draft edits.
- Partition local data by UID/clinic. Pause work on logout/membership removal; clear confirmed sensitive cache and lock retained unsent drafts to their original identity. Define retention/discard UX and reauthentication before reusing drafts.
- Use a reviewed encrypted database/file design with Android Keystore-managed keys; Room alone does not encrypt a database. Exclude clinical caches/outbox/tokens/keys from backup and device-transfer paths. Use app-private storage and a shared-device lock policy.
- Use system photo/document pickers for attachments, minimal permissions, server validation, and scoped sharing URIs. Keep identifiable patient data out of lock-screen notifications and crash logs.
- Provisional minimum: Android 10/API 29, subject to actual clinic device inventory and pinned Godot compatibility. Pin compile/target SDK and Kotlin/Compose/Firebase/Godot/Gradle/native versions during Phase 0. Recheck distribution, target-SDK, and native-library requirements at release.
- Confirm managed/Play or signed APK distribution. Protect signing credentials outside source control; separate dev/staging/prod and document migration/rollback compatibility.

No Android or Godot build may contain service-account keys. Test command APIs and access rules across roles, clinics, disabled accounts, revoked membership, stale sessions, and attachments. Phase 0 must prove lifecycle handling, correct selection mapping, secure save/load, offline draft recovery, conflict review, and 2D equivalence.

## 10. Non-Functional Requirements

### Security and privacy

- Least-privilege clinic isolation for backend records and device caches; no cross-patient renderer state.
- Encryption in transit/at rest and reviewed local encryption/backup behavior per Section 9.
- Audited clinical/financial mutations and authorized exports with server-owned metadata.
- Document consent, retention, deletion/export, account removal, cache expiry, and lost/shared-device access. Resolve jurisdiction and applicable healthcare requirements before release.

### Reliability and offline operation

- Cached viewing/local drafts work offline; uncached records and backend-only actions explain their connectivity requirement.
- Every save distinguishes local draft, pending, confirmed, conflict, or rejected state. Pending is never presented as confirmed care.
- Preserve drafts across rotation/background/process death/restart. Identity or clinic changes cannot rebind a command.
- Handle duplicate submissions, timeout-after-commit, concurrent edits, and stale responses without data loss.
- Renderer failure preserves the draft and opens 2D editing. Test restoration from backups; define recovery-time/recovery-point objectives in discovery.

### Performance and efficiency

Initial release targets must be measured in release builds on a documented Android 10+ baseline phone/tablet with 4 GB RAM, 64-bit ARM, and a supported GPU. Phase 0 may revise targets with measured evidence and clinic agreement.

| Scenario | Provisional acceptance target |
|---|---|
| Cached 2D chart opens | p95 <= 1 second over 30 warm opens |
| Local tooth tap to native details | p95 <= 100 ms, excluding remote history |
| Installed 3D chart ready | p95 <= 3 seconds over 30 engine starts |
| 3D rotate/zoom | >= 30 FPS during a 60-second scripted interaction |
| Backend-confirmed save | p95 <= 3 seconds over 30 commands on documented clinic Wi-Fi; conflict cases excluded |
| Snapshot transport | <= 32 KiB compact UTF-8; one snapshot fetch, no per-tooth fan-out |
| Clinical history | Cursor pagination, initial page <= 50 records; no full-history preload |

Measure memory, APK/AAB and installed asset size, network bytes, database reads/writes, thermal behavior, crashes, and ANRs in Phase 0, then set release budgets. Compact JSON alone does not solve renderer performance. Keep expensive parsing/projection, disk, and network work off the main thread; show pending status for slow saves.

### Accessibility and maintainability

- Adaptive phone/tablet layouts; portrait/landscape, large text, optional keyboard, and supported multi-window behavior.
- Native TalkBack descriptions include tooth/assessment/presence/surface; minimum 48 dp touch targets, readable contrast, visible focus, and non-color cues.
- All essential chart tasks work in 2D; precise 3D gestures are never the only way to select a tooth/surface.
- Separate UI, clinical rules, sync, storage, and rendering. Version schemas/migrations and review code.
- Run relevant contract, authorization, transaction/concurrency, migration, Android lifecycle, accessibility, and device checks.

## 11. Delivery Phases

### Phase 0 — Android discovery and proof of concept

- Confirm clinic/tenant model, roles, devices, language, network, time zone/currency, distribution, retention, and offline draft policy.
- Obtain dental approval for numbering/catalogue, mixed dentition, surfaces/codes, loss/replacement, and amendments.
- Spike native Godot embedding, one engine, lifecycle/configuration changes, mapping, and native 2D fallback.
- Verify v2 parsing, encrypted Room/outbox design, secure server commit, timeout recovery, and two-device conflict.
- Measure Section 10 targets, approve any adjustments, and pin build/native dependencies.

**Exit criteria:** A supported phone and tablet select correct primary/permanent slots; mixed chart and 2D/3D views agree; drafts survive process death; backend commits once or reports conflict; cache isolation and fallback work.

### Phase 1 — Android MVP

- Authentication, membership/roles, profiles/alerts, appointments, staff directory.
- Encounters, findings, plans, performed care, and traceable amendments.
- Schema v2 chart, 3D/2D views, immutable revisions, durable offline drafts/sync.
- Services, backend-confirmed invoicing/payments/receipts, basic reports.
- Authorized exports, audit, backups, settings, device security, and signed Android delivery.

### Phase 2 — Automation and refinements

- Consent-aware appointment/follow-up reminders and optional private push notifications.
- Advanced reports, revision comparison, improved patient explanation mode, approved provider integrations.
- Additional clinical catalogue coverage/extensions after dental review.

### Phase 3 — Expansion

- Optional web/desktop clients, independent patient portal, self-service booking using compatible APIs.
- Multi-branch analytics, advanced permissions, additional integrations after privacy review.

## 12. Acceptance Criteria

### Patients and appointments

- Authorized staff create/search/update records and see likely duplicates.
- Appointment booking/rescheduling/cancellation/completion uses backend conflict checks. An offline draft never confirms a reservation.

### Dental contract and efficiency

- Snapshot passes the v2 schema; Firestore uses maps/typed fields/Timestamp adapters, not serialized JSON.
- Primary/permanent/mixed/empty/fully assessed fixtures load. Slots 51 and 11 coexist; unsupported 19/56/extra-tooth keys are rejected.
- Tooth anatomy, names, node paths, notes, and growing ID arrays are absent from patient entries. Catalogue/assets change independently of clinical facts.
- Omitted 11 renders unassessed/unknown; explicit extracted 48 renders recorded loss. Omitted surfaces never mean healthy; tooth assessment does not assess all surfaces.
- A surface supports caries plus fracture alongside an existing restoration. O on 11 and I on 16 are rejected; primary canines 53/63/73/83 accept I and reject O.
- Presence/replacement/assessment/findings/restorations/workflow remain independent. Omission versus empty set survives Kotlin, Room, Firestore, and export round trips.
- Snapshot stays within 32 KiB; detail/history pages are bounded and indexed; chart loading has no per-tooth document fan-out.
- Unsupported versions open safe update-required/read-only behavior. Migration never infers normal findings/presence from missing legacy values.

### Android save integrity and offline behavior

- Direct client writes to server-owned clinical projections/revisions/receipts/audits are denied.
- Accepted command atomically writes source changes, projection, revision, receipt, audit, and one version increment.
- Two devices submit distinct commands against version 7: exactly one commits as 8, the other conflicts and retains its draft.
- Timeout/process-death retry with the same ID returns the original receipt without duplicate findings/procedures/revisions/charges; changed content under the same ID is rejected.
- Offline Room drafts survive rotation/process death/restart and display pending. Saved requires a backend receipt; revoked membership prevents later commit.
- Edits during a pending command do not assume future revision numbers or disappear on acknowledgment. Foreground/worker senders share an atomic claim.
- Observation/performed times remain separate from server-recorded time. Finalized records require traceable amendments.

### Renderer, lifecycle, and accessibility

- Every supported slot has validated asset mapping or explicit native fallback; wrong-tooth highlights are prevented.
- Switching patients during async load/selection clears prior visuals/details and ignores stale callbacks/responses.
- Draft versus confirmed overlays, legend, and multiple clinical facts remain understandable without color alone.
- Initialization failure, rotation/resizing/background/resume, and process recreation preserve clinical state or safely open 2D; at most one engine per process.
- Complete essential tooth/surface workflow works in native 2D with TalkBack/large text. Baseline phone/tablet meet approved release targets.

### Billing, privacy, and release

- Integer minor units, explicit rounding, immutable adjustments/refunds, and partial-payment balances reconcile. Reports distinguish billed from received and use clinic time zone.
- Financial finalization requires idempotent backend confirmation; retries cannot create duplicate charges/payments.
- Restricted roles/tenants/disabled users/revoked memberships cannot access records or attachments.
- Caches/drafts/work remain UID/clinic-bound; logout/backup/device-transfer tests expose no other user's records. Logs/lock-screen notifications contain no sensitive content.
- No privileged client credentials; signed release, restore, and migration procedures pass before launch.

## 13. Risks and Mitigations

| Risk | Mitigation |
|---|---|
| Godot embedding/configuration/GPU fails on clinic devices | Native device spike, pinned versions, one engine, complete 2D fallback |
| Repeated anatomy and growing patient documents | Shared catalogue and bounded sparse snapshot; separate clinical records |
| Unknown shown as healthy or primary/permanent slots confused | Explicit omission semantics, canonical keys, mixed inventory, clinical approval |
| Concurrent edits or clinical history overwritten | Backend revision checks, immutable revisions/events, explicit conflict review |
| Retry duplicates procedures or charges | Immutable commands, receipt/fingerprint, retry-window policy, financial idempotency |
| Draft lost or submitted as another user | Durable UID/clinic-bound Room outbox, pause on logout, receipt recovery |
| Shared/lost Android device leaks cache | Reviewed encryption, lock policy, bounded cache, backup exclusion, controlled sharing |
| Async callback displays wrong patient/tooth | Context-bound loading and session tokens, reset protocol, stale-response rejection |
| Unbounded reads/revision storage cost | One snapshot fetch, cursor-paginated detail, measured budgets, retention policy |
| Financial totals disagree | Minor-unit arithmetic, centralized rules, immutable source transactions |
| Platform/clinical scope expands | Android-first MVP and explicit deferred chart extensions |

## 14. Open Decisions Before Implementation

Android is the first-release platform. Discovery/prototyping can proceed with the stated defaults; resolve these items before production implementation:

1. Single/multiple clinics or branches, expected patients/users/concurrent editors?
2. Actual phone/tablet OS/GPU inventory, shared-device policy, network conditions, and distribution channel?
3. Dental approval for FDI/catalogue/surfaces, display/import conversion, mixed dentition, and valid presence/replacement combinations?
4. Clinical finalization/amendment permissions, reasons, and workflow transitions?
5. Offline cache expiry, draft retention/logout UX, retry window, device lock/encryption, and conflict-review ownership?
6. Currency/taxes/rounding, receipt format, payment/refund permissions, and planned-charge policy?
7. Languages, time zone, communication consent/channels/quiet hours, and patient explanation needs?
8. Data jurisdiction, retention/deletion, backup/export, recovery-time/recovery-point objectives?
9. Legacy import format and reconciliation of incomplete clinical data?
10. Pinned build dependencies and measured budgets that pass the Phase 0 Android/Godot gate?

## 15. Definition of Done

A feature is done when:

- Requirements and acceptance criteria pass on the approved Android phone/tablet matrix.
- Authorization is enforced and tested.
- Relevant contract, security, concurrency, migration, lifecycle, offline recovery, accessibility, and manual QA checks pass.
- Loading, empty, local draft, pending, confirmed, conflict, and rejected states have clear recovery actions.
- Audit requirements are met.
- Documentation and deployment/rollback steps are updated.
- No critical security or data-integrity issue remains open.
- Clinical workflows are reviewed by a qualified clinic representative.

## 16. Contract Artifacts, Validation, and Migration

Companion files:

- [Snapshot JSON Schema v2](contracts/dental-chart.schema.json), draft 2020-12.
- [Sparse permanent example](contracts/examples/chart.permanent.sparse.json): restoration, multiple surface findings, planned care, extracted tooth.
- [Primary example](contracts/examples/chart.primary.json) and [mixed example](contracts/examples/chart.mixed.json).
- [Empty chart](contracts/examples/chart.empty.json): every supported slot unknown/unassessed.
- [Fully assessed permanent](contracts/examples/chart.permanent.complete.json) and [fully assessed primary](contracts/examples/chart.primary.complete.json): explicit five-surface empty observations.
- [Contract validation script](contracts/validate-contract.ps1): checks all six fixtures, their size budget, and 27 rejection cases. Run from this folder in PowerShell 7.4+ with `./contracts/validate-contract.ps1`.

The snapshot schema validates transport shape, initial codes, inventories, and valid surface keys. It does not validate separate command/detail contracts, clinical appropriateness, permission, trusted audit metadata, Firestore-native types, or source/projection consistency. Backend/domain tests and clinical review cover those requirements. Check encoded size separately.

Before Android implementation, publish typed Kotlin models and command/result, finding, treatment-scope/transition, performed-procedure, and bridge contracts. Add fixtures for conflict/lifecycle recovery, multi-tooth procedures, source amendments, replacement after extraction, explicit unknowns, oversized data, future versions, and v1 migration.

### Migration from PRD contract v1 to v2

1. Preserve original charts/revisions and identify source schema. Convert native Timestamp values through adapters, never literal placeholder text.
2. Use valid FDI map keys as stable slots. Check repeated legacy toothId/toothNumber agree; quarantine disagreements/unsupported keys for review.
3. Move static labels/anatomy to the catalogue. Preserve notes, record links, and assessment provenance in authoritative or retained legacy records rather than discarding them.
4. Map mesial/distal to M/D, labial/buccal to F, lingual/palatal to L, incisal/occlusal to I/O with tooth validation. Expand old single-value observations into findings/restorations only when meaning is explicit; ambiguous cases require review.
5. Preserve explicit assessment. Unknown/missing legacy presence stays unknown. A mesh or default 32-tooth map does not prove presence; no_finding_recorded does not prove health.
6. Derive workflow summaries from authoritative treatments. overallStatus/reference arrays alone cannot establish performed care. Incomplete records remain flagged for reconciliation.
7. Validate v2/reference integrity, create an audited migration revision, and update active linkage atomically. Migration is idempotent with preserved originals, read/rollback compatibility, and a reconciliation report. Prevent old writable clients from overwriting v2.

### Review findings addressed in version 1.3

| Previous gap | Resolution |
|---|---|
| Web frontend/web-export assumptions | Native Android stack, lifecycle handling, signed delivery, embedding spike |
| Repeated tooth identity/anatomy and reference arrays | Shared catalogue, canonical map keys, paginated source records |
| No mixed dentition | Explicit mixed inventory and identity-preserving transitions |
| One value per surface, conflated overallStatus | Independent dimensions and multi-value surface data |
| Timestamp placeholders | Valid UTC transport and native Firestore adapters |
| Unspecified offline/conflict behavior | Durable outbox, receipt-confirmed saves, transaction/review workflow |
| Vague responsiveness | Provisional measured targets and snapshot byte budget |
| No executable snapshot shape | Companion JSON Schema and representative fixtures |

Platform choices reference official documentation in Sections 5.5 and 9; recheck dependency versions and release requirements when implementation begins.


---

**Note:** This PRD is for product planning, not clinical or legal advice. Qualified dental professionals and appropriate compliance specialists should review clinical terminology, recordkeeping workflows, privacy, and production security before launch.
