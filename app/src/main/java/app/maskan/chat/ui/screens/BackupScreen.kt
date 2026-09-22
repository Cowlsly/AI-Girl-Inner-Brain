package app.maskan.chat.ui.screens

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.maskan.chat.R
import app.maskan.chat.ui.theme.maskanColors
import app.maskan.chat.ui.viewmodel.BackupViewModel

/**
 * Settings -> Backup. Its own screen and not a dialog: it has to say what is in the file, take a
 * password, and then do minutes of work with a cancel button that means it.
 *
 * The file goes wherever the user points the system picker. Maskan never chooses a directory,
 * which is also why this feature needs no storage permission on any Android version.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    viewModel: BackupViewModel,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()

    val createDocument = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(MIME_TYPE)
    ) { uri ->
        if (uri != null) viewModel.write(uri, uri.lastPathSegment)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.backup_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.maskanColors.softLavender
                ),
                navigationIcon = {
                    TextButton(onClick = onNavigateBack) {
                        Text(stringResource(R.string.about_back))
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
                    CountLine(stringResource(R.string.backup_count_chats, state.counts.chats))
                    CountLine(stringResource(R.string.backup_count_messages, state.counts.messages))
                    CountLine(stringResource(R.string.backup_count_folders, state.counts.folders))
                    CountLine(stringResource(R.string.backup_count_documents, state.counts.documents))
                    CountLine(stringResource(R.string.backup_count_keys, state.counts.apiKeys))
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

            OutlinedTextField(
                value = state.password,
                onValueChange = viewModel::setPassword,
                label = { Text(stringResource(R.string.backup_password_label)) },
                singleLine = true,
                enabled = state.status != BackupViewModel.Status.WRITING,
                isError = state.status == BackupViewModel.Status.NO_PASSWORD,
                visualTransformation = if (state.showPassword) VisualTransformation.None
                else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                trailingIcon = {
                    TextButton(onClick = viewModel::toggleShowPassword) {
                        Text(
                            stringResource(
                                if (state.showPassword) R.string.backup_hide_password
                                else R.string.backup_show_password
                            )
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
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
                            if (viewModel.readyToChooseLocation()) {
                                createDocument.launch(viewModel.suggestedFileName())
                            }
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

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun CountLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(vertical = 2.dp)
    )
}

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
