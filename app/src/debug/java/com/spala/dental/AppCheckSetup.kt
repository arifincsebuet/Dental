package com.spala.dental

import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * Debug builds: App Check debug provider. On first run Logcat prints
 * "Enter this debug secret into the allow list in the Firebase Console…: <token>".
 * Add that token under Firebase console → App Check → Apps → (app) ⋮ → Manage debug tokens.
 */
object AppCheckSetup {
    fun install(app: FirebaseApp) {
        FirebaseAppCheck.getInstance(app).apply {
            installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
            setTokenAutoRefreshEnabled(true)
        }
    }
}
