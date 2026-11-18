package com.laksh.forge.data.backup

import android.content.Context
import com.laksh.forge.data.db.ForgeDatabase
import com.laksh.forge.data.db.TableCounts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class StorageReport(
    val databaseBytes: Long,
    val mealPhotoBytes: Long,
    val mealPhotoFiles: Int,
    val progressPhotoBytes: Long,
    val progressPhotoFiles: Int,
    val settingsBytes: Long,
    val cacheBytes: Long,
    val counts: TableCounts
) {
    val totalBytes: Long
        get() = databaseBytes + mealPhotoBytes + progressPhotoBytes + settingsBytes + cacheBytes
}

/** What Forge keeps on the device, and the one bulk clean-up it offers. */
object StorageUsage {

    /** Where [com.laksh.forge.util.ImageUtils.savePhoto] keeps meal photos. */
    private const val MEAL_PHOTO_DIR = "meal_photos"
    private const val DB_NAME = "forge.db"

    suspend fun measure(context: Context): StorageReport = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val dao = ForgeDatabase.get(app).backupDao()

        val dbFile = app.getDatabasePath(DB_NAME)
        // WAL mode keeps recent writes in side files; leaving them out under-reports badly.
        val dbBytes = listOf(dbFile, File(dbFile.path + "-wal"), File(dbFile.path + "-shm"))
            .sumOf { if (it.exists()) it.length() else 0L }

        val mealDir = File(app.filesDir, MEAL_PHOTO_DIR)
        val mealFiles = mealDir.listFiles()?.filter { it.isFile }.orEmpty()

        val progressFiles = dao.progressPhotoPaths().map(::File).filter { it.exists() }

        StorageReport(
            databaseBytes = dbBytes,
            mealPhotoBytes = mealFiles.sumOf { it.length() },
            mealPhotoFiles = mealFiles.size,
            progressPhotoBytes = progressFiles.sumOf { it.length() },
            progressPhotoFiles = progressFiles.size,
            settingsBytes = dirSize(File(app.filesDir, "datastore")),
            cacheBytes = dirSize(app.cacheDir),
            counts = dao.counts()
        )
    }

    /**
     * Deletes every stored meal photo and detaches them from their food entries. The entries
     * and their numbers stay; only the pictures go. Progress photos are never touched here.
     * Returns the number of files deleted.
     */
    suspend fun deleteMealPhotos(context: Context): Int = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val dao = ForgeDatabase.get(app).backupDao()
        val mealDir = File(app.filesDir, MEAL_PHOTO_DIR).canonicalFile

        var deleted = 0
        mealDir.listFiles()?.filter { it.isFile }?.forEach { if (it.delete()) deleted++ }
        // Entries can point at files outside the folder (older builds); remove those too, but
        // only inside app-private storage - never follow a path out of filesDir.
        val filesRoot = app.filesDir.canonicalFile
        dao.mealPhotoPaths().forEach { path ->
            val f = File(path).canonicalFile
            if (f.exists() && f.path.startsWith(filesRoot.path) && f.delete()) deleted++
        }
        dao.clearMealPhotoPaths()
        deleted
    }

    private fun dirSize(dir: File): Long =
        if (!dir.exists()) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** "1.4 MB" - binary units, one decimal under 10. */
    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB")
        var v = bytes / 1024.0
        var i = 0
        while (v >= 1024 && i < units.lastIndex) {
            v /= 1024
            i++
        }
        return if (v < 10) String.format(java.util.Locale.ROOT, "%.1f %s", v, units[i])
        else String.format(java.util.Locale.ROOT, "%.0f %s", v, units[i])
    }
}
