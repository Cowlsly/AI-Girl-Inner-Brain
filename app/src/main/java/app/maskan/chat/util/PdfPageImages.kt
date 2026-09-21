package app.maskan.chat.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * The first few pages of a PDF, as pictures.
 *
 * This is the camera path pointed at a document: a scan has no text to extract, but a model that
 * can see can read a photograph of a page perfectly well. Same numbers as a camera photo -
 * 1,536 px and JPEG 85 - because a page of text is exactly the case the gallery's 1,024 px
 * default destroys, which is the whole reason those constants exist.
 *
 * The file is copied into the cache before rendering. PdfRenderer needs a seekable descriptor
 * and a document provider is free to hand back a pipe; copying is a few megabytes and always
 * works, where opening the Uri directly works on most phones and fails on someone's.
 *
 * The cache directory is deliberately NOT declared in res/xml/file_paths.xml. That file is the
 * FileProvider's reach, and nothing here is ever shared out of the app - declaring it would
 * widen the provider's surface for no reason.
 */
object PdfPageImages {

    /** How many pages of a scan go to the model. Enough for a letter or an invoice. */
    const val MAX_PAGES = 3

    private const val DIR = "documents"

    /**
     * Render up to [MAX_PAGES] pages of [uri] to JPEG bytes, first page first.
     *
     * Blocking and bitmap-sized: call it on Dispatchers.IO. Returns an empty list rather than
     * throwing when the file cannot be rendered at all, so a scan that defeats PdfRenderer ends
     * as "no text in this PDF" and not as a crash.
     */
    fun render(context: Context, uri: Uri, pages: Int = MAX_PAGES): List<ByteArray> {
        val copy = copyToCache(context, uri) ?: return emptyList()
        return try {
            val descriptor = ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY)
            descriptor.use { fd ->
                PdfRenderer(fd).use { renderer ->
                    val count = minOf(pages, renderer.pageCount)
                    (0 until count).mapNotNull { index -> renderPage(renderer, index) }
                }
            }
        } catch (t: Throwable) {
            emptyList()
        } finally {
            copy.delete()
        }
    }

    private fun renderPage(renderer: PdfRenderer, index: Int): ByteArray? {
        renderer.openPage(index).use { page ->
            val longest = maxOf(page.width, page.height)
            if (longest <= 0) return null
            val scale = ImageUtils.CAMERA_MAX_DIMENSION.toFloat() / longest
            val width = (page.width * scale).toInt().coerceAtLeast(1)
            val height = (page.height * scale).toInt().coerceAtLeast(1)

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            // PdfRenderer draws only the page's own marks; without a white ground the paper
            // comes out transparent, which a JPEG turns black and no model can read.
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

            return try {
                ImageUtils.compressBitmap(bitmap, ImageUtils.CAMERA_MAX_KB)
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun copyToCache(context: Context, uri: Uri): File? {
        return try {
            val dir = File(context.cacheDir, DIR).apply { mkdirs() }
            val file = File(dir, "page-source-" + System.currentTimeMillis() + ".pdf")
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            file
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Drop anything a dead process left behind. Called on entering a chat, by age rather than
     * wholesale, for the same reason the camera sweep is: a render in flight has its file
     * already created.
     */
    fun sweep(context: Context) {
        val dir = File(context.cacheDir, DIR)
        if (!dir.isDirectory) return
        val cutoff = System.currentTimeMillis() - 60 * 60 * 1000
        dir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) file.delete()
        }
    }
}
