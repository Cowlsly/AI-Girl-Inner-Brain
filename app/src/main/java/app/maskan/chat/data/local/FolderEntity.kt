package app.maskan.chat.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A folder is a project. [instructions] and [memory] are what makes it one.
 *
 * Both are plain text, both are optional, and neither is a file on disk: they live in the
 * encrypted database like everything else, and are only PRESENTED as two files
 * (instructions.md, memory.md) in the editor. Nothing readable is ever written to the
 * filesystem.
 *
 * They are sent with every request made from a chat inside this folder, assembled fresh each
 * time (ChatRepository.projectSystemText). That is the whole point: change the text and the
 * conversation that started last week obeys the new text on its next message.
 */
@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val colorHex: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** Who I am, how to answer, what this project is. Written by the user. */
    val instructions: String? = null,
    /** Facts the model was told to keep. Appended on request, editable by the user. */
    val memory: String? = null
)
