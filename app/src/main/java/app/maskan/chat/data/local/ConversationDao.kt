package app.maskan.chat.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for conversations.
 * Exposes Flow-based queries so the UI can reactively observe changes.
 */
@Dao
interface ConversationDao {

    @Query("SELECT * FROM conversations ORDER BY createdAt DESC")
    fun getAllConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getConversationById(id: Long): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: ConversationEntity): Long

    @Delete
    suspend fun deleteConversation(conversation: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversationById(id: Long)

    @Query("UPDATE conversations SET title = :title WHERE id = :id")
    suspend fun updateConversationTitle(id: Long, title: String)

    @Query("UPDATE conversations SET systemPromptId = :systemPromptId, dialectId = :dialectId WHERE id = :id")
    suspend fun updateSystemPrompt(id: Long, systemPromptId: String?, dialectId: String?)

    @Query("UPDATE conversations SET folderId = :folderId WHERE id = :id")
    suspend fun moveToFolder(id: Long, folderId: Long?)

    /**
     * Move a conversation onto a different model WITHOUT touching its provider. A chat freezes
     * its modelId at creation, so this is what un-sticks an old chat from a model the provider
     * has since retired. updateProvider() rewrites providerId too and is wrong for that.
     */
    @Query("UPDATE conversations SET modelId = :modelId WHERE id = :id")
    suspend fun updateConversationModel(id: Long, modelId: String?)

    @Query("UPDATE conversations SET providerId = :providerId, modelId = :modelId WHERE id = :id")
    suspend fun updateProvider(id: Long, providerId: String, modelId: String?)

    /**
     * Conversations that were opened and left without a word: no message rows at all, no
     * document read into them, and still carrying the default title.
     *
     * All three conditions, deliberately narrow. A chat the user typed in has rows; a chat they
     * renamed has a different title; a chat they dropped a PDF into has a document. Anything
     * else is a row the FAB created and nobody used, and there are installs carrying a dozen of
     * them from 2.5.
     */
    @Query(
        "SELECT id FROM conversations WHERE title = :defaultTitle " +
            "AND NOT EXISTS (SELECT 1 FROM messages WHERE messages.conversationId = conversations.id) " +
            "AND NOT EXISTS (SELECT 1 FROM documents WHERE documents.conversationId = conversations.id)"
    )
    suspend fun getDiscardableConversationIds(defaultTitle: String): List<Long>

    @Query("SELECT * FROM conversations WHERE title LIKE '%' || :query || '%' ORDER BY createdAt DESC")
    suspend fun searchConversationsByTitle(query: String): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE id IN (:ids) ORDER BY createdAt DESC")
    suspend fun getConversationsByIds(ids: List<Long>): List<ConversationEntity>
}

