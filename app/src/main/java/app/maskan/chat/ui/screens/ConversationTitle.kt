package app.maskan.chat.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.maskan.chat.R
import app.maskan.chat.data.repository.ChatRepository

/**
 * What a conversation is called, on screen.
 *
 * A chat is born titled "New Chat" and that string is stored, in English, in every install -
 * it is the sentinel half a dozen places compare against to answer "has anything named this
 * chat yet", and a stored value that changed with the app's language would stop matching the
 * moment the user switched. So it is translated HERE, at the last possible moment, which is why
 * an Arabic phone showed a list of English "New Chat" rows for a whole release.
 */
@Composable
internal fun displayTitle(stored: String): String =
    if (stored == ChatRepository.DEFAULT_TITLE) stringResource(R.string.new_chat_title) else stored

/**
 * How many chats are in a folder, shown only where two folders share a name.
 *
 * A number and an icon rather than a number and a noun, deliberately: Arabic agrees the counted
 * noun with the number - three to ten take the plural, eleven upwards the singular - so any
 * "%d chats" format string is wrong at most counts. The icon carries the noun and the screen
 * reader gets a sentence with the number moved out of the way.
 */
@Composable
internal fun ChatCount(count: Int, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.folder_chat_count_desc, count)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.semantics { contentDescription = description }
    ) {
        Icon(
            imageVector = Icons.Filled.ChatBubble,
            contentDescription = null,
            modifier = Modifier.size(11.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(3.dp))
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
