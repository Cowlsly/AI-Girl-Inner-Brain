package app.maskan.chat.util

/**
 * Cut a document into pieces, and pick the pieces a question is about.
 *
 * No embeddings, no index, no server: keyword overlap over the chunks the app already holds.
 * It is a weaker retriever than a vector store and it is the right one here - a vector store
 * means a model to run or a service to call, and the whole point of this app is that neither
 * exists. The notes pass carries the meaning; this only has to get the user near the right page.
 *
 * Chunking is DETERMINISTIC from (text, maxTokens). That is load-bearing: the notes pass stores
 * how many chunks it has summarised, not the chunks themselves, so resuming after the process
 * died re-cuts the same document into exactly the same pieces and continues at the right one.
 * Which is why the size it was cut at is persisted alongside the count.
 */
object DocumentChunks {

    /** Chunk size on a cloud provider. */
    const val CHUNK_TOKENS = 1500

    /** Chunk size on a local one: a 4k-context Ollama model has room for little more. */
    const val CHUNK_TOKENS_LOCAL = 1000

    /** Below this, a document is pasted into the message whole and none of this runs. */
    const val INLINE_CEILING_TOKENS = 6000

    /** How many chunks go with a question. */
    const val CHUNKS_PER_QUESTION = 2

    /**
     * Split on paragraph boundaries, filling each chunk as far as it will go.
     *
     * A paragraph bigger than the whole budget is split on line breaks and then, if it is still
     * too big (a spreadsheet row, a PDF page with no breaks at all), cut by characters - a hard
     * cut is ugly, but a chunk that does not fit the request is worse.
     */
    fun chunk(text: String, maxTokens: Int): List<String> {
        val chunks = ArrayList<String>()
        val current = StringBuilder()
        var currentTokens = 0

        fun flush() {
            if (current.isNotEmpty()) {
                chunks.add(current.toString().trim())
                current.setLength(0)
                currentTokens = 0
            }
        }

        for (paragraph in splitToFitting(text, maxTokens)) {
            val tokens = TokenEstimate.of(paragraph)
            if (currentTokens > 0 && currentTokens + tokens > maxTokens) flush()
            if (current.isNotEmpty()) current.append("\n\n")
            current.append(paragraph)
            currentTokens += tokens
        }
        flush()
        return chunks.filter { it.isNotBlank() }
    }

    /** Paragraphs, with any single paragraph over budget broken down until it fits. */
    private fun splitToFitting(text: String, maxTokens: Int): List<String> {
        val out = ArrayList<String>()
        for (paragraph in text.split("\n\n")) {
            if (paragraph.isBlank()) continue
            if (TokenEstimate.of(paragraph) <= maxTokens) {
                out.add(paragraph.trim())
                continue
            }
            for (line in paragraph.split("\n")) {
                if (line.isBlank()) continue
                if (TokenEstimate.of(line) <= maxTokens) {
                    out.add(line.trim())
                    continue
                }
                // Tokens are estimated from characters, so a character budget is a safe
                // over-estimate of where the token budget runs out.
                val step = (maxTokens * 2).coerceAtLeast(200)
                var i = 0
                while (i < line.length) {
                    out.add(line.substring(i, minOf(i + step, line.length)))
                    i += step
                }
            }
        }
        return out
    }

    /**
     * The indexes of the [n] chunks that best match [question], best first.
     *
     * Score is the number of DISTINCT question words a chunk contains, with a small bonus for
     * repeats. Distinct-first on purpose: a chunk that says "cancellation" forty times is about
     * cancellation, but a chunk that says "cancellation", "policy" and "refund" once each is the
     * one that answers "what is the cancellation policy for refunds".
     */
    fun rank(question: String, chunks: List<String>, n: Int = CHUNKS_PER_QUESTION): List<Int> {
        if (chunks.isEmpty()) return emptyList()
        if (chunks.size <= n) return chunks.indices.toList()

        val needles = needlesOf(question)
        if (needles.isEmpty()) return (0 until n).toList()

        val scored = chunks.mapIndexed { index, chunk ->
            val hay = fold(chunk)
            var score = 0.0
            for (needle in needles) {
                val count = countOf(hay, needle)
                if (count > 0) score += 1.0 + minOf(count, 5) * 0.1
            }
            index to score
        }
        return scored
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .take(n)
            .map { it.first }
            .ifEmpty { (0 until n).toList() }
    }

    /**
     * What to look for: the question's own words, or - for a script that does not put spaces
     * between them - slices of it.
     *
     * Thai writes a sentence as one run of characters, so word overlap finds nothing at all.
     * Four-character slices are a crude stand-in and they do match a Thai noun where splitting
     * on spaces matches nothing. Arabic and English go the ordinary way.
     */
    private fun needlesOf(question: String): List<String> {
        val folded = fold(question)
        val words = folded
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 && it !in STOP_WORDS }

        val hasUnsplittable = words.any { it.length > 20 }
        if (words.size >= 2 && !hasUnsplittable) return words.distinct().take(12)

        val slices = ArrayList<String>()
        val compact = folded.filter { !it.isWhitespace() }
        var i = 0
        while (i + 4 <= compact.length && slices.size < 24) {
            slices.add(compact.substring(i, i + 4))
            i += 2
        }
        return if (slices.isEmpty()) words.distinct() else slices
    }

    private fun countOf(hay: String, needle: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val at = hay.indexOf(needle, from)
            if (at < 0) break
            count++
            from = at + needle.length
        }
        return count
    }

    /**
     * Lowercase, and strip what Arabic readers do not type: the short vowels a formal document
     * carries and the tatweel used to stretch a word across a line. Without this, a question
     * about "الإلغاء" misses every occurrence in a vocalised contract.
     */
    private fun fold(text: String): String {
        val out = StringBuilder(text.length)
        for (c in text.lowercase()) {
            val code = c.code
            val isHaraka = code in 0x064B..0x0652 || code == 0x0670
            val isTatweel = code == 0x0640
            if (isHaraka || isTatweel) continue
            out.append(
                when (c) {
                    'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                    'ة' -> 'ه'
                    'ى' -> 'ي'
                    else -> c
                }
            )
        }
        return out.toString()
    }

    private val STOP_WORDS = setOf(
        "the", "and", "for", "are", "was", "were", "what", "which", "who", "whom", "this",
        "that", "these", "those", "with", "from", "have", "has", "had", "can", "could",
        "would", "should", "about", "into", "does", "did", "you", "your", "their", "there",
        "here", "when", "where", "how", "why", "not", "but", "all", "any", "its",
        "في", "من", "على", "الي",
        "ما", "هل", "كيف", "هذا",
        "هذه", "ذلك", "التي",
        "الذي", "عن", "مع", "كل",
        "اي", "لماذا", "متى",
        "اين", "لل", "او"
    )
}
