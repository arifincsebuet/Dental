// Firebase settings for Dental Care Web.
// Option A (easiest): leave apiKey empty. When the site runs on Firebase Hosting, the app reads
//   the settings of the web app registered in your Firebase project from /__/firebase/init.json.
// Option B: paste the config from Firebase console › Project settings › Your apps › Web app.
// With no settings at all (e.g. opened from a local folder) the app runs a demo clinic in the browser.
export default {
  firebase: {
    apiKey: "",
    authDomain: "dental-sm.firebaseapp.com",
    projectId: "dental-sm",
    storageBucket: "dental-sm.firebasestorage.app",
    messagingSenderId: "199381444458",
    appId: "",
  },
  // App Check: if Firestore enforcement is on, register this site with reCAPTCHA Enterprise in
  // Firebase console › App Check › Apps › (web app) and paste the site key here.
  appCheckSiteKey: "",
  appCheckDebug: false,
};
