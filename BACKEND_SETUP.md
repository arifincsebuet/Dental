# Trusted Firebase backend

This directory contains deployable Cloud Functions source and restrictive Firestore/Storage rules. No Firebase project, billing configuration, cloud resources, service credentials, rules, or functions were deployed during implementation. No npm installation, build, emulator run, or device test was performed, per the request to implement without verification.

## Cloud setup

1. The Android app is registered in the Firebase project **`dental-sm`**. Its `app/google-services.json` is committed, and the Google services Gradle plugin (4.5.0) turns it into the Firebase resources at build time, including `default_web_client_id` for Google sign-in. Don't also add a `res/values/firebase.xml`, because duplicate resources break the build. `firebase/firebase.xml.example` is only kept for builds without the plugin.
   - The API key in `google-services.json` is an identifier, not a secret. Restrict it to this Android app (package plus SHA-1) in Google Cloud Console › APIs & Services › Credentials.
   - Analytics (BoM 35.1.0) is included without the advertising ID or ad personalisation. The app logs no clinical data as events.
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

## Subscription (Google Play Billing)

One subscription covers a whole clinic: the owner, every doctor and every staff member. Patients are never charged. **Prices live in Play Console, not in the app**, which reads them at runtime. Ending or changing an offer therefore needs no app update.

1. Play Console › Monetize › Products › Subscriptions → create product **`dental_care_pro`**. The ID is `SUBSCRIPTION_PRODUCT_ID` in `app/build.gradle.kts`.
2. Add a base plan **`monthly`**: auto-renewing, billing period 1 month, price **USD 10.00**. Let Play convert local prices, or set them per country.
3. Add an offer **`launch`** on that base plan:
   - Eligibility: *New customer acquisition* ("Never had this subscription").
   - Phase: *Single payment* or *Discounted recurring payment* of **USD 5.00 per month** for the number of months you choose, e.g. 12.
   - Tag: `launch`.

   The app picks the eligible offer with the cheapest first payment. It shows "$5 / month for the first N months, then $10 / month", with $10 struck through.
4. When the promotion ends, **deactivate the `launch` offer**. New subscribers then see and pay $10/month. Existing subscribers keep the terms they signed up with until their offer phase ends.
5. Server verification:
   - Enable the *Google Play Android Developer API* in the Google Cloud project used by Firebase.
   - Play Console › Users and permissions → invite the Cloud Functions service account (`PROJECT_ID@appspot.gserviceaccount.com`, or the account your functions run as). Grant *View financial data* and *Manage orders and subscriptions*.
   - Optional environment overrides: `ANDROID_PACKAGE` (default `com.spala.dental`) and `SUBSCRIPTION_PRODUCTS` (default `dental_care_pro`).
6. Test with license testers (Play Console › Settings › License testing). Their renewals happen within minutes.

How it works:
- **Purchase:** the app starts Google Play checkout with an obfuscated account ID derived from the clinic ID. It sends the purchase token to `verifySubscription`, which:
  - checks the token with the Play Developer API
  - refuses tokens bought for another clinic
  - acknowledges the purchase (Google refunds unacknowledged purchases after 3 days)
  - stores `subscription` (active, expiry, offer, auto-renew) on the clinic document
- **Renewals:** checked lazily. `getClinic` re-verifies only after the stored period has ended. That needs no Pub/Sub topic, no polling and no extra documents.
- **Without an active subscription:** staff see the paywall. Only the owner or an admin can subscribe. Patient logins are never blocked.
- **Debug builds** skip the paywall so you can test features.
- **Offline mode** (no Firebase): the purchase is acknowledged on the device and checked from the device's Play purchases.

## Appointment slots

- **Creating availability (admins):** Schedule › Calendar › **Manage slots**. Each availability entry sets a doctor, a date range, weekdays, start and end time, slot length and an optional room. It's stored in the clinic document as `config.availability` (at most 300 entries). Slots are computed from it and never stored individually, so there are no per-slot documents.
- **The calendar:** a month calendar marks days with free slots (green) and fully booked days (grey). Picking a day lists its slots by doctor. Staff see who booked each slot and can tap a free slot to book any patient, searching by name, phone or Patient ID.
- **Patient booking:** patients book from the patient app (`getSlots` makes 1 read; `bookSlot` is one transaction on the clinic and patient documents). The server re-checks the slot is still free, so two people can't take the same time. Patients may hold up to 3 upcoming bookings and can cancel up to 2 hours before (`cancelMyAppointment`). Online booking can be turned off in Manage slots (`config.patientBooking`).
- **After changing availability,** Sync so patients see it.

## Super admins

`dev.spala@gmail.com` and `spalatechnologies@gmail.com` are platform super admins:
- They use the app without a subscription.
- Clinics they own are marked `complimentary`, so their doctors and staff aren't asked to pay either.
- The server accepts only verified Google emails from the Firebase ID token.
- To change the list, set `SUPER_ADMINS` (comma-separated) in the Functions environment and `SUPER_ADMIN_EMAILS` in `app/build.gradle.kts`.

## Patient appointment reminders (no Cloud Function)

- **Token storage:** after sign-in, the patient app writes its FCM token to `workspaces/{clinic}/patients/{patient}.patientDevices.{deviceId}`, one entry per phone and at most 5. This is a direct Firestore update allowed by `firestore.rules`: only the signed-in patient, only that field, only their own document. Sign-out deletes the entry. `commitPatient` preserves the field, and staff responses never include tokens.
- **Delivery:** sending a push through FCM requires server credentials, which must never ship in the app. So reminders are scheduled on the patient's phone with WorkManager (24 h and 2 h before each upcoming appointment). They survive reboots and are re-planned whenever the patient opens the app, books or cancels. Patients can turn them off in the app.
- **Admin pushes (Cloud Functions):** use drawer › **Notify patients**, or a patient's Overview › **Send notification**. Choose the recipients: patients with appointments on a day, all patients with app access, a hand-picked list, or one patient. Pick a template or write a message, then send now or schedule it.
  - `queuePatientPush` delivers "send now" immediately.
  - Scheduled messages are kept in the clinic document (`patientPushJobs`, last 100) and delivered by **`dispatchPatientPushes`**, which runs every 5 minutes and queries only clinics with a due job (`nextPatientPushAt`).
  - `cancelPatientPush` cancels a scheduled message.
  - Invalid tokens are removed from the patient document after each send.
- **Automatic reminders to patients:** turn on the switch in the Notify patients dialog (`config.patientPushReminders`). The existing `dispatchClinicReminders` then also pushes each due appointment reminder to that patient's phones.
- Only patients who signed in to the patient app and allowed notifications receive pushes. The on-device reminders above still work as a fallback.
- Deploy the updated rules: `firebase deploy --only firestore:rules`.
