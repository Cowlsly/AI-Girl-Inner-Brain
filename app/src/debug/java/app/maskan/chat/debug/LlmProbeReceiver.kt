package app.maskan.chat.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.maskan.chat.MaskanApplication
import app.maskan.chat.ondevice.GemmaPrompt
import app.maskan.chat.ondevice.LlmEngine
import app.maskan.chat.ondevice.ModelCatalog
import app.maskan.chat.util.TokenEstimate
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Loads an on-device model and answers one prompt, with the numbers, straight to logcat.
 *
 * DEBUG BUILDS ONLY - this file lives in src/debug and is not compiled into a release.
 *
 * It exists first as the five-minute guard Humam asked for: load the `.task` and generate a
 * single token BEFORE a provider is built around it, so a model file the runtime cannot read
 * shows up as five minutes rather than as a day. It then stays as the measurement harness -
 * ten fixed prompts through the same engine the app uses, with tokens/second counted by the
 * model's own tokenizer instead of guessed.
 *
 *   adb shell am broadcast -n app.maskan.chat.debug/app.maskan.chat.debug.LlmProbeReceiver \
 *       --es prompt 'ما عاصمة الأردن؟'
 *
 * `--es file <name>` points at another file in filesDir/models/ (a `.task` pushed by hand
 * before the download manager exists); `--ei max <n>` overrides the window.
 */
class LlmProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? MaskanApplication
        if (app == null) {
            Log.w(TAG, "no MaskanApplication")
            return
        }
        // Arabic and Thai do not survive the trip through adb's argument handling on a Windows
        // host - the extra arrives empty and the model is asked nothing, which reads on the
        // logcat line as promptTokens=1 and nowhere else. Base64 carries UTF-8 unharmed, the
        // same reason ClipReceiver exists.
        val prompt = intent.getStringExtra("b64")
            ?.let { String(android.util.Base64.decode(it, android.util.Base64.DEFAULT), Charsets.UTF_8) }
            ?: intent.getStringExtra("prompt")
            ?: DEFAULT_PROMPT
        val fileName = intent.getStringExtra("file")
        val maxTokens = intent.getIntExtra("max", 0)

        // By catalogue entry, not by file name alone: the entry carries the turn markers that
        // file declares, and a 3n bundle driven with Gemma 3's markers is a prompt with no turn
        // structure - it answers anyway, which is exactly why it has to be got right here.
        var model = fileName?.let { ModelCatalog.byFileName(it) }
            ?: ModelCatalog.GEMMA_3_1B
        if (fileName != null && model.fileName != fileName) model = model.copy(fileName = fileName)
        if (maxTokens > 0) model = model.copy(contextTokens = maxTokens)
        intent.getStringExtra("backend")?.let { model = model.copy(backend = it) }

        val file = File(File(app.filesDir, "models"), model.fileName)
        if (!file.isFile) {
            Log.w(TAG, "no model at " + file.absolutePath)
            return
        }
        Log.d(TAG, "probe file=" + model.fileName + " bytes=" + file.length() +
            " window=" + model.contextTokens)

        val engine = engineFor(app)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val loadStart = System.currentTimeMillis()
            try {
                engine.ensureLoaded(model)
            } catch (e: Throwable) {
                Log.e(TAG, "LOAD FAILED: " + e.javaClass.simpleName + ": " + e.message)
                return@launch
            }
            val loadMs = System.currentTimeMillis() - loadStart
            Log.d(TAG, "loaded in " + loadMs + "ms")

            val answer = StringBuilder()
            val genStart = System.currentTimeMillis()
            var firstAt = 0L
            try {
                // No system text and one user turn: the harness measures the engine, and the
                // request a real user sends - voice, preset, folder, document - is measured
                // through the app itself once OnDeviceProvider exists.
                engine.generate(
                    model,
                    null,
                    listOf(GemmaPrompt.Turn("user", prompt))
                ).collect { piece ->
                    if (firstAt == 0L) firstAt = System.currentTimeMillis()
                    answer.append(piece)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "GENERATE FAILED: " + e.javaClass.simpleName + ": " + e.message)
                return@launch
            }
            val endedAt = System.currentTimeMillis()
            val text = answer.toString()
            // Counted by Gemma's tokenizer inside the session, reported on the engine's own
            // "gen" line; here the estimate is only for the rate, and it is named as one.
            val answerTokens = TokenEstimate.of(text)
            val decodeMs = (endedAt - firstAt).coerceAtLeast(1)
            Log.d(
                TAG,
                "RESULT estimatedAnswerTokens=" + answerTokens +
                    " chars=" + text.length +
                    " loadMs=" + loadMs +
                    " prefillMs=" + (firstAt - genStart) +
                    " decodeMs=" + decodeMs +
                    " tokensPerSec=" + String.format("%.2f", answerTokens * 1000.0 / decodeMs)
            )
            // In pieces: logcat truncates a long line, and an Arabic answer judged on half of
            // itself is not judged at all.
            text.chunked(600).forEachIndexed { i, part -> Log.d(TAG, "ANSWER[$i] " + part) }
        }
    }

    companion object {
        private const val TAG = "MaskanLlmProbe"
        private const val DEFAULT_PROMPT = "Say OK."

        /**
         * The one engine, held here rather than on the Application.
         *
         * On-device generation is cut from 2.6.0 and MediaPipe is a debug-only dependency, so
         * nothing in the shipped app may hold a model - not even a lazy field that is never
         * touched. The probe is the only thing that loads one now.
         */
        private var engine: LlmEngine? = null

        @Synchronized
        private fun engineFor(app: MaskanApplication): LlmEngine =
            engine ?: LlmEngine(app).also { engine = it }
    }
}
