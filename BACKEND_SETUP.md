# Trusted Firebase backend

This directory contains deployable Cloud Functions source and restrictive Firestore/Storage rules. No Firebase project, billing configuration, cloud resources, service credentials, rules, or functions were deployed during implementation. No npm installation, build, emulator run, or device test was performed, per the request to implement without verification.

## Cloud setup

1. Create/register the Android app in your Firebase project and fill in the Android Firebase resources from `firebase/firebase.xml.example`.
   - **Google** sign-in provider: required for clinic staff. Register the app's SHA-1/SHA-256 signing fingerprints and put the web OAuth client ID in `default_web_client_id`.
   - **Email/Password** provider: required for patient logins. Patients use synthetic `<PatientID>@patient.dentalcare.local` addresses, which never receive mail.
   - Anonymous auth is only needed for older device-only workspaces.

   See [Docs/firebase_architecture.md](Docs/firebase_architecture.md) for the full design and cost controls.
2. Set up Firestore and Cloud Storage in appropriate regions. Cloud Functions and Cloud Scheduler deployment require a billing-enabled project and the relevant Google Cloud APIs. Storage/Firestore/Functions/Scheduler/FCM delivery consume their respective service quotas; using one patient document does not make requests free.
3. Install Node.js 22 and Firebase CLI on your development/deployment machine. In `functions`, run `npm install`. Deploy from the project root with your explicitly selected project: `firebase deploy --project YOUR_PROJECT_ID --only functions,firestore:rules,firestore:indexes,storage`.
4. Default function region is `us-central1`. To choose another supported region, set `FUNCTION_REGION` in the Functions environment before deployment and use the same value in the Android `firebase_functions_region` resource. Place compute close to Firestore where practical.
5. All callable functions require Firebase Authentication. Anonymous Firebase users are allowed to own their personal workspace. Optional App Check enforcement is enabled by `ENFORCE_APP_CHECK=true` after configuring the Android provider; do not turn it on before the Android app is registered for it. The backend does not need service-account keys in the Android app.
6. Cloud Storage rules use `firestore.get` to check clinic membership; grant the cross-service rules access when prompted by Firebase deployment. Direct patient/config document reads/writes are denied. The app uses callable endpoints for role-filtered reads and commits.

The supplied Firebase emulator configuration provides Auth, Functions, Firestore, and Storage ports for later local verification. These services are not started by this implementation.

## Storage layout and costs

```text
workspaces/{ownerFirebaseUid}                       # one shared clinic config
workspaces/{clinicId}/patients/{patientId}          # exactly one doc per patient
Storage: workspaces/{clinicId}/patients/{id}/{sha256}.png
```

The clinic ID is its owner's Firebase UID. Other users join that workspace by their exact Firebase UIDs in `config.members`; knowledge of a clinic ID alone grants no access. A personal workspace requires no bootstrap: the UID owner has owner rights, and the first patient commit or clinic update creates its config document. One shared clinic document holds configuration, members, staff/services, the active scheduling index, reminder queue, device registrations, and configuration audit. No tooth, event, receipt, invoice, or reminder subcollection is created.

Each patient document contains its profile, chart, clinical story, source records, history, invoices/payments/refunds, image references, revision, embedded immutable audit, and recent command receipts. App-local `dirty`, `remoteVersion`, and `cloudOwner` fields are not accepted as clinical source data. The server controls revisions, author/recording metadata, current treatment-state projection, audit entries, and operation receipts. Server audit stores changed prior fields/items and after-value hashes rather than copying every full patient snapshot.

The server reads the clinic and patient in a transaction for authorization/concurrency, then writes only the changed patient document in the usual case. It writes the clinic document only for first initialization or changed scheduling/reminder metadata. There are no real-time listeners or background full-patient scans. Explicit listing uses document-ID cursor pagination (maximum 100 records per page). Large maps/arrays and non-query fields have index exemptions. Reminder polling queries only due clinics through one compound index; it does not poll every patient.

Single documents remain bounded: the server stops writes at a conservative 750,000 UTF-8 JSON bytes before Firestore's separate 1 MiB encoded document limit. It also caps histories, source arrays, active scheduling index, reminder queue, memberships and devices. It never silently truncates clinical history or audit. Export and an operator-approved archive/migration plan are necessary when a patient/clinic reaches the limit. Unlimited lifetime history and an invariant single bounded Firestore document cannot both be guaranteed.

## Callable contracts

All return JSON-compatible fields with integer UTC epoch milliseconds for recording times; chart `updatedAt` is UTC RFC 3339.

- `getClinic({clinicId})` returns `{clinicId, revision, config, reminderSummary}`. Missing personal config returns defaults at revision 0 without a write. Reminder summary aggregates queue statuses and contains no FCM tokens.
- `updateClinic({clinicId, expectedRevision, operationId, config})` returns the same shape. Only owner/admin can edit config; only owner can change exact-UID memberships. Config includes `name`, `currency`, `timeZone`, `members` UID map, `staff`, `services`, and optional flat `taxBps`, `bufferMinutes`, `reminderHours`, `remindersEnabled`, `quietStart`, `quietEnd`; nested legacy `settings` is supported.
- `listPatients({clinicId,pageSize,afterId?})` returns `{patients,nextCursor}`. Omit `afterId` initially; continue with `nextCursor` until null. Page size is 1–100.
- `getPatient({clinicId,patientId})` returns `{patient}` or `not-found`.
- `commitPatient({clinicId,patientId,expectedRevision,operationId,patient})` returns `{revision,updatedAt,patient}` with the canonical committed patient filtered for the caller's role. Use that returned patient for future edits. The same operation ID/fingerprint returns an accepted current canonical state without duplicating the mutation; `operationRevision` identifies the original revision on a retried older accepted command. Reusing an ID with changed content is rejected. A stale expected revision produces `aborted` and no clinical changes. An unresolved operation must retain its immutable payload/ID until its receipt is resolved. Embedded receipts retain the latest 128 accepted operations; an older unknown retry conflicts safely on its stale revision rather than replaying against newer data.
- `registerDevice({clinicId,token,enabled})` registers/removes the current user's private FCM token. At most eight recent tokens per member are retained. Tokens never appear in `getClinic` results.

## Authorization and records

Roles: `owner`, `admin`/`administrator`, `dentist`, `assistant`, `receptionist`, `cashier`, `auditor`. Personal owners have all permissions. Dentist roles can write clinical fields; assistants require explicit `permissions.clinicalWrite`, and clinical read requires `clinicalRead` or `clinicalWrite`. Receptionists can maintain demographic/scheduling fields and basic billing visibility; cashiers can change invoices/payments/refunds. Auditors have read-only clinical access. Only authorized clinical readers can access Storage images. Nonclinical patient responses omit chart/story/medical history/images/clinical source records/audit; hidden fields are preserved server-side during partial updates. Client-side hidden controls are only a convenience; endpoints enforce authorization independently.

The backend validates standard FDI inventories, tooth-specific I/O surfaces, controlled findings/restorations/presence codes, treatment transitions, immutable source events, finalized encounter base fields, append-only amendments, and performed-care references. Completing a procedure does not itself resolve all findings. Recorded history cannot be rewritten by a clinical reader. Server audit remains authoritative even if a client submits a misleading free-text annotation.

Invoices use integer minor units. New line items have historical `unitPrice`, integer `quantity`, fixed discount and basis-point tax with HALF_UP rounding. Issued invoice amounts/currency/lines/discount/tax remain immutable. Payments/refunds are append-only; refunds reference the original payment and cannot exceed its unrefunded receipts. Voiding requires a reason and all receipts refunded. Currency totals must not be mixed. Cloud sync validates these accounting records but does not process a payment gateway charge.

Active appointment reservations are checked transactionally against a bounded clinic scheduling index, including duration, clinician, room and clinic buffer. Clinic-local `YYYY-MM-DD HH:mm` dates are converted with the configured IANA time zone; explicit ISO instants are preferred around daylight-saving transitions. Two clients cannot silently reserve an overlapping active slot through separate patient transactions.

## Reminders and images

`dispatchClinicReminders` runs every 15 minutes after deployment. Appointment reminder jobs are embedded in the clinic document and created only when clinic reminders are enabled, the appointment enables its reminder, and patient/appointment communication consent exists. Explicit global patient consent revocation overrides an older appointment opt-in. Lead hours and clinic quiet hours apply. Notifications are generic and contain no patient names, clinical details, or balances. Delivery is to explicitly registered staff devices, not SMS/email/WhatsApp. Those channels require separately selected provider credentials and consent/template integration.

The dispatcher atomically claims jobs before sending to avoid concurrent repeated sends. Statuses include `pending`, `claimed`, `sent`, `delivery_partial_or_failed`, `delivery_failed`, and `no_registered_device`; counts are exposed by `getClinic`. FCM does not guarantee exactly-once delivery. A process failure after claiming and before recording a send result leaves `claimed` as an uncertain outcome; it is intentionally not blindly resent. Operator review and the app's local appointment view remain necessary. Completed queue entries are retained for 30 days and queue capacity is 256 jobs. The scheduler handles at most 50 due clinics/30 jobs per clinic per run; higher volume needs explicit capacity review.

Images remain in Storage as immutable SHA-256 PNG objects up to 512 KiB. New references are validated against the authorized clinic/patient prefix and actual Storage metadata before a patient commit. Private `gs://` references are canonicalized by the server; no public download tokens are generated. Pixels are losslessly encoded by Android; images that cannot fit without discarding detail are rejected. Storage upload and Firestore commit cannot be one transaction. An interrupted upload may leave an unreferenced object; no automatic deletion job is enabled because it could delete an unsynchronized clinical image. An operator-approved orphan cleanup/retention policy is required before bulk cleanup.

Official references: [callable functions](https://firebase.google.com/docs/functions/callable), [scheduled functions](https://firebase.google.com/docs/functions/schedule-functions), [Admin SDK FCM delivery](https://firebase.google.com/docs/cloud-messaging/send/admin-sdk).

## Audit regression checks

The PRD audit added `test/audit-regressions.test.js`. From `functions`, `npm test` runs nine pure-helper regression tests without installing Firebase SDKs or contacting services. They passed during the audit, along with `npm run check`'s equivalent `node --check index.js`. This is limited source-level coverage, not callable transaction, Firestore Rules, Storage, FCM, or deployed integration verification. See [PRD_AUDIT.md](PRD_AUDIT.md) for remaining requirements.

## Accounts and patient logins

- The first Google account to sign in creates a clinic and becomes its owner.
- Other doctors and staff are added in Settings › Clinic › Staff with their Google email. After the owner syncs, they can sign in with Google and join automatically (`resolveWorkspace`).
- Deactivating a staff entry removes that person's access.
- An admin or dentist opens a synced patient → Overview → **Patient app access** → **Create patient login**. The Patient ID and temporary password are shown once, ready to share. The patient must choose a new password at first sign-in. **Reset password** issues a new temporary password and signs out the patient's devices.
- New callables: `resolveWorkspace`, `createPatientLogin`, `resetPatientPassword`, `completePasswordChange` and `getMyRecord`. `listPatients` also accepts `updatedAfter` for incremental sync.
