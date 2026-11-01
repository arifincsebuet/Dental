# Dental Care Android implementation

The application contains major Android clinic workflows, an interactive Godot dental chart, and trusted Firebase cloud functions. The [PRD audit](PRD_AUDIT.md) identifies remaining partial requirements; complete MVP or release compliance is not claimed. Google sign-in is implemented and **disabled by default**. The app opens directly and stores records locally before cloud configuration.

## Features and source

| Area | Implementation |
|---|---|
| Help | Settings → How to use: 18 searchable offline guides, topic filters, expandable steps and accessible back navigation |
| Patients | Registration with duplicate checks, search, profiles, DOB validation, allergies/alerts, clinical story, communication consent, archive/restore, JSON import/export |
| Clinical care | Encounters with drafts/finalization and reasoned amendments; tooth/surface assessment; simultaneous findings/restorations; treatment decisions; performed care and corrections |
| Chart | FDI primary/permanent/mixed chart; native accessible Canvas view and embedded Godot; loss/replacement/assessment independent; read-only history comparison and patient explanation |
| Schedule | Day/week/month/all views, duration/room/clinician conflicts and buffers, booking/rescheduling, confirmation/check-in/completion/cancellation/no-show, consent and reminder timing |
| Staff/services/settings | Staff directory/availability, active status, service catalogue/default prices, clinic currency/time zone/tax/buffer, cloud account membership roles |
| Billing | Historical line prices, quantities, discounts, tax, issued invoices, partial payments, receipts, payment-linked refunds, reasoned voids, integer minor units |
| Reports | Day/week/month/custom filters, cash received versus billed amounts, refunds, outstanding balances, appointments, clinicians and treatment states, CSV sharing |
| History/export | Embedded append-only source history and server audit, chart comparison/earlier read-only 3D, clinical amendments, care-summary PDF, JSON backup/import |
| Synchronization | Encrypted durable local storage, explicit dirty-record sync, revision conflict detection, retained conflict drafts/export, idempotent server receipts, bounded WorkManager retries of already submitted commands |
| Reminders | Consent-aware local WorkManager notifications with quiet hours, and optional Firebase scheduled FCM clinic reminders; no patient details on lock screens |
| Cloud security | Callable role-filtered reads/commits, trusted clinical/financial validation, immutable finalized source data and append-only audit/receipts; restrictive Firestore/Storage rules |

Main files: `MainActivity.kt`, `ClinicStore.kt`, `ClinicFeatures.kt`, `ClinicalRules.kt`, `PatientValidation.kt`, `ClinicalWorkspace.kt`, `NativeDentalChart.kt`, `ClinicApplication.kt`, and `functions/index.js`.

## Open the Android project

Use Android Studio with its installed JDK and SDK. Set your machine's SDK location through Android Studio; the inherited `local.properties` points to another Windows user's SDK. Android 10/API 29 or newer is required. Resolve the Gradle dependencies on your development machine. Android builds, emulator runs and visual/device verification have not been performed. The subsequent PRD audit ran nine backend source regression tests and JavaScript syntax/configuration checks; see PRD_AUDIT.md for evidence and limits.

## Firebase activation

See [BACKEND_SETUP.md](BACKEND_SETUP.md) for function deployment, regions, roles, indexes, configuration and provider setup. No external Firebase project, authentication provider, billing account, rules or cloud functions were deployed from this workspace.

1. Copy `firebase/firebase.xml.example` to `app/src/main/res/values/firebase.xml` and provide the Firebase Android app ID, API key, project ID, Storage bucket, sender ID, and Google web client ID. Resources are initialized directly; the google-services Gradle plugin is not required.
2. Enable Firebase **Anonymous Authentication** initially. There is no interactive login gate. Selecting Sync creates a private authenticated workspace behind the scenes. Anonymous credentials are device-bound until linked to Google; keep exports before uninstalling.
3. Deploy `functions`, rules and indexes using your chosen Firebase project and region. Android defaults to `us-central1`; optional string resource `firebase_functions_region` overrides this to match the deployed region.
4. Configure the clinic, staff and services in Settings, then synchronize. Restore uses paginated callable reads without live listeners. To join an existing shared clinic, close the current synchronized device workspace, select its workspace ID, and restore records. An owner must first add the member's exact Firebase UID.
5. Google login remains hidden behind `GOOGLE_LOGIN_ENABLED=false` in `app/build.gradle.kts`. To enable later, configure Firebase Google authentication, signing fingerprints and web client ID, then change the flag. Anonymous linking keeps the same workspace UID. Account switching requires closing the prior local workspace.
6. Enable local reminders in clinic settings and give notification permission. Cloud reminders additionally require device registration in Settings and deployment of the scheduled function. Registering cloud reminders switches this device away from local reminder delivery to reduce duplicates. Notifications are reminders to clinic staff; direct patient SMS/email requires a consent-aware provider integration and provider credentials.

## One patient, one document

Cloud path: `workspaces/{clinicId}/patients/{patientId}`. Personal `clinicId` defaults to Firebase UID. Each patient document embeds the profile, story, chart JSON, findings in the chart, treatments/events, performed procedures, encounters/amendments, history, invoices/payments/refunds, image references, revisions, server audit and bounded idempotency receipts. There are no tooth, image, history, invoice or notification documents. One additional workspace document contains clinic configuration, members, schedule conflict index, reminder jobs and registered devices.

The Android JSON remains structured native maps/arrays in Firestore. Only dirty patients are submitted. Transactions check revision/authorization on the server, and repeated operation IDs return the canonical saved result. The clinic document is rewritten only when scheduling/reminder data actually changes. Large nested fields have index exemptions. Reads are explicit and paginated, and returned fields respect role permissions. Clinical fields hidden from reception/cashier users are omitted rather than defaulted to empty data.

A single document cannot hold unlimited clinical history. Local writes stop at a conservative 650 KB JSON budget; server commits stop at 750 KB including trusted metadata, below Firestore's separate encoded document limit. Full documents and embedded audit can grow faster than current tooth data. Export and plan retention/archive before reaching the limit; the app never silently truncates history. Workspace schedule/reminder arrays are also bounded and return explicit capacity errors rather than creating many hidden documents.

## Local data and conflict recovery

`clinic-v1.enc` is an atomic AES-GCM encrypted file using an Android Keystore key. It holds local patient records, clinic configuration, pending submission identities, retained conflict drafts and encrypted export drafts. Sensitive storage is excluded from backup/device transfer. The app-level store survives Activity recreation. InputStream imports are bounded, and large export payloads stay out of saved-state bundles. Image pickers capture their launch patient ID.

A local save is labeled device saved/sync pending. Only a successful cloud receipt marks it synchronized. Pending patient submissions are immutable until acknowledged or explicitly reviewed. On a conflict, preserve/export the retained draft, load the canonical server record, compare, then reapply selected reviewed changes through normal editors. A replay never silently overwrites a newer chart. WorkManager retries only transient failures of previously submitted commands and stops after bounded attempts; it does not automatically upload fresh drafts.

## Images

The picker decodes the original image at full resolution (maximum 20 MP) and encodes PNG losslessly for the decoded bitmap. The app rejects output over **512 KiB**, rather than resizing or discarding pixels. Arbitrary images cannot always be compressed under a fixed byte cap losslessly. Original metadata/HDR/container representation is not preserved; no lossy quality slider is applied.

Objects use content-addressed paths `workspaces/{clinicId}/patients/{patientId}/{sha256}.png`. The patient document stores a private `gs://` reference, path, byte size and attachment time. No base64 or public download token is stored. Storage rules enforce authentication, clinical access, PNG type and size; the server verifies object metadata before accepting a new reference. Repeat attachments are deduplicated; a successfully uploaded object can be reused after an interrupted metadata save. Storage upload and Firestore commit cannot be atomic, so an abandoned upload can leave an orphan requiring an operational retention/cleanup policy.

## Godot dental structure

Godot 4.5.1 Android library hosts the source project directly from `app/src/main/assets`. No texture imports, downloaded meshes or generated PCK are required. `chart.gd` constructs custom continuous enamel crown meshes for incisors, canines, premolars and molars, with cusp and fissure relief, gingiva, optional roots, implant collars, loss slots, labels, workflow cues and surface overlays. Primary/permanent/mixed inventories use canonical FDI slot mapping. The camera supports orbit, pinch zoom, pan, reset/focus, and arch/root/label toggles.

The full-screen chart uses a studio sky and lighting, and its camera eases toward its target with inertia. A view switcher offers Front, Upper occlusal, Lower occlusal, Left and Right presets. A floating rail holds zoom, tooth-number and root controls; turning roots on also makes the gingiva translucent. A bottom sheet first shows the arch filter and legend. After a tooth is tapped, it shows the tooth name, status chips, an M/D/F/L/O surface pad laid out like an odontogram, and one primary "Assess" action. The bridge contract (`chart`, `selectTooth`, `selectSurface`, `closeChart`) is unchanged.

### Process isolation, loading and Back

`Dental3DActivity` runs in its own `:chart3d` process. Godot force-quits its host process when its activity is destroyed, which previously closed the whole app on Back or after a tooth was chosen. The boot splash image is disabled. The chart window uses the chart background colour, and `chart.gd` fades out a "Preparing 3D chart…" overlay after the meshes are built. Back clears a selected tooth first and then returns to the profile (`quit_on_go_back=false`).

### Tooth numbering

Records always use canonical FDI keys. Display numbering is a device setting in Settings › Tooth numbering:
- **Palmer** (default): 1–8 / A–E per quadrant, e.g. UR6.
- **FDI**: e.g. 16.
- **Universal**: 1–32 / A–T, e.g. #3.

The setting applies to the inline model, the Godot chart (passed as a whitelisted `notation` field), the 2D odontogram, the history list and the dialogs.

### Treatment history and roles

The patient profile opens on the 3D model. Teeth are tinted by their full history: blue for treated, amber for findings, a teal + for planned care and a cross for loss. Below the model is a vertical per-tooth history list built from treatments, performed procedures and chart findings (`TreatmentHistory.kt`). Tapping a row expands its timeline and highlights the tooth; tapping a tooth on the model opens its row.

Only `owner`, `admin`, `administrator` and `dentist` roles can record treatment, for one tooth or a selected set of teeth. Each tooth gets its own treatment item with a shared `groupId`. Completed care also writes a performed-procedure record and its coded chart outcome:
- filling, crown, inlay, onlay or sealant adds a restoration code
- extraction records the tooth as extracted
- a root canal adds the `endodontically_treated` finding
- an implant on a lost tooth records the implant replacement

Other roles get a read-only model and history, and the Godot chart opens read-only for them.

### One model in both views (geometry spec v2)

The profile preview (`Teeth3DView.kt`) is a line-for-line port of the Godot generator (`chart.gd`). Both use the same values for:
- arch constants, tooth dimensions, collar height and crown functions
- vertex-colour enamel: dentin-warm neck, translucent incisal edges and gumline occlusion
- incisor/canine lingual shovel shape
- per-surface finding and restoration colours, and the treated tint
- the alveolar-ridge gingiva, with papillae between the teeth, and the domed upper palate
- the camera: 40° FOV, raised by 0.30 × distance, distance 12, intro swing from −0.6 rad

- anatomical roots: one, two or three roots by tooth type, tapered, divergent and distally curved, in cementum colour. Godot's Roots toggle and the preview's X-ray button show them through translucent gum.
- enamel mottling and stained occlusal fissures. Godot also adds a procedural micro-relief normal map and gloss texture.

- gingiva colour zones: a lighter marginal gingiva and a dark sulcus line at the tooth, then coral stippled attached gingiva. A mucogingival junction leads into deeper red, glossier alveolar mucosa with fine vessels. The palate has transverse rugae. Godot also uses a cellular-noise stippling normal map and gloss texture. The preview carries per-vertex gloss in the vertex-colour alpha.

Changes to one must be made in the other.

### Profile renderer: GPU first

The profile model draws with OpenGL ES 2.0 (`TeethGLView`, a TextureView with its own EGL thread). It uses a depth buffer, 4× MSAA and per-pixel lighting ported from the CPU shader. That removes the saw-tooth edges where crowns meet the gingiva, which the painter's-algorithm path could not resolve. If EGL or GL setup fails, the view falls back to the CPU `drawVertices` path automatically. Overlays (labels, badges, selection) stay in Compose. Labels are decluttered so overlapping numbers are skipped.

### Inline 3D on profiles

`Teeth3DView.kt` is a small Compose software renderer. It uses smooth-shaded triangles, a painter's sort and `Canvas.drawVertices`. It draws the same schema-2 chart snapshot directly inside the patient profile, as the first clinical card, and inside the doctor profile, using a neutral reference dentition. Only one Godot engine may run per process, so Godot cannot be embedded in scrolling screens. The inline model supports rotate, pinch zoom, double-tap reset, optional tooth numbers and tap-to-select, and selection opens the native tooth editor. Status colours match the Godot chart: amber for findings, blue for restorations, a teal "+" for planned care and a slate cross for recorded loss.

`DentalBridge` validates schema/catalogue/inventory/codes and strips private profile/narrative/financial fields. Selection results contain tooth, optional surface, session token and chart revision; the native host rejects stale context. History/explanation charts are read-only. Native Canvas teeth supply the same selection/edit workflow with TalkBack and large touch targets when 3D is unavailable. These procedural teeth are educational anatomy, not clinically validated scans or diagnostic models.

## Scope and operational limits

The implementation has partial coverage of the clinic MVP and its requested 3D/cloud features in source; see [PRD_AUDIT.md](PRD_AUDIT.md) for the requirement-by-requirement findings. Production release still needs dependency resolution, build/device/access-rule verification, clinical vocabulary/anatomy review, Firebase activation and restore/recovery exercises. These were not represented as completed. Insurance, PACS/lab systems, periodontal/root-canal chart extensions, external accounting, a separate patient portal/web app, and payment-card processing remain outside the original Android MVP. Multi-branch analytics and unlimited-history archiving require a later storage/retention design rather than bypassing the one-document constraint.
