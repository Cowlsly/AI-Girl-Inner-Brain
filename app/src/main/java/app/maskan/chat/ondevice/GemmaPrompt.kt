package app.maskan.chat.ondevice

/**
 * The app's message list, written the way Gemma expects to read it.
 *
 * Gemma has **no system role**. Its template is a sequence of `user` and `model` turns and
 * nothing else, so the assembled system text - preset, dialect voice, folder instructions,
 * memory, document block - is folded into the first user turn. That is a wire format, the same
 * kind of thing AnthropicProvider does when it lifts the system message into its own field, and
 * it is why MaskanCtx names this shape `gemma-folded`: the text is all there, it is just not in
 * a message of its own any more.
 *
 * The templating is ours to do, but not because the file has none - the opposite. Every `.task`
 * carries a template in its METADATA and the runtime wraps whatever it is given in that
 * template, which for an already-marked multi-turn prompt means one extra user turn around the
 * whole conversation and an empty model turn after it. So the session is given EMPTY templates
 * (see LlmEngine) and this object is the only thing that writes a marker.
 *
 * Which markers those are is the model's business and differs between files: Gemma 3 1B declares
 * `<start_of_turn>` / `<end_of_turn>`, Gemma 3n E2B declares `<ctrl99>` / `<ctrl100>`. They come
 * in from OnDeviceModel rather than being assumed here.
 */
object GemmaPrompt {

    data class Turn(val role: String, val text: String)

    /** The markers one model file declares for itself; see OnDeviceModel.turnStart. */
    data class Markers(val start: String, val end: String)

    /**
     * One prompt from [system] and [turns].
     *
     * A turn whose role is not "user" is written as `model`, which covers "assistant" and
     * anything else that reaches here; a system row in the middle of the list (2.5 chats have
     * one) is dropped, because its text has already been assembled into [system].
     */
    fun build(system: String?, turns: List<Turn>, markers: Markers): String {
        val body = StringBuilder()
        var systemPending = system?.takeIf { it.isNotBlank() }

        for (turn in turns) {
            if (turn.role == "system") continue
            val isUser = turn.role == "user"
            val text = if (isUser && systemPending != null) {
                val folded = systemPending + "\n\n" + turn.text
                systemPending = null
                folded
            } else {
                turn.text
            }
            body.append(markers.start).append(if (isUser) "user" else "model").append("\n")
                .append(text).append(markers.end).append("\n")
        }

        // Instructions with nothing to apply them to: a chat whose first turn is somehow not the
        // user's still has to carry them, or the model is answering with no idea who it is.
        if (systemPending != null) {
            body.insert(0, markers.start + "user\n" + systemPending + markers.end + "\n")
        }

        // The open model turn is the invitation to answer. Without it Gemma continues the
        // conversation as the user.
        body.append(markers.start).append("model\n")
        return body.toString()
    }

    /**
     * Drop whole turns from the front until the prompt fits, keeping the newest.
     *
     * Pairs, not messages: half an exchange left at the front of the history reads to the model
     * as a question it already answered, or an answer to a question that is not there. The
     * system text is never what gives way here - it has already been clamped to its own budget
     * by the time it arrives, and an app that drops the user's folder instructions to fit one
     * more old message has its priorities backwards.
     */
    fun trimToFit(
        system: String?,
        turns: List<Turn>,
        markers: Markers,
        limit: Int,
        countTokens: (String) -> Int
    ): Pair<String, Int> {
        var kept = turns
        while (true) {
            val prompt = build(system, kept, markers)
            val tokens = countTokens(prompt)
            // A count the runtime refused to give (-1) is not a reason to keep trimming.
            if (tokens < 0 || tokens <= limit || kept.size <= 1) return prompt to tokens
            kept = kept.drop(if (kept.size >= 2) 2 else 1)
        }
    }
}
