// Interface languages. English strings in the code are the keys; i18n/<code>.json maps them to
// the chosen language (the same files are used by the Android app). A DOM observer translates
// text and placeholder / title / aria-label attributes after each render, so pages stay in English
// in the code. Clinical records, names and notes are never translated.

export const LANGS = [
  ["en", "English", "en-GB"], ["bn", "বাংলা", "bn-BD"], ["hi", "हिन्दी", "hi-IN"], ["ar", "العربية", "ar"],
  ["zh", "中文 (简体)", "zh-CN"], ["es", "Español", "es-ES"], ["fr", "Français", "fr-FR"], ["pt", "Português", "pt-BR"],
  ["de", "Deutsch", "de-DE"], ["ru", "Русский", "ru-RU"], ["ja", "日本語", "ja-JP"], ["ko", "한국어", "ko-KR"],
  ["id", "Bahasa Indonesia", "id-ID"], ["tr", "Türkçe", "tr-TR"], ["it", "Italiano", "it-IT"], ["vi", "Tiếng Việt", "vi-VN"],
  ["th", "ไทย", "th-TH"],
];
/** Scripts the PDF fonts can draw; other languages get English PDFs. */
const LATIN = new Set(["en", "es", "fr", "pt", "de", "it", "id"]);

let lang = "en", dict = {};
try { lang = localStorage.getItem("dc-lang") || "en"; } catch { /* default */ }
if (!LANGS.some((l) => l[0] === lang)) lang = "en";

export const currentLang = () => lang;
export const locale = () => (LANGS.find((l) => l[0] === lang) || LANGS[0])[2] + "-u-ca-gregory-nu-latn";
export const isLatin = () => LATIN.has(lang);
export const t = (s) => (lang === "en" ? s : dict[s] ?? dict[String(s).trim()] ?? s);
/** Translation for PDF text (only where the PDF font has the script). */
export const pt = (s) => (LATIN.has(lang) ? t(s) : s);

export async function loadLang(code = lang) {
  lang = LANGS.some((l) => l[0] === code) ? code : "en";
  try { localStorage.setItem("dc-lang", lang); } catch { /* ignore */ }
  dict = {};
  if (lang !== "en") {
    try { const r = await fetch(`i18n/${lang}.json`, { cache: "no-cache" }); if (r.ok) dict = await r.json(); } catch { /* stay English */ }
  }
  document.documentElement.lang = (LANGS.find((l) => l[0] === lang) || LANGS[0])[2];
}

const ATTRS = ["placeholder", "title", "aria-label"];
const SKIP = new Set(["SCRIPT", "STYLE", "TEXTAREA", "CODE", "PRE"]);
function textNode(n) {
  const raw = n.nodeValue, key = raw.trim();
  if (!key || key.length > 200) return;
  const tr = dict[key];
  if (tr && tr !== key) n.nodeValue = raw.replace(key, tr);
}
function element(el) {
  for (const a of ATTRS) { const v = el.getAttribute?.(a); if (v && dict[v.trim()]) el.setAttribute(a, dict[v.trim()]); }
}
export function translateTree(root) {
  if (lang === "en" || !root) return;
  if (root.nodeType === 3) { if (!skipped(root.parentNode)) textNode(root); return; }
  if (root.nodeType !== 1 || skipped(root)) return;
  element(root);
  const w = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT, {
    acceptNode: (n) => (n.nodeType === 1 && (SKIP.has(n.tagName) || n.hasAttribute("translate")) ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT),
  });
  for (let n = w.nextNode(); n; n = w.nextNode()) n.nodeType === 3 ? textNode(n) : element(n);
}
function skipped(el) { return !!el?.closest?.('[translate="no"],script,style,textarea,code,pre'); }

let observer = null;
export function watch(root = document.body) {
  observer?.disconnect();
  if (lang === "en") return;
  translateTree(root);
  observer = new MutationObserver((list) => {
    for (const m of list) {
      if (m.type === "characterData") textNode(m.target);
      else if (m.type === "attributes") element(m.target);
      else m.addedNodes.forEach(translateTree);
    }
  });
  observer.observe(root, { subtree: true, childList: true, characterData: true, attributes: true, attributeFilter: ATTRS });
}

/** A language menu (works on any page; data-act="lang"). */
export const langSelect = (cls = "input") => `<select class="${cls}" data-act="lang" aria-label="Language" translate="no">${LANGS.map(([c, n]) => `<option value="${c}" ${c === lang ? "selected" : ""}>${n}</option>`).join("")}</select>`;
