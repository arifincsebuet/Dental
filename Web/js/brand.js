// Clinic branding: the admin's clinic name, logo, address, phone, map location and colour replace
// the Dental Care branding everywhere (sign-in, sidebar, patient app, PDFs, browser tab).
// The last brand is cached in this browser so the next launch shows the clinic straight away.
import { esc } from "./util.js";
import { LOGO } from "./icons.js";

export const COLORS = ["#0F766E", "#0E7490", "#1D4ED8", "#4338CA", "#7C3AED", "#BE185D", "#B91C1C", "#C2410C", "#A16207", "#15803D", "#334155"];
export const CURRENCIES = ["BDT", "INR", "PKR", "NPR", "LKR", "USD", "EUR", "GBP", "JPY", "KRW", "CNY", "SAR", "AED", "QAR", "KWD", "MYR", "SGD", "IDR", "THB", "PHP", "VND", "AUD", "CAD", "BRL", "MXN", "ARS", "COP", "ZAR", "NGN", "EGP", "TRY", "RUB", "CHF"];

const DEFAULT = { name: "", logo: "", color: "" };
let cache = DEFAULT;
try { cache = { ...DEFAULT, ...JSON.parse(localStorage.getItem("dc-brand") || "{}") }; } catch { /* none */ }
export const cachedBrand = () => cache;

export const brandOf = (c = {}) => ({ name: c.name || "", logo: c.logo || "", color: /^#[0-9a-f]{6}$/i.test(c.brandColor || "") ? c.brandColor : "", address: c.address || "", phone: c.clinicPhone || "", mapUrl: c.mapUrl || "" });

/** The clinic logo (or the Dental Care logo when none is set) at a given size. */
export function logoHtml(size = 36, b = cache) {
  if (b.logo) return `<img class="logo clinic-logo" src="${esc(b.logo)}" alt="" style="width:${size}px;height:${size}px">`;
  return LOGO.replace('class="logo"', `class="logo" style="width:${size}px;height:${size}px"`);
}
export const appName = (b = cache) => b.name || "Dental Care";

let applied = "";
/** Applies a clinic's brand to the page (colours, favicon, title) and caches it. */
export function applyBrand(c) {
  const b = brandOf(c);
  const key = b.name + "|" + b.color + "|" + b.logo.length + b.logo.slice(-40);
  if (key === applied) return;
  applied = key;
  cache = { name: b.name, logo: b.logo, color: b.color };
  try { localStorage.setItem("dc-brand", JSON.stringify(cache)); } catch { /* full */ }
  paint();
}
export function paint() {
  let st = document.getElementById("brand-css");
  if (!st) { st = document.createElement("style"); st.id = "brand-css"; document.head.appendChild(st); }
  const c = cache.color;
  const light = c ? `--brand:${c};--brand-2:color-mix(in srgb,${c} 65%,white);--brand-soft:color-mix(in srgb,${c} 20%,white);--brand-softer:color-mix(in srgb,${c} 10%,white);` : "";
  const dark = c ? `--brand:color-mix(in srgb,${c} 55%,white);--brand-ink:#08110f;--brand-2:color-mix(in srgb,${c} 40%,white);--brand-soft:color-mix(in srgb,${c} 38%,#0F1B1E);--brand-softer:color-mix(in srgb,${c} 22%,#0F1B1E);` : "";
  st.textContent = c ? `:root{--brand-deep:${c};${light}}@media (prefers-color-scheme:dark){:root:not([data-theme="light"]){${dark}}}:root[data-theme="dark"]{${dark}}` : "";
  document.title = cache.name ? cache.name : "Dental Care";
  const icon = document.querySelector('link[rel="icon"]');
  if (icon) { icon.href = cache.logo || "img/logo.svg"; icon.type = cache.logo ? "image/png" : "image/svg+xml"; }
  document.querySelector('meta[name="theme-color"]')?.setAttribute("content", cache.color || "#0F766E");
}
paint();

/** Google Maps search text for a saved map link, "lat,lng" or the address. */
export function mapQuery(b) {
  const u = (b.mapUrl || "").trim();
  if (u) {
    const at = u.match(/@(-?\d+\.\d+),(-?\d+\.\d+)/) || u.match(/[?&](?:q|query|ll)=(-?\d+\.\d+),\s*(-?\d+\.\d+)/) || u.match(/^(-?\d+\.\d+)\s*,\s*(-?\d+\.\d+)$/);
    if (at) return `${at[1]},${at[2]}`;
    const q = u.match(/[?&](?:q|query)=([^&]+)/);
    if (q) return decodeURIComponent(q[1].replace(/\+/g, " "));
    const place = u.match(/\/place\/([^/@]+)/);
    if (place) return decodeURIComponent(place[1].replace(/\+/g, " "));
    if (!/^https?:/i.test(u)) return u;
  }
  return [b.name, b.address].filter(Boolean).join(", ");
}
export const mapLink = (b) => (/^https?:\/\//i.test(b.mapUrl || "") ? b.mapUrl : `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(mapQuery(b))}`);
export const mapEmbed = (b) => `https://maps.google.com/maps?q=${encodeURIComponent(mapQuery(b))}&z=16&output=embed`;

/** Reads an image file and returns a square-fit PNG data URL (max 256 px, kept small for Firestore). */
export async function logoFromFile(file) {
  if (!/^image\//.test(file.type)) throw new Error("Choose an image file (PNG, JPG, WebP or SVG)");
  const url = URL.createObjectURL(file);
  try {
    const img = await new Promise((ok, bad) => { const i = new Image(); i.onload = () => ok(i); i.onerror = () => bad(new Error("Could not read that image")); i.src = url; });
    for (const max of [256, 192, 128, 96]) {
      const s = Math.min(1, max / Math.max(img.naturalWidth || max, img.naturalHeight || max));
      const w = Math.max(1, Math.round((img.naturalWidth || max) * s)), h = Math.max(1, Math.round((img.naturalHeight || max) * s));
      const cv = document.createElement("canvas"); cv.width = w; cv.height = h;
      cv.getContext("2d").drawImage(img, 0, 0, w, h);
      const data = cv.toDataURL("image/png");
      if (data.length < 120000) return data;
    }
    throw new Error("That logo is too detailed. Use a simpler image.");
  } finally { URL.revokeObjectURL(url); }
}
