package com.spala.dental

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream

private class PdfJob { var writer: ((OutputStream) -> Unit)? = null }

/**
 * Save-as-PDF for any report: `val savePdf = rememberPdfSaver()` then
 * `savePdf("report.pdf") { out -> ReportPdfs.financialReport(..., out) }`. Android asks where to
 * save the file (Downloads, Drive…); the PDF is written off the main thread.
 */
@Composable
fun rememberPdfSaver(): (String, (OutputStream) -> Unit) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val job = remember { PdfJob() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val writer = job.writer
        job.writer = null
        if (uri != null && writer != null) scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val stream = context.contentResolver.openOutputStream(uri) ?: throw IllegalStateException("Cannot open the PDF file")
                    stream.use { writer(it) }
                }
            }
            val message = if (result.isSuccess) "PDF saved" else "PDF export failed: ${result.exceptionOrNull()?.localizedMessage ?: "unknown error"}"
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
    return remember(launcher) {
        { fileName: String, writer: (OutputStream) -> Unit ->
            job.writer = writer
            launcher.launch(fileName)
        }
    }
}

/** File-name friendly text: "Rahim Uddin" -> "rahim-uddin". */
fun pdfName(vararg parts: String): String =
    parts.joinToString("-") { it.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-') }.replace(Regex("-+"), "-").trim('-') + ".pdf"
