package app.maskan.chat.navigation

/**
 * Type-safe navigation routes for the app.
 * Using string-based routes with arguments for MVP simplicity.
 */
object Routes {
    const val CONVERSATION_LIST = "conversation_list"
    const val CHAT = "chat/{conversationId}"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
    const val WELCOME = "welcome"
    const val PRIVACY_INTRO = "privacy_intro"
    const val PRIVACY = "privacy"

    /** Settings -> Backup. Its own screen: it states what is in the file, takes a password and
     *  then does real work with a cancel that means it. */
    const val BACKUP = "backup"

    /** A folder seen as a project: its instructions and memory files. */
    const val FOLDER = "folder/{folderId}"

    /**
     * One project file, open for editing. `folderId` 0 is the SHARED memory file, which belongs
     * to no folder - Room autogenerates folder ids from 1, so the two can never collide.
     */
    const val PROJECT_FILE = "project_file/{folderId}/{file}"

    fun chatRoute(conversationId: Long) = "chat/$conversationId"

    fun folderRoute(folderId: Long) = "folder/$folderId"

    fun projectFileRoute(folderId: Long, file: String) = "project_file/$folderId/$file"
}
