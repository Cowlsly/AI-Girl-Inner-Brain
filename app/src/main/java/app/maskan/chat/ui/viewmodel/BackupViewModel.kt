package app.maskan.chat.ui.viewmodel

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.maskan.chat.MaskanApplication
import app.maskan.chat.data.backup.ArchiveException
import app.maskan.chat.data.backup.ArchiveRefusal
import app.maskan.chat.data.backup.BackupCounts
import app.maskan.chat.data.backup.BackupFormat
import app.maskan.chat.data.backup.BackupHeader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Backup screen's state, both halves. Writing is [app.maskan.chat.data.backup.BackupWriter];
 * restoring is [app.maskan.chat.data.backup.BackupRestorer], which stages and commits but never
 * touches the live data - that happens at the next start. Nothing here touches a file.
 */
class BackupViewModel(private val app: MaskanApplication) : ViewModel() {

    enum class Status { LOADING, READY, WRITING, DONE, CANCELLED, FAILED, NO_PASSWORD }

    /**
     * NONE -> READING (the plaintext header) -> CONFIRM (what is about to be replaced) ->
     * PASSWORD -> RESTORING -> STAGED (the restart follows). REFUSED and FAILED end the flow with
     * a sentence; a wrong password goes back to PASSWORD with the field cleared, because that is
     * the one refusal worth retyping.
     */
    enum class RestoreStep { NONE, READING, CONFIRM, PASSWORD, RESTORING, STAGED, REFUSED, FAILED }

    data class RestoreState(
        val step: RestoreStep = RestoreStep.NONE,
        val uri: Uri? = null,
        val header: BackupHeader? = null,
        val password: String = "",
        val showPassword: Boolean = false,
        val refusal: ArchiveRefusal? = null,
        val wrongPassword: Boolean = false,
        val noPassword: Boolean = false
    )

    data class UiState(
        val status: Status = Status.LOADING,
        val counts: BackupCounts = BackupCounts(),
        val estimatedBytes: Long = 0L,
        val password: String = "",
        val showPassword: Boolean = false,
        val progress: Float = 0f,
        val savedName: String? = null,
        val restore: RestoreState = RestoreState()
    ) {
        val hasSomethingToSave: Boolean
            get() = counts.chats > 0 || counts.folders > 0 || counts.documents > 0 ||
                counts.apiKeys > 0
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var writeJob: Job? = null
    private var restoreJob: Job? = null

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

    // -- Backup ---------------------------------------------------------

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

    // -- Restore --------------------------------------------------------

    /** The file was chosen. Read its plaintext header - no password - and show it. */
    fun beginRestore(uri: Uri) {
        restoreJob?.cancel()
        refresh()
        _uiState.update { it.copy(restore = RestoreState(step = RestoreStep.READING, uri = uri)) }
        restoreJob = viewModelScope.launch {
            try {
                val result = app.backupReader.readHeader(uri)
                Log.i(TAG, "restore header: " + result.header.counts + " schema " +
                    result.header.schema + " format " + result.header.format)
                updateRestore { it.copy(step = RestoreStep.CONFIRM, header = result.header) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ArchiveException) {
                Log.w(TAG, "restore refused at the header: " + e.refusal + " (" + e.message + ")")
                updateRestore { it.copy(step = RestoreStep.REFUSED, refusal = e.refusal) }
            } catch (e: Throwable) {
                Log.e(TAG, "restore header failed", e)
                updateRestore { it.copy(step = RestoreStep.FAILED) }
            }
        }
    }

    fun confirmRestore() {
        updateRestore { if (it.step == RestoreStep.CONFIRM) it.copy(step = RestoreStep.PASSWORD) else it }
    }

    fun setRestorePassword(value: String) {
        updateRestore { it.copy(password = value, noPassword = false, wrongPassword = false) }
    }

    fun toggleShowRestorePassword() {
        updateRestore { it.copy(showPassword = !it.showPassword) }
    }

    fun restore() {
        val current = _uiState.value.restore
        val uri = current.uri ?: return
        if (current.password.isEmpty()) {
            updateRestore { it.copy(noPassword = true) }
            return
        }
        updateRestore { it.copy(step = RestoreStep.RESTORING, wrongPassword = false, noPassword = false) }
        restoreJob = viewModelScope.launch {
            try {
                val staged = app.backupRestorer.stage(uri, current.password)
                Log.i(TAG, "restore staged: " + staged.manifest.counts + " schema " +
                    staged.manifest.schema + "; restarting")
                updateRestore { it.copy(step = RestoreStep.STAGED, password = "") }
            } catch (e: CancellationException) {
                // The restorer wipes the stage in its own finally block, so "cancelled" is
                // already true of the phone by the time it is on screen.
                _uiState.update { it.copy(restore = RestoreState()) }
            } catch (e: ArchiveException) {
                Log.w(TAG, "restore refused: " + e.refusal + " (" + e.message + ")")
                if (e.refusal == ArchiveRefusal.WRONG_PASSWORD) {
                    updateRestore {
                        it.copy(step = RestoreStep.PASSWORD, password = "", wrongPassword = true)
                    }
                } else {
                    updateRestore { it.copy(step = RestoreStep.REFUSED, refusal = e.refusal) }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "restore failed", e)
                updateRestore { it.copy(step = RestoreStep.FAILED) }
            }
        }
    }

    /** Out of the flow, at any step before the restart. */
    fun cancelRestore() {
        if (_uiState.value.restore.step == RestoreStep.STAGED) return
        restoreJob?.cancel()
        _uiState.update { it.copy(restore = RestoreState()) }
    }

    private fun updateRestore(transform: (RestoreState) -> RestoreState) {
        _uiState.update { it.copy(restore = transform(it.restore)) }
    }

    companion object {
        private const val TAG = "MaskanBackup"
    }
}
