package app.maskan.chat.ui.viewmodel

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.maskan.chat.MaskanApplication
import app.maskan.chat.data.backup.BackupCounts
import app.maskan.chat.data.backup.BackupFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Backup screen's state. The writing itself is [app.maskan.chat.data.backup.BackupWriter] and
 * runs on Dispatchers.IO; nothing here touches a file.
 */
class BackupViewModel(private val app: MaskanApplication) : ViewModel() {

    enum class Status { LOADING, READY, WRITING, DONE, CANCELLED, FAILED, NO_PASSWORD }

    data class UiState(
        val status: Status = Status.LOADING,
        val counts: BackupCounts = BackupCounts(),
        val estimatedBytes: Long = 0L,
        val password: String = "",
        val showPassword: Boolean = false,
        val progress: Float = 0f,
        val savedName: String? = null
    ) {
        val hasSomethingToSave: Boolean
            get() = counts.chats > 0 || counts.folders > 0 || counts.documents > 0 ||
                counts.apiKeys > 0
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var writeJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val inventory = runCatching { app.backupWriter.inventory() }.getOrNull()
            _uiState.update {
                if (inventory == null) it.copy(status = Status.FAILED)
                else it.copy(
                    status = Status.READY,
                    counts = inventory.counts,
                    estimatedBytes = inventory.estimatedBytes
                )
            }
        }
    }

    fun setPassword(value: String) {
        _uiState.update {
            it.copy(
                password = value,
                status = if (it.status == Status.NO_PASSWORD) Status.READY else it.status
            )
        }
    }

    fun toggleShowPassword() {
        _uiState.update { it.copy(showPassword = !it.showPassword) }
    }

    /** True when the picker may be opened. Refusing an empty password here is what keeps the
     *  system from creating a document we are then going to have to delete again. */
    fun readyToChooseLocation(): Boolean {
        if (_uiState.value.password.isEmpty()) {
            _uiState.update { it.copy(status = Status.NO_PASSWORD) }
            return false
        }
        return true
    }

    fun suggestedFileName(): String = BackupFormat.suggestedFileName(System.currentTimeMillis())

    fun write(uri: Uri, displayName: String?) {
        val password = _uiState.value.password
        if (password.isEmpty()) {
            _uiState.update { it.copy(status = Status.NO_PASSWORD) }
            return
        }
        _uiState.update { it.copy(status = Status.WRITING, progress = 0f) }
        writeJob = viewModelScope.launch {
            try {
                val header = app.backupWriter.write(uri, password) { progress ->
                    _uiState.update { it.copy(progress = progress) }
                }
                Log.i(TAG, "backup written: " + header.counts + " schema " + header.schema)
                _uiState.update {
                    it.copy(status = Status.DONE, progress = 1f, savedName = displayName)
                }
            } catch (e: CancellationException) {
                // The writer deletes the half-written document in its own finally block before
                // this is reached, so by the time the screen says "cancelled" it is already true.
                _uiState.update { it.copy(status = Status.CANCELLED, progress = 0f) }
            } catch (e: Throwable) {
                Log.e(TAG, "backup failed", e)
                _uiState.update { it.copy(status = Status.FAILED, progress = 0f) }
            }
        }
    }

    fun cancel() {
        writeJob?.cancel()
    }

    /** Back to a state where the button can be pressed again, after a result was shown. */
    fun dismissResult() {
        _uiState.update { it.copy(status = Status.READY, progress = 0f, savedName = null) }
    }

    companion object {
        private const val TAG = "MaskanBackup"
    }
}
