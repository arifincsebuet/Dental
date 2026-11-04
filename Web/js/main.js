import config from "./config.js";
import * as St from "./store.js";
import { render } from "./app.js";
import { loadLang, watch } from "./i18n.js";

async function start() {
  const cfg = structuredClone(config);
  const params = new URLSearchParams(location.search);
  cfg.forceDemo = params.has("demo");
  if (!cfg.firebase.apiKey && !cfg.forceDemo && location.protocol.startsWith("http")) {
    try {
      const r = await fetch("/__/firebase/init.json", { cache: "no-store" });
      if (r.ok) { const auto = await r.json(); if (auto.apiKey) cfg.firebase = { ...cfg.firebase, ...auto }; }
    } catch { /* not on Firebase Hosting */ }
  }
  await loadLang();
  watch(document.body);
  render();
  await St.init(cfg);
  render();
}
start();
