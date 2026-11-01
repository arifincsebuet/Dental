# Dental Care · Firebase architecture

Firebase holds all clinic data:
- **Firestore:** patients, staff/doctors, clinic settings, appointments, billing and treatment history.
- **Cloud Storage:** clinical images.
- **Firebase Authentication:** Google sign-in for the clinic team, and Patient ID + password for patients.

The Android app never reads or writes Firestore directly. Every call goes through **callable Cloud Functions**. They enforce roles, validate clinical rules, and return only what the caller may see.

The design is built to **keep the number of documents and reads small**:
- one document per clinic and one per patient
- no subcollections, listeners or polling
- incremental sync that reads only patients that changed

## 1. System overview

```mermaid
flowchart LR
  subgraph Android["Android app (com.spala.dental)"]
    UI["Compose UI<br/>Home · Patients · 3D profile · Schedule · Billing"]
    Store["ClinicStore<br/>encrypted local cache (AES-GCM, Keystore)<br/>offline edits marked dirty"]
    Img["ImageCompressor<br/>lossless WebP/PNG ≤ 512 KB"]
    G3D["Godot 3D chart<br/>separate :chart3d process"]
    UI --> Store
    UI --> G3D
    UI --> Img
  end

  subgraph Firebase["Firebase project"]
    Auth["Authentication<br/>Google (staff) · Email/Password (patients, synthetic email)<br/>custom claims: role=patient, clinicId, patientId"]
    Fn["Cloud Functions (callable, us-central1)<br/>resolveWorkspace · getClinic · updateClinic<br/>listPatients · getPatient · commitPatient<br/>createPatientLogin · resetPatientPassword<br/>completePasswordChange · getMyRecord · registerDevice"]
    FS[("Firestore<br/>workspaces/{clinicId}<br/>workspaces/{clinicId}/patients/{patientId}")]
    ST[("Cloud Storage<br/>workspaces/{clinicId}/patients/{patientId}/{sha256}.webp|png")]
    Sch["Scheduled function<br/>dispatchClinicReminders (15 min)"]
    FCM["Cloud Messaging"]
  end

  Store -- "ID token" --> Auth
  Store -- "callable HTTPS" --> Fn
  Fn --> FS
  Fn -- "verify image metadata" --> ST
  Img -- "putBytes (rules: member + ≤512 KB + png/webp)" --> ST
  Sch --> FS
  Sch --> FCM --> Android
  Fn -- "createUser / setCustomUserClaims / updateUser" --> Auth
```

## 2. Firestore data model (two document types)

```mermaid
erDiagram
  WORKSPACE ||--o{ PATIENT : "subcollection patients"
  WORKSPACE {
    string id "clinicId = owner's Firebase UID"
    map config "name, timeZone, currency, staff[], services[], members{uid: role}"
    array inviteEmails "active staff emails (only indexed lookup)"
    array scheduleIndex "active appointment slots (conflict checks)"
    array reminderJobs "bounded queue, 256 max"
    map devices "FCM tokens per member (≤8)"
    int revision
    array audit "configuration audit"
    array receipts "idempotent operation receipts (128)"
  }
  PATIENT {
    string id
    string name "profile: phone, dob, allergies, consent"
    map chart "schema 2 sparse FDI tooth map (≤32 KB)"
    array treatments "one item per tooth, groupId for multi-tooth"
    array performedProcedures "completed care + outcome"
    array appointments
    array invoices "payments/refunds embedded"
    array images "gs:// references only"
    array encounters
    array history "append-only clinical history"
    map portal "patient login: loginId, uid, createdAt, passwordResetAt"
    int revision
    array audit
    array receipts
  }
```

- **Staff and doctors** are entries in `config.staff` inside the clinic document. That means no `staff/` collection, and adding a doctor costs one write.
- **Patient logins** live in Firebase Authentication, not Firestore. Their custom claims say which clinic and patient they belong to. The patient document keeps one small `portal` field.
- **Large arrays and maps are exempt from indexing** (`firestore.indexes.json`), which saves index storage and write cost. Only two lookups are indexed: `inviteEmails`, and `updatedAt` for incremental sync.
- **Hard limit:** a patient document is capped at about 750 KB (Firestore allows 1 MiB). The server refuses writes near the cap rather than truncating history.

## 3. Key flows

### Staff sign-in (Google) and joining a clinic

```mermaid
sequenceDiagram
  participant A as Android (staff)
  participant Auth as Firebase Auth
  participant F as Functions
  participant DB as Firestore
  A->>Auth: Google ID token → signInWithCredential
  A->>F: resolveWorkspace()
  F->>DB: get workspaces/{uid}  (1 read)
  alt caller owns a clinic
    F-->>A: {clinicId: uid, role: owner}
  else invited by email
    F->>DB: query inviteEmails array-contains email (1 read)
    F->>DB: add config.members[uid] once (1 write)
    F-->>A: {clinicId, role}
  else new user
    F-->>A: {clinicId: uid, role: owner} (clinic doc created on first save)
  end
  A->>F: getClinic + listPatients (1 + N reads, first time only)
```

### Patient login created by the admin, then the patient's first sign-in

```mermaid
sequenceDiagram
  participant Ad as Admin app
  participant F as Functions
  participant Auth as Firebase Auth
  participant DB as Firestore
  participant P as Patient app
  Ad->>F: createPatientLogin(clinicId, patientId)
  F->>DB: read patient (1 read)
  F->>Auth: createUser(ID@patient.dentalcare.local, temp password)
  F->>Auth: claims {role: patient, clinicId, patientId, mustChange: true}
  F->>DB: update patient.portal (1 write)
  F-->>Ad: Patient ID + temporary password (shown once → share)
  P->>Auth: signInWithEmailAndPassword
  P->>P: forced "Choose your password" (reauthenticate + updatePassword)
  P->>F: completePasswordChange → claims mustChange=false
  P->>F: getMyRecord (2 reads) → read-only 3D chart, history, appointments, balance
  Note over Ad,F: Forgotten password → resetPatientPassword: new temporary password,<br/>mustChange=true, refresh tokens revoked (1 read + 1 write)
```

### Save and sync (offline-first, incremental)

```mermaid
sequenceDiagram
  participant A as Android
  participant F as Functions
  participant DB as Firestore
  A->>A: edit → validate → encrypted local cache (dirty)
  A->>F: commitPatient(expectedRevision, operationId) per dirty patient
  F->>DB: transaction: read patient + clinic (2 reads) → write patient (1 write)<br/>(+ clinic write only if schedule/reminders changed)
  A->>F: listPatients(updatedAfter = lastPullAt)
  F->>DB: query updatedAt > lastPullAt (reads only changed patients)
  F-->>A: changed patients → merge (local unsynced edits are never overwritten)
```

### Clinical image upload

```mermaid
sequenceDiagram
  participant A as Android
  participant ST as Storage
  participant F as Functions
  A->>A: decode → lossless WebP vs PNG (keep smaller)<br/>if > 512 KB: scale down in steps, re-encode losslessly
  A->>ST: put {sha256}.webp (rules: clinical member, ≤512 KB, png/webp)
  A->>A: add gs:// reference to patient (dirty)
  A->>F: commitPatient → server checks Storage metadata (size/type) once
```

## 4. Cost controls

| Area | Choice | Effect |
|---|---|---|
| Documents | 1 clinic doc + 1 doc per patient. Staff, appointments, invoices and treatments are embedded. | Few documents, and opening a patient is 1 read |
| Sync | Push only dirty patients, then pull `updatedAt > lastPullAt` | Routine sync reads only changed patients, not the whole clinic |
| Listeners | None (callables on demand) | No continuous read charges |
| Indexes | Large fields exempt; only `inviteEmails`, `updatedAt`, reminder compound index | Lower index storage and write cost |
| Patient logins | Firebase Auth users + custom claims | No Firestore docs per login |
| Images | Lossless WebP ≤ 512 KB, SHA-256 names (deduplicated), private `gs://` refs | Small storage and egress, no duplicate uploads |
| Functions | `maxInstances: 4`, 60 s timeout, no min instances | No idle compute cost |
| Reminders | One due-clinic query every 15 min; jobs embedded in the clinic doc | No per-patient polling |
| Offline cache | Encrypted local copy | Screens read locally; the cloud is touched only on sync |

## 5. Security

- **Firestore rules** deny all direct client access. Only Cloud Functions (Admin SDK) read and write Firestore.
- **Storage rules** check clinic membership through `firestore.get`. Images are capped at 512 KB, and the type must be PNG or WebP.
- **Roles:** owner, admin, dentist, assistant, receptionist, cashier, auditor and patient.
  - Dentists and admins record treatment.
  - Patients see only their own record, read-only, without internal history or audit.
- **Patient emails** are synthetic, so no email is ever sent. Password recovery is an admin reset.
