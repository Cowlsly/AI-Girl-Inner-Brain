package app.maskan.chat.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {

    @Query("SELECT * FROM folders ORDER BY createdAt ASC")
    fun getAll(): Flow<List<FolderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(folder: FolderEntity): Long

    @Query("UPDATE folders SET name = :newName WHERE id = :id")
    suspend fun rename(id: Long, newName: String)

    @Query("UPDATE folders SET colorHex = :colorHex WHERE id = :id")
    suspend fun updateColor(id: Long, colorHex: String)

    /** One-shot read on the send path: the instructions must be the CURRENT ones, every time. */
    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun getById(id: Long): FolderEntity?

    @Query("UPDATE folders SET instructions = :text WHERE id = :id")
    suspend fun updateInstructions(id: Long, text: String?)

    @Query("UPDATE folders SET memory = :text WHERE id = :id")
    suspend fun updateMemory(id: Long, text: String?)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM conversations WHERE folderId = :folderId ORDER BY createdAt DESC")
    fun getConversationsInFolder(folderId: Long): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE folderId IS NULL ORDER BY createdAt DESC")
    fun getUnfiledConversations(): Flow<List<ConversationEntity>>
}
