// Small shared helpers: escaping, ids, hashing, time zones, money (minor units, like the Android app).

export const $ = (s, root = document) => root.querySelector(s);
export const $$ = (s, root = document) => [...root.querySelectorAll(s)];
export const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
export const clone = (o) => JSON.parse(JSON.stringify(o ?? null));
export const pad = (n) => String(n).padStart(2, "0");
export const uid = () => (crypto.randomUUID ? crypto.randomUUID() : "id-" + Math.random().toString(36).slice(2) + Date.now().toString(36));
export const arr = (o, k) => (Array.isArray(o[k]) ? o[k] : (o[k] = []));
export const obj = (o, k) => (o[k] && typeof o[k] === "object" && !Array.isArray(o[k]) ? o[k] : (o[k] = {}));
export const initials = (n) => String(n || "?").replace(/^Dr\.?\s*/i, "").split(/\s+/).filter(Boolean).map((w) => w[0]).slice(0, 2).join("").toUpperCase() || "?";

export async function sha256Hex(text) {
  const data = typeof text === "string" ? new TextEncoder().encode(text) : text;
  const digest = await crypto.subtle.digest("SHA-256", data);
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** JSON with every non-ASCII character escaped (safe to hand to the Godot engine). */
export const asciiJson = (value) => JSON.stringify(value).replace(/[\u007f-￿]/g, (c) => "\\u" + c.charCodeAt(0).toString(16).padStart(4, "0"));

// ---------------------------------------------------------------- time zones (clinic time zone)
const fmtCache = new Map();
function partsIn(tz, ms) {
  let f = fmtCache.get(tz);
  if (!f) {
    f = new Intl.DateTimeFormat("en-US", { timeZone: tz, hourCycle: "h23", year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit", weekday: "short" });
    fmtCache.set(tz, f);
  }
  const m = {};
  for (const p of f.formatToParts(new Date(ms))) m[p.type] = p.value;
  return m;
}
function offsetMs(tz, ms) {
  const m = partsIn(tz, ms);
  const asUtc = Date.UTC(+m.year, +m.month - 1, +m.day, +m.hour % 24, +m.minute, +m.second);
  return asUtc - Math.floor(ms / 1000) * 1000;
}
/** "YYYY-MM-DD", "HH:mm" in the clinic zone → epoch ms. */
export function zonedMs(date, time, tz) {
  const [y, mo, d] = date.split("-").map(Number);
  const [h, mi] = (time || "00:00").split(":").map(Number);
  const guess = Date.UTC(y, mo - 1, d, h, mi);
  const o1 = offsetMs(tz, guess);
  let t = guess - o1;
  const o2 = offsetMs(tz, t);
  if (o2 !== o1) t = guess - o2;
  return t;
}
const DOW = { Mon: 1, Tue: 2, Wed: 3, Thu: 4, Fri: 5, Sat: 6, Sun: 7 };
/** epoch ms → {date, time, dow (1 = Monday … 7 = Sunday), minutes} in the clinic zone. */
export function zoned(ms, tz) {
  const m = partsIn(tz, ms);
  return { date: `${m.year}-${m.month}-${m.day}`, time: `${pad(+m.hour % 24)}:${m.minute}`, dow: DOW[m.weekday], minutes: (+m.hour % 24) * 60 + +m.minute };
}
export const todayIn = (tz) => zoned(Date.now(), tz).date;
export function addDaysIso(iso, n) {
  const [y, m, d] = iso.split("-").map(Number);
  const t = new Date(Date.UTC(y, m - 1, d + n));
  return `${t.getUTCFullYear()}-${pad(t.getUTCMonth() + 1)}-${pad(t.getUTCDate())}`;
}
export function dowOf(iso) { const [y, m, d] = iso.split("-").map(Number); const w = new Date(Date.UTC(y, m - 1, d)).getUTCDay(); return w === 0 ? 7 : w; }
export const minutesOf = (hhmm) => { const [h, m] = String(hhmm).split(":").map(Number); return h * 60 + m; };
export const hm = (m) => `${pad(Math.floor(m / 60))}:${pad(m % 60)}`;
const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
const MONTHS_LONG = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"];
const DAYS_LONG = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"];
export function fmtIso(iso, long = false) {
  if (!iso) return "";
  const [y, m, d] = iso.split("-").map(Number);
  return long ? `${DAYS_LONG[dowOf(iso) - 1]}, ${d} ${MONTHS_LONG[m - 1]} ${y}` : `${d} ${MONTHS[m - 1]} ${y}`;
}
export const fmtMonth = (y, m) => `${MONTHS_LONG[m - 1]} ${y}`;
export function fmtMs(ms, tz, withTime = false) {
  if (!ms) return "Date not recorded";
  const z = zoned(ms, tz);
  return fmtIso(z.date) + (withTime ? " · " + z.time : "");
}
export function ageFrom(dob, tz) {
  if (!dob) return null;
  const t = todayIn(tz).split("-").map(Number), b = dob.split("-").map(Number);
  let a = t[0] - b[0];
  if (t[1] < b[1] || (t[1] === b[1] && t[2] < b[2])) a--;
  return a;
}

// ---------------------------------------------------------------- money (stored in minor units)
const digitsCache = new Map();
export function currencyDigits(code = "BDT") {
  if (!digitsCache.has(code)) {
    let d = 2;
    try { d = new Intl.NumberFormat("en", { style: "currency", currency: code }).resolvedOptions().maximumFractionDigits; } catch { d = 2; }
    digitsCache.set(code, d);
  }
  return digitsCache.get(code);
}
export function money(minor, code = "BDT") {
  const d = currencyDigits(code);
  const value = (Number(minor) || 0) / 10 ** d;
  const whole = Number.isInteger(value);
  let symbol = code;
  try { symbol = new Intl.NumberFormat("en", { style: "currency", currency: code, currencyDisplay: "narrowSymbol" }).formatToParts(0).find((p) => p.type === "currency").value; } catch { /* keep code */ }
  return symbol + " " + value.toLocaleString("en-IN", { minimumFractionDigits: whole ? 0 : d, maximumFractionDigits: d });
}
export function compactMoney(minor, code = "BDT") {
  const v = (Number(minor) || 0) / 10 ** currencyDigits(code);
  if (v >= 100000) return (v / 100000).toFixed(v >= 1000000 ? 0 : 1).replace(/\.0$/, "") + "L";
  if (v >= 1000) return (v / 1000).toFixed(v >= 10000 ? 0 : 1).replace(/\.0$/, "") + "k";
  return String(Math.round(v));
}
export function parseMinor(text, code = "BDT") {
  const t = String(text ?? "").trim().replace(/,/g, "");
  if (!/^\d+(\.\d+)?$/.test(t)) throw new Error("Enter a nonnegative amount");
  const d = currencyDigits(code);
  const [w, f = ""] = t.split(".");
  if (f.length > d && /[1-9]/.test(f.slice(d))) throw new Error(`Use at most ${d} decimal places`);
  return Number(w) * 10 ** d + Number((f + "0".repeat(d)).slice(0, d) || 0);
}
export const minorToText = (minor, code = "BDT") => { const d = currencyDigits(code); const v = (Number(minor) || 0) / 10 ** d; return Number.isInteger(v) ? String(v) : v.toFixed(d); };

export function download(name, text, type = "application/json") {
  const blob = text instanceof Blob ? text : new Blob([text], { type });
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob);
  a.download = name;
  document.body.appendChild(a);
  a.click();
  setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 500);
}
export const debounce = (fn, ms = 200) => { let t; return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); }; };
