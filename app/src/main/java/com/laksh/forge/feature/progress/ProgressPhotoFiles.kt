package com.laksh.forge.feature.progress

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.laksh.forge.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Progress photos on disk. They live only in app-private storage (filesDir/progress_photos,
 * which the backup rules do not include) and are only ever read back for an analysis the
 * person explicitly starts.
 */
object ProgressPhotoFiles {

    /** Stored resolution: enough to compare definition month to month without bloating storage. */
    private const val STORE_EDGE = 1600

    /** Sent to the AI: full body at this size reads fine and keeps image tokens modest. */
    private const val ANALYSIS_EDGE = 1024

    private fun stagingDir(context: Context) = File(context.cacheDir, "progress_staging").apply { mkdirs() }
    private fun photoDir(context: Context) = File(context.filesDir, "progress_photos").apply { mkdirs() }

    /**
     * Decode, EXIF-rotate and downscale a picked or captured image into a staging file in the
     * cache, so nothing reaches permanent storage until the person saves.
     */
    suspend fun stage(context: Context, uri: Uri): String {
        val prepared = ImageUtils.prepare(context, uri, STORE_EDGE)
        return withContext(Dispatchers.IO) {
            val file = File(stagingDir(context), "stage_${System.nanoTime()}.jpg")
            FileOutputStream(file).use { prepared.bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            prepared.bitmap.recycle()
            discardCameraCapture(context, uri)
            file.absolutePath
        }
    }

    /**
     * The in-app camera writes full-resolution captures into the cache; once staged they are
     * redundant copies of a body photo, so they are removed rather than left for the OS to
     * evict. Only files inside this app's cache are touched - never a gallery original.
     */
    private fun discardCameraCapture(context: Context, uri: Uri) {
        if (uri.scheme != "file") return
        val path = uri.path ?: return
        runCatching {
            val f = File(path).canonicalFile
            if (f.path.startsWith(context.cacheDir.canonicalPath)) f.delete()
        }
    }

    /** Move a staged photo into permanent private storage and return its new path. */
    suspend fun commit(context: Context, stagedPath: String): String = withContext(Dispatchers.IO) {
        val src = File(stagedPath)
        if (!src.exists()) throw IllegalStateException("A staged photo went missing before it could be saved.")
        if (src.parentFile == photoDir(context)) return@withContext src.absolutePath
        val dest = File(photoDir(context), "progress_${System.currentTimeMillis()}_${src.nameWithoutExtension.takeLast(6)}.jpg")
        if (!src.renameTo(dest)) {
            src.copyTo(dest, overwrite = true)
            src.delete()
        }
        dest.absolutePath
    }

    /** Stage and commit in one go, for the Progress screen where saving is immediate. */
    suspend fun importPhoto(context: Context, uri: Uri): String = commit(context, stage(context, uri))

    /** A downscaled JPEG for one analysis call. The bitmap is discarded straight after. */
    suspend fun base64ForAnalysis(path: String): String = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        var longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        while (longEdge / 2 >= ANALYSIS_EDGE) {
            longEdge /= 2
            sample *= 2
        }
        val decoded = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalArgumentException("A photo could not be read for analysis.")
        val edge = maxOf(decoded.width, decoded.height)
        val scaled = if (edge > ANALYSIS_EDGE) {
            val r = ANALYSIS_EDGE.toFloat() / edge
            Bitmap.createScaledBitmap(decoded, (decoded.width * r).toInt().coerceAtLeast(1), (decoded.height * r).toInt().coerceAtLeast(1), true)
                .also { if (it != decoded) decoded.recycle() }
        } else decoded
        try {
            ImageUtils.toBase64Jpeg(scaled, 85)
        } finally {
            scaled.recycle()
        }
    }

    fun delete(path: String?) = ImageUtils.deletePhoto(path)

    /** Drop any staged photos left behind by an abandoned onboarding. */
    suspend fun clearStaging(context: Context) = withContext(Dispatchers.IO) {
        stagingDir(context).listFiles()?.forEach { it.delete() }
        Unit
    }
}
