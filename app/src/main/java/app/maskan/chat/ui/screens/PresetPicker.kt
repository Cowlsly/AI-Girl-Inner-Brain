package app.maskan.chat.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.maskan.chat.R
import app.maskan.chat.data.local.Presets
import app.maskan.chat.data.local.PresetCategory
import app.maskan.chat.data.local.SystemPromptPreset
import app.maskan.chat.data.local.isAppArabic
import app.maskan.chat.data.local.localizedDescription
import app.maskan.chat.data.local.localizedName
import app.maskan.chat.data.model.Dialect
import app.maskan.chat.ui.theme.maskanColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetPicker(
    defaultDialect: Dialect,
    onPresetSelected: (SystemPromptPreset, Dialect?) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Start with no system prompt at all.
     *
     * A line under the heading rather than a fifteenth card, because it is not a fifteenth
     * personality - it is the absence of one, and the cards are all answers to "who should the
     * assistant be". Until this existed there was no way past this screen without giving the
     * model a character first.
     */
    onNoPreset: (() -> Unit)? = null,
    /** Non-null while this is being shown over an existing chat, to back out of it. */
    onCancel: (() -> Unit)? = null
) {
    val uiLanguage = LocalConfiguration.current.locales.get(0)?.language ?: "en"
    val presets = Presets.all(defaultDialect).filter { visibleIn(it.id, uiLanguage) }

    var showDialectSheet by remember { mutableStateOf(false) }

    if (showDialectSheet) {
        DialectBottomSheet(
            currentDialect = defaultDialect,
            onDialectSelected = { dialect ->
                showDialectSheet = false
                onPresetSelected(Presets.enToArPreset(dialect), dialect)
            },
            onDismiss = { showDialectSheet = false }
        )
    }

    // A height-filling column instead of a scrolling grid: the heading sits on top and the cards
    // are laid out in equal-weight rows of two, so all presets fit on a single screen with no scroll.
    //
    // Measured before assuming (Pixel 10 Pro, Arabic): a weighted row gives each card about 87dp
    // and the content wants about 76dp. The card is NOT what was cutting the Arabic - the
    // lineHeight clamp on the text was, and that is what the rest of this file no longer does.
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.preset_picker_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
        )

        if (onNoPreset != null || onCancel != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                // contentPadding, not a fixed height: Material gives a TextButton a 48dp
                // minimum, which was most of the gap between the question and the first card.
                // Trimming the padding closes it without capping the label.
                onNoPreset?.let {
                    TextButton(onClick = it, contentPadding = TIGHT_BUTTON_PADDING) {
                        Text(
                            text = stringResource(R.string.preset_none_action),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
                onCancel?.let {
                    TextButton(onClick = it, contentPadding = TIGHT_BUTTON_PADDING) {
                        Text(
                            text = stringResource(R.string.cancel_button),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }

        presets.chunked(2).forEach { rowPresets ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowPresets.forEach { preset ->
                    PresetCard(
                        preset = preset,
                        onClick = {
                            if (preset.id == "en_to_ar") {
                                showDialectSheet = true
                            } else {
                                onPresetSelected(preset, null)
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
                // Keep a single trailing card left-aligned if the count is ever odd.
                if (rowPresets.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * A translation card's icon is two flags; shown with an arrow between them, source first in the
 * reading direction, like the card's name. Two regional-indicator flags are exactly 8 UTF-16
 * units; anything else is a single emoji and is returned as it is.
 */
private fun presetIcon(icon: String, rtl: Boolean): String {
    if (icon.length != 8 || icon.codePointCount(0, 8) != 4) return icon
    val source = icon.substring(0, 4)
    val target = icon.substring(4)
    return if (rtl) target + " \u2190 " + source else source + " \u2192 " + target
}

/**
 * Whether a preset belongs on the picker for someone reading the app in [uiLanguage].
 *
 * Humam's table, 2026-09-22 (session 8):
 *  - English: English to Arabic, English to Thai. Not the "to English" pairs, not the Arabic
 *    writing coach, not the Classical Arabic reader.
 *  - Thai: Thai to English, Thai to Arabic. Not the pairs that start from English or Arabic,
 *    not the coach, not the reader.
 *  - Arabic: Arabic to English, Arabic to Thai, the coach and the reader. Not the pairs that
 *    start from English or Thai.
 * Everything not named here is always shown.
 */
private fun visibleIn(presetId: String, uiLanguage: String): Boolean = when (uiLanguage) {
    "ar" -> presetId !in setOf("en_to_ar", "en_to_th", "th_to_en", "th_to_ar")
    "th" -> presetId !in setOf("en_to_ar", "ar_to_en", "en_to_th", "ar_to_th", "arabic_coach", "classical_arabic")
    else -> presetId !in setOf("ar_to_en", "th_to_en", "th_to_ar", "ar_to_th", "arabic_coach", "classical_arabic")
}

/** Horizontal room to tap, no vertical padding of its own - the label sets the height. */
private val TIGHT_BUTTON_PADDING = PaddingValues(horizontal = 16.dp, vertical = 0.dp)

@Composable
private fun PresetCard(
    preset: SystemPromptPreset,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val name = preset.localizedName()
    val description = preset.localizedDescription()
    val cardColor = presetColor(preset.id)

    Card(
        modifier = modifier
            .clickable(role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            // Top-anchored so the icon + title sit together near the top (matching EN/TH) and the
            // description has a clear, unclipped spot directly beneath the title.
            verticalArrangement = Arrangement.Center
        ) {
            // The emoji/flag line-box renders ~2x the glyph height regardless of
            // includeFontPadding, which eats the whole card and lays the description out at
            // height 0. This cap is what stops that. It caps ONE KNOWN EMOJI being normalised,
            // not translated text - relaxing it to a minimum was tried and reverted, because
            // the icon then took the room the description needed and every Arabic description
            // on the screen was sliced to its top two pixels.
            Box(
                modifier = Modifier.height(28.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = presetIcon(preset.icon, LocalLayoutDirection.current == LayoutDirection.Rtl),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 18.sp,
                        // Pinned: the string below is already in visual order for this layout,
                        // and a run of neutral emoji would otherwise be reordered by BiDi.
                        textDirection = TextDirection.Ltr,
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                        lineHeightStyle = LineHeightStyle(
                            alignment = LineHeightStyle.Alignment.Center,
                            trim = LineHeightStyle.Trim.Both
                        )
                    )
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = name,
                // includeFontPadding=false + Trim.Both is what keeps Arabic single-spaced here;
                // it strips the leading, not the glyphs. lineHeight is 1.5x rather than the 1.08x
                // it used to be, because a line box SHORTER than the ink is what cuts the face in
                // half - and on a single line Trim.Both trims back to the ink, so the larger
                // number costs no space at all.
                style = MaterialTheme.typography.titleSmall.copy(
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                    lineHeightStyle = LineHeightStyle(
                        alignment = LineHeightStyle.Alignment.Center,
                        trim = LineHeightStyle.Trim.Both
                    )
                ),
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (preset.category != PresetCategory.TRANSLATION) Text(
                text = description,
                // The line the Honor sliced through the middle. 11.sp around a 10.sp Arabic
                // glyph is shorter than the ink; 15.sp is above it for both the Arabic face and
                // the Thai one with its marks above and below. Trim.Both still removes the
                // leading, so the spacing is exactly what it was.
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 10.sp,
                    lineHeight = 15.sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                    lineHeightStyle = LineHeightStyle(
                        alignment = LineHeightStyle.Alignment.Center,
                        trim = LineHeightStyle.Trim.Both
                    )
                ),
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DialectBottomSheet(
    currentDialect: Dialect,
    onDialectSelected: (Dialect) -> Unit,
    onDismiss: () -> Unit
) {
    val isArabic = isAppArabic()
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
        ) {
            Text(
                text = stringResource(R.string.dialect_picker_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Dialect.entries.forEach { dialect ->
                val selected = dialect == currentDialect
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clickable(role = Role.Button) { onDialectSelected(dialect) },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected)
                            MaterialTheme.maskanColors.skyBlue
                        else
                            MaterialTheme.colorScheme.surfaceVariant
                    ),
                    border = if (selected)
                        BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                    else
                        null
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = if (isArabic) dialect.nameAr else dialect.nameEn,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                        Text(
                            text = dialect.nativeName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

// Per-card pastel colors, mirroring the design mockup. Pulled from the theme-aware
// MaskanColors palette so they adapt to dark mode.
@Composable
private fun presetColor(id: String): Color = when (id) {
    "general" -> MaterialTheme.maskanColors.periwinkle
    "arabic_coach" -> MaterialTheme.maskanColors.paleYellow
    "en_to_ar" -> MaterialTheme.maskanColors.mintGreen
    "ar_to_en" -> MaterialTheme.maskanColors.aqua
    "en_to_th" -> MaterialTheme.maskanColors.softLavender
    "th_to_en" -> MaterialTheme.maskanColors.warmSand
    "classical_arabic" -> MaterialTheme.maskanColors.palePink
    "code_reviewer" -> MaterialTheme.maskanColors.mintGreen
    "email_drafter" -> MaterialTheme.maskanColors.paleYellow
    "summarizer" -> MaterialTheme.maskanColors.skyBlue
    "brainstorm" -> MaterialTheme.maskanColors.warmPeach
    "tutor" -> MaterialTheme.maskanColors.softLavender
    "concise_expert" -> MaterialTheme.maskanColors.aqua
    "custom" -> MaterialTheme.maskanColors.softCoral
    else -> MaterialTheme.maskanColors.skyBlue
}