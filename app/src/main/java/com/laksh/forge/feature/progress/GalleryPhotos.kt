package com.laksh.forge.feature.progress

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Where a photo's date came from, so the screen can say how sure it is. */
enum class DateSource(val label: String) {
    EXIF("from the photo"),
    MEDIA_STORE("from your gallery"),
    USER("set by you"),
    NONE("date needed")
}

/** A photo decoded for this screen only. The bitmap lives in memory and nowhere else. */
class LoadedPhoto(val bitmap: Bitmap, val date: LocalDate?, val source: DateSource)

/**
 * The person's own photos, read from and written to their gallery - never to Forge's storage.
 *
 * Reading: a picked image is read into memory once, its capture date taken from EXIF
 * DateTimeOriginal (else MediaStore's DATE_TAKEN), then decoded downscaled. No file is written
 * and Coil is not used, because its memory cache would outlive this screen.
 *
 * Writing (the one-time v2.0 export and the pose-guide camera): straight into the shared
 * Pictures/Forge Progress album through MediaStore, which needs no storage permission on
 * Android 10+. Android 8-9 write into a folder the person picks with the system picker instead.
 */
object GalleryPhotos {

    const val ALBUM = "Forge Progress"
    val albumLabel: String get() = "Pictures/$ALBUM"

    /** Long edge kept in memory: sharp on a phone at half-screen width, ~3.5 MB per photo. */
    const val COMPARE_EDGE = 1080

    /** MediaStore RELATIVE_PATH (Android 10+) lets Forge add to the gallery without a permission. */
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.Q)
    val canSaveDirectly: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    private val exifFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    // ------------------------------------------------------------------ reading

    suspend fun load(context: Context, uri: Uri, maxEdge: Int = COMPARE_EDGE): LoadedPhoto = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("That photo couldn't be opened.")
        val exif = runCatching { ExifInterface(bytes.inputStream()) }.getOrNull()

        val exifDate = exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.let(::parseExifDate)
        val (date, source) = when {
            exifDate != null -> exifDate to DateSource.EXIF
            else -> dateTaken(resolver, uri)?.let { it to DateSource.MEDIA_STORE } ?: (null to DateSource.NONE)
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("That file isn't a photo Forge can read.")
        var sample = 1
        var longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        while (longEdge / 2 >= maxEdge) {
            longEdge /= 2
            sample *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("That photo couldn't be decoded.")
        val upright = rotate(decoded, exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
        LoadedPhoto(scale(upright, maxEdge), date, source)
    }

    /** "yyyy:MM:dd HH:mm:ss"; cameras with an unset clock write zeros or 1970, which mean "unknown". */
    internal fun parseExifDate(raw: String): LocalDate? = runCatching {
        LocalDate.parse(raw.trim().take(10).replace(':', '-'))
    }.getOrNull()?.takeIf { it.year >= 1990 }

    private fun dateTaken(resolver: ContentResolver, uri: Uri): LocalDate? = runCatching {
        // Photo Picker and MediaStore URIs expose DATE_TAKEN; a document URI from the pre-picker
        // fallback usually doesn't, and the person then sets the date.
        resolver.query(uri, arrayOf(MediaStore.Images.ImageColumns.DATE_TAKEN), null, null, null)?.use { c ->
            if (!c.moveToFirst() || c.isNull(0)) null
            else c.getLong(0).takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }
        }
    }.getOrNull()?.takeIf { it.year >= 1990 }

    private fun rotate(src: Bitmap, orientation: Int?): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return src
        }
        return runCatching {
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true).also { if (it != src) src.recycle() }
        }.getOrDefault(src)
    }

    private fun scale(src: Bitmap, maxEdge: Int): Bitmap {
        val edge = maxOf(src.width, src.height)
        if (edge <= maxEdge) return src
        val r = maxEdge.toFloat() / edge
        return Bitmap.createScaledBitmap(src, (src.width * r).toInt().coerceAtLeast(1), (src.height * r).toInt().coerceAtLeast(1), true)
            .also { if (it != src) src.recycle() }
    }

    // ------------------------------------------------------------------ writing

    /** A gallery-friendly name; MediaStore adds " (1)" itself if it already exists. */
    fun displayName(date: LocalDate, label: String): String =
        "Forge progress $date ${label.lowercase()}".replace(Regex("[^A-Za-z0-9 _-]"), "") + ".jpg"

    /**
     * Copy a JPEG into Pictures/Forge Progress (Android 10+). The item stays pending (hidden)
     * until fully written, and is removed again if anything fails, so the gallery never shows
     * a half-written body photo.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    suspend fun saveToGallery(context: Context, source: File, displayName: String, takenAt: Long): Uri = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
            put(MediaStore.Images.ImageColumns.DATE_TAKEN, takenAt)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val item = resolver.insert(collection, values) ?: throw IOException("Your gallery didn't accept the photo.")
        try {
            resolver.openOutputStream(item)?.use { out -> source.inputStream().use { it.copyTo(out) } }
                ?: throw IOException("Your gallery didn't accept the photo.")
            stampDate(resolver, item, takenAt)
            resolver.update(item, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            item
        } catch (e: Exception) {
            runCatching { resolver.delete(item, null, null) }
            throw e
        }
    }

    /** Android 8-9: write into a folder the person picked (OpenDocumentTree), no permission needed. */
    suspend fun saveToFolder(context: Context, tree: Uri, source: File, displayName: String, takenAt: Long): Uri = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = DocumentsContract.createDocument(resolver, parent, "image/jpeg", displayName)
            ?: throw IOException("That folder didn't accept the photo.")
        try {
            resolver.openOutputStream(doc)?.use { out -> source.inputStream().use { it.copyTo(out) } }
                ?: throw IOException("That folder didn't accept the photo.")
            stampDate(resolver, doc, takenAt)
            doc
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, doc) }
            throw e
        }
    }

    /**
     * v2.0 stored re-encoded JPEGs without EXIF. Writing DateTimeOriginal means Compare (here
     * or in any gallery app) still knows which month each exported photo is from.
     */
    private fun stampDate(resolver: ContentResolver, uri: Uri, takenAt: Long) {
        runCatching {
            resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                if (exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) == null) {
                    val stamp = LocalDateTime.ofInstant(Instant.ofEpochMilli(takenAt), ZoneId.systemDefault()).format(exifFormat)
                    exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, stamp)
                    exif.setAttribute(ExifInterface.TAG_DATETIME, stamp)
                    exif.saveAttributes()
                }
            }
        }
    }
}
