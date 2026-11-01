package com.spala.dental

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await
import java.util.UUID

/*
 * Patient appointment reminders are push notifications sent by the server: admin messages
 * (queuePatientPush / dispatchPatientPushes) and, when enabled, automatic appointment reminders
 * (dispatchClinicReminders). The patient app only stores its FCM token in its own patient
 * document (Firestore rules allow just that field) and displays the pushes it receives.
 */

private const val CHANNEL = "patient-reminders"
private const val TAG = "patient-reminder"

object PatientPush {
    private fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences("device", Context.MODE_PRIVATE)
        return prefs.getString("id", null) ?: UUID.randomUUID().toString().replace("-", "").also { prefs.edit().putString("id", it).apply() }
    }

    /** Stores this device's FCM token in the patient's own Firestore document (one field, one write). */
    suspend fun register(context: Context, clinicId: String, patientId: String, token: String? = null) {
        val value = token ?: FirebaseMessaging.getInstance().token.await()
        FirebaseFirestore.getInstance().document("workspaces/$clinicId/patients/$patientId")
            .update(FieldPath.of("patientDevices", deviceId(context)), mapOf("token" to value, "platform" to "android", "updatedAt" to FieldValue.serverTimestamp()))
            .await()
    }

    /** Removes this device's token on sign-out so a shared phone stops receiving the patient's reminders. */
    suspend fun unregister(context: Context, clinicId: String, patientId: String) {
        FirebaseFirestore.getInstance().document("workspaces/$clinicId/patients/$patientId")
            .update(FieldPath.of("patientDevices", deviceId(context)), FieldValue.delete()).await()
    }
}

/** Displays patient reminder pushes sent by the server (queuePatientPush / dispatchPatientPushes). */
object PatientReminders {
    /** Removes reminders scheduled on the phone by older app versions (reminders are server pushes now). */
    fun cancelAll(context: Context) = WorkManager.getInstance(context).cancelAllWorkByTag(TAG)

    /** Shows a reminder on the patient's own phone (details are hidden on a locked screen). */
    fun notify(context: Context, id: String, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Appointment reminders", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val public = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Dental appointment reminder").setContentText("Unlock to see details").build()
        val notification = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title).setContentText(text).setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public)
            .setContentIntent(open).setAutoCancel(true).build()
        NotificationManagerCompat.from(context).notify(id.hashCode(), notification)
    }
}
