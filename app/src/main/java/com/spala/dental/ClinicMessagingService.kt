package com.spala.dental

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class ClinicMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["kind"] != "appointment_reminder") return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val intent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // Deliberately use a fixed private message, never remote patient text on a lock screen.
        val notification = NotificationCompat.Builder(this, "clinic-reminders").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Clinic appointment reminder").setContentText("Open Dental Care to review the clinic schedule.")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setAutoCancel(true).setContentIntent(intent).build()
        NotificationManagerCompat.from(this).notify("cloud-reminder", 1, notification)
    }
    override fun onNewToken(token: String) {
        val store = (application as ClinicApplication).store
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            snapshotFlow { store.busy }.first { !it }
            if (store.cloudReminders) store.registerCloudReminders(true)
        }
    }
}
