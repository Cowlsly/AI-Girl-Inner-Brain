package app.maskan.chat.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.maskan.chat.util.TokenEstimate

/**
 * DEBUG BUILDS ONLY - a scratch way to fill a folder's instructions and memory.
 *
 * Session 1 of 2.6 builds the SPINE (the columns and the per-request assembly); the real editor,
 * with its token meter, export/import and translated strings, is session 2. Without something
 * like this the spine can only be tested by writing to an encrypted database from outside, which
 * is not possible - the key lives in EncryptedSharedPreferences. Hard-coded English on purpose:
 * translating a screen that is about to be deleted would be waste.
 */
@Composable
internal fun DebugFolderFilesDialog(
    folderName: String,
    instructions: String,
    memory: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var draftInstructions by remember { mutableStateOf(instructions) }
    var draftMemory by remember { mutableStateOf(memory) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Project files - " + folderName) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = draftInstructions,
                    onValueChange = { draftInstructions = it },
                    label = { Text("instructions.md") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp)
                )
                Text("~" + TokenEstimate.of(draftInstructions) + " tokens, sent with every message")
                OutlinedTextField(
                    value = draftMemory,
                    onValueChange = { draftMemory = it },
                    label = { Text("memory.md") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp)
                )
                Text("~" + TokenEstimate.of(draftMemory) + " tokens, sent with every message")
                Row {
                    TextButton(onClick = { draftInstructions = SAMPLE_AR }) { Text("ar") }
                    TextButton(onClick = { draftInstructions = SAMPLE_LONG_AR }) { Text("ar x40") }
                    TextButton(onClick = { draftMemory = SAMPLE_MEMORY_AR }) { Text("mem") }
                    TextButton(onClick = { draftInstructions = ""; draftMemory = "" }) { Text("clear") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(draftInstructions, draftMemory) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * The acceptance-test instructions: Arabic, and it asks for a marker word that no model would
 * emit on its own, so "did this provider obey?" is answerable by looking rather than by judging
 * the tone of a reply.
 */
private const val SAMPLE_AR =
    "أنت مساعد شركة سياحة اسمها بان إيست. أجب باختصار شديد وبالعربية دائمًا. " +
        "ابدأ كل رد بالعلامة [PANEAST] ثم سطر جديد."

private const val SAMPLE_MEMORY_AR = "- 2026-09-21: مكتبنا في جبل عمّان."

/** Long enough to cross the 6,000-token clamp, so the backstop can be seen working. */
private val SAMPLE_LONG_AR: String = buildString {
    repeat(40) { index ->
        append("الفقرة رقم ")
        append(index)
        append(": ")
        append("هذه فقرة طويلة مكتوبة بالعربية لاختبار عدّاد الرموز وحدّ الاقتطاع في التجميع. ")
        append("تتكرر هذه الجملة عدة مرات حتى يتجاوز النص الحد الأعلى المسموح به في الطلب الواحد.")
        append("\n\n")
    }
}
