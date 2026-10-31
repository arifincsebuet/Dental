package com.spala.dental

import android.content.Context
import androidx.compose.runtime.snapshotFlow
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Retries only commands the user previously submitted, never fresh local drafts. */
class CloudRetryWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (runAttemptCount >= 3) return Result.failure()
        val store = (applicationContext as ClinicApplication).store
        withContext(Dispatchers.Main) {
            snapshotFlow { store.busy }.first { !it }
            store.sync(retryOnly = true)
            snapshotFlow { store.busy }.first { !it }
        }
        val pending = store.patients.any { it.has("operationId") } || store.config.has("operationId")
        return if (!pending) Result.success() else if (store.retryableFailure) Result.retry() else Result.failure()
    }
}
