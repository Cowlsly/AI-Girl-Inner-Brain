package app.maskan.chat.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream

object ImageUtils {

    /**
     * Longest side for a photo taken with the camera. Higher than the gallery default because
     * this photo is going to be READ - a menu, a label, a form - and text is the first thing a
     * downscale destroys. 1,536 px at quality 85 stays well under the 4-5 MB inline limit that
     * cloud providers enforce.
     */
    const val CAMERA_MAX_DIMENSION = 1536

    /** Backstop, not a target: at 1,536 px quality 85 lands far below this for ordinary scenes. */
    const val CAMERA_MAX_KB = 900

    /**
     * JPEG bytes for an already-sized bitmap, dropping quality until it fits [maxSizeKb].
     *
     * Split out of compressImage so a rendered PDF page goes through exactly the same ladder as
     * a camera photo - the whole point of reusing the camera's numbers is undone if the page
     * takes a different route to a JPEG. Does not recycle the bitmap: the caller owns it.
     */
    fun compressBitmap(bitmap: Bitmap, maxSizeKb: Int): ByteArray {
        var quality = 85
        var compressed: ByteArray
        do {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            compressed = out.toByteArray()
            quality -= 10
        } while (compressed.size > maxSizeKb * 1024 && quality > 10)
        return compressed
    }

    fun compressImage(
        context: Context,
        uri: Uri,
        maxSizeKb: Int = 500,
        maxDimension: Int = 1024
    ): Pair<ByteArray, String> {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw Exception("Cannot read image")
        val originalBytes = inputStream.use { it.readBytes() }

        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size, options)
        val width = options.outWidth
        val height = options.outHeight

        var sampleSize = 1
        while (width / sampleSize > maxDimension || height / sampleSize > maxDimension) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size, decodeOptions)
            ?: throw Exception("Cannot decode image")

        val scaled = if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
            val scale = maxDimension.toFloat() / maxOf(bitmap.width, bitmap.height)
            val newW = (bitmap.width * scale).toInt()
            val newH = (bitmap.height * scale).toInt()
            Bitmap.createScaledBitmap(bitmap, newW, newH, true).also {
                if (it !== bitmap) bitmap.recycle()
            }
        } else {
            bitmap
        }

        val compressed = compressBitmap(scaled, maxSizeKb)

        scaled.recycle()

        return compressed to "image/jpeg"
    }
}
