# Firebase backend (no Cloud Functions)

Dental Care has **no server code**. The Android app reads and writes Firestore and Storage directly,
and `firebase/firestore.rules` + `firebase/storage.rules` are the security boundary. App Check with
Play Integrity makes sure only the genuine app from Google Play can call Firebase.

That keeps the bill to Firestore reads/writes/storage and Storage only: no Functions, Cloud
Scheduler, Cloud Build, Artifact Registry or FCM sending costs, and the project can stay on the
free Spark plan until the free Firestore/Storage quotas are exceeded.

See [Docs/firebase_architecture.md](Docs/firebase_architecture.md) for diagrams.

## One-time setup

1. **Project:** `dental-sm`. `app/google-services.json` is committed and the Google services Gradle
   plugin turns it into resources. Restrict the API key to this Android app (package + SHA-1) in
   Google Cloud Console › APIs & Services › Credentials.
2. **Authentication** (Firebase console › Authentication › Sign-in method): only **Google**, for
   clinic staff. Register the app's SHA-1 and SHA-256 fingerprints (debug, upload and Play
   app-signing keys) in Project settings › Your apps. Patients do **not** use Firebase
   Authentication (see Patient logins below), so Email/Password can stay disabled.
3. **Deploy rules and indexes:** double-click `deploy.bat` (or `.\deploy.bat` in PowerShell). It
   deploys `firestore:rules`, `firestore:indexes` and `storage`. Partial: `.\deploy.bat rules` or
   `.\deploy.bat indexes`. Equivalent: `firebase deploy --project dental-sm --only firestore:rules,firestore:indexes,storage`.
   - Storage rules read Firestore to check membership; accept the cross-service access prompt.
4. **Delete the old Cloud Functions** if an earlier version deployed them (they are no longer used):
   ```
   firebase functions:list --project dental-sm
   firebase functions:delete getClinic updateClinic listPatients getPatient commitPatient registerDevice resolveWorkspace createPatientLogin resetPatientPassword completePasswordChange getMyRecord verifySubscription getSlots bookSlot cancelMyAppointment queuePatientPush cancelPatientPush dispatchClinicReminders dispatchPatientPushes --project dental-sm
   ```
   Also delete the matching Cloud Scheduler jobs if any remain (Google Cloud console › Cloud Scheduler).

## App Check with Play Integrity

The app installs the provider in `ClinicApplication.onCreate`, before any Firebase call:
release builds use **Play Integrity** (`app/src/release/.../AppCheckSetup.kt`), debug builds use the
**debug provider** (`app/src/debug/.../AppCheckSetup.kt`).

1. Firebase console › **App Check** › Apps › Android app `com.spala.dental` › **Play Integrity** ›
   enter the **SHA-256** of the *app signing key* (Play Console › Test and release › App integrity ›
   App signing). Save.
2. Play Console › App integrity › **Play Integrity API** › link the same Google Cloud project
   (`dental-sm`).
3. Debug builds: run the app once, find `Enter this debug secret into the allow list…` in Logcat,
   and add it under App Check › Apps › ⋮ › **Manage debug tokens**.
4. Watch App Check › **APIs** metrics for a few days. When almost all requests are *verified*, press
   **Enforce** for **Cloud Firestore**, **Cloud Storage** and **Authentication**. Older app versions
   without App Check stop working once enforced, so update testers first.

The "No AppCheckProvider installed" log line from earlier versions disappears with this change.

## Data layout and cost

```text
workspaces/{clinicId}                       clinic: config (staff + roles + access toggles, members,
                                            services, availability…), revision, invites, subscription
workspaces/{clinicId}/public/info           patient-visible: name, time zone, currency, availability,
                                            busy times {s,e,d} (no names)
workspaces/{clinicId}/patients/{patientId}  one per patient: profile, chart, treatments, appointments,
                                            invoices, history, image refs, portal login
portals/{key}                               only for patients with an app login: their read-only copy
                                            + booking requests (key = SHA-256 of Patient ID + password)
Storage: workspaces/{clinicId}/patients/{patientId}/{sha256}.webp|png   (≤ 512 KB, lossless, deduplicated)
```

- **Two documents per clinic, one per patient, plus one per patient who uses the patient app.** Every list lives inside one of these documents:
  staff, doctors, services, availability, members, invitations and busy times in the clinic documents;
  appointments, treatments, invoices, payments, history and images inside the patient document.
  There are no per-appointment, per-device, per-job or per-slot documents.
- **Why not all patients in one document?** Firestore rules protect whole documents. A patient who can
  read a shared document can read everyone in it, so each patient needs their own document for the
  patient app. A single document is also capped at 1 MiB.
- **Reads:** the staff app keeps an encrypted local copy and syncs on demand. A sync writes only
  changed patients and reads only patients changed since the last sync (`syncedAt` query). No live
  listeners. The patient app reads 2 documents when it opens (its portal document + public/info).
- **Writes:** saving a patient is 1 write. Clinic settings are 2 writes (clinic + public/info). Busy
  times are rewritten only when appointments change.
- **Indexes:** large fields are exempt; only `inviteEmails`, `syncedAt` and portal `clinicId` + `pending` are queried.
- **Size guard:** the app refuses to save a patient document above ~750 KB.

## Accounts

- **Clinic owner:** the first Google account to sign in owns a new clinic (clinic ID = their uid).
- **Team & roles** (drawer › Team & roles, or Settings › Team & roles): the admin adds each staff
  member with their Google email, chooses a role (Admin, Dentist, Assistant, Receptionist, Cashier,
  Auditor) and switches individual access items on or off:
  view charts, record/edit treatment, register patients, book appointments, open/close slots,
  billing, patient app logins, reports, clinic settings & team. The role only fills in the starting
  toggles. Saving shares it with the clinic at once. When the person signs in with Google the app
  finds the invitation (`inviteEmails`) and adds them to `config.members` with that role and access;
  the rules allow only that exact self-entry. Removing or deactivating them takes access away.
- **Patient logins (no Firebase Authentication):** anyone with "Patient app logins" access opens a
  patient › Overview › **Patient app access** › **Create patient login** and types a simple
  **Patient ID** (4–12 letters/numbers) and **password** (6+ characters). The app writes
  `portals/{key}`, where key = SHA-256 of the Patient ID and password, holding a read-only copy of
  that patient's record, and stores `portal {loginId, key}` in the patient document.
  - The patient app signs in by computing the same key and reading that one document. Without the
    exact Patient ID and password the document cannot be found; portals cannot be listed except by
    the clinic's own staff.
  - **Change ID / password** writes a new portal document and deletes the old one, so the old
    password stops working at once. **Turn off** deletes it.
  - Patients book and cancel by adding a request to their portal document (plus the busy time in
    public/info, in one transaction). The next staff sync turns requests into real appointments,
    marks a clash as "Requested" for the front desk, and refreshes every portal copy that changed.
  - Choose passwords that are not easy to guess, and enforce App Check so only the genuine app can
    try sign-ins.

## What the rules enforce

- Clinic document: owner/admins update settings only with `revision + 1`; only the owner can change the
  owner's member entry; subscription is a separate admin-only field; `complimentary` only by a super
  admin owner; invited staff can add only their own member entry with the invited role.
- Patient documents: any active staff member reads; staff write with `revision + 1` and a server
  timestamp. The access toggles are enforced: clinical fields need "record treatment", invoices need
  "billing", the portal login needs "patient app logins", new patients need "register patients".
  No deletes.
- Clinic settings and team need "clinic settings & team"; slots only need "open & close slots" (that
  path may change only `availability` and `patientBooking`).
- portals/{key}: anyone with the key reads it; the patient may only append one request and show the
  appointment change; staff of that clinic list, refresh, create and delete them.
- public/info: readable without sign-in (no personal data); staff write it; a patient booking may add
  or remove exactly one busy time, only together with a new request in their portal document.

Limits of a server-less design (accepted to save cost):
- Rules cannot hide fields inside a document. Every active staff role can read full patient records;
  the app hides charts from staff without "view charts" access. The patient app copy leaves out staff
  notes and audit.
- Clinical and accounting validation (FDI codes, invoice arithmetic, append-only history) runs in the
  app, not on a server. Rules enforce who may write which fields, but not every value.
- Patient booking checks for a free slot in a transaction on the public busy list. Staff sync rebuilds
  that list from the real appointments, so a tampered entry is corrected at the next sync.

## Reminders (on the device)

- **Patients:** the patient app schedules its own reminders (WorkManager) from its appointments each
  time it opens: one at the clinic's reminder lead time (default 24 h) and one 2 h before. No push
  server, no tokens stored.
- **Staff:** consent-aware local reminders with quiet hours, as before.
- **Removed:** admin "Notify patients" pushes and server-sent reminders. Sending a push to another phone
  needs a server holding the FCM credentials, which cannot safely ship inside an app.

## Subscription (Google Play Billing)

Prices live in Play Console (product `dental_care_pro`, base plan `monthly` USD 10, offer `launch`
USD 5); see the paywall comments in `Subscription.kt`.

Without a server the purchase is handled on the device:
1. The admin buys in the app. Checkout carries an obfuscated account ID derived from the clinic ID.
2. The app **acknowledges** the purchase with the Play Billing library (required within 3 days).
3. The admin's device writes `subscription {active, expiresAt ≈ 34 days, productId, orderId}` to the
   clinic document, so every staff device of that clinic unlocks.
4. Each app start on the purchasing device re-reads Play's purchase and extends `expiresAt` (at most
   once a day). If the subscription is cancelled, it stops being extended and expires.

Super admins (`dev.spala@gmail.com`, `spalatechnologies@gmail.com`) never pay; clinics they own are
marked `complimentary` for all their staff. The list is in `SUPER_ADMIN_EMAILS` (app/build.gradle.kts)
and in `firebase/firestore.rules` (`superAdmin()`); change both together.

## Appointment slots

- **Opening slots:** Schedule › Calendar, pick a day, tap **Open slots on …**. Choose the doctor,
  hours (Morning / Afternoon / Evening / Full day, or exact times), the appointment length and
  whether it repeats every week on that weekday (4 weeks to 1 year). The preview shows the slots
  before saving. Saving shares them with staff and patients at once (no separate Sync needed).
- **On a day:** each doctor's hours are listed with **Close this day** (for repeating hours) and
  **Remove / Delete all repeats**. **All availability** lists everything, with pause/remove. The
  switch at the top turns online booking by patients on or off.
- **Booking by staff:** tap a free (outlined) time, find the patient, book. The booking is synced
  right away so the time shows as taken in the patient app.
- Needs "open & close appointment slots" access to open slots and "book & change appointments" to
  book (Team & roles). Slots are computed from `config.availability`, never stored one by one.
- **Patients** book from the patient app: free slots come from public/info (availability +
  anonymous busy times). Up to 3 upcoming bookings; cancel up to 2 hours before.
