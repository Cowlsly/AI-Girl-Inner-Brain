package app.maskan.chat.debug

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Puts a string on the clipboard, for device tests that need Arabic or Thai typed into the app.
 *
 * DEBUG BUILDS ONLY - this file lives in src/debug and is not compiled into a release.
 *
 * It exists because `adb shell input text` cannot type either script: KeyCharacterMap has no
 * mapping for the Arabic or Thai blocks and the command throws before anything is typed. An intent
 * extra carries UTF-8 unharmed, so the clipboard is the way in, and KEYCODE_PASTE (279) does the
 * rest. Bring the app to the front first: from Android 10 the clipboard belongs to the focused
 * app, and a background write can be dropped without saying so.
 */
class ClipReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // --es b64 <base64 utf-8> for Arabic and Thai. `--es text` with either script from a
        // WINDOWS host arrives mangled - session 6 sent a 33-character Arabic sentence and this
        // receiver logged "set 2 chars", which looks like a working clipboard write and is not.
        // LlmProbeReceiver hit the same wall; this is the same door.
        val text = intent.getStringExtra(EXTRA_B64)
            ?.let { String(android.util.Base64.decode(it, android.util.Base64.DEFAULT), Charsets.UTF_8) }
            ?: intent.getStringExtra(EXTRA_TEXT)
        if (text == null) {
            Log.w(TAG, "no --es text and no --es b64, nothing to put on the clipboard")
            return
        }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            Log.w(TAG, "no clipboard service")
            return
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(TAG, text))
        // Read it back: a clipboard write that was dropped for lack of focus looks exactly like
        // one that worked, and a device test must not be told "done" on the strength of asking.
        val readBack = runCatching { clipboard.primaryClip?.getItemAt(0)?.text?.toString() }
            .getOrNull()
        Log.d(TAG, "set " + text.length + " chars, read back " + (readBack?.length ?: -1))
    }

    companion object {
        private const val TAG = "MaskanDebugClip"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_B64 = "b64"
    }
}
