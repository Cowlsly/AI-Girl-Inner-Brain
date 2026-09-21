package app.maskan.chat.ui.screens

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import app.maskan.chat.R
import app.maskan.chat.data.repository.ChatRepository
import java.util.Locale

/**
 * The one place the token thresholds live.
 *
 * Used by the editor, the folder screen and the folder row in the conversation list, so a file
 * that reads amber in the editor cannot read plain on the row that led to it.
 */
object TokenThresholds {
    /** Getting long: every reply now carries this. */
    const val AMBER = 1500

    /** Long enough that it is costing the user money and speed on every single message. */
    const val RED = 3000

    /** What ChatRepository will actually cut at. Not ours to invent a second number for. */
    const val CEILING = ChatRepository.MAX_SYSTEM_TOKENS
}

// Material's scheme here has no warning colour between "fine" and "error" - the palette is
// deliberately pastel - so amber is named here, once, dark-aware, rather than each caller
// inventing one.
private val AmberOnLight = Color(0xFF9A5B00)
private val AmberOnDark = Color(0xFFE2A93B)

@Composable
internal fun tokenMeterColor(tokens: Int): Color = when {
    tokens >= TokenThresholds.RED -> MaterialTheme.colorScheme.error
    tokens >= TokenThresholds.AMBER -> if (isSystemInDarkTheme()) AmberOnDark else AmberOnLight
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * Latin digits with thousands separators, in every UI language.
 *
 * The count is a technical quantity, and the rest of the Arabic strings already write quantities
 * in Latin digits ("50 كيلوبايت"). Formatting through the UI locale instead would render Arabic
 * as ١٢٤٠ beside a hard-coded 6000 in the ceiling warning, which looks like two different units.
 */
internal fun formatTokens(tokens: Int): String = String.format(Locale.US, "%,d", tokens)

/**
 * The meter under a file in the editor: how much this file costs on every message, and - when
 * the two files together pass the ceiling - the fact that the rest is not sent at all.
 *
 * [tokens] is this file; [assembledTokens] is instructions plus memory, because that total is
 * what ChatRepository clamps. The user cannot be told the clamp fired by looking at one half.
 */
@Composable
internal fun TokenMeter(
    tokens: Int,
    assembledTokens: Int,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.token_meter, formatTokens(tokens)),
            style = MaterialTheme.typography.bodySmall,
            color = tokenMeterColor(tokens)
        )
        if (tokens >= TokenThresholds.RED) {
            Text(
                text = stringResource(R.string.token_meter_trim),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        if (assembledTokens >= TokenThresholds.CEILING) {
            Text(
                text = stringResource(R.string.token_meter_clamped),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/** The short form, for a row that leads to a file rather than one that holds it. */
@Composable
internal fun TokenCount(tokens: Int, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.token_meter_short, formatTokens(tokens)),
        style = MaterialTheme.typography.labelSmall,
        color = tokenMeterColor(tokens),
        modifier = modifier
    )
}
