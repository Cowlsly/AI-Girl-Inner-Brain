package app.maskan.chat.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A file the user attached to one conversation, and what the app has understood of it so far.
 *
 * It lives in its own table rather than on the message row because it is not a message: the text
 * of a 30-page contract must never be pasted into the chat history, or every following request
 * re-sends the whole contract. The message the user sent stays a sentence; this row is what the
 * repository consults when a question needs the document.
 *
 * Encrypted with everything else - the database is SQLCipher - and deleted with the conversation
 * by the foreign key.
 */
@Entity(
    tableName = "documents",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("conversationId")]
)
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val conversationId: Long,
    /** The user message this file arrived with, so the card can sit under it. */
    val attachedMessageId: Long? = null,
    val name: String,
    /** DocumentExtract.KIND_*. */
    val kind: String,
    /** 0 where the format has no pages, or does not record how many. */
    val pages: Int = 0,
    val tokens: Int = 0,
    /** The whole extracted text. Chunks are cut from this on demand, never stored. */
    val text: String,
    /** The notes pass's output so far: one block per summarised chunk. */
    val notes: String? = null,
    /**
     * How many chunks have been summarised. With [chunkTokens] this is the entire resume state:
     * re-cut the text at the same size, skip this many, carry on. Nothing is duplicated because
     * the count is written in the same transaction as the notes it counts.
     */
    val notesDone: Int = 0,
    val chunkCount: Int = 0,
    /**
     * The size the chunks were cut at. Persisted because it is not a constant: a local provider
     * gets 1,000-token chunks and a cloud one 1,500, and a chat moved between them mid-pass
     * would otherwise resume against a different set of chunks than it counted.
     */
    val chunkTokens: Int = 0,
    /**
     * Message ids of the pages sent as pictures, comma-separated, for a scanned PDF.
     *
     * The ordinary photo carry keeps the two most recent pictures, which would drop page 1 of a
     * three-page scan - the page that usually matters. These ids travel together as a set.
     */
    val pageImageIds: String? = null,
    /** DocumentExtract.WARN_*, shown on the card as a sentence. */
    val warning: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    /** True while the notes pass has chunks left. Questions still work; they say so. */
    val partial: Boolean get() = chunkCount > 0 && notesDone < chunkCount

    /** A scan: pictures went instead of text. */
    val isPages: Boolean get() = !pageImageIds.isNullOrBlank()

    fun pageIds(): List<Long> =
        pageImageIds?.split(",")?.mapNotNull { it.trim().toLongOrNull() }.orEmpty()
}
