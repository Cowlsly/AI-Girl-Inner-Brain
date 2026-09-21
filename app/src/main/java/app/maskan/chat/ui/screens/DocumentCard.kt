package app.maskan.chat.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import app.maskan.chat.data.local.DocumentEntity
import app.maskan.chat.ui.theme.maskanColors
import app.maskan.chat.ui.viewmodel.Reading
import app.maskan.chat.util.DocumentExtract

/**
 * The "Document notes" card: what the app has read of a file, in the conversation it was
 * attached to.
 *
 * Collapsed by default and deliberately quiet - a 30-page contract has thirty summaries and
 * nobody wants them in the middle of a chat. What is always visible is the honest part: the
 * name, the size, how much of it has been read, and a Continue button when the answer is "not
 * all of it". Nothing here sends anything until that button is tapped.
 */
@Composable
fun DocumentNotesCard(
    document: DocumentEntity,
    reading: Reading?,
    onContinue: () -> Unit,
    onStop: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember(document.id) { mutableStateOf(false) }
    val busy = reading?.documentId == document.id

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.maskanColors.skyBlue.copy(alpha = 0.22f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.document_notes_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = document.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = metaLine(document),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            val status = statusLine(document, reading)
            if (status != null) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            if (busy && document.chunkCount > 0) {
                LinearProgressIndicator(
                    progress = {
                        (document.notesDone.toFloat() / document.chunkCount).coerceIn(0f, 1f)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                )
            }

            warningLine(document)?.let { warning ->
                Text(
                    text = warning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (expanded) {
                Text(
                    text = stringResource(R.string.document_not_read),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
                document.notes?.takeIf { it.isNotBlank() }?.let { notes ->
                    Text(
                        text = notes,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // A Continue button ONLY where there is something to continue, and never a
                // pass that started itself: these are the user's requests being spent.
                if (document.partial && !busy) {
                    TextButton(onClick = onContinue) {
                        Text(stringResource(R.string.document_continue))
                    }
                }
                if (busy) {
                    TextButton(onClick = onStop) {
                        Text(stringResource(R.string.document_stop))
                    }
                }
                if (!document.notes.isNullOrBlank() || document.isPages) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(
                            stringResource(
                                if (expanded) R.string.document_hide_notes
                                else R.string.document_show_notes
                            )
                        )
                    }
                }
                TextButton(onClick = onRemove) {
                    Text(stringResource(R.string.document_remove))
                }
            }
        }
    }
}

@Composable
private fun metaLine(document: DocumentEntity): String {
    val parts = ArrayList<String>()
    parts.add(document.kind.uppercase())
    if (document.pages > 0) parts.add(stringResource(R.string.document_meta_pages, document.pages))
    if (document.tokens > 0) parts.add(stringResource(R.string.document_meta_tokens, document.tokens))
    return parts.joinToString(" · ")
}

@Composable
private fun statusLine(document: DocumentEntity, reading: Reading?): String? {
    val busy = reading?.documentId == document.id
    if (busy && reading.waitingSeconds > 0) {
        return stringResource(R.string.document_waiting, reading.waitingSeconds)
    }
    if (document.chunkCount <= 0) return null
    return if (document.partial) {
        stringResource(R.string.document_progress, document.notesDone, document.chunkCount)
    } else {
        stringResource(R.string.document_read_done)
    }
}

@Composable
private fun warningLine(document: DocumentEntity): String? =
    documentWarningText(document.warning)

/**
 * A DocumentExtract.WARN_* code as a sentence, or null.
 *
 * Shared with the composer, which shows the same warning under the attachment chip: a file small
 * enough to be pasted into the message has no card to put it on, and a silent warning is the
 * same as no warning.
 */
@Composable
fun documentWarningText(warning: String?): String? {
    if (warning == null) return null
    return when {
        warning == DocumentExtract.WARN_ARABIC -> stringResource(R.string.document_warn_arabic)
        warning.startsWith(DocumentExtract.WARN_PAGES + ":") -> {
            val (read, total) = twoNumbers(warning) ?: return null
            stringResource(R.string.document_warn_pages, read, total)
        }
        warning.startsWith(DocumentExtract.WARN_ROWS + ":") -> {
            val (read, total) = twoNumbers(warning) ?: return null
            stringResource(R.string.document_warn_rows, read, total)
        }
        else -> null
    }
}

/** "pages:300/412" -> 300 to 412. */
private fun twoNumbers(warning: String): Pair<Int, Int>? {
    val body = warning.substringAfter(':', "")
    val read = body.substringBefore('/').toIntOrNull() ?: return null
    val total = body.substringAfter('/', "").toIntOrNull() ?: return null
    return read to total
}
