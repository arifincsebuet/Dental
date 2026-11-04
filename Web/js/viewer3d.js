// The Godot 3D chart (godot/index.html, exported from godot-src/) in one persistent iframe.
// Pages place an empty ".stage-slot"; the layer follows that slot, so the engine starts once per
// session and only receives new chart data when the patient changes.
//   Host → Godot: window.DentalGodot methods (setChart, selectTooth, setLens, setBadges, setView…)
//   Godot → host: window.DentalBridge (chart, attach, ready, toothFocused, hoverTooth, selectTooth…)
import { asciiJson } from "./util.js";

const hasWebGL2 = (() => { try { return !!document.createElement("canvas").getContext("webgl2"); } catch { return false; } })();

export const Viewer = (window.DentalViewer = {
  supported: hasWebGL2,
  layer: null, frame: null, ui: null, tip: null, api: null, ready: false,
  slot: null, handlers: {}, chartJson: "", fullscreen: false, loop: 0, lastRect: "",
  state: { view: "Front", arch: "Both", labels: true, roots: false, rotate: true, badges: true, lens: "status", sounds: true },

  ensure() {
    if (this.layer) return;
    const layer = document.createElement("div");
    layer.className = "v3d";
    layer.innerHTML = `<iframe title="3D dental chart" allow="autoplay; fullscreen" src="godot/index.html"></iframe><div class="v3d-ui"></div><div class="v3d-loading">Preparing 3D chart…</div>`;
    document.body.appendChild(layer);
    this.layer = layer;
    this.frame = layer.querySelector("iframe");
    this.ui = layer.querySelector(".v3d-ui");
    this.tip = document.createElement("div");
    this.tip.className = "tip3d";
    document.body.appendChild(this.tip);
    layer.addEventListener("click", (e) => {
      const el = e.target.closest("[data-v3d]");
      if (el) this.handlers.action?.(el.dataset.v3d, el.dataset.arg, e);
    });
    this.frame.addEventListener("load", () => {
      try {
        const w = this.frame.contentWindow;
        w.addEventListener("mousemove", (e) => { const r = this.frame.getBoundingClientRect(); this.mouse = [r.left + e.clientX, r.top + e.clientY]; this.moveTip(); });
        w.addEventListener("mouseleave", () => this.hideTip());
        w.addEventListener("keydown", (e) => { if (e.key === "Escape") this.handlers.escape?.(); });
      } catch { /* cross-origin: not expected */ }
    });
    const self = this;
    window.DentalBridge = {
      chart: () => self.chartJson || asciiJson({ schemaVersion: 2, dentition: "permanent", teeth: {}, hostUi: true }),
      attach(api) { self.api = api; },
      ready() {
        self.ready = true;
        layer.querySelector(".v3d-loading")?.remove();
        if (!self.slot) self.call("setActive", false);
        self.pushState();
        self.handlers.ready?.();
      },
      toothFocused(key, surface) { self.handlers.focus?.(String(key || ""), String(surface || "")); },
      hoverTooth(key) { self.hoverKey = String(key || ""); if (self.hoverKey) self.showTip(); else self.hideTip(); },
      selectTooth(key) { self.handlers.record?.(String(key), ""); },
      selectSurface(key, surface) { self.handlers.record?.(String(key), String(surface)); },
      closeChart() { self.setFullscreen(false); },
    };
    addEventListener("keydown", (e) => { if (e.key === "Escape" && this.fullscreen) this.setFullscreen(false); });
  },

  /** Show the chart over [slot]. chart = schema-2 chart (display copy). */
  mount(slot, chart, handlers) {
    if (!this.supported) return false;
    this.ensure();
    this.slot = slot;
    this.handlers = handlers || {};
    this.setChart(chart);
    this.layer.classList.add("on");
    this.call("setActive", true);
    if (!this.loop) this.track();
    return true;
  },
  unmount() {
    this.slot = null;
    this.hideTip();
    if (this.layer) { this.layer.classList.remove("on"); this.call("setActive", false); if (this.fullscreen) this.setFullscreen(false); }
  },
  setChart(chart) {
    const json = asciiJson({ ...chart, hostUi: true, notation: this.notation || chart.notation || "palmer", sounds: this.state.sounds, autoRotate: this.state.rotate });
    if (json === this.chartJson) return;
    this.chartJson = json;
    if (this.ready && this.api) this.api.setChart(json);
  },
  call(method, arg = "") { if (this.ready && this.api && typeof this.api[method] === "function") this.api[method](String(arg)); },
  pushState() {
    const s = this.state;
    this.call("setView", s.view); this.call("setArch", s.arch); this.call("setLabels", s.labels); this.call("setRoots", s.roots);
    this.call("setAutoRotate", s.rotate); this.call("setSounds", s.sounds);
    this.handlers.refreshOverlays?.();
  },
  setLens(map) { this.call("setLens", asciiJson(map || {})); },
  setBadges(map) { this.call("setBadges", asciiJson(this.state.badges ? map || {} : {})); },
  renderUi(html) { if (this.ui) this.ui.innerHTML = html; },
  setFullscreen(on) {
    this.fullscreen = on;
    this.layer?.classList.toggle("fs", on);
    this.handlers.refreshUi?.();
  },
  track() {
    const step = () => {
      this.loop = requestAnimationFrame(step);
      if (!this.layer || !this.slot) return;
      if (!this.slot.isConnected) { this.unmount(); return; }
      const wide = (this.fullscreen ? innerWidth : this.slot.clientWidth) >= 960;
      if (wide !== this.layer.classList.contains("wide")) this.layer.classList.toggle("wide", wide);
      if (this.fullscreen) return;
      const r = this.slot.getBoundingClientRect();
      const main = document.getElementById("main")?.getBoundingClientRect() || { top: 0, left: 0, right: innerWidth, bottom: innerHeight };
      const key = [r.left, r.top, r.width, r.height, main.top, main.bottom].map(Math.round).join(",");
      if (key === this.lastRect) return;
      this.lastRect = key;
      Object.assign(this.layer.style, { left: r.left + "px", top: r.top + "px", width: r.width + "px", height: r.height + "px" });
      const ct = Math.max(0, main.top - r.top), cb = Math.max(0, r.bottom - main.bottom);
      this.layer.style.clipPath = ct || cb ? `inset(${ct}px 0 ${cb}px 0 round 20px)` : "";
      this.layer.style.visibility = r.bottom < main.top || r.top > main.bottom || r.width < 10 ? "hidden" : "visible";
    };
    step();
  },
  showTip() {
    const html = this.handlers.tooltip?.(this.hoverKey);
    if (!html) return this.hideTip();
    this.tip.innerHTML = html;
    this.tip.classList.add("on");
    this.moveTip();
  },
  moveTip() {
    if (!this.tip?.classList.contains("on") || !this.mouse) return;
    const [x, y] = this.mouse, w = this.tip.offsetWidth, h = this.tip.offsetHeight;
    this.tip.style.left = Math.min(innerWidth - w - 8, x + 18) + "px";
    this.tip.style.top = Math.min(innerHeight - h - 8, Math.max(8, y - h / 2)) + "px";
  },
  hideTip() { this.tip?.classList.remove("on"); },
});
