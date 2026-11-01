# Dental Care · Firebase architecture

Firebase holds all clinic data:
- **Firestore:** patients, staff/doctors, clinic settings, appointments, billing and treatment history.
- **Cloud Storage:** clinical images.
- **Firebase Authentication:** Google sign-in for the clinic team, and Patient ID + password for patients.
- **App Check (Play Integrity):** only the genuine app from Google Play can call Firebase.

There are **no Cloud Functions**. The Android app reads and writes Firestore directly. Security
rules decide who may read or change what. The design keeps documents, reads and writes small:
- two documents per clinic (settings + patient-visible info) and one per patient
- every list (staff, members, availability, appointments, treatments, invoices, history) is embedded
- no listeners or polling; incremental sync reads only patients that changed

## 1. System overview

```mermaid
flowchart LR
  subgraph Android["Android app (com.spala.dental)"]
    UI["Compose UI<br/>Home · Patients · 3D profile · Schedule · Billing"]
    Store["ClinicStore + ClinicCloud<br/>encrypted local cache (AES-GCM, Keystore)<br/>direct Firestore transactions"]
    Img["ImageCompressor<br/>lossless WebP/PNG ≤ 512 KB"]
    WM["WorkManager<br/>on-device reminders"]
    G3D["Godot 3D chart<br/>separate :chart3d process"]
    AC["App Check<br/>Play Integrity (release) · debug provider (debug)"]
    UI --> Store
    UI --> G3D
    UI --> Img
    Store --> WM
  end

  subgraph Firebase["Firebase project dental-sm"]
    Auth["Authentication<br/>Google (staff) · Email/Password (patients, synthetic email)"]
    Rules{{"Security rules<br/>firestore.rules · storage.rules"}}
    FS[("Firestore<br/>workspaces/{clinicId}<br/>workspaces/{clinicId}/public/info<br/>workspaces/{clinicId}/patients/{patientId}")]
    ST[("Cloud Storage<br/>workspaces/{clinicId}/patients/{patientId}/{sha256}.webp|png")]
  end
  Play["Google Play Billing<br/>dental_care_pro: $10/month, $5 launch offer"]

  Store -- "sign-in" --> Auth
  AC -. "token on every request" .-> Rules
  Store -- "get / query / transaction" --> Rules --> FS
  Img -- "putBytes" --> Rules --> ST
  Store -- "checkout + acknowledge" --> Play
```

## 2. Firestore data model (three document kinds)

```mermaid
erDiagram
  WORKSPACE ||--|| PUBLIC_INFO : "public/info"
  WORKSPACE ||--o{ PATIENT : "patients"
  WORKSPACE {
    string id "clinicId = owner's Firebase UID"
    map config "name, timeZone, currency, staff[], services[], availability[], members{uid: role}"
    map invites "email -> role (from the staff list)"
    array inviteEmails "lookup for joining staff"
    map subscription "active, expiresAt, productId (from the admin device)"
    bool complimentary "super-admin clinic"
    int revision "optimistic concurrency"
  }
  PUBLIC_INFO {
    string name
    string timeZone
    array availability "slot sessions"
    array busy "{s, e, d} busy times, no names"
    bool patientBooking
    int reminderHours
  }
  PATIENT {
    string id
    string name "profile: phone, dob, allergies, consent"
    map chart "sparse FDI tooth map"
    array treatments
    array appointments
    array invoices "payments/refunds embedded"
    array images "gs:// references"
    array history "append-only"
    map portal "loginId, uid, mustChange"
    int revision
    timestamp syncedAt "server time, for incremental sync"
  }
```

- **Why one document per patient, not one for all?** Rules protect whole documents. A patient who can
  read a shared list document would see every other patient in it, and one document holds at most 1 MiB.
- **Patient logins** are Firebase Auth accounts; the patient document's `portal.uid` links them, so no
  login or index documents are needed.
- **Large arrays and maps are exempt from indexing.** Only `inviteEmails`, `syncedAt` and
  `portal.uid` (collection group) are queried.

## 3. Key flows

### Staff sign-in (Google) and joining a clinic

```mermaid
sequenceDiagram
  participant A as Android (staff)
  participant Auth as Firebase Auth
  participant DB as Firestore (rules)
  A->>Auth: Google ID token → signInWithCredential
  A->>DB: get workspaces/{uid}  (1 read)
  alt owns a clinic
    A->>A: clinicId = uid, role owner
  else invited by email
    A->>DB: query inviteEmails array-contains email (1 read)
    A->>DB: transaction: add config.members[uid] = invited role (rules check it)
  else new user
    A->>A: clinicId = uid (clinic doc created on first sync)
  end
  A->>DB: get clinic + all patients (first time only)
```

### Patient login created on the admin's phone

```mermaid
sequenceDiagram
  participant Ad as Admin app
  participant Auth2 as Firebase Auth (sign-up endpoint)
  participant DB as Firestore (rules)
  participant P as Patient app
  Ad->>Auth2: accounts:signUp(ID@patient.dentalcare.local, password chosen by admin) + App Check token
  Note over Ad,Auth2: nobody is signed in on the admin's phone; the admin stays signed in
  Ad->>DB: transaction: patient.portal = {loginId, uid, mustChange: true}
  Ad-->>Ad: Patient ID + password shown once → share
  P->>DB: signIn, then collectionGroup('patients').where('portal.uid','==',uid) (1 read)
  P->>P: forced "Choose your password" (reauthenticate + updatePassword)
  P->>DB: portal.mustChange = false
  P->>DB: read own record + public/info (2 reads), schedule reminders on the phone
  Note over Ad,DB: Reset = new Patient ID + password chosen by the admin; the old ID loses access
```

### Save and sync (offline-first, incremental)

```mermaid
sequenceDiagram
  participant A as Android
  participant DB as Firestore (rules)
  A->>A: edit → validate → encrypted local cache (dirty)
  A->>DB: transaction per dirty patient: read revision → write with revision+1, syncedAt=server time
  A->>DB: query patients where syncedAt > lastPull (only changed patients)
  A->>DB: public/info.busy rewritten only if appointments changed
```

### Patient booking

```mermaid
sequenceDiagram
  participant P as Patient app
  participant DB as Firestore (rules)
  P->>P: free slots = availability − busy (from public/info)
  P->>DB: transaction: read public/info + own record, re-check slot free
  P->>DB: append appointment + 1 history line (revision+1), arrayUnion busy time
```

## 4. Cost controls

| Area | Choice | Effect |
|---|---|---|
| Server | No Cloud Functions, Scheduler or FCM sends | No compute bill; Spark plan is enough until Firestore/Storage quotas |
| Documents | 2 per clinic + 1 per patient; every list embedded | Fewest documents; opening a patient is 0 cloud reads (local cache) |
| Sync | Write dirty patients; read `syncedAt > lastPull` | Routine sync reads only changed patients |
| Listeners | None | No continuous read charges |
| Indexes | Large fields exempt; 3 queried fields | Lower index storage and write cost |
| Patient logins | Auth accounts linked by `portal.uid` | No login documents |
| Images | Lossless WebP ≤ 512 KB, SHA-256 names (deduplicated), private refs | Small storage and egress, no duplicate uploads |
| Reminders | WorkManager on each phone | No scheduled jobs, no push costs |
| Subscription | Play purchase acknowledged on the device; entitlement on the clinic doc | No Play API calls or extra documents |

## 5. Security

- **App Check** (Play Integrity) blocks requests that don't come from the genuine app once enforced.
- **Firestore rules:** clinic membership and roles come from the clinic document; writes need the next
  revision; field groups (clinical, invoices, patient login) need the right role; patients can only
  read their own record and book/cancel.
- **Storage rules** check clinic membership through `firestore.get`. Images are capped at 512 KB and
  must be PNG or WebP.
- **Patient emails** are synthetic, so no email is ever sent. Password recovery is an admin reset that
  issues a new Patient ID.
- See BACKEND_SETUP.md › "Limits of a server-less design" for what rules cannot enforce.
