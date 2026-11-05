// Dental Cloud SDK (web) v1.0
// Reusable Firebase data layer for clinic software: one Firestore document per patient (lists kept
// inside it), revision-checked writes, incremental sync, and lossless clinical images (<= 512 KB)
// in Firebase Storage whose links are stored in the patient document.
//
// No bundled Firebase: pass the Firestore and Storage instances and modules your app already uses,
// so an existing system adopts it without a second Firebase copy. Same API as the Android SDK
// (dentalcloud module, com.spala.dental.cloud.DentalCloud).
//
//   import * as fs from "firebase/firestore"; import * as st from "firebase/storage";
//   const cloud = createDentalCloud({ db, storage, fs, st });
//   const saved = await cloud.records.commit(clinicId, uid, patient);
//   const { records, newest } = await cloud.records.listSince(clinicId, lastSync);
//   const ref = await cloud.images.upload(clinicId, patient.id, file);   // compress + upload + link
//   await cloud.records.commit(clinicId, uid, cloud.images.attach(patient, ref));

export const VERSION = "1.0.0";
export const MAX_IMAGE_BYTES = 512 * 1024;

/** The record changed on another device since it was loaded (optimistic concurrency). */
export class Conflict extends Error {}

const DEFAULTS = {
  root: "workspaces",                 // workspaces/{clinicId}
  records: "patients",                // workspaces/{clinicId}/patients/{id}
  maxDocBytes: 750000,                // below Firestore's 1 MiB document limit
  maxImageBytes: MAX_IMAGE_BYTES,
  localFields: ["dirty", "operationId", "remoteVersion", "cloudOwner", "retainedAt", "syncedAt", "patientDevices"],
  serverOwnedFields: ["portal"],      // kept from the cloud copy, never overwritten by a record write
  conflictMessage: (r) => `${r.name || "This record"} was changed on another device. Review the conflict before saving again.`,
};

/**
 * @param {{db: any, storage?: any, fs: any, st?: any, options?: Partial<typeof DEFAULTS>}} deps
 *   db / storage: Firestore and Storage instances; fs / st: the firebase/firestore and firebase/storage modules.
 */
export function createDentalCloud({ db, storage = null, fs, st = null, options = {} }) {
  const o = { ...DEFAULTS, ...options };
  const { doc, collection, getDoc, getDocs, query, where, runTransaction, serverTimestamp, Timestamp } = fs;

  const fromValue = (v) => {
    if (v instanceof Timestamp) return v.toMillis();
    if (Array.isArray(v)) return v.map(fromValue);
    if (v && typeof v === "object") { const out = {}; for (const [k, x] of Object.entries(v)) out[k] = fromValue(x); return out; }
    return v;
  };
  const clean = (snap) => { const d = fromValue(snap.data() || {}); delete d.syncedAt; delete d.patientDevices; return d; };
  const size = (obj) => new TextEncoder().encode(JSON.stringify(obj)).length;

  const paths = {
    clinic: (c) => `${o.root}/${c}`,
    publicInfo: (c) => `${o.root}/${c}/public/info`,
    record: (c, id) => `${o.root}/${c}/${o.records}/${id}`,
    image: (c, id, sha256, ext) => `${o.root}/${c}/${o.records}/${id}/${sha256}.${ext}`,
  };

  const records = {
    /**
     * Writes the whole record as ONE document (1 read + 1 write) if its cloud revision still equals
     * record.remoteVersion. Returns the stored record (revision + 1). Throws Conflict otherwise.
     */
    async commit(clinicId, uid, record) {
      return runTransaction(db, async (tx) => {
        const ref = doc(db, paths.record(clinicId, record.id));
        const snap = await tx.get(ref);
        const serverRevision = snap.exists() ? snap.get("revision") || 0 : 0;
        if (serverRevision !== (record.remoteVersion || 0)) throw new Conflict(o.conflictMessage(record));
        const d = JSON.parse(JSON.stringify(record));
        o.localFields.forEach((k) => delete d[k]);
        for (const k of o.serverOwnedFields) { delete d[k]; const v = snap.exists() ? snap.get(k) : undefined; if (v !== undefined) d[k] = fromValue(v); }
        Object.assign(d, { revision: serverRevision + 1, clinicId, updatedBy: uid });
        if (size(d) > o.maxDocBytes) throw new Error("Patient document is full. Export and archive older history first.");
        tx.set(ref, { ...d, syncedAt: serverTimestamp() });
        return d;
      });
    },
    /** Records changed after `since` (ms, server time); all records when since is falsy. Reads = changed docs only. */
    async listSince(clinicId, since) {
      let q = collection(db, `${o.root}/${clinicId}/${o.records}`);
      if (since) q = query(q, where("syncedAt", ">", Timestamp.fromMillis(since)));
      const snaps = await getDocs(q);
      let newest = 0;
      const list = snaps.docs.map((s) => { const t = s.get("syncedAt"); if (t?.toMillis && t.toMillis() > newest) newest = t.toMillis(); return clean(s); });
      return { records: list, newest };
    },
    async get(clinicId, id) { const s = await getDoc(doc(db, paths.record(clinicId, id))); return s.exists() ? clean(s) : null; },
    /** Bytes the record would take in Firestore (to warn before the 1 MiB limit). */
    size,
  };

  const urlCache = new Map();
  const images = {
    encodeLossless: (file, max = o.maxImageBytes) => encodeLossless(file, max),
    /**
     * Compresses losslessly on the device, then uploads to
     * {root}/{clinic}/{records}/{id}/{sha256}.{webp|png}. Identical images are stored once (content
     * hash name + one metadata check). Returns the link object to keep in the record's images list.
     */
    async upload(clinicId, recordId, fileOrEncoded) {
      if (!storage || !st) throw new Error("Pass storage and the firebase/storage module to createDentalCloud");
      const e = fileOrEncoded?.bytes ? fileOrEncoded : await encodeLossless(fileOrEncoded, o.maxImageBytes);
      if (e.bytes.length > o.maxImageBytes) throw new Error("Image is larger than 512 KB");
      const sha256 = await sha256Hex(e.bytes);
      const path = paths.image(clinicId, recordId, sha256, e.ext);
      const ref = st.ref(storage, path);
      try { await st.getMetadata(ref); } catch (err) {
        if (err.code !== "storage/object-not-found") throw err;
        await st.uploadBytes(ref, e.bytes, { contentType: e.mime, cacheControl: "private, max-age=31536000, immutable" });
      }
      return { path, reference: `gs://${ref.bucket}/${path}`, sha256, bytes: e.bytes.length, width: e.width, height: e.height, format: e.ext, resized: e.resized, at: Date.now() };
    },
    /** Returns a copy of the record with the image link added (no duplicate links). */
    attach(record, ref) {
      const copy = JSON.parse(JSON.stringify(record));
      copy.images = Array.isArray(copy.images) ? copy.images : [];
      if (!copy.images.some((x) => x.path === ref.path)) { const { resized, ...link } = ref; copy.images.push(link); }
      return copy;
    },
    /** Short-lived signed URL for display (cached per session, so each image costs one lookup). */
    async url(path) {
      if (!urlCache.has(path)) urlCache.set(path, st.getDownloadURL(st.ref(storage, path)).catch((e) => { urlCache.delete(path); throw e; }));
      return urlCache.get(path);
    },
  };

  return { VERSION, options: o, paths, records, images, Conflict };
}

export async function sha256Hex(bytes) {
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/**
 * Lossless image encoding in the browser: PNG and WebP are both produced, the WebP is decoded
 * again and compared pixel by pixel (browsers may encode WebP lossily), and the smallest exact
 * copy is kept. If no lossless file fits `max`, the image is scaled down in steps (still lossless
 * at the new size) until it fits.
 */
export async function encodeLossless(file, max = MAX_IMAGE_BYTES) {
  if (!file || !/^image\//.test(file.type || "image/")) throw new Error("Choose an image file");
  const img = await createImageBitmap(file);
  let w = img.width, h = img.height;
  for (let i = 0; i < 12; i++) {
    const c = document.createElement("canvas"); c.width = w; c.height = h;
    const g = c.getContext("2d", { willReadFrequently: true });
    g.drawImage(img, 0, 0, w, h);
    const png = await blob(c, "image/png");
    let best = png;
    const webp = await blob(c, "image/webp", 1);
    if (webp && webp.type === "image/webp" && webp.size < png.size && await samePixels(webp, g.getImageData(0, 0, w, h).data, w, h)) best = webp;
    if (best.size <= max) {
      const ext = best.type === "image/webp" ? "webp" : "png";
      return { bytes: new Uint8Array(await best.arrayBuffer()), ext, mime: best.type, width: w, height: h, resized: w !== img.width };
    }
    const f = Math.min(0.9, Math.max(0.5, Math.sqrt((max * 0.9) / best.size)));
    w = Math.max(1, Math.round(w * f)); h = Math.max(1, Math.round(h * f));
    if (Math.max(w, h) < 320) break;
  }
  throw new Error("This image cannot be stored losslessly within 512 KB");
}
const blob = (c, type, q) => new Promise((r) => c.toBlob(r, type, q));
async function samePixels(webpBlob, ref, w, h) {
  try {
    const b = await createImageBitmap(webpBlob);
    const c = document.createElement("canvas"); c.width = w; c.height = h;
    const g = c.getContext("2d", { willReadFrequently: true }); g.drawImage(b, 0, 0);
    const d = g.getImageData(0, 0, w, h).data;
    for (let i = 0; i < d.length; i++) if (d[i] !== ref[i]) return false;
    return true;
  } catch { return false; }
}
