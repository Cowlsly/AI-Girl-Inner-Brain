package app.maskan.chat.util

/**
 * How many tokens a piece of text costs, near enough to warn someone with.
 *
 * Locally, deliberately: asking a provider to count would cost a request per keystroke, and the
 * number is only ever used to say "this is getting long". Latin-ish text runs about four
 * characters to the token; Arabic and Thai are far denser in tokens per character on every
 * tokenizer the app talks to, so they are counted at 2.5 - which is why a count by Unicode block
 * and not a flat chars/4 is worth the twenty lines.
 *
 * Shared by the request-assembly clamp and (session 2) the editor's meter, so the number the
 * user is shown is the number the clamp enforces.
 */
object TokenEstimate {

    /** Arabic, Arabic Supplement/Extended, Arabic Presentation Forms A and B. */
    private fun isArabic(c: Char): Boolean {
        val code = c.code
        return code in 0x0600..0x06FF || code in 0x0750..0x077F ||
            code in 0x08A0..0x08FF || code in 0xFB50..0xFDFF || code in 0xFE70..0xFEFF
    }

    private fun isThai(c: Char): Boolean = c.code in 0x0E00..0x0E7F

    fun of(text: String?): Int {
        if (text.isNullOrEmpty()) return 0
        var dense = 0
        var plain = 0
        for (c in text) {
            if (isArabic(c) || isThai(c)) dense++ else plain++
        }
        val denseTokens = Math.ceil(dense / 2.5).toInt()
        val plainTokens = Math.ceil(plain / 4.0).toInt()
        return denseTokens + plainTokens
    }

    /**
     * Cut [text] to at most [maxTokens], on a paragraph boundary where one is near enough.
     *
     * A 4k-context local model given a 3,000-token instructions file plus history has the
     * history silently truncated by the server, and the user is left wondering why the model
     * forgot the last three messages. Better to cut the instructions, visibly, at a boundary we
     * choose.
     */
    fun clamp(text: String, maxTokens: Int): String {
        if (of(text) <= maxTokens) return text
        // Tokens are estimated from characters, so invert the estimate to get a character budget.
        var budget = text.length
        while (budget > 0 && of(text.take(budget)) > maxTokens) {
            budget = (budget * 0.9).toInt()
        }
        val cut = text.take(budget)
        val boundary = cut.lastIndexOf("\n\n")
        return if (boundary > budget / 2) cut.take(boundary) else cut
    }
}
