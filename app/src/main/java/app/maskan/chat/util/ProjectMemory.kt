package app.maskan.chat.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Remember this" - the only way a fact ever gets into a project's memory file.
 *
 * Nothing in Maskan writes to memory on its own. The user says so, in their own language, and
 * the editor is opened on the result: the write is visible in the same breath as the asking,
 * which is the whole reason there is no silent background memory here.
 *
 * Matching is deliberately anchored to the START of the message. "Remember that our office is in
 * Jabal Amman" is an instruction; "do you remember what I said about the office?" is a question,
 * and a question must still reach the model.
 */
object ProjectMemory {

    /**
     * The opening of a "remember this" message, in the three UI languages.
     *
     * Arabic carries optional diacritics (the shadda in `تذكّر` is written by some keyboards and
     * not others) and an optional `أن` / `إن` / `بأن`, so those are optional in the pattern rather
     * than stripped beforehand - stripping would make the fact's own text harder to cut cleanly.
     * Thai puts no space between the trigger and the fact, so none is required.
     */
    private val TRIGGER = Regex(
        "^\\s*(?:" +
            "remember(?:\\s+(?:that|this))?\\s*[:,]?\\s+" +
            "|" +
            "تذكّ?ري?\\s*(?:ب?[أإا]نّ?)?\\s*[:،]?\\s*" +
            "|" +
            "(?:จำไว้ว่า|จำว่า|จดจำว่า)\\s*" +
            ")",
        RegexOption.IGNORE_CASE
    )

    /**
     * The fact inside a "remember this" message, or null when this is an ordinary message.
     *
     * Null for a bare trigger with nothing after it too: "remember" on its own is not a fact, and
     * appending an empty line to the file would be worse than doing nothing.
     */
    fun factOrNull(message: String): String? {
        val match = TRIGGER.find(message) ?: return null
        val fact = message.substring(match.range.last + 1).trim()
        return fact.takeIf { it.isNotBlank() }
    }

    /**
     * One line, dated, the way plan 2.1 asks for it: `- 2026-09-21: the office is in Jabal Amman`.
     *
     * The date is ISO and in Latin digits regardless of UI language - the file is meant to be
     * exported, read on another phone and diffed by eye, and Eastern Arabic numerals in a file
     * that travels are a needless surprise.
     */
    fun line(fact: String, at: Long = System.currentTimeMillis()): String =
        "- " + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(at)) + ": " + fact.trim()

    /** [existing] with one more remembered line at the end, and no stray blank lines. */
    fun append(existing: String?, fact: String, at: Long = System.currentTimeMillis()): String {
        val base = existing?.trimEnd().orEmpty()
        val line = line(fact, at)
        return if (base.isEmpty()) line else base + "\n" + line
    }
}
