package app.maskan.chat.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.maskan.chat.PhoenixActivity
import app.maskan.chat.R
import app.maskan.chat.data.backup.ArchiveRefusal
import app.maskan.chat.data.backup.BackupCounts
import app.maskan.chat.ui.theme.maskanColors
import app.maskan.chat.ui.viewmodel.BackupViewModel
import kotlinx.coroutines.delay

/**
 * Settings -> Backup. Its own screen and not a dialog: it has to say what is in the file, take a
 * password, and then do minutes of work with a cancel button that means it.
 *
 * The file goes wherever the user points the system picker, and comes back from wherever they
 * point it. Maskan never chooses a directory, which is also why neither half needs a storage
 * permission on any Android version.
 *
 * Restore takes over the whole screen while it runs: header first (no password), the
 * confirmation with the file's counts beside this phone's, the password after the user has said
 * yes, then the restart.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    viewModel: BackupViewModel,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val restoring = state.restore.step != BackupViewModel.RestoreStep.NONE

    // Counted when the screen is shown, not when the ViewModel was built. The ViewModel outlives
    // this screen, so init-time counts are whatever was true when the app was first opened - and
    // this screen exists to say what is in the app NOW.
    LaunchedEffect(Unit) { viewModel.refresh() }

    val createDocument = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(MIME_TYPE)
    ) { uri ->
        if (uri != null) viewModel.write(uri, uri.lastPathSegment)
    }

    // "*/*": a .mkb has no registered type, and a picker filtered to one would show nothing.
    val openDocument = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.beginRestore(uri)
    }

    // Back leaves the restore flow at any step before the restart, and does nothing after the
    // stage is committed - there is nothing to go back to at that point.
    BackHandler(enabled = restoring) {
        viewModel.cancelRestore()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (restoring) R.string.restore_section_title else R.string.backup_title
                        )
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.maskanColors.softLavender
                ),
                navigationIcon = {
                    if (state.restore.step != BackupViewModel.RestoreStep.STAGED) {
                        TextButton(onClick = {
                            if (restoring) viewModel.cancelRestore() else onNavigateBack()
                        }) {
                            Text(stringResource(R.string.about_back))
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            if (restoring) {
                RestoreFlow(state = state, viewModel = viewModel)
            } else {
                BackupSection(state = state, viewModel = viewModel) {
                    createDocument.launch(viewModel.suggestedFileName())
                }

                Spacer(modifier = Modifier.height(28.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = stringResource(R.string.restore_section_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.restore_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { openDocument.launch(arrayOf("*/*")) },
                    enabled = state.status != BackupViewModel.Status.WRITING,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.restore_choose))
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// -- Backup -------------------------------------------------------------

@Composable
private fun BackupSection(
    state: BackupViewModel.UiState,
    viewModel: BackupViewModel,
    onChooseLocation: () -> Unit
) {
    Text(
        text = stringResource(R.string.backup_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(20.dp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.backup_contents_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(10.dp))
            CountLines(state.counts)
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(
                    R.string.backup_size_about,
                    formatBytes(state.estimatedBytes)
                ),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.backup_excluded),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    Spacer(modifier = Modifier.height(20.dp))

    Text(
        text = stringResource(R.string.backup_keys_warning),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold
    )

    Spacer(modifier = Modifier.height(16.dp))

    PasswordField(
        value = state.password,
        onValueChange = viewModel::setPassword,
        show = state.showPassword,
        onToggleShow = viewModel::toggleShowPassword,
        enabled = state.status != BackupViewModel.Status.WRITING,
        isError = state.status == BackupViewModel.Status.NO_PASSWORD
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = stringResource(R.string.backup_password_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    if (state.status == BackupViewModel.Status.NO_PASSWORD) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.backup_password_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
    }

    Spacer(modifier = Modifier.height(24.dp))

    when (state.status) {
        BackupViewModel.Status.WRITING -> {
            Text(
                text = stringResource(R.string.backup_writing),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(
                onClick = viewModel::cancel,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.backup_cancel))
            }
        }

        else -> {
            Button(
                onClick = {
                    if (viewModel.readyToChooseLocation()) onChooseLocation()
                },
                enabled = state.status != BackupViewModel.Status.LOADING &&
                    state.hasSomethingToSave,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.backup_create))
            }
            if (!state.hasSomethingToSave &&
                state.status != BackupViewModel.Status.LOADING
            ) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.backup_nothing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    val resultText = when (state.status) {
        BackupViewModel.Status.DONE -> stringResource(R.string.backup_done)
        BackupViewModel.Status.CANCELLED -> stringResource(R.string.backup_cancelled)
        BackupViewModel.Status.FAILED -> stringResource(R.string.backup_failed)
        else -> null
    }
    if (resultText != null) {
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = resultText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.status == BackupViewModel.Status.DONE)
                    MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = viewModel::dismissResult) {
                Text(stringResource(R.string.backup_dismiss))
            }
        }
    }
}

// -- Restore ------------------------------------------------------------

@Composable
private fun RestoreFlow(
    state: BackupViewModel.UiState,
    viewModel: BackupViewModel
) {
    val restore = state.restore
    val context = LocalContext.current

    when (restore.step) {
        BackupViewModel.RestoreStep.READING -> {
            Text(
                text = stringResource(R.string.restore_reading),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        BackupViewModel.RestoreStep.CONFIRM -> {
            val header = restore.header ?: return
            val locale = LocalConfiguration.current.locales[0]
            val made = remember_dateFormat(locale, header.createdAtEpochMs)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.restore_confirm_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.restore_made, made, header.app.versionName),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.restore_file_has),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    CountLines(header.counts)
                    Text(
                        text = stringResource(
                            if (header.keysIncluded) R.string.restore_keys_yes
                            else R.string.restore_keys_no
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.restore_phone_has),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    CountLines(state.counts)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.restore_replaces),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = viewModel::confirmRestore,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.restore_confirm))
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = viewModel::cancelRestore,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.backup_cancel))
            }
        }

        BackupViewModel.RestoreStep.PASSWORD -> {
            Text(
                text = stringResource(R.string.restore_password_prompt),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(12.dp))
            PasswordField(
                value = restore.password,
                onValueChange = viewModel::setRestorePassword,
                show = restore.showPassword,
                onToggleShow = viewModel::toggleShowRestorePassword,
                enabled = true,
                isError = restore.wrongPassword || restore.noPassword
            )
            if (restore.wrongPassword || restore.noPassword) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        if (restore.wrongPassword) R.string.restore_wrong_password
                        else R.string.backup_password_empty
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = viewModel::restore,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.restore_start))
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = viewModel::cancelRestore,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.backup_cancel))
            }
        }

        BackupViewModel.RestoreStep.RESTORING -> {
            Text(
                text = stringResource(R.string.restore_working),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(
                onClick = viewModel::cancelRestore,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.backup_cancel))
            }
        }

        BackupViewModel.RestoreStep.STAGED -> {
            Text(
                text = stringResource(R.string.restore_done),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            // The sentence above is on screen BEFORE the process ends, so if the relaunch is
            // refused the user has already been told what to do.
            LaunchedEffect(Unit) {
                delay(1500)
                PhoenixActivity.restart(context)
            }
        }

        BackupViewModel.RestoreStep.REFUSED, BackupViewModel.RestoreStep.FAILED -> {
            Text(
                text = stringResource(
                    when (restore.refusal) {
                        ArchiveRefusal.NOT_A_BACKUP -> R.string.restore_refused_not_backup
                        ArchiveRefusal.NEWER_FORMAT, ArchiveRefusal.NEWER_SCHEMA ->
                            R.string.restore_refused_newer
                        ArchiveRefusal.WRONG_PASSWORD -> R.string.restore_wrong_password
                        ArchiveRefusal.DAMAGED -> R.string.restore_refused_damaged
                        null -> R.string.restore_failed
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(20.dp))
            OutlinedButton(
                onClick = viewModel::cancelRestore,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.backup_dismiss))
            }
        }

        BackupViewModel.RestoreStep.NONE -> Unit
    }
}

// -- Shared pieces --------------------------------------------------------

@Composable
private fun CountLines(counts: BackupCounts) {
    CountLine(stringResource(R.string.backup_count_chats, counts.chats))
    CountLine(stringResource(R.string.backup_count_messages, counts.messages))
    CountLine(stringResource(R.string.backup_count_folders, counts.folders))
    CountLine(stringResource(R.string.backup_count_documents, counts.documents))
    CountLine(stringResource(R.string.backup_count_keys, counts.apiKeys))
}

@Composable
private fun CountLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(vertical = 2.dp)
    )
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    show: Boolean,
    onToggleShow: () -> Unit,
    enabled: Boolean,
    isError: Boolean
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.backup_password_label)) },
        singleLine = true,
        enabled = enabled,
        isError = isError,
        visualTransformation = if (show) VisualTransformation.None
        else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done
        ),
        trailingIcon = {
            TextButton(onClick = onToggleShow) {
                Text(
                    stringResource(
                        if (show) R.string.backup_hide_password
                        else R.string.backup_show_password
                    )
                )
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
}

/** The archive's date in the UI language, medium date and short time. */
@Suppress("FunctionName")
private fun remember_dateFormat(locale: java.util.Locale, epochMs: Long): String =
    java.text.DateFormat.getDateTimeInstance(
        java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT, locale
    ).format(java.util.Date(epochMs))

/**
 * An honest number rather than a round one: the screen is telling somebody what they are about to
 * carry, and "about 2 MB" for a 40 MB file is the kind of small lie that ends in a full disk.
 */
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L ->
        String.format(java.util.Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L ->
        String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
    else -> bytes.toString() + " B"
}

/**
 * Deliberately not `application/zip`, although the container is one: DocumentsUI appends an
 * extension derived from the MIME type when the name it is given does not already match, and a
 * zip type would turn `maskan-backup-2026-09-22.mkb` into `...mkb.zip`.
 */
private const val MIME_TYPE = "application/octet-stream"
