package com.spala.dental

import androidx.compose.runtime.staticCompositionLocalOf

/** Lets editors retain their input when the repository rejects a synchronous save attempt. */
data class EditorSaveStatus(val sequence: Long, val rejection: String?)
val LocalEditorSaveStatus = staticCompositionLocalOf<() -> EditorSaveStatus> { { EditorSaveStatus(0, null) } }

fun requireAcceptedSave(before: EditorSaveStatus, after: EditorSaveStatus) {
    if (after.sequence != before.sequence && after.rejection != null) throw IllegalStateException(after.rejection)
}
