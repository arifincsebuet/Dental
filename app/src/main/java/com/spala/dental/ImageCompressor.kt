package com.spala.dental

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Clinical image encoding for Firebase Storage.
 *
 * Every candidate is encoded losslessly (no compression artefacts): lossless WebP, which is usually
 * 25–40 % smaller than PNG, and PNG, keeping whichever is smaller. If even the lossless file exceeds
 * the 512 KB budget, the image is scaled down in steps until it fits, so the stored pixels are an
 * exact, artefact-free copy of a slightly smaller image. The result reports whether it was resized.
 */
object ImageCompressor {
    const val MAX_BYTES = 512 * 1024
    private const val MAX_DECODE_PIXELS = 24_000_000L
    private const val MIN_EDGE = 320

    class Encoded(val bytes: ByteArray, val extension: String, val mimeType: String, val width: Int, val height: Int, val resized: Boolean)

    fun encode(context: Context, uri: Uri): Encoded {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val original = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val pixels = info.size.width.toLong() * info.size.height
            // Very large camera files are decoded at a reduced size to avoid running out of memory.
            if (pixels > MAX_DECODE_PIXELS) {
                val scale = sqrt(MAX_DECODE_PIXELS.toDouble() / pixels)
                decoder.setTargetSize((info.size.width * scale).roundToInt(), (info.size.height * scale).roundToInt())
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        try {
            val sourceWidth = original.width; val sourceHeight = original.height
            var bitmap = original
            var resized = false
            while (true) {
                val best = smallestLossless(bitmap)
                if (best.first.size <= MAX_BYTES) {
                    return Encoded(best.first, best.second, if (best.second == "webp") "image/webp" else "image/png", bitmap.width, bitmap.height, resized || bitmap.width != sourceWidth)
                }
                val longEdge = max(bitmap.width, bitmap.height)
                check(longEdge > MIN_EDGE) { "Image cannot be stored under 512 KB" }
                // Bytes scale roughly with pixel count: aim for the budget with a 10 % margin.
                val factor = sqrt(MAX_BYTES * 0.9 / best.first.size).coerceIn(0.5, 0.9)
                val next = Bitmap.createScaledBitmap(bitmap, max(1, (bitmap.width * factor).roundToInt()), max(1, (bitmap.height * factor).roundToInt()), true)
                if (bitmap !== original) bitmap.recycle()
                bitmap = next
                resized = true
            }
        } finally {
            original.recycle()
        }
    }

    /** Smallest of lossless WebP and PNG for this bitmap. */
    private fun smallestLossless(bitmap: Bitmap): Pair<ByteArray, String> {
        val webp = compress(bitmap, webpFormat(), 100)
        val png = compress(bitmap, Bitmap.CompressFormat.PNG, 100)
        return if (webp != null && (png == null || webp.size <= png.size)) webp to "webp" else (png ?: error("Image encoding failed")) to "png"
    }

    /** WEBP_LOSSLESS on Android 11+; on Android 10 the legacy WEBP format at quality 100 is lossless. */
    @Suppress("DEPRECATION")
    private fun webpFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP

    private fun compress(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): ByteArray? = runCatching {
        ByteArrayOutputStream().use { out -> check(bitmap.compress(format, quality, out)); out.toByteArray().takeIf { it.isNotEmpty() } }
    }.getOrNull()
}
