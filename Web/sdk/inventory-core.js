// Dental Cloud SDK: clinic inventory (pure logic, no Firebase). Same rules as the Android
// module (com.spala.dental.cloud.InventoryCore), so both apps produce identical documents.
//
// The whole clinic inventory is ONE Firestore document: workspaces/{clinicId}/inventory/main
//   items { id: { c catalog id?, n name?, g category (custom items)?, u unit, q stock, m reorder level, p unit cost (minor),
//                 b [{e expiry "YYYY-MM-DD", q qty, l lot?}] expiring batches, s supplier id?, k SKU?, off 1? } }
//   sup   { id: { n name, ph phone? } }                    suppliers
//   tpl   { procedure: { itemId: qty } }                   materials used per procedure (clinic edits)
//   log   ["time s|itemId|change|type|ref|by"]             last 300 stock movements (one short string each)
// Firestore does not allow arrays inside arrays, so batches are small maps and log lines are strings.
//   mon   { "YYYY-MM": { itemId: used } }                  monthly use, last 24 months
//   done  [opId]                                           last 100 applied operations (safe retries)
// Names, categories, purposes, units and default material lists come from the catalog inside the
// app (inventory/catalog.json) and are never stored in the cloud.

export const LOG_MAX = 300, DONE_MAX = 100, MONTHS_MAX = 24;
export const TYPES = { in: "Received", use: "Used", adj: "Count adjusted", dmg: "Damaged", exp: "Expired / disposed", ret: "Returned to supplier" };
const r2 = (x) => Math.round((+x || 0) * 100) / 100;

export function emptyInventory() { return { v: 1, rev: 0, items: {}, sup: {}, tpl: {}, log: [], mon: {}, done: [] }; }

export function newId(prefix = "i") {
  const a = "abcdefghijkmnpqrstuvwxyz23456789";
  let s = prefix;
  for (let i = 0; i < 7; i++) s += a[Math.floor(Math.random() * a.length)];
  return s;
}

/** Quantity not tied to an expiring batch. */
const loose = (it) => r2((it.q || 0) - (it.b || []).reduce((n, x) => n + (+x.q || 0), 0));
const clip = (v, n) => String(v ?? "").replace(/\|/g, "/").slice(0, n);

/** A log line as an object: {t seconds, id, dq change, type, ref, by}. */
export function parseLog(line) {
  const [t, id, dq, type, ref = "", by = ""] = String(line).split("|");
  return { t: +t || 0, id, dq: +dq || 0, type, ref, by };
}

/** Takes up to qty from an item, earliest expiry first (FEFO). Returns the amount taken. */
function take(it, qty) {
  let need = r2(qty);
  const free0 = Math.max(0, loose(it));
  const batches = (it.b || []).slice().sort((a, b) => String(a.e).localeCompare(String(b.e)));
  for (const batch of batches) {
    if (need <= 0) break;
    const t = Math.min(batch.q, need);
    batch.q = r2(batch.q - t); need = r2(need - t);
  }
  it.b = batches.filter((x) => x.q > 0);
  if (!it.b.length) delete it.b;
  const fromFree = Math.min(free0, need);
  need = r2(need - fromFree);
  const taken = r2(qty - need);
  it.q = r2(Math.max(0, (it.q || 0) - taken));
  return taken;
}

/**
 * Applies operations to an inventory document (in a transaction, so edits from several devices
 * merge instead of conflicting). Operations:
 *   {op:"item", id, c?, n?, u?, m?, p?, s?, k?, off?}  add or edit an item (stock untouched)
 *   {op:"receive", id, qty, cost?, exp?, batch?, s?}    purchase / delivery
 *   {op:"use", id, qty, type?: use|dmg|exp|ret, ref?}  consumption or write-off (earliest expiry first)
 *   {op:"count", id, qty}                               stock count: sets the quantity
 *   {op:"supplier", id, n, ph?}                         add or edit a supplier
 *   {op:"tpl", procedure, list | null}                  materials for a procedure (null = app default)
 * Returns the new document (input is not modified).
 */
export function applyOps(doc, ops, { opId = "", by = "", now = Date.now() } = {}) {
  const d = JSON.parse(JSON.stringify(doc && doc.items ? doc : emptyInventory()));
  for (const k of ["sup", "tpl", "mon"]) d[k] ||= {};
  d.log ||= []; d.done ||= [];
  if (opId && d.done.includes(opId)) return d;
  const t = Math.floor(now / 1000), month = new Date(now).toISOString().slice(0, 7);
  const log = (id, dq, type, ref) => { let e = `${t}|${clip(id, 40)}|${r2(dq)}|${type}`; if (ref || by) e += `|${clip(ref, 40)}`; if (by) e += `|${clip(by, 40)}`; d.log.push(e); };
  for (const o of ops || []) {
    const it = d.items[o.id];
    switch (o.op) {
      case "item": {
        const cur = it || { u: o.u || "piece", q: 0, m: 0 };
        for (const k of ["c", "n", "u", "s", "k", "g"]) if (o[k] !== undefined) { if (o[k] === "" || o[k] === null) delete cur[k]; else cur[k] = String(o[k]).slice(0, 80); }
        if (o.m !== undefined) cur.m = Math.max(0, r2(o.m));
        if (o.p !== undefined) cur.p = Math.max(0, Math.round(+o.p || 0));
        if (o.off !== undefined) { if (o.off) cur.off = 1; else delete cur.off; }
        if (!cur.c && !cur.n) throw new Error("Give the item a name");
        d.items[o.id] = cur;
        break;
      }
      case "receive": {
        if (!it) throw new Error("Unknown item");
        const qty = r2(o.qty);
        if (!(qty > 0)) throw new Error("Quantity must be more than zero");
        it.q = r2((it.q || 0) + qty);
        if (o.exp) { (it.b ||= []).push({ e: String(o.exp).slice(0, 10), q: qty, ...(o.batch ? { l: clip(o.batch, 30) } : {}) }); it.b.sort((a, b) => a.e.localeCompare(b.e)); }
        if (o.cost !== undefined && o.cost !== null && o.cost !== "") it.p = Math.max(0, Math.round(+o.cost));
        if (o.s) it.s = o.s;
        delete it.off;
        log(o.id, qty, "in", o.batch || "");
        break;
      }
      case "use": {
        if (!it) break; // item removed meanwhile: nothing to deduct
        const type = TYPES[o.type] ? o.type : "use";
        const taken = take(it, r2(o.qty));
        if (taken > 0) {
          log(o.id, -taken, type, o.ref || "");
          if (type === "use") { const m = (d.mon[month] ||= {}); m[o.id] = r2((m[o.id] || 0) + taken); }
        }
        break;
      }
      case "count": {
        if (!it) throw new Error("Unknown item");
        const qty = Math.max(0, r2(o.qty)), diff = r2(qty - (it.q || 0));
        if (diff < 0) take(it, -diff); else it.q = qty;
        if (diff) log(o.id, diff, "adj");
        break;
      }
      case "supplier": {
        if (!o.n) throw new Error("Give the supplier a name");
        d.sup[o.id] = { n: String(o.n).slice(0, 80), ...(o.ph ? { ph: String(o.ph).slice(0, 30) } : {}) };
        break;
      }
      case "tpl": {
        if (o.list === null || o.list === undefined) delete d.tpl[o.procedure];
        else {
          const rows = Array.isArray(o.list) ? o.list : Object.entries(o.list);
          d.tpl[String(o.procedure).slice(0, 60)] = Object.fromEntries(rows.filter((x) => x[0] && x[1] > 0).slice(0, 30).map((x) => [String(x[0]), r2(x[1])]));
        }
        break;
      }
      default: throw new Error("Unknown inventory operation");
    }
  }
  if (d.log.length > LOG_MAX) d.log = d.log.slice(-LOG_MAX);
  const months = Object.keys(d.mon).sort();
  while (months.length > MONTHS_MAX) delete d.mon[months.shift()];
  if (opId) { d.done.push(opId); if (d.done.length > DONE_MAX) d.done = d.done.slice(-DONE_MAX); }
  d.v = 1;
  return d;
}

// ------------------------------------------------------------------ derived (computed in the app)

/** Days from today (YYYY-MM-DD) to an expiry date. */
export function daysTo(iso, today) { return Math.round((Date.parse(iso) - Date.parse(today)) / 86400000); }

/** Status of an item: out, low, expired, expiring (within `soon` days) or ok. */
export function itemStatus(it, today, soon = 90) {
  if (it.off) return "inactive";
  const exp = (it.b || []).filter((x) => x.q > 0).map((x) => x.e).sort()[0];
  if (exp && daysTo(exp, today) < 0) return "expired";
  if ((it.q || 0) <= 0) return "out";
  if ((it.q || 0) <= (it.m || 0)) return "low";
  if (exp && daysTo(exp, today) <= soon) return "expiring";
  return "ok";
}

export function summary(doc, today) {
  const s = { items: 0, low: 0, out: 0, expiring: 0, expired: 0, value: 0 };
  for (const it of Object.values(doc?.items || {})) {
    if (it.off) continue;
    s.items++;
    const st = itemStatus(it, today);
    if (st in s) s[st]++;
    s.value += Math.round((it.q || 0) * (it.p || 0));
  }
  return s;
}

/** Materials for a procedure: the clinic's list, else the catalog default limited to stocked items. */
export function materialsFor(doc, catalog, procedure) {
  const items = doc?.items || {};
  if (doc?.tpl?.[procedure]) return Object.entries(doc.tpl[procedure]).filter(([id]) => items[id] && !items[id].off);
  const byCatalog = {};
  for (const [id, it] of Object.entries(items)) if (it.c && !it.off) byCatalog[it.c] = id;
  return (catalog?.templates?.[procedure] || []).filter(([c]) => byCatalog[c]).map(([c, q]) => [byCatalog[c], q]);
}

/** Cost of materials (minor units) from the items' unit costs. list: [[id, qty]] or {id: qty}. */
export function materialCost(doc, list) {
  const rows = Array.isArray(list) ? list : Object.entries(list || {});
  return Math.round(rows.reduce((n, [id, q]) => n + (doc?.items?.[id]?.p || 0) * q, 0));
}

/** Batches with stock left, earliest expiry first. */
export function batchesOf(it) { return (it?.b || []).filter((x) => x.q > 0).slice().sort((a, b) => a.e.localeCompare(b.e)); }

/** Average monthly use of an item over the stored months (for reorder suggestions). */
export function monthlyUse(doc, id, months = 3) {
  const keys = Object.keys(doc?.mon || {}).sort().slice(-months);
  if (!keys.length) return 0;
  return r2(keys.reduce((n, k) => n + (doc.mon[k][id] || 0), 0) / keys.length);
}
