package app.maskan.chat.util

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Where a photo taken with the system camera lands, and how long it is allowed to stay.
 *
 * Maskan asks no CAMERA permission. `ActivityResultContracts.TakePicture` starts whichever
 * camera app the phone has and hands it a one-time write grant on a Uri we own, so the photo is
 * taken by that app, with its permission, and arrives here as bytes. Adding the permission would
 * change the Play data-safety form and the F-Droid permission list for nothing.
 *
 * Its own named cache subdirectory, not one of the two the share sheet uses: for the seconds the
 * grant is live, a foreign process can write into this directory, and that must not be a
 * directory anything else reads from.
 */
object CameraCapture {

    /** Matches the `camera_photos` cache-path in res/xml/file_paths.xml. */
    private const val DIR = "camera_photos"

    /**
     * Old enough that nothing could still be waiting for it. The process can be killed while the
     * camera app is in front, and the file it then writes belongs to no one; but a capture still
     * in flight has its file already created, so a sweep of EVERYTHING would empty the photo the
     * user is at that moment taking.
     */
    private const val MAX_AGE_MS = 60L * 60L * 1000L

    fun newPhotoFile(context: Context): File {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        return File(dir, "photo-" + System.currentTimeMillis() + ".jpg")
    }

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)

    /** Drop captures nobody collected. Called on entering a chat; safe to call at any time. */
    fun sweep(context: Context) {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        File(context.cacheDir, DIR).listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) file.delete()
        }
    }
}
