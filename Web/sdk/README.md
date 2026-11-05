# Dental Cloud SDK (web) 1.0

Same API as the Android module (`dentalcloud/`). One file, no build step, no bundled Firebase:
pass the Firebase instances and modules your app already uses. Diagram: `Docs/cloud-architecture.svg`.

```js
import * as fs from "https://www.gstatic.com/firebasejs/11.0.2/firebase-firestore.js";
import * as st from "https://www.gstatic.com/firebasejs/11.0.2/firebase-storage.js";
import { createDentalCloud, Conflict } from "./sdk/dental-cloud.js";

const cloud = createDentalCloud({ db: fs.getFirestore(app), storage: st.getStorage(app), fs, st,
  options: { root: "workspaces", records: "patients" } });

await cloud.records.commit(clinicId, uid, patient);              // 1 read + 1 write; throws Conflict
const { records, newest } = await cloud.records.listSince(clinicId, lastSync); // changed docs only
const link = await cloud.images.upload(clinicId, patient.id, file);  // lossless, <= 512 KB, deduped
await cloud.records.commit(clinicId, uid, cloud.images.attach(patient, link));
img.src = await cloud.images.url(link.path);                     // cached per session
```

`encodeLossless(file)` makes WebP and PNG, decodes the WebP again and compares every pixel (some
browsers encode WebP lossily), and keeps the smallest exact copy; it scales down in steps only when
needed to fit 512 KB. Options are the same as on Android (`maxDocBytes`, `maxImageBytes`,
`localFields`, `serverOwnedFields`).
