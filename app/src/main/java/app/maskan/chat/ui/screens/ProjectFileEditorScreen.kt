package app.maskan.chat.ui.screens

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import app.maskan.chat.R
import app.maskan.chat.ui.theme.maskanColors
import app.maskan.chat.ui.viewmodel.ProjectFile
import app.maskan.chat.ui.viewmodel.ProjectFilesViewModel
import app.maskan.chat.util.TokenEstimate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One project file, open for writing.
 *
 * A plain text field, not a Markdown editor: the text is instructions to a model, and a toolbar
 * that reformats while it is being written gets in the way of someone typing Arabic prose.
 * Markdown is rendered in the Preview tab, where it is a check rather than an interference.
 *
 * The file saves when the screen is left, not behind a Save button. These are presented as files;
 * a file you edited and closed should be the file you get back, and the alternative is a
 * "discard changes?" dialog on the way out of every visit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectFileEditorScreen(
    viewModel: ProjectFilesViewModel,
    folderId: Long,
    file: String,
    onNavigateBack: () -> Unit
) {
    LaunchedEffect(folderId) { viewModel.observe(folderId) }
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val isInstructions = file == ProjectFile.INSTRUCTIONS
    val isSharedMemory = folderId == ProjectFilesViewModel.GLOBAL_SCOPE

    // Null until the stored text has arrived. Editing a draft that has not loaded yet, and then
    // auto-saving it on the way out, is how an editor eats a file.
    var draft by remember(folderId, file) { mutableStateOf<String?>(null) }
    LaunchedEffect(state.loaded, folderId, file) {
        if (state.loaded && draft == null) {
            draft = if (isInstructions) state.instructions else state.memory
        }
    }

    var monospace by rememberSaveable { mutableStateOf(false) }
    var previewing by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmImport by remember { mutableStateOf(false) }

    // onDispose captures its lambda once, so the draft has to be read through a holder that is
    // kept current - otherwise leaving the screen saves whatever was in the field on the first
    // frame.
    val latestDraft = rememberUpdatedState(draft)
    DisposableEffect(folderId, file) {
        onDispose { latestDraft.value?.let { viewModel.save(file, it) } }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use {
                            it.bufferedReader().readText()
                        }
                    }.getOrNull()
                }
                if (text == null) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.project_file_import_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    draft = text
                }
            }
        }
    }

    val title = when {
        isSharedMemory -> stringResource(R.string.global_memory_label)
        isInstructions -> stringResource(R.string.project_instructions)
        else -> stringResource(R.string.project_memory)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(text = title, style = MaterialTheme.typography.titleMedium)
                        if (state.folderName.isNotEmpty()) {
                            Text(
                                text = state.folderName,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    textDirection = TextDirection.Content
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back_button)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { monospace = !monospace }) {
                        Icon(
                            imageVector = Icons.Default.Code,
                            contentDescription = stringResource(R.string.project_editor_monospace),
                            tint = if (monospace) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(imageVector = Icons.Default.MoreVert, contentDescription = null)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.project_file_export)) },
                            onClick = {
                                menuOpen = false
                                exportProjectFile(
                                    context = context,
                                    fileName = exportFileName(state.folderName, file, isSharedMemory),
                                    text = draft.orEmpty()
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.project_file_import)) },
                            onClick = {
                                menuOpen = false
                                if (draft.orEmpty().isBlank()) {
                                    importLauncher.launch(IMPORT_MIME_TYPES)
                                } else {
                                    confirmImport = true
                                }
                            }
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.maskanColors.warmPeach
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding()
        ) {
            TabRow(selectedTabIndex = if (previewing) 1 else 0) {
                Tab(
                    selected = !previewing,
                    onClick = { previewing = false },
                    text = { Text(stringResource(R.string.project_editor_edit)) }
                )
                Tab(
                    selected = previewing,
                    onClick = { previewing = true },
                    text = { Text(stringResource(R.string.project_editor_preview)) }
                )
            }

            val text = draft.orEmpty()
            if (previewing) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    if (text.isBlank()) {
                        Text(
                            text = stringResource(R.string.project_editor_preview_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        MarkdownText(text = text)
                    }
                }
            } else {
                TextField(
                    value = text,
                    onValueChange = { draft = it },
                    placeholder = { Text(stringResource(R.string.project_editor_placeholder)) },
                    // Content, not Ltr: an Arabic paragraph has to lay out right-to-left inside an
                    // English UI and an English one left-to-right inside an Arabic UI. The field
                    // follows the text, which is the only rule that is right in both directions.
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        textDirection = TextDirection.Content,
                        fontFamily = if (monospace) FontFamily.Monospace else null
                    ),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            }

            TokenMeter(
                tokens = TokenEstimate.of(text),
                assembledTokens = assembledTokens(state.instructions, state.memory, file, text),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }

    if (confirmImport) {
        AlertDialog(
            onDismissRequest = { confirmImport = false },
            text = { Text(stringResource(R.string.project_file_import_replace)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmImport = false
                    importLauncher.launch(IMPORT_MIME_TYPES)
                }) {
                    Text(stringResource(R.string.confirm_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmImport = false }) {
                    Text(stringResource(R.string.cancel_button))
                }
            }
        )
    }
}

/**
 * What the clamp will actually see: both files, with the one being edited taken from the draft
 * rather than from the database, so the ceiling warning appears while the text is being pasted and
 * not only after it has been saved.
 */
private fun assembledTokens(
    storedInstructions: String,
    storedMemory: String,
    file: String,
    draft: String
): Int {
    val instructions = if (file == ProjectFile.INSTRUCTIONS) draft else storedInstructions
    val memory = if (file == ProjectFile.INSTRUCTIONS) storedMemory else draft
    return TokenEstimate.of(instructions) + TokenEstimate.of(memory)
}

/**
 * A .md name a human can recognise in a share sheet and on the phone it lands on.
 *
 * Only the characters Android and the usual desktop file systems refuse are replaced - Arabic and
 * Thai folder names survive intact, because a file called "بان-إيست-instructions.md" is the whole
 * point of being able to move a project between phones.
 */
private fun exportFileName(folderName: String, file: String, isSharedMemory: Boolean): String {
    if (isSharedMemory) return "shared-memory.md"
    val stem = if (file == ProjectFile.INSTRUCTIONS) "instructions" else "memory"
    val cleaned = folderName
        .map { if (it in ILLEGAL_FILENAME_CHARS || it.code < 0x20) '-' else it }
        .joinToString("")
        .trim('-', ' ', '.')
    return if (cleaned.isEmpty()) stem + ".md" else cleaned + "-" + stem + ".md"
}

/** Char(92) is the backslash, written by code so the set reads as a set. */
private val ILLEGAL_FILENAME_CHARS = charArrayOf(
    '/', ':', '*', '?', '"', '<', '>', '|', Char(92)
)

/**
 * Hand the file to another app.
 *
 * The stored text is inside the encrypted database, so exporting means writing a readable copy
 * somewhere a share target can reach. That copy goes to the cache directory behind the app's own
 * FileProvider, never to shared storage, and the directory is swept first so plaintext project
 * files do not accumulate on the phone.
 *
 * Sent as text/plain with a .md name on purpose: text/markdown is a real type that many share
 * targets still refuse, and the extension is what the receiving phone reads anyway.
 */
private fun exportProjectFile(context: Context, fileName: String, text: String) {
    try {
        val dir = java.io.File(context.cacheDir, "shared_files").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = java.io.File(dir, fileName)
        out.writeBytes(text.toByteArray())

        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            out
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(
            Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Exception) {
        Toast.makeText(context, context.getString(R.string.project_file_export_failed), Toast.LENGTH_SHORT).show()
    }
}

/**
 * What the picker will show. The wildcard text type covers .md, .txt and .markdown wherever the
 * provider knows the type; a .md on a phone whose media store never learned the extension comes
 * back as application/octet-stream, which is why that is listed too.
 */
private val IMPORT_MIME_TYPES = arrayOf("text/*", "application/octet-stream")
