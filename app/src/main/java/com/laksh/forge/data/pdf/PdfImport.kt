package com.laksh.forge.data.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.laksh.forge.util.ImageUtils
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

sealed interface PdfContent {
    /** A text-layer PDF: the usual case for a program bought as a PDF. */
    data class Text(val text: String, val pages: Int) : PdfContent

    /** A scanned or image-only PDF: pages are handed to vision instead. */
    data class Pages(val imagesBase64: List<String>) : PdfContent
}

class PdfImportException(message: String) : Exception(message)

object PdfImport {

    /** Below this many characters we assume there is no usable text layer. */
    private const val MIN_USEFUL_CHARS = 400
    private const val MAX_VISION_PAGES = 12

    fun fileName(context: Context, uri: Uri): String {
        var name: String? = null
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
            }
        }
        return name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "program.pdf"
    }

    suspend fun extract(context: Context, uri: Uri): PdfContent = withContext(Dispatchers.IO) {
        val cached = copyToCache(context, uri)
        try {
            val text = runCatching {
                PDDocument.load(cached).use { doc ->
                    if (doc.isEncrypted) throw PdfImportException("That PDF is password protected.")
                    val stripper = PDFTextStripper().apply { sortByPosition = true }
                    stripper.getText(doc) to doc.numberOfPages
                }
            }.getOrElse { e ->
                if (e is PdfImportException) throw e
                "" to 0
            }

            val cleaned = normalise(text.first)
            if (cleaned.length >= MIN_USEFUL_CHARS) {
                PdfContent.Text(cleaned, text.second)
            } else {
                val images = renderPages(cached)
                if (images.isEmpty()) {
                    throw PdfImportException("Could not read anything from that PDF.")
                }
                PdfContent.Pages(images)
            }
        } finally {
            cached.delete()
        }
    }

    /** PDF text extraction leaves ragged whitespace; collapse it so the model sees clean lines. */
    private fun normalise(raw: String): String =
        raw.replace("\r\n", "\n")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .lines()
            .joinToString("\n") { it.trim() }
            .trim()

    private fun copyToCache(context: Context, uri: Uri): File {
        val out = File.createTempFile("import_", ".pdf", context.cacheDir)
        context.contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { input.copyTo(it) }
        } ?: throw PdfImportException("Could not open that file.")
        if (out.length() == 0L) throw PdfImportException("That file is empty.")
        return out
    }

    /** Rasterise pages for scanned PDFs so vision can read them. */
    private fun renderPages(file: File): List<String> {
        val descriptor = runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }.getOrNull() ?: return emptyList()

        return descriptor.use { fd ->
            runCatching {
                PdfRenderer(fd).use { renderer ->
                    val count = minOf(renderer.pageCount, MAX_VISION_PAGES)
                    (0 until count).mapNotNull { i ->
                        runCatching {
                            renderer.openPage(i).use { page ->
                                val scale = 1400f / maxOf(page.width, page.height).toFloat()
                                val w = (page.width * scale).toInt().coerceAtLeast(1)
                                val h = (page.height * scale).toInt().coerceAtLeast(1)
                                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                bmp.eraseColor(Color.WHITE)
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                val b64 = ImageUtils.toBase64Jpeg(bmp, quality = 80)
                                bmp.recycle()
                                b64
                            }
                        }.getOrNull()
                    }
                }
            }.getOrDefault(emptyList())
        }
    }
}
