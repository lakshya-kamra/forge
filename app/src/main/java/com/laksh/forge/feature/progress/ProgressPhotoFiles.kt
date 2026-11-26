package com.laksh.forge.feature.progress

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What Forge v2.0 left on disk. Since v2.1 Forge never keeps progress photos (people keep
 * their own in their gallery), so the only jobs left here are finding the old files - for the
 * one-time "save them to your gallery / delete from Forge" card - and removing them once the
 * person taps delete. Nothing in this object ever writes a photo.
 */
object ProgressPhotoFiles {

    /** v2.0 kept saved photos here (app-private, never backed up). */
    fun legacyDir(context: Context): File = File(context.filesDir, "progress_photos")

    /** v2.0 onboarding staged photos here before saving them. */
    private fun stagingDir(context: Context): File = File(context.cacheDir, "progress_staging")

    /** Every legacy photo file still on disk, oldest first; empty when there are none. */
    suspend fun legacyFiles(context: Context): List<File> = withContext(Dispatchers.IO) {
        legacyDir(context).listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() }.orEmpty()
    }

    fun exists(path: String?): Boolean = !path.isNullOrBlank() && File(path).isFile

    /**
     * Remove every legacy photo file and the folders that held them. Only ever called from an
     * explicit "Delete from Forge" confirmation. Returns how many files were removed.
     */
    suspend fun deleteAllLegacy(context: Context): Int = withContext(Dispatchers.IO) {
        var removed = 0
        legacyDir(context).listFiles()?.forEach { if (it.delete()) removed++ }
        legacyDir(context).delete()
        clearStagingNow(context)
        removed
    }

    /** Staged files from an abandoned v2.0 onboarding are cache leftovers; nobody chose to keep them. */
    suspend fun clearStaging(context: Context) = withContext(Dispatchers.IO) { clearStagingNow(context) }

    private fun clearStagingNow(context: Context) {
        stagingDir(context).listFiles()?.forEach { it.delete() }
        stagingDir(context).delete()
    }
}
