// Clinic inventory (web). Cloud cost rules:
//  - The clinic's whole stock is ONE Firestore document (workspaces/{clinic}/inventory/main).
//  - It is read only when the Inventory page, a home alert or the record-treatment dialog needs it,
//    and then at most once every 15 minutes per device (cached in this browser). Refresh forces a read.
//  - Every change is one transaction (1 read + 1 write) that merges with other devices' changes.
//  - Changes made offline wait in a queue in this browser and are sent on the next load or sync.
//  - Item names, categories, purposes and default material lists come from inventory/catalog.json
//    (shipped with the app, never stored in the cloud).
import { currencyDigits } from "./util.js";
import { $, $$, esc, money, parseMinor, minorToText, todayIn, fmtIso, fmtMs, download, uid } from "./util.js";
import * as D from "./domain.js";
import * as Core from "../sdk/inventory-core.js";
import { S, emit, say, can, friendly, currentRole } from "./store.js";
import { I } from "./icons.js";
import { modal, field, input, select, check, confirmBox } from "./dialogs.js";

const TTL = 15 * 60000;
export const INV = { doc: null, at: 0, pending: [], clinic: "", catalog: null, loading: false, filter: "all", cat: "all", q: "", tab: "stock" };
const z = () => D.tz(S.config), c = () => D.cur(S.config);
const today = () => todayIn(z());
const whole = (minor) => { const d = currencyDigits(c()); return Math.round(minor / 10 ** d) * 10 ** d; };

// ------------------------------------------------------------------ access
export const canStock = () => can("inventory");
export const canUseStock = () => can("inventory") || can("recordTreatment");
export const canSeeInventory = () => canUseStock() || can("reports") || currentRole() === "auditor";

// ------------------------------------------------------------------ catalog (local only)
let catalogP = null;
export function loadCatalog() {
  if (!catalogP) catalogP = fetch("inventory/catalog.json").then((r) => r.json()).then((j) => {
    j.byId = Object.fromEntries(j.items.map((x) => [x.id, x]));
    j.catName = Object.fromEntries(j.categories.map((x) => [x.id, x.n]));
    INV.catalog = j; return j;
  }).catch(() => { catalogP = null; return { categories: [], items: [], templates: {}, byId: {}, catName: {} }; });
  return catalogP;
}
const cat = () => INV.catalog || { categories: [], items: [], templates: {}, byId: {}, catName: {} };
export const itemName = (id, it = INV.doc?.items?.[id]) => it?.n || cat().byId[it?.c]?.n || (INV.doc ? "Removed item" : "Stock item");
export const itemCat = (it) => it?.g || cat().byId[it?.c]?.cat || "other";
const itemUse = (it) => cat().byId[it?.c]?.use || "";
const catLabel = (id) => cat().catName[id] || (id === "other" ? "Other" : id);
const r2 = (n) => Math.round((+n || 0) * 100) / 100;
const qty = (n) => String(r2(n));

// ------------------------------------------------------------------ local cache + queue
const key = () => `dc-inv:${S.clinicId}`;
function readCache() {
  if (INV.clinic === S.clinicId) return;
  Object.assign(INV, { doc: null, at: 0, pending: [], clinic: S.clinicId });
  try { const j = JSON.parse(localStorage.getItem(key()) || "null"); if (j) Object.assign(INV, { doc: j.doc, at: j.at || 0, pending: j.pending || [] }); } catch { /* ignore */ }
}
function writeCache() { try { localStorage.setItem(key(), JSON.stringify({ doc: INV.doc, at: INV.at, pending: INV.pending })); } catch { /* ignore */ } }
/** The doc as shown: last cloud copy plus this device's changes still waiting to be sent. */
function withPending(doc) {
  let d = doc || Core.emptyInventory();
  for (const p of INV.pending) { try { d = Core.applyOps(d, p.ops, { opId: p.opId, by: S.user?.uid || "", now: p.at }); } catch { /* shown after sync */ } }
  return d;
}
let base = null;

/** Loads the inventory when needed (cached 15 minutes). Cost: 0 or 1 read. */
export async function ensureInventory(force = false) {
  if (!S.clinicId || !S.backend?.getInventory) return INV.doc;
  readCache();
  await loadCatalog();
  if (!force && INV.doc && Date.now() - INV.at < TTL) { if (INV.pending.length) flush(); return INV.doc; }
  if (INV.loading) return INV.doc;
  INV.loading = true;
  try {
    base = await S.backend.getInventory(S.clinicId);
    INV.at = Date.now();
    INV.doc = withPending(base);
    writeCache();
    if (INV.pending.length) await flush();
  } catch (e) { if (force) say(friendly(e), "error"); } finally { INV.loading = false; emit(); }
  return INV.doc;
}
/** Loads in the background only if the cached copy is old (used by the home page). */
export function touchInventory() {
  if (!canSeeInventory() || !S.clinicId) return;
  readCache();
  if (!INV.catalog) loadCatalog().then(emit);
  if (!INV.loading && (!INV.doc || Date.now() - INV.at >= TTL)) ensureInventory();
}

let flushing = false;
async function flush() {
  if (flushing || !INV.pending.length) return;
  flushing = true;
  try {
    while (INV.pending.length) {
      const p = INV.pending[0];
      try {
        base = await S.backend.applyInventory(S.clinicId, S.user.uid, p.ops, p.opId);
        INV.pending.shift(); INV.at = Date.now();
      } catch (e) {
        const offline = e?.code === "unavailable" || /offline|network|failed to fetch/i.test(e?.message || "");
        if (offline) break;
        INV.pending.shift(); // refused (rules or invalid): drop and show the cloud copy again
        say("Stock change not saved: " + friendly(e), "error");
      }
    }
    INV.doc = withPending(base || INV.doc);
    writeCache();
  } finally { flushing = false; emit(); }
}

/** Applies stock operations now (shown at once) and sends them to the cloud. */
export async function invApply(ops, message) {
  readCache();
  const onlyUse = ops.every((o) => o.op === "use" && (!o.type || o.type === "use"));
  if (!(canStock() || (onlyUse && canUseStock()))) { say("You don't have access to change the inventory.", "error"); return "denied"; }
  const p = { opId: "op" + uid().replace(/-/g, "").slice(0, 16), ops, at: Date.now() };
  // Validate locally first (unknown item, zero quantity...).
  INV.doc = Core.applyOps(INV.doc || Core.emptyInventory(), ops, { opId: p.opId, by: S.user?.uid || "", now: p.at });
  INV.pending.push(p); writeCache(); emit();
  await flush();
  if (INV.pending.some((x) => x.opId === p.opId)) say((message || "Saved") + " · will sync when online");
  else if (message) say(message);
  return null;
}
/** Called by the Sync button: sends waiting changes, refreshes if the copy is old. */
export async function syncInventory() {
  if (!S.clinicId || !canSeeInventory()) return;
  readCache();
  if (INV.pending.length) await flush();
  if (INV.doc && Date.now() - INV.at >= TTL) await ensureInventory();
}

// ------------------------------------------------------------------ derived
const rows = () => Object.entries(INV.doc?.items || {}).map(([id, it]) => ({ id, it, name: itemName(id, it), cat: itemCat(it), st: Core.itemStatus(it, today()) }));
const STATUS = { ok: ["In stock", "green"], low: ["Low", "amber"], out: ["Out of stock", "red"], expiring: ["Expiring soon", "amber"], expired: ["Expired", "red"], inactive: ["Not used", ""] };
const badge = (st) => `<span class="badge ${STATUS[st][1]}">${STATUS[st][0]}</span>`;
const nextExpiry = (it) => Core.batchesOf(it)[0]?.e || "";
/** Suggested order quantity: enough for 2 months of use (or twice the reorder level). */
export function suggestOrder(id, it) {
  const m = Core.monthlyUse(INV.doc, id, 3);
  const target = Math.max(it.m * 2, m * 2, it.m + 1);
  return Math.max(0, Math.ceil(target - (it.q || 0)));
}
/** Items that need action (for the home page). */
export function alerts() {
  if (!INV.doc) return [];
  return rows().filter((r) => ["low", "out", "expiring", "expired"].includes(r.st)).sort((a, b) => ["expired", "out", "low", "expiring"].indexOf(a.st) - ["expired", "out", "low", "expiring"].indexOf(b.st));
}

// ------------------------------------------------------------------ page
export function pageInventory() {
  readCache();
  if (!INV.catalog || (!INV.doc && !INV.loading)) { ensureInventory(); }
  if (!INV.doc) return `<div class="page"><div class="page-head"><div><h1 class="h1">Inventory</h1></div></div><div class="card"><div class="card-b"><div class="empty">${INV.loading ? "Loading stock…" : "Stock list not loaded yet."}</div></div></div></div>`;
  const t = today(), sum = Core.summary(INV.doc, t);
  const all = rows();
  const q = INV.q.trim().toLowerCase();
  const shown = all.filter((r) => (INV.filter === "all" ? r.st !== "inactive" : INV.filter === "attention" ? ["low", "out"].includes(r.st) : INV.filter === "expiry" ? ["expiring", "expired"].includes(r.st) : r.st === INV.filter)
    && (INV.cat === "all" || r.cat === INV.cat) && (!q || `${r.name} ${r.it.k || ""} ${catLabel(r.cat)} ${itemUse(r.it)}`.toLowerCase().includes(q)))
    .sort((a, b) => catLabel(a.cat).localeCompare(catLabel(b.cat)) || a.name.localeCompare(b.name));
  const cats = [...new Set(all.map((r) => r.cat))].sort((a, b) => catLabel(a).localeCompare(catLabel(b)));
  const k = (lbl, val, ic, col, soft, sub, f) => `<button class="card kpi" style="text-align:left;cursor:pointer" data-act="stkf" data-f="${f}"><div class="lbl"><span class="ico" style="background:${soft};color:${col}">${I(ic)}</span>${lbl}</div><div class="val num">${val}</div><div class="delta" style="color:${col}">${sub}</div></button>`;
  const stock = canStock();
  const tabs = [["stock", "Stock"], ["reorder", "Reorder"], ["activity", "Activity"], ["materials", "Materials per procedure"], ["suppliers", "Suppliers"]];
  const waiting = INV.pending.length ? `<span class="badge amber" title="Saved on this device, waiting to reach the cloud">${INV.pending.length} waiting to sync</span>` : "";
  return `<div class="page">
  <div class="page-head"><div><h1 class="h1">Inventory</h1><p class="muted small" style="margin:2px 0 0">Stock, expiry and materials used in treatments · updated ${INV.at ? fmtMs(INV.at, z(), true) : "-"} ${waiting}</p></div>
    <div class="r"><button class="btn" data-act="stkrefresh" title="Load the latest stock from the cloud">${I("sync", INV.loading ? "spin" : "")}Refresh</button><button class="btn" data-act="stkcsv">${I("download")}CSV</button><button class="btn" data-act="stkpdf">${I("print")}PDF</button>
    ${stock ? `<button class="btn" data-act="stkadd">${I("plus")}Add item</button><button class="btn primary" data-act="stkrecv">${I("box")}Receive stock</button>` : ""}</div></div>
  <div class="kpis">
    ${k("Items in use", sum.items, "box", "var(--brand)", "var(--brand-softer)", `${cats.length} categories`, "all")}
    ${k("Low or out", sum.low + sum.out, "alert", "var(--amber)", "var(--amber-soft)", `${sum.out} out of stock`, "attention")}
    ${k("Expiring in 90 days", sum.expiring, "clock", "var(--violet)", "var(--violet-soft)", "Use these first", "expiry")}
    ${k("Expired", sum.expired, "x", "var(--red)", "var(--red-soft)", "Dispose and record", "expired")}
    ${can("reports") || stock ? k("Stock value", money(whole(sum.value), c()), "wallet", "var(--green)", "var(--green-soft)", "At last purchase cost", "all") : ""}
  </div>
  <div class="card"><div class="card-h"><div class="chips">${tabs.map(([v, l]) => `<button class="chip ${INV.tab === v ? "on" : ""}" data-act="stktab" data-t="${v}">${l}</button>`).join("")}</div></div>
  <div class="card-b">${INV.tab === "reorder" ? reorderTab(all) : INV.tab === "activity" ? activityTab() : INV.tab === "materials" ? materialsTab() : INV.tab === "suppliers" ? suppliersTab() : stockTab(shown, cats, stock)}</div></div>
  </div>`;
}
function stockTab(shown, cats, stock) {
  const f = [["all", "All"], ["attention", "Low or out"], ["expiry", "Expiry"], ["expired", "Expired"], ["inactive", "Not used"]];
  return `<div class="row" style="flex-wrap:wrap;gap:8px;margin-bottom:10px">
    <div class="chips">${f.map(([v, l]) => `<button class="chip ${INV.filter === v ? "on" : ""}" data-act="stkf" data-f="${v}">${l}</button>`).join("")}</div>
    <select class="input" style="max-width:240px" data-act="stkcat" id="invcat"><option value="all">All categories</option>${cats.map((x) => `<option value="${esc(x)}" ${INV.cat === x ? "selected" : ""}>${esc(catLabel(x))}</option>`).join("")}</select>
    <input class="input" id="invq" style="max-width:260px" placeholder="Search items" value="${esc(INV.q)}"></div>
  <div class="table-wrap"><table class="t"><thead><tr><th>Item</th><th style="text-align:right">Stock</th><th style="text-align:right" class="hide-sm">Reorder at</th><th class="hide-sm">Next expiry</th><th style="text-align:right" class="hide-sm">Unit cost</th><th>Status</th><th></th></tr></thead><tbody>
  ${shown.map((r) => { const e = nextExpiry(r.it); return `<tr><td><b>${esc(r.name)}</b><div class="small muted">${esc(catLabel(r.cat))}${r.it.k ? " · " + esc(r.it.k) : ""}${itemUse(r.it) ? " · " + esc(itemUse(r.it)) : ""}</div></td>
    <td style="text-align:right" class="num"><b>${qty(r.it.q || 0)}</b> <span class="small muted">${esc(r.it.u || "")}</span></td><td style="text-align:right" class="num hide-sm">${qty(r.it.m || 0)}</td>
    <td class="hide-sm num">${e ? `${fmtIso(e)}<div class="small muted">${Core.daysTo(e, today()) < 0 ? "expired" : Core.daysTo(e, today()) + " days"}</div>` : "-"}</td>
    <td style="text-align:right" class="num hide-sm">${r.it.p ? money(r.it.p, c()) : "-"}</td><td>${badge(r.st)}</td>
    <td style="white-space:nowrap;text-align:right">${stock ? `<button class="btn sm" data-act="stkrecv" data-id="${r.id}" title="Receive">${I("plus")}</button>` : ""}${canUseStock() ? `<button class="btn sm" data-act="stkuse" data-id="${r.id}" title="Use or write off">${I("minus")}</button>` : ""}${stock ? `<button class="btn sm" data-act="stkcount" data-id="${r.id}" title="Stock count">${I("hash")}</button><button class="btn sm" data-act="stkedit" data-id="${r.id}" title="Edit">${I("edit")}</button>` : ""}</td></tr>`; }).join("")}
  </tbody></table></div>${shown.length ? "" : `<div class="empty">${Object.keys(INV.doc.items || {}).length ? "No items match." : "No items yet. Add the items your clinic keeps in stock."}</div>`}`;
}
function reorderTab(all) {
  const list = all.filter((r) => ["low", "out"].includes(r.st)).map((r) => ({ ...r, use: Core.monthlyUse(INV.doc, r.id, 3), order: suggestOrder(r.id, r.it) }));
  const sup = INV.doc.sup || {};
  return `<p class="small muted" style="margin-top:0">Suggested quantity covers about two months, from the average use of the last three months and the reorder level.</p>
  <div class="table-wrap"><table class="t"><thead><tr><th>Item</th><th style="text-align:right">Stock</th><th style="text-align:right">Used / month</th><th style="text-align:right">Suggested order</th><th class="hide-sm">Supplier</th><th style="text-align:right" class="hide-sm">Estimated cost</th></tr></thead><tbody>
  ${list.map((r) => `<tr><td><b>${esc(r.name)}</b></td><td style="text-align:right" class="num">${qty(r.it.q || 0)} ${esc(r.it.u || "")}</td><td style="text-align:right" class="num">${qty(r.use)}</td><td style="text-align:right" class="num"><b>${r.order}</b> ${esc(r.it.u || "")}</td><td class="hide-sm">${esc(sup[r.it.s]?.n || "-")}${sup[r.it.s]?.ph ? `<div class="small muted">${esc(sup[r.it.s].ph)}</div>` : ""}</td><td style="text-align:right" class="num hide-sm">${r.it.p ? money(r.it.p * r.order, c()) : "-"}</td></tr>`).join("")}
  </tbody></table></div>${list.length ? `<p class="small" style="margin-bottom:0"><b>Total estimated:</b> ${money(list.reduce((a, r) => a + (r.it.p || 0) * r.order, 0), c())}</p>` : `<div class="empty">Nothing to reorder. 🎉</div>`}`;
}
function activityTab() {
  const members = S.config.members || {};
  const who = (u) => { const m = members[u]; const s = (S.config.staff || []).find((x) => m?.email && String(x.email).toLowerCase() === String(m.email).toLowerCase()); return s?.name || m?.name || (u === S.user?.uid ? S.user.name : "") || ""; };
  const lines = (INV.doc.log || []).slice(-80).reverse().map(Core.parseLog);
  return `<p class="small muted" style="margin-top:0">The latest ${Core.LOG_MAX} stock movements are kept.</p><div class="table-wrap"><table class="t"><thead><tr><th>When</th><th>Item</th><th>Change</th><th class="hide-sm">Type</th><th class="hide-sm">Reference</th><th class="hide-sm">By</th></tr></thead><tbody>
  ${lines.map((l) => `<tr><td class="num">${fmtMs(l.t * 1000, z(), true)}</td><td>${esc(itemName(l.id))}</td><td class="num" style="color:${l.dq < 0 ? "var(--red)" : "var(--green)"}"><b>${l.dq > 0 ? "+" : ""}${qty(l.dq)}</b> ${esc(INV.doc.items[l.id]?.u || "")}</td><td class="hide-sm">${esc(Core.TYPES[l.type] || l.type)}</td><td class="hide-sm small">${esc(refText(l.ref))}</td><td class="hide-sm small">${esc(who(l.by))}</td></tr>`).join("")}
  </tbody></table></div>${lines.length ? "" : `<div class="empty">No stock movements yet.</div>`}`;
}
function refText(ref) {
  if (!ref) return "";
  for (const p of S.patients) { const t = (p.treatments || []).find((x) => x.id === ref); if (t) return `${p.name} · ${t.procedure}`; }
  return ref;
}
function materialsTab() {
  const procs = [...new Set([...D.PROCEDURES.map((p) => p[0]), ...Object.keys(cat().templates || {}), ...Object.keys(INV.doc.tpl || {}), ...(S.config.services || []).map((s) => s.name)])];
  return `<p class="small muted" style="margin-top:0">When a treatment is recorded as Completed, these materials are proposed and, after you confirm, taken from stock (earliest expiry first). Defaults come from the app; your changes are saved for the whole clinic.</p>
  <div class="table-wrap"><table class="t"><thead><tr><th>Procedure</th><th>Materials (per visit)</th><th style="text-align:right" class="hide-sm">Cost</th><th></th></tr></thead><tbody>
  ${procs.map((p) => { const list = Core.materialsFor(INV.doc, cat(), p); return `<tr><td><b>${esc(p)}</b>${INV.doc.tpl?.[p] ? `<div class="small muted">Clinic list</div>` : ""}</td><td class="small">${list.map(([id, q]) => `${esc(itemName(id))} × ${qty(q)}`).join(", ") || `<span class="muted">None in stock</span>`}</td><td style="text-align:right" class="num hide-sm">${money(Core.materialCost(INV.doc, list), c())}</td><td>${canStock() ? `<button class="btn sm" data-act="stktpl" data-p="${esc(p)}">${I("edit")}</button>` : ""}</td></tr>`; }).join("")}
  </tbody></table></div>`;
}
function suppliersTab() {
  const sup = Object.entries(INV.doc.sup || {});
  const count = (id) => Object.values(INV.doc.items || {}).filter((it) => it.s === id).length;
  return `${canStock() ? `<div class="row" style="margin-bottom:10px"><button class="btn" data-act="stksup">${I("plus")}Add supplier</button></div>` : ""}
  <div class="table-wrap"><table class="t"><thead><tr><th>Supplier</th><th>Phone</th><th style="text-align:right">Items</th><th></th></tr></thead><tbody>
  ${sup.map(([id, s]) => `<tr><td><b>${esc(s.n)}</b></td><td class="num">${s.ph ? `<a href="tel:${esc(s.ph)}">${esc(s.ph)}</a>` : "-"}</td><td style="text-align:right" class="num">${count(id)}</td><td>${canStock() ? `<button class="btn sm" data-act="stksup" data-id="${id}">${I("edit")}</button>` : ""}</td></tr>`).join("")}
  </tbody></table></div>${sup.length ? "" : `<div class="empty">No suppliers yet.</div>`}`;
}
export function afterInventory(render) {
  const q = $("#invq");
  if (q) q.oninput = () => { INV.q = q.value; render(); const n = $("#invq"); n.focus(); n.setSelectionRange(n.value.length, n.value.length); };
  const s = $("#invcat");
  if (s) s.onchange = () => { INV.cat = s.value; render(); };
}

// ------------------------------------------------------------------ home card
export function homeAlerts() {
  touchInventory();
  return alerts().slice(0, 5).map((r) => {
    const e = nextExpiry(r.it);
    const text = r.st === "expired" ? `Expired on ${fmtIso(e)}: dispose and record` : r.st === "expiring" ? `Expires ${fmtIso(e)} (${Core.daysTo(e, today())} days): use first` : r.st === "out" ? "Out of stock" : `${qty(r.it.q)} ${r.it.u} left, reorder at ${qty(r.it.m)}`;
    const col = ["expired", "out"].includes(r.st) ? "red" : "amber";
    return `<div class="list-item"><span style="width:34px;height:34px;border-radius:10px;display:grid;place-items:center;background:var(--${col}-soft);color:var(--${col})">${I("box")}</span><div class="grow"><b>${esc(r.name)}</b><small>${esc(text)}</small></div><a class="btn sm" href="#/inventory">Stock</a></div>`;
  });
}

// ------------------------------------------------------------------ dialogs
const unitOpts = ["piece", "pair", "box", "pack", "bottle", "tube", "syringe", "cartridge", "capsule", "jar", "kit", "set", "sheet", "roll", "ml", "g"];
const supplierOpts = () => [["", "None"], ...Object.entries(INV.doc?.sup || {}).map(([id, s]) => [id, s.n])];
const itemOpts = () => rows().filter((r) => r.st !== "inactive").sort((a, b) => a.name.localeCompare(b.name)).map((r) => [r.id, `${r.name} (${qty(r.it.q || 0)} ${r.it.u})`]);
function catalogSelect(selected = "") {
  const used = new Set(Object.values(INV.doc?.items || {}).map((it) => it.c).filter(Boolean));
  return `<select class="input" name="c"><option value="">Custom item (type the name below)</option>${cat().categories.map((g) => `<optgroup label="${esc(g.n)}">${cat().items.filter((x) => x.cat === g.id).map((x) => `<option value="${x.id}" ${x.id === selected ? "selected" : ""} ${used.has(x.id) && x.id !== selected ? "disabled" : ""}>${esc(x.n)}${used.has(x.id) && x.id !== selected ? " (added)" : ""}</option>`).join("")}</optgroup>`).join("")}</select>`;
}

export function itemDialog(id = "") {
  const it = id ? INV.doc.items[id] : null;
  modal({
    title: it ? `Edit item · ${esc(itemName(id))}` : "Add item", wide: true,
    body: `<div class="grid-form">
      ${field("From the dental list", catalogSelect(it?.c || ""), "182 common dental items. Only the link is saved, the name and purpose stay in the app.", true)}
      ${field("Name", input("n", it?.n || "", 'placeholder="Only for custom items, or to rename"'))}
      ${field("Category (custom items)", select("g", cat().categories.map((g) => [g.id, g.n]).concat([["other", "Other"]]), it?.g || "consumables"))}
      ${field("Unit", `<input class="input" name="u" list="dl-units" value="${esc(it?.u || "piece")}"><datalist id="dl-units">${unitOpts.map((u) => `<option>${u}</option>`).join("")}</datalist>`)}
      ${field("Reorder level", input("m", it ? qty(it.m || 0) : "", 'inputmode="decimal" placeholder="Alert at or below"'))}
      ${field(`Unit cost (${c()})`, input("p", it?.p ? minorToText(it.p, c()) : "", 'inputmode="decimal"'))}
      ${field("SKU / code (optional)", input("k", it?.k || ""))}
      ${field("Supplier", select("s", supplierOpts(), it?.s || ""))}
      ${it ? field("", check("off", "Not used any more (hidden from lists)", !!it.off)) : field("Opening stock (optional)", input("q", "", 'inputmode="decimal"'))}
      ${it ? "" : field("Expiry of this stock (optional)", input("exp", "", 'type="date"'))}</div>`,
    onOpen: (f) => {
      f.c.addEventListener("change", () => { const x = cat().byId[f.c.value]; if (x) { f.u.value = x.u; f.g.value = x.cat; } });
    },
    onSave: async (v) => {
      if (!v.c && !v.n.trim()) return "Choose an item from the list or type a name";
      const newId = id || Core.newId("i");
      const x = cat().byId[v.c];
      const op = { op: "item", id: newId, c: v.c || "", n: v.n.trim() && v.n.trim() !== x?.n ? v.n.trim() : "", g: v.c ? "" : v.g, u: (v.u || "piece").trim(), m: +v.m || 0, k: v.k.trim(), s: v.s };
      if (v.p.trim()) op.p = parseMinor(v.p, c());
      if (it) op.off = !!v.off;
      const ops = [op];
      if (!it && +v.q > 0) ops.push({ op: "receive", id: newId, qty: +v.q, exp: v.exp || "" });
      return invApply(ops, it ? "Item saved" : "Item added");
    },
  });
}

export function receiveDialog(id = "") {
  const opts = itemOpts();
  if (!opts.length) return itemDialog();
  const it = id ? INV.doc.items[id] : null;
  modal({
    title: it ? `Receive stock · ${esc(itemName(id))}` : "Receive stock",
    body: `<div class="grid-form">
      ${it ? `<input type="hidden" name="id" value="${id}">` : field("Item", select("id", opts, id), "", true)}
      ${field("Quantity received", input("qty", "", 'inputmode="decimal" required'))}
      ${field(`Unit cost (${c()})`, input("cost", it?.p ? minorToText(it.p, c()) : "", 'inputmode="decimal"'))}
      ${field("Expiry date", input("exp", "", 'type="date"'), "For medicines and materials that expire")}
      ${field("Batch / lot", input("batch", ""))}
      ${field("Supplier", select("s", supplierOpts(), it?.s || ""), "", true)}</div>`,
    saveLabel: "Add to stock",
    onSave: async (v) => {
      if (!(+v.qty > 0)) return "Enter the quantity received";
      const op = { op: "receive", id: v.id, qty: +v.qty, exp: v.exp || "", batch: v.batch.trim(), s: v.s };
      if (v.cost.trim()) op.cost = parseMinor(v.cost, c());
      return invApply([op], `Received ${v.qty} ${INV.doc.items[v.id]?.u || ""} of ${itemName(v.id)}`);
    },
  });
}

export function useDialog(id) {
  const it = INV.doc.items[id];
  const types = canStock() ? [["use", "Used in the clinic"], ["dmg", "Damaged"], ["exp", "Expired / disposed"], ["ret", "Returned to supplier"]] : [["use", "Used in the clinic"]];
  const b = Core.batchesOf(it);
  modal({
    title: `Take from stock · ${esc(itemName(id))}`,
    body: `<div class="grid-form">
      ${field("Reason", select("type", types, b[0] && Core.daysTo(b[0].e, today()) < 0 ? "exp" : "use"))}
      ${field(`Quantity (${esc(it.u)})`, input("qty", "", 'inputmode="decimal" required'), `In stock: ${qty(it.q || 0)}`)}
      ${field("Note (optional)", input("ref", ""), "", true)}</div>
      ${b.length ? `<p class="small muted">Taken from the earliest expiry first: ${b.map((x) => `${fmtIso(x.e)} × ${qty(x.q)}${x.l ? " (" + esc(x.l) + ")" : ""}`).join(", ")}</p>` : ""}`,
    saveLabel: "Take from stock",
    onSave: async (v) => {
      if (!(+v.qty > 0)) return "Enter the quantity";
      if (+v.qty > (it.q || 0)) return `Only ${qty(it.q || 0)} ${it.u} in stock. Use Stock count if the number is wrong.`;
      return invApply([{ op: "use", id, qty: +v.qty, type: v.type, ref: v.ref.trim() }], `${Core.TYPES[v.type]}: ${v.qty} ${it.u} of ${itemName(id)}`);
    },
  });
}

export function countDialog(id) {
  const it = INV.doc.items[id];
  modal({
    title: `Stock count · ${esc(itemName(id))}`,
    body: `<div class="grid-form">${field(`Counted quantity (${esc(it.u)})`, input("qty", qty(it.q || 0), 'inputmode="decimal" required'), `The app shows ${qty(it.q || 0)}. The difference is logged as a count adjustment.`, true)}</div>`,
    onSave: async (v) => {
      if (v.qty.trim() === "" || +v.qty < 0) return "Enter the counted quantity";
      return invApply([{ op: "count", id, qty: +v.qty }], "Stock count saved");
    },
  });
}

export function supplierDialog(id = "") {
  const s = id ? INV.doc.sup[id] : null;
  modal({
    title: s ? "Edit supplier" : "Add supplier",
    body: `<div class="grid-form">${field("Name", input("n", s?.n || "", "required"))}${field("Phone", input("ph", s?.ph || "", 'inputmode="tel"'))}</div>`,
    onSave: async (v) => (!v.n.trim() ? "Enter the supplier's name" : invApply([{ op: "supplier", id: id || Core.newId("s"), n: v.n.trim(), ph: v.ph.trim() }], "Supplier saved")),
  });
}

/** Rows of [itemId, qty] with inputs; used by the template editor and the record-treatment dialog. */
export function materialRows(list, prefix = "mat_") {
  const its = INV.doc?.items || {};
  return list.map(([id, q]) => `<div class="row mat-row" style="gap:8px;align-items:center;margin-bottom:6px" data-mid="${id}"><span class="grow small"><b>${esc(itemName(id))}</b> <span class="muted">· ${qty(its[id]?.q || 0)} ${esc(its[id]?.u || "")} in stock</span></span><input class="input" style="width:90px" name="${prefix}${id}" value="${qty(q)}" inputmode="decimal"><span class="small muted" style="width:56px">${esc(its[id]?.u || "")}</span></div>`).join("");
}
export function wireMaterialAdd(form, prefix = "mat_") {
  const add = form.querySelector("[data-matadd]");
  if (!add) return;
  add.onchange = () => {
    const id = add.value; if (!id) return;
    const box = form.querySelector("[data-matbox]");
    if (!box.querySelector(`[data-mid="${id}"]`)) box.insertAdjacentHTML("beforeend", materialRows([[id, 1]], prefix));
    add.value = "";
  };
}
export const materialAddSelect = () => `<select class="input" data-matadd style="max-width:320px"><option value="">+ Add another item</option>${itemOpts().map(([v, l]) => `<option value="${v}">${esc(l)}</option>`).join("")}</select>`;
export function readMaterials(v, prefix = "mat_") {
  return Object.entries(v).filter(([k, x]) => k.startsWith(prefix) && +x > 0).map(([k, x]) => [k.slice(prefix.length), r2(x)]);
}

export function templateDialog(procedure) {
  const list = Core.materialsFor(INV.doc, cat(), procedure);
  modal({
    title: `Materials · ${esc(procedure)}`, wide: true,
    body: `<p class="small muted" style="margin-top:0">Quantity per visit. Set 0 to remove an item.</p><div data-matbox>${materialRows(list, "tpl_")}</div>${materialAddSelect()}
      ${INV.doc.tpl?.[procedure] ? `<p class="small" style="margin-bottom:0"><a href="#" data-tplreset>Use the app's default list again</a></p>` : ""}`,
    onOpen: (f) => {
      wireMaterialAdd(f, "tpl_");
      const r = f.querySelector("[data-tplreset]");
      if (r) r.onclick = (e) => { e.preventDefault(); invApply([{ op: "tpl", procedure, list: null }], "Default materials restored").then(() => document.querySelector("#scrim [data-close]")?.click()); };
    },
    onSave: async (v) => invApply([{ op: "tpl", procedure, list: readMaterials(v, "tpl_") }], `Materials for ${procedure} saved`),
  });
}

// ------------------------------------------------------------------ record treatment link
/** Default materials for a completed treatment on n teeth: consumables once per visit, the rest per tooth. */
export function proposeMaterials(procedure, teeth) {
  if (!INV.doc) return [];
  return Core.materialsFor(INV.doc, cat(), procedure).map(([id, q]) => [id, itemCat(INV.doc.items[id]) === "consumables" ? q : r2(q * teeth)]);
}
/** Takes the confirmed materials from stock for a saved treatment (one operation). */
export function deductMaterials(list, treatmentId, procedure) {
  if (!list.length) return;
  invApply(list.map(([id, q]) => ({ op: "use", id, qty: q, ref: treatmentId })), `Materials for ${procedure} taken from stock`);
}

// ------------------------------------------------------------------ exports
export function inventoryCsv(csv) {
  const sup = INV.doc?.sup || {};
  const data = [["Item", "Category", "SKU", "Unit", "Stock", "Reorder level", "Unit cost", "Value", "Next expiry", "Status", "Supplier"],
    ...rows().sort((a, b) => a.name.localeCompare(b.name)).map((r) => [r.name, catLabel(r.cat), r.it.k || "", r.it.u, r.it.q || 0, r.it.m || 0, minorToText(r.it.p || 0, c()), minorToText(Math.round((r.it.q || 0) * (r.it.p || 0)), c()), nextExpiry(r.it), STATUS[r.st][0], sup[r.it.s]?.n || ""])];
  download(`inventory-${today()}.csv`, csv(data), "text/csv");
}
export function pdfRows() {
  const sup = INV.doc?.sup || {};
  return {
    summary: Core.summary(INV.doc, today()),
    items: rows().filter((r) => r.st !== "inactive").sort((a, b) => catLabel(a.cat).localeCompare(catLabel(b.cat)) || a.name.localeCompare(b.name))
      .map((r) => ({ cat: catLabel(r.cat), name: r.name, stock: `${qty(r.it.q || 0)} ${r.it.u}`, reorder: qty(r.it.m || 0), expiry: nextExpiry(r.it), cost: r.it.p || 0, value: Math.round((r.it.q || 0) * (r.it.p || 0)), status: STATUS[r.st][0], st: r.st })),
    reorder: rows().filter((r) => ["low", "out"].includes(r.st)).map((r) => ({ name: r.name, stock: `${qty(r.it.q || 0)} ${r.it.u}`, order: `${suggestOrder(r.id, r.it)} ${r.it.u}`, supplier: sup[r.it.s]?.n || "", cost: (r.it.p || 0) * suggestOrder(r.id, r.it) })),
    usage: (() => { const m = Object.keys(INV.doc?.mon || {}).sort().slice(-1)[0]; return m ? { month: m, rows: Object.entries(INV.doc.mon[m]).map(([id, q]) => ({ name: itemName(id), used: `${qty(q)} ${INV.doc.items[id]?.u || ""}`, cost: Math.round(q * (INV.doc.items[id]?.p || 0)) })).sort((a, b) => b.cost - a.cost) } : null; })(),
  };
}

// ------------------------------------------------------------------ page actions
export function inventoryAction(act, ds, render, csv, pdfFn) {
  switch (act) {
    case "stktab": INV.tab = ds.t; break;
    case "stkf": INV.filter = ds.f; INV.tab = "stock"; break;
    case "stkrefresh": ensureInventory(true); break;
    case "stkadd": itemDialog(); return true;
    case "stkedit": itemDialog(ds.id); return true;
    case "stkrecv": receiveDialog(ds.id || ""); return true;
    case "stkuse": useDialog(ds.id); return true;
    case "stkcount": countDialog(ds.id); return true;
    case "stksup": supplierDialog(ds.id || ""); return true;
    case "stktpl": templateDialog(ds.p); return true;
    case "stkcsv": inventoryCsv(csv); return true;
    case "stkpdf": pdfFn(); return true;
    default: return false;
  }
  render();
  return true;
}
