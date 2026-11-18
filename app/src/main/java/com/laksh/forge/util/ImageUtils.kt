package com.laksh.forge.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

object ImageUtils {

    // Long-edge caps for images sent to the API. Image tokens scale with area
    // (roughly width x height / 750), so this is a direct cost lever.

    /** Meal photos: enough to judge a plate, ~1000 image tokens. */
    const val EDGE_FOOD = 1000

    /** Nutrition panels: printed small text, so detail earns its cost here. */
    const val EDGE_LABEL = 1400

    data class Prepared(val base64: String, val mediaType: String, val bitmap: Bitmap)

    /** Decode, EXIF-rotate and downscale a user-picked or camera-captured image. */
    suspend fun prepare(
        context: Context,
        uri: Uri,
        maxEdge: Int = EDGE_FOOD
    ): Prepared = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("Could not open that image.")

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

        val sample = sampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
        val decoded = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: throw IllegalArgumentException("That image could not be decoded.")

        val rotated = applyExif(bytes, decoded)
        val scaled = scaleToMaxEdge(rotated, maxEdge)
        Prepared(toBase64Jpeg(scaled, 85), "image/jpeg", scaled)
    }

    fun toBase64Jpeg(bitmap: Bitmap, quality: Int = 85): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    /** Persist a meal photo into app-private storage. Only called when the user allows it. */
    fun savePhoto(context: Context, bitmap: Bitmap): String {
        val dir = File(context.filesDir, "meal_photos").apply { mkdirs() }
        val file = File(dir, "meal_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        return file.absolutePath
    }

    fun deletePhoto(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching { File(path).delete() }
    }

    /** A temp file for the camera intent to write into, exposed through FileProvider. */
    fun newCaptureFile(context: Context): File {
        val dir = File(context.cacheDir, "captures").apply { mkdirs() }
        return File(dir, "capture_${System.currentTimeMillis()}.jpg")
    }

    private fun sampleSize(w: Int, h: Int, maxEdge: Int): Int {
        var sample = 1
        var longEdge = maxOf(w, h)
        while (longEdge / 2 >= maxEdge * 2) {
            longEdge /= 2
            sample *= 2
        }
        return sample
    }

    private fun scaleToMaxEdge(src: Bitmap, maxEdge: Int): Bitmap {
        val longEdge = maxOf(src.width, src.height)
        if (longEdge <= maxEdge) return src
        val ratio = maxEdge.toFloat() / longEdge
        val scaled = Bitmap.createScaledBitmap(
            src,
            (src.width * ratio).toInt().coerceAtLeast(1),
            (src.height * ratio).toInt().coerceAtLeast(1),
            true
        )
        if (scaled != src) src.recycle()
        return scaled
    }

    private fun applyExif(bytes: ByteArray, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            ExifInterface(bytes.inputStream()).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                .also { if (it != bitmap) bitmap.recycle() }
        }.getOrDefault(bitmap)
    }
}
