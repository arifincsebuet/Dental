# Dental Care Web

The web version of Dental Care for PCs, laptops, tablets and phones. It uses the **same Firebase
project (`dental-sm`) and the same Firestore documents and security rules** as the Android app,
so the clinic team can use both at once.

The 3D teeth are the **same Godot chart as the Android app** (`app/src/main/assets/chart.gd`),
exported for the browser (`godot/`, built from `godot-src/`).

## Folder

| Path | What it is |
|---|---|
| `index.html` | The app (single page; pages use `#/` links, so no rewrites are needed) |
| `css/` | Styles (`base.css` = approved design, `app.css` = app and 3D stage) |
| `js/config.js` | Firebase settings (optional on Firebase Hosting, see below) |
| `js/store.js`, `js/cloud.js`, `js/domain.js` | Data, Firestore access and clinic rules (ports of ClinicStore, ClinicCloud, ClinicalRules…) |
| `js/app.js`, `js/patient.js`, `js/portal.js` | Pages: clinic, patient workspace with 3D, patient app |
| `js/viewer3d.js` | Connects the page to the Godot 3D chart |
| `sdk/dental-cloud.js` | **Dental Cloud SDK** (web): one Firestore document per patient, lossless images in Storage. Same API as the Android `dentalcloud` module; see `sdk/README.md` |
| `js/i18n.js`, `i18n/` | Languages. `i18n/<code>.json` maps the English text to each language (same files as the Android app's `assets/i18n`). To add or fix a translation, edit the JSON in both places |
| `js/brand.js` | Clinic branding (logo, colour, map) applied to the whole app |
| `js/reports.js`, `vendor/` | PDF reports (jsPDF 2.5.1 + AutoTable 3.8.2, bundled so no CDN is needed) |
| `godot/` | Godot 4.5.1 web export of the 3D chart (do not edit by hand). The engine file `index.wasm` (38 MB) is delivered as `index.wasm.part1` + `.part2`; `deploy.bat web` joins them, or double-click `godot/join-wasm.bat` |
| `godot-src/` | Godot project used for the web export (not uploaded to Hosting) |

## Try it locally (demo clinic)

Opening `index.html` directly from the folder does not work (browsers block modules from files).
Run a small local server instead, then open the address it prints:

1. Double-click `Web\godot\join-wasm.bat` once (creates `index.wasm`).
2. Open **Command Prompt** in `C:\Users\Admin\Documents\Dental\Web`.
3. Run `npx serve .` (needs Node.js) or `python -m http.server 8080`.
4. Open `http://localhost:8080` (or the address shown). Without Firebase settings the app opens a
   **demo clinic** stored in your browser. Patient app demo login: `RAHIM01` / `smile123`.

Add `?demo=1` to the address at any time to force the demo.

## Put it online with Firebase Hosting

1. **Register a web app** (once): Firebase console › Project `dental-sm` › Project settings ›
   *Your apps* › **Add app › Web**. Name it "Dental Care Web". You don't need to copy the config:
   on Firebase Hosting the app reads it automatically from `/__/firebase/init.json`.
2. **Google sign-in for the web** (once): Firebase console › Authentication › Settings ›
   *Authorised domains*: check `dental-sm.web.app` and `dental-sm.firebaseapp.com` are listed
   (add your own domain if you connect one).
3. **App Check** (only if Firestore/Storage enforcement is on, which blocks web requests without a
   token): Firebase console › App Check › Apps › your web app › **reCAPTCHA Enterprise**, then
   paste the site key into `js/config.js` → `appCheckSiteKey`.
4. **Deploy**: in `C:\Users\Admin\Documents\Dental` double-click `deploy.bat` with the argument
   `web`, i.e. run `.\deploy.bat web` in PowerShell. (It runs `firebase deploy --only hosting`.)
5. Open `https://dental-sm.web.app`. The first Google account that signs in to a new clinic
   becomes its owner; existing clinic staff land in their clinic with their Team & roles access.

Patients use the same address and choose **Patient** on the sign-in page (or go to `#/portal`).

## Updating the 3D chart

`godot-src/chart.gd` is the Android `chart.gd` plus a web bridge (`connect_web_host` and the
functions under "Web host"). The Android code path is unchanged, so this file can also replace
`app/src/main/assets/chart.gd`. After changing it, re-export:

1. Open `godot-src/project.godot` in **Godot 4.5.1**.
2. Project › Export › **Web** (preset included; threads off, no special headers needed) › Export
   Project to `../godot/index.html`.

## Clinic branding and languages

- **Branding**: Clinic settings › Clinic branding (owner or admin). Saved in the clinic settings, so
  the Android app, the web app and the patient app all show it after the next sync.
- **Languages** (17): English, Bangla, Hindi, Arabic, Chinese (Simplified), Spanish, French,
  Portuguese, German, Russian, Japanese, Korean, Indonesian, Turkish, Italian, Vietnamese, Thai.
  Choose in Settings, on the sign-in page or in the patient app. Web PDFs are translated for
  English, Spanish, French, Portuguese, German, Italian and Indonesian (the built-in PDF fonts lack
  the other scripts, so those print in English); Android PDFs use every language.

## Browser support

Chrome, Edge, Firefox and Safari (recent versions). The 3D chart needs WebGL 2; without it the
patient page shows the 2D chart. The first visit downloads the 3D engine (about 9–38 MB depending
on compression); later visits use the browser cache.
