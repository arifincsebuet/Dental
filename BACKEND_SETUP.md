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
2. **Authentication providers** (Firebase console › Authentication › Sign-in method):
   - **Google**: clinic staff. Register the app's SHA-1 and SHA-256 fingerprints (debug, upload and
     Play app-signing keys) in Project settings › Your apps.
   - **Email/Password**: patient logins. Patients use synthetic `<PatientID>@patient.dentalcare.local`
     addresses that never receive mail. The admin's phone creates these accounts.
3. **Deploy rules and indexes:** double-click `deploy.bat` (or `.\deploy.bat` in PowerShell). It
   deploys `firestore:rules`, `firestore:indexes` and `storage`. Partial: `.\deploy.bat rules` or
   `.\deploy.bat indexes`. Equivalent: `firebase deploy --project dental-sm --only firestore:rules,firestore:indexes,storage`.
   - The `portal.uid` collection-group index must finish building (Firestore › Indexes ›
     Single field) before patients can sign in.
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
workspaces/{clinicId}                       clinic: config (staff, members, services, availability,
                                            reminder settings…), revision, invites, subscription
workspaces/{clinicId}/public/info           patient-visible: name, time zone, currency, availability,
                                            busy times {s,e,d} (no names)
workspaces/{clinicId}/patients/{patientId}  one per patient: profile, chart, treatments, appointments,
                                            invoices, history, image refs, portal login
Storage: workspaces/{clinicId}/patients/{patientId}/{sha256}.webp|png   (≤ 512 KB, lossless, deduplicated)
```

- **Two documents per clinic plus one per patient.** Every list lives inside one of these documents:
  staff, doctors, services, availability, members, invitations and busy times in the clinic documents;
  appointments, treatments, invoices, payments, history and images inside the patient document.
  There are no per-appointment, per-login, per-device, per-job or per-slot documents.
- **Why not all patients in one document?** Firestore rules protect whole documents. A patient who can
  read a shared document can read everyone in it, so each patient needs their own document for the
  patient app. A single document is also capped at 1 MiB.
- **Reads:** the staff app keeps an encrypted local copy and syncs on demand. A sync writes only
  changed patients and reads only patients changed since the last sync (`syncedAt` query). No live
  listeners. The patient app reads 2 documents when it opens.
- **Writes:** saving a patient is 1 write. Clinic settings are 2 writes (clinic + public/info). Busy
  times are rewritten only when appointments change.
- **Indexes:** large fields are exempt; only `inviteEmails`, `syncedAt` and `portal.uid` are queried.
- **Size guard:** the app refuses to save a patient document above ~750 KB.

## Accounts

- **Clinic owner:** the first Google account to sign in owns a new clinic (clinic ID = their uid).
- **Staff:** add them in Settings › Clinic › Staff with their Google email, then Sync. When they sign
  in with Google the app finds the invitation (`inviteEmails`) and adds them to `config.members` with
  the role from the staff list. The rules allow only that exact self-entry. Removing or deactivating
  the staff entry deactivates them at the next settings sync.
- **Patient logins:** an admin or dentist opens a synced patient › Overview › **Patient app access** ›
  **Create patient login**. The admin's phone creates the Email/Password account through a second
  Firebase instance (so the admin stays signed in) and stores `portal {loginId, uid, mustChange}` in the
  patient document. The patient app finds its record with
  `collectionGroup('patients').where('portal.uid', '==', uid)`.
- **Reset password:** changing another user's password needs a server, so **Reset** issues a *new
  Patient ID and temporary password*. The old Patient ID can no longer open the record because the
  record now names the new account.
- **First sign-in:** the patient must choose a new password; the app then clears `portal.mustChange`.

## What the rules enforce

- Clinic document: owner/admins update settings only with `revision + 1`; only the owner can change the
  owner's member entry; subscription is a separate admin-only field; `complimentary` only by a super
  admin owner; invited staff can add only their own member entry with the invited role.
- Patient documents: any active staff member reads; non-auditor staff write with `revision + 1` and a
  server timestamp. Clinical fields need owner/admin/dentist (or an assistant with `clinicalWrite`),
  invoices need owner/admin/cashier, the portal login needs owner/admin/dentist. No deletes.
- Patients: read only their own record; may only clear `portal.mustChange`, or book/cancel (change
  `appointments`, append one history line, `revision + 1`).
- public/info: any signed-in account reads it; staff write it; a patient may add or remove exactly one
  busy time.

Limits of a server-less design (accepted to save cost):
- Rules cannot hide fields inside a document. Every active staff role can read full patient records;
  the app still hides clinical fields from front-desk roles in its screens. A patient can read their
  whole own record (their own data).
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

- Admins define availability in Schedule › Calendar › **Manage slots** (`config.availability`, copied to
  public/info on sync). Slots are computed, never stored.
- Staff see bookings from their local patients. Patients see free slots computed from public/info
  (availability + anonymous busy times).
- A patient booking is one transaction: it re-checks the slot is free, appends the appointment to the
  patient's document and the busy time to public/info. Up to 3 upcoming bookings; cancel up to 2 h
  before.
