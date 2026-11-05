# Dental Cloud SDK (Android) 1.0

Firebase data layer of Dental Care, packaged as a library module so other clinic systems can use
it. The web version with the same API is `Web/sdk/dental-cloud.js`. Diagram: `Docs/cloud-architecture.svg`.

## What it does
- **One Firestore document per patient** (`workspaces/{clinicId}/patients/{patientId}`). Profile,
  chart, treatments, appointments, invoices, history and image links are lists / JSON inside it.
- **Low cost**: a save is 1 read + 1 write (transaction with revision check); sync reads only
  documents changed since the last sync.
- **Images**: lossless WebP or PNG (the smaller), scaled down in steps only if needed to stay
  within 512 KB, named by SHA-256 so the same image is uploaded once. The Storage link
  (`gs://` reference + path) is saved in the patient document.

## Add to an existing Android app
1. Copy the `dentalcloud` folder next to your `app` folder.
2. `settings.gradle.kts`: `include(":dentalcloud")`
3. `gradle/libs.versions.toml` › `[plugins]`: `android-library = { id = "com.android.library", version.ref = "agp" }`
   and in the root `build.gradle.kts`: `alias(libs.plugins.android.library) apply false`
4. `app/build.gradle.kts`: `implementation(project(":dentalcloud"))`
5. Deploy the security rules in `firebase/firestore.rules` and `firebase/storage.rules` (or adapt the paths).

## API
```kotlin
val cloud = DentalCloud(FirebaseFirestore.getInstance(), FirebaseStorage.getInstance(),
    DentalCloud.Options(root = "workspaces", records = "patients"))

cloud.records.commit(clinicId, uid, patient)          // JSONObject; throws DentalCloud.Conflict
cloud.records.listSince(clinicId, lastSyncMs)         // Pair(changed records, newest syncedAt)
cloud.records.get(clinicId, patientId)

val link = cloud.images.upload(context, clinicId, patientId, uri)   // compress + upload
val updated = cloud.images.attach(patient, link)                    // adds to images[]
cloud.records.commit(clinicId, uid, updated)
cloud.images.download(link.path)                                     // bytes for display

cloud.paths.record(clinicId, id); LosslessImage.encode(context, uri); DocCodec.toMap(json)
```
Records are `org.json.JSONObject` with an `id`; `remoteVersion` holds the revision last loaded.

## Options
| Option | Default | Use |
|---|---|---|
| `root` / `records` | `workspaces` / `patients` | Collection names in your project |
| `maxDocBytes` | 750 000 | Refuse saves before Firestore's 1 MiB limit |
| `maxImageBytes` | 512 KB | Image budget (Storage rules enforce the same) |
| `localFields` | dirty, operationId… | Device-only fields never written |
| `serverOwnedFields` | portal | Kept from the cloud copy on every save |
