package app.maskan.chat.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.maskan.chat.R
import app.maskan.chat.data.local.ConversationEntity
import app.maskan.chat.data.local.Presets
import app.maskan.chat.data.local.localizedName
import app.maskan.chat.data.repository.ChatRepository
import app.maskan.chat.ui.theme.maskanColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ConversationCard(
    conversation: ConversationEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onMoveToFolder: () -> Unit,
    onRename: () -> Unit = {},
    /**
     * True when another row on screen carries this exact title.
     *
     * Two chats may genuinely be called "Petra" and the app does not stop anyone doing that.
     * What it must not do is draw two rows identically - so these rows, and only these rows,
     * say more: the first line of what was asked, and a time down to the second.
     */
    duplicateName: Boolean = false,
    /** The first line the user wrote here. Shown only where it says something the title does not. */
    firstLine: String? = null
) {
    // The title of a chat nobody renamed IS the first 50 characters of the first message, so
    // for most rows the "extra" line is the title over again - which distinguishes nothing and
    // reads as a rendering bug. (Seen on the device: two chats called "kittens playing at
    // home", each with "kittens playing at home" underneath.) The time carries those.
    val distinguisher = firstLine
        ?.takeIf { duplicateName && it.isNotBlank() }
        ?.takeIf { line ->
            val title = conversation.title.removeSuffix("...").trim()
            title.isNotEmpty() && !line.startsWith(title) && !title.startsWith(line)
        }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showContextMenu by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_conversation_title)) },
            confirmButton = {
                TextButton(onClick = { onDelete(); showDeleteDialog = false }) {
                    Text(stringResource(R.string.delete_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel_button))
                }
            }
        )
    }

    Box {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { showContextMenu = true }
                ),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = displayTitle(conversation.title),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!distinguisher.isNullOrBlank()) {
                        Text(
                            text = distinguisher,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            // To the second where two rows share a name: two chats started in
                            // the same minute is what a busy evening looks like, and the date
                            // is the last thing left telling them apart.
                            text = formatDate(conversation.createdAt, duplicateName),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // A custom prompt gets a badge like any other preset. It used to be
                        // skipped here, and with nothing in the chat header either, a prompt
                        // the user had written by hand left no trace anywhere in the app - the
                        // whole of the "custom prompts are not saved" report, for a prompt that
                        // was in the database and going out with every request.
                        val badge = when (val presetId = conversation.systemPromptId) {
                            null, ChatRepository.PRESET_NONE -> null
                            ChatRepository.PRESET_CUSTOM -> stringResource(R.string.preset_custom_label)
                            else -> Presets.getById(presetId)?.localizedName()
                        }
                        badge?.let {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.maskanColors.skyBlue,
                                tonalElevation = 0.dp
                            ) {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
                IconButton(onClick = { showDeleteDialog = true }) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.delete_button),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        DropdownMenu(
            expanded = showContextMenu,
            onDismissRequest = { showContextMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.rename_chat)) },
                onClick = { showContextMenu = false; onRename() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.move_to_folder)) },
                onClick = { showContextMenu = false; onMoveToFolder() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete_button)) },
                onClick = { showContextMenu = false; showDeleteDialog = true }
            )
        }
    }
}

private fun formatDate(timestamp: Long, withSeconds: Boolean = false): String {
    val pattern = if (withSeconds) "MMM dd, yyyy HH:mm:ss" else "MMM dd, yyyy HH:mm"
    val sdf = SimpleDateFormat(pattern, Locale.getDefault())
    return sdf.format(Date(timestamp))
}
