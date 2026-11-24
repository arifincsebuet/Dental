# Dental Cloud SDK (web) 1.1

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

## Inventory (1.1)
The clinic's whole stock is **one document**: `workspaces/{clinicId}/inventory/main`. Item names,
categories, purposes and default material lists stay in the app (`inventory/catalog.json`); the
cloud keeps only numbers and IDs with short keys. Changes are operations applied in a transaction
(1 read + 1 write), so edits from several devices merge instead of overwriting each other.

```js
const stock = await cloud.inventory.get(clinicId);                       // 1 read
await cloud.inventory.apply(clinicId, uid, [
  { op: "item", id: "i1", c: "restorative.composite", u: "syringe", m: 4, p: 85000 },
  { op: "receive", id: "i1", qty: 10, cost: 85000, exp: "2027-06-30", batch: "L-24" },
  { op: "use", id: "i1", qty: 0.1, ref: treatmentId },                   // earliest expiry first
], opId);                                                                // opId: retry-safe
import { InventoryCore } from "./sdk/dental-cloud.js";                   // status, summary, materialsFor…
```

| Key | Holds |
|---|---|
| `items{id}` | `c` catalog id or `n` name, `g` category (custom), `u` unit, `q` stock, `m` reorder level, `p` unit cost (minor), `b[{e,q,l}]` expiring batches, `s` supplier, `k` SKU, `off` |
| `sup{id}` | `n` name, `ph` phone |
| `tpl{procedure}` | `{itemId: qty}` the clinic's materials per procedure (defaults come from the app) |
| `log[]` | `"time|item|change|type|ref|by"` strings, last 300 |
| `mon{YYYY-MM}` | `{itemId: used}` monthly use, last 24 months (reorder suggestions) |
| `done[]` | last 100 operation ids (a retried call is applied once) |

Operations: `item`, `receive`, `use` (type `use`, `dmg`, `exp`, `ret`), `count`, `supplier`, `tpl`.
Firestore does not allow arrays inside arrays, so batches are small maps and log lines are strings.
About 300 items fit in roughly 60 KB.
