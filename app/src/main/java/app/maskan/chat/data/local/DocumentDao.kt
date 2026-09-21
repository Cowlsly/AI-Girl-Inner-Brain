package app.maskan.chat.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {

    @Query("SELECT * FROM documents WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun getForConversation(conversationId: Long): Flow<List<DocumentEntity>>

    /** One-shot read on the send path, the same reason FolderDao has one. */
    @Query("SELECT * FROM documents WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    suspend fun getForConversationOnce(conversationId: Long): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun getById(id: Long): DocumentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(document: DocumentEntity): Long

    /**
     * The notes and the count they describe, written together.
     *
     * One statement rather than two on purpose: these two columns ARE the resume state, and a
     * process killed between them would either re-run a chunk that is already summarised or skip
     * one that is not.
     */
    @Query("UPDATE documents SET notes = :notes, notesDone = :notesDone WHERE id = :id")
    suspend fun updateNotes(id: Long, notes: String?, notesDone: Int)

    @Query("UPDATE documents SET pageImageIds = :ids WHERE id = :id")
    suspend fun updatePageImageIds(id: Long, ids: String?)

    @Query("UPDATE documents SET attachedMessageId = :messageId WHERE id = :id")
    suspend fun updateAttachedMessage(id: Long, messageId: Long?)

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun delete(id: Long)
}
