package app.maskan.chat.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.maskan.chat.data.repository.ChatRepository
import app.maskan.chat.data.repository.PreferenceRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Which of a project's two files a screen is looking at. A navigation argument, so a plain string.
 */
object ProjectFile {
    const val INSTRUCTIONS = "instructions"
    const val MEMORY = "memory"
}

data class ProjectFilesUiState(
    val folderId: Long = ProjectFilesViewModel.GLOBAL_SCOPE,
    val folderName: String = "",
    val instructions: String = "",
    val memory: String = "",
    /**
     * False until the read has come back. An editor must not open on an empty draft and then
     * auto-save it over the text that was still loading.
     */
    val loaded: Boolean = false
)

/**
 * The two files behind a folder, and the one shared file behind no folder.
 *
 * Reads come from the folder FLOW, not a one-shot: the editor and the folder screen are separate
 * destinations with separate instances of this, so a save in the editor has to reach the screen
 * the user pops back to without either of them knowing about the other.
 */
class ProjectFilesViewModel(
    private val chatRepository: ChatRepository,
    private val preferenceRepository: PreferenceRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProjectFilesUiState())
    val uiState: StateFlow<ProjectFilesUiState> = _uiState.asStateFlow()

    private var watchJob: Job? = null

    fun observe(folderId: Long) {
        watchJob?.cancel()
        if (folderId == GLOBAL_SCOPE) {
            // Shared memory lives in the encrypted preferences, which have no flow. One read is
            // enough: the only writer is this screen.
            _uiState.value = ProjectFilesUiState(
                folderId = folderId,
                memory = preferenceRepository.getGlobalMemory().orEmpty(),
                loaded = true
            )
            return
        }
        watchJob = viewModelScope.launch {
            chatRepository.getAllFolders().collect { folders ->
                val folder = folders.find { it.id == folderId }
                _uiState.value = ProjectFilesUiState(
                    folderId = folderId,
                    folderName = folder?.name.orEmpty(),
                    instructions = folder?.instructions.orEmpty(),
                    memory = folder?.memory.orEmpty(),
                    loaded = true
                )
            }
        }
    }

    /**
     * Write one file.
     *
     * Blank is stored as nothing at all, which is what makes "empty both files and the folder
     * behaves like a folder that was never configured" true rather than nearly true: the
     * assembly in ChatRepository takes its 2.5.0 early return on null, not on an empty string.
     */
    fun save(file: String, text: String) {
        val folderId = _uiState.value.folderId
        if (folderId == GLOBAL_SCOPE) {
            // Written here and not on a coroutine: this is a preference, the write is already
            // asynchronous inside SharedPreferences, and the screen behind this one re-reads the
            // count the moment the editor is popped. Launching it first made that a race, which
            // the device won by showing a stale count.
            preferenceRepository.setGlobalMemory(text)
            _uiState.value = _uiState.value.copy(memory = text)
            return
        }
        viewModelScope.launch {
            if (file == ProjectFile.INSTRUCTIONS) {
                chatRepository.updateFolderInstructions(folderId, text)
            } else {
                chatRepository.updateFolderMemory(folderId, text)
            }
        }
    }

    companion object {
        /**
         * The shared memory file, which belongs to no folder. Room autogenerates folder ids from
         * 1, so 0 can never collide with a real folder.
         */
        const val GLOBAL_SCOPE = 0L
    }
}
