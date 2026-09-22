package app.maskan.chat.ondevice

/**
 * The models this app knows how to run, pinned in the app itself.
 *
 * Pinned rather than fetched: the sha256 is what makes "downloaded from the internet and then
 * loaded into a native runtime" safe to do at all, and a checksum served from the same place as
 * the file it describes checks nothing. A new model means a new release.
 *
 * [contextTokens] is the window the FILE was built with, not a preference. Gemma-3 1B is
 * published in several builds and the name says which: `ekv2048` and `ekv4096` are the same
 * weights with a different KV cache. Everything the request assembly is allowed to spend is
 * derived from this number (see ChatRepository.budgetFor), so it has to be the truth about the
 * file, and a file whose sha256 does not match is never loaded.
 */
data class OnDeviceModel(
    val id: String,
    /** The file name under filesDir/models/. */
    val fileName: String,
    /** English display name. Always names Google, never Maskan - it is not our model. */
    val displayName: String,
    val bytes: Long,
    val sha256: String,
    /**
     * Where the file comes from, in order. More than one entry means the file is split because
     * a GitHub release asset stops at 2 GB; the parts are joined on the device in this order and
     * the sha256 above is checked against the JOINED file.
     */
    val parts: List<Part>,
    val contextTokens: Int,
    /**
     * The turn markers THIS file uses, read out of its own METADATA rather than assumed.
     *
     * Gemma 3 1B declares `<start_of_turn>` / `<end_of_turn>`; Gemma 3n E2B declares
     * `<ctrl99>` / `<ctrl100>` for the same job. A prompt built with the other model's markers
     * is a prompt with no turn structure at all, so this travels with the file.
     */
    val turnStart: String,
    val turnEnd: String,
    /**
     * Which backend to ask MediaPipe for. "gpu", "cpu", or null for its own default.
     *
     * Measured per model rather than assumed: the runtime's LiteRT accelerator registry reports
     * that it cannot load a GPU accelerator on this phone, while the engine's own OpenCL path is
     * compiled into the same library, so which one actually runs is a question for the device.
     */
    val backend: String? = null,
    /** Below this much RAM the download is refused rather than the phone being made unusable. */
    val minRamBytes: Long,
    val vision: Boolean = false
) {
    data class Part(val url: String, val bytes: Long, val sha256: String)

    val isSplit: Boolean get() = parts.size > 1
}

object ModelCatalog {

    /**
     * Gemma 3 1B, 4-bit, with a 4,096-token window.
     *
     * The 2,048-token build is 135 MB smaller and cannot hold the request this app builds for an
     * Arabic user: the default MSA voice alone is 413 tokens and the Algerian one is 732, before
     * a folder's instructions, an attached file or one word of history. Humam's call, session 5.
     *
     * The url and the sha256 are filled in when the file is published to a Maskan GitHub
     * release; until then this entry is what the DEBUG probe loads from a pushed file.
     */
    val GEMMA_3_1B = OnDeviceModel(
        id = "gemma3-1b-it-int4-ekv4096",
        fileName = "gemma3-1b-it-int4-ekv4096.task",
        displayName = "Gemma 3 1B (Google)",
        bytes = 689_308_662L,
        sha256 = "",
        parts = emptyList(),
        contextTokens = 4096,
        turnStart = "<start_of_turn>",
        turnEnd = "<end_of_turn>",
        minRamBytes = 3L * 1024 * 1024 * 1024
    )

    /**
     * Gemma 3n E2B, 4-bit, text only for now.
     *
     * Its bundle also carries TF_LITE_VISION_ENCODER and TF_LITE_VISION_ADAPTER (170 MB of the
     * 2.9 GB), but reading a photo needs com.google.mediapipe:tasks-core for MPImage - another
     * native library in every APK - and that is not being paid for until the Arabic is worth it.
     * Vision stays off here and in the session's graph options.
     */
    val GEMMA_3N_E2B = OnDeviceModel(
        id = "gemma-3n-e2b-it-int4",
        fileName = "gemma-3n-E2B-it-int4.task",
        displayName = "Gemma 3n E2B (Google)",
        bytes = 3_136_226_711L,
        sha256 = "a7f544cfee68f579fabadb22aa9284faa4020a0f5358d0e15b49fdd4cefe4200",
        parts = emptyList(),
        contextTokens = 4096,
        turnStart = "<ctrl99>",
        turnEnd = "<ctrl100>",
        minRamBytes = 6L * 1024 * 1024 * 1024
    )

    val ALL: List<OnDeviceModel> = listOf(GEMMA_3_1B, GEMMA_3N_E2B)

    fun byId(id: String): OnDeviceModel? = ALL.firstOrNull { it.id == id }

    fun byFileName(name: String): OnDeviceModel? = ALL.firstOrNull { it.fileName == name }
}
