package app.maskan.chat.util

import android.content.Context
import android.net.Uri
import android.util.Xml
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.xmlpull.v1.XmlPullParser
import java.io.FilterInputStream
import java.io.InputStream
import java.text.Normalizer
import java.util.zip.ZipInputStream

/**
 * Turn a file the user picked into plain text the model can be asked about.
 *
 * Four kinds, one shape of answer. PDF goes through PDFBox; .docx and .xlsx are ZIP + XML and
 * are read with the platform's own parser, so neither costs a dependency. Everything comes out
 * NFKC-normalised, because a PDF that stores Arabic as presentation forms and a .docx that
 * stores it as base letters must not answer differently.
 *
 * What is NOT read, and is said so in the UI rather than discovered later: pictures, formatting,
 * charts, comments, and a formula's definition - a spreadsheet stores the computed value, and
 * that value is what comes out. Dates in a spreadsheet are stored as numbers and come out as
 * numbers.
 */
object DocumentExtract {

    /** Bigger than any document worth asking a question about, and small enough to hold. */
    const val MAX_FILE_BYTES = 25L * 1024 * 1024

    /** Read this many pages of a PDF; past it the document says how many it left. */
    const val MAX_PDF_PAGES = 300

    /** Rows across all sheets. A 20,000-row export is a cost trap, not a document. */
    const val MAX_SHEET_ROWS = 5000

    /**
     * Below this many characters per page, a PDF is a picture of a document rather than a
     * document: a scan carries page furniture (a stamp, a page number) and nothing else.
     */
    private const val MIN_CHARS_PER_PAGE = 40

    const val KIND_PDF = "pdf"
    const val KIND_DOCX = "docx"
    const val KIND_XLSX = "xlsx"
    const val KIND_TEXT = "text"

    data class Doc(
        val name: String,
        val kind: String,
        /** 0 where the format does not have pages, or does not say how many. */
        val pages: Int,
        val text: String,
        /** Machine-readable, turned into a sentence by the screen: see WARN_* below. */
        val warning: String? = null
    )

    /** Arabic came out of a PDF as presentation forms; the word order may be reversed. */
    const val WARN_ARABIC = "arabic"

    /** "pages:300/412" - the file was longer than MAX_PDF_PAGES. */
    const val WARN_PAGES = "pages"

    /** "rows:5000/20431" - the sheets were longer than MAX_SHEET_ROWS. */
    const val WARN_ROWS = "rows"

    sealed class Outcome {
        data class Ok(val doc: Doc) : Outcome()

        /** A PDF that is a scan. The caller offers the pages as pictures instead. */
        data class NoText(val pages: Int) : Outcome()

        /** [reason] is one of the REFUSED_* keys, which the screen turns into a sentence. */
        data class Refused(val reason: String) : Outcome()
    }

    const val REFUSED_TOO_LARGE = "too_large"
    const val REFUSED_LEGACY = "legacy"
    const val REFUSED_MACRO = "macro"
    const val REFUSED_ENCRYPTED = "encrypted"
    const val REFUSED_CERT = "cert"
    const val REFUSED_UNREADABLE = "unreadable"
    const val REFUSED_EMPTY = "empty"

    /**
     * Which reader this file gets - by extension first.
     *
     * The MIME type is what the picker filtered on, but a document provider is free to answer
     * `application/octet-stream` for a perfectly ordinary .docx, and the name is the thing the
     * user actually chose. Returns null for a file this app does not read at all.
     */
    fun kindOf(name: String, mime: String?): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        when (ext) {
            "pdf" -> return KIND_PDF
            "docx" -> return KIND_DOCX
            "xlsx" -> return KIND_XLSX
            "txt", "text", "md", "markdown", "csv", "log", "json", "xml", "html", "htm" ->
                return KIND_TEXT
        }
        return when (mime) {
            "application/pdf" -> KIND_PDF
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> KIND_DOCX
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> KIND_XLSX
            else -> if (mime?.startsWith("text/") == true) KIND_TEXT else null
        }
    }

    /** A file we recognise but will not read, and why. Null when there is no objection. */
    fun refusalFor(name: String): String? {
        return when (name.substringAfterLast('.', "").lowercase()) {
            "doc", "xls", "ppt" -> REFUSED_LEGACY
            "docm", "xlsm", "dotm", "xltm" -> REFUSED_MACRO
            else -> null
        }
    }

    /**
     * Read [uri]. Blocking and file-sized - call it on Dispatchers.IO.
     *
     * Every reader runs inside one catch for Throwable, not Exception: this is a third-party
     * parser on a file from outside the app, and a malformed PDF that throws an Error must come
     * back as "cannot read this file", never as a crash in the user's chat.
     */
    fun extract(context: Context, uri: Uri, name: String, mime: String?): Outcome {
        refusalFor(name)?.let { return Outcome.Refused(it) }
        val kind = kindOf(name, mime) ?: return Outcome.Refused(REFUSED_UNREADABLE)

        val size = sizeOf(context, uri)
        if (size != null && size > MAX_FILE_BYTES) return Outcome.Refused(REFUSED_TOO_LARGE)

        return try {
            when (kind) {
                KIND_PDF -> readPdf(context, uri, name)
                KIND_DOCX -> readDocx(context, uri, name)
                KIND_XLSX -> readXlsx(context, uri, name)
                else -> readText(context, uri, name, mime)
            }
        } catch (t: Throwable) {
            when {
                isMissingBouncyCastle(t) -> Outcome.Refused(REFUSED_CERT)
                isPasswordProblem(t) -> Outcome.Refused(REFUSED_ENCRYPTED)
                else -> Outcome.Refused(REFUSED_UNREADABLE)
            }
        }
    }

    /**
     * BouncyCastle is excluded from the build, so a certificate-encrypted PDF - and only that -
     * arrives here as a missing class rather than as a parse failure. Saying "encrypted with a
     * certificate" beats "cannot read this file" for the one person a year who meets it.
     */
    private fun isMissingBouncyCastle(t: Throwable): Boolean {
        var e: Throwable? = t
        while (e != null) {
            if (e is NoClassDefFoundError || e is ClassNotFoundException) {
                val m = e.message.orEmpty()
                if (m.contains("bouncycastle", ignoreCase = true)) return true
                if (m.contains("SecurityProvider")) return true
            }
            e = e.cause
        }
        return false
    }

    private fun isPasswordProblem(t: Throwable): Boolean {
        var e: Throwable? = t
        while (e != null) {
            if (e.javaClass.name.contains("InvalidPassword")) return true
            if (e.message?.contains("password", ignoreCase = true) == true) return true
            e = e.cause
        }
        return false
    }

    private fun sizeOf(context: Context, uri: Uri): Long? {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (i >= 0 && c.moveToFirst() && !c.isNull(i)) return c.getLong(i)
        }
        return null
    }

    // PDF

    private fun readPdf(context: Context, uri: Uri, name: String): Outcome {
        // Loads the glyph list and the standard-14 font metrics out of the library's assets.
        // Cheap, idempotent, and text extraction on a font-subsetted PDF is wrong without it.
        PDFBoxResourceLoader.init(context.applicationContext)

        val stream = context.contentResolver.openInputStream(uri)
            ?: return Outcome.Refused(REFUSED_UNREADABLE)

        stream.use { input ->
            PDDocument.load(input).use { pdf ->
                val total = pdf.numberOfPages
                if (total <= 0) return Outcome.Refused(REFUSED_EMPTY)
                val read = minOf(total, MAX_PDF_PAGES)

                val stripper = PDFTextStripper().apply {
                    // Without this a two-column page comes out as one interleaved sentence per
                    // line. With it the columns come out one after the other.
                    sortByPosition = true
                    startPage = 1
                    endPage = read
                }
                val raw = stripper.getText(pdf)

                if (raw.trim().length < read * MIN_CHARS_PER_PAGE) {
                    return Outcome.NoText(total)
                }

                val jumbled = arabicLooksJumbled(raw)
                val text = normalise(raw)
                val warning = when {
                    read < total -> WARN_PAGES + ":" + read + "/" + total
                    jumbled -> WARN_ARABIC
                    else -> null
                }
                return Outcome.Ok(Doc(name, KIND_PDF, read, text, warning))
            }
        }
    }

    /**
     * Whether the Arabic in this text is stored as presentation forms.
     *
     * A PDF that writes Arabic with U+FB50..FDFF / U+FE70..FEFF is writing the SHAPES, in the
     * order they were painted - which for right-to-left text is often the reverse of the order
     * they were written. NFKC turns the shapes back into letters, so the text stops looking like
     * mojibake, but nothing can put a reversed line back in order. Hence a warning rather than a
     * silent fix: better the user is told the answer may be about jumbled text than shown a
     * confident answer about nonsense.
     */
    private fun arabicLooksJumbled(raw: String): Boolean {
        var arabic = 0
        var forms = 0
        for (c in raw) {
            val code = c.code
            val isForm = code in 0xFB50..0xFDFF || code in 0xFE70..0xFEFF
            val isBase = code in 0x0600..0x06FF || code in 0x0750..0x077F || code in 0x08A0..0x08FF
            if (isForm) forms++
            if (isForm || isBase) arabic++
        }
        if (arabic < 40) return false
        return forms.toDouble() / arabic > 0.5
    }

    // Word

    /**
     * `word/document.xml` only: the body text, its tables' cell text, and nothing else.
     *
     * Headers, footers, footnotes and text boxes live in their own parts and are left alone on
     * purpose - a header repeated on 40 pages is 40 copies of the company name in the model's
     * context, and the caveat line says they are not read.
     */
    private fun readDocx(context: Context, uri: Uri, name: String): Outcome {
        var body: String? = null
        var pages = 0

        forEachZipEntry(context, uri) { entryName, input ->
            when (entryName) {
                "word/document.xml" -> body = parseWordBody(input)
                // Word records its own page count here; nothing else on Android knows it.
                "docProps/app.xml" -> pages = parseAppPages(input)
            }
            body == null || pages == 0
        }

        val text = normalise(body.orEmpty()).trim()
        if (text.isEmpty()) return Outcome.Refused(REFUSED_EMPTY)
        return Outcome.Ok(Doc(name, KIND_DOCX, pages, text, null))
    }

    private fun parseWordBody(input: InputStream): String {
        val out = StringBuilder()
        val parser = newParser(input)
        var inText = false
        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "w:t" -> inText = true
                    "w:tab" -> out.append('\t')
                    "w:br", "w:cr" -> out.append('\n')
                    // A cell boundary inside a table row, so a row does not run together.
                    "w:tc" -> if (out.isNotEmpty() && out.last() != '\n') out.append('\t')
                }
                XmlPullParser.TEXT -> if (inText) out.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "w:t" -> inText = false
                    // One blank line per paragraph: this is what the chunker splits on.
                    "w:p" -> out.append("\n\n")
                    "w:tr" -> out.append('\n')
                }
            }
        }
        return out.toString()
    }

    private fun parseAppPages(input: InputStream): Int {
        val parser = newParser(input)
        var inPages = false
        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            when (event) {
                XmlPullParser.START_TAG -> inPages = parser.name == "Pages"
                XmlPullParser.TEXT -> if (inPages) return parser.text.trim().toIntOrNull() ?: 0
                XmlPullParser.END_TAG -> inPages = false
            }
        }
        return 0
    }

    // Excel

    /**
     * Sheets as plain rows, tab-separated, each under its own heading.
     *
     * Two passes over the zip rather than one: pass one takes the shared-string pool and the
     * sheet names, pass two streams the sheets themselves. A 20,000-row sheet's XML is tens of
     * megabytes, and holding every entry in memory to sort them out afterwards is how this
     * crashes on the one file it was built for.
     */
    private fun readXlsx(context: Context, uri: Uri, name: String): Outcome {
        val shared = ArrayList<String>()
        // rId -> visible name, and rId -> sheet part: the only correct way to a sheet's name.
        val nameByRel = HashMap<String, String>()
        val relTarget = HashMap<String, String>()

        forEachZipEntry(context, uri) { entryName, input ->
            when (entryName) {
                "xl/sharedStrings.xml" -> parseSharedStrings(input, shared)
                "xl/workbook.xml" -> parseWorkbook(input, nameByRel)
                "xl/_rels/workbook.xml.rels" -> parseRels(input, relTarget)
            }
            true
        }

        val titleFor = HashMap<String, String>()
        for ((rid, sheetName) in nameByRel) {
            val target = relTarget[rid] ?: continue
            val part = if (target.startsWith("/")) target.removePrefix("/") else "xl/" + target
            titleFor[part] = sheetName
        }

        val out = StringBuilder()
        var rowsRead = 0
        var rowsTotal = 0

        forEachZipEntry(context, uri) { entryName, input ->
            if (entryName.startsWith("xl/worksheets/sheet") && entryName.endsWith(".xml")) {
                val title = titleFor[entryName]
                    ?: entryName.substringAfterLast('/').removeSuffix(".xml")
                var first = true
                val counted = parseSheet(input, shared, MAX_SHEET_ROWS - rowsRead) { row ->
                    if (first) {
                        out.append("\n## ").append(title).append("\n")
                        first = false
                    }
                    out.append(row).append('\n')
                    rowsRead++
                }
                rowsTotal += counted
            }
            true
        }

        val text = normalise(out.toString()).trim()
        if (text.isEmpty()) return Outcome.Refused(REFUSED_EMPTY)
        val warning = if (rowsTotal > rowsRead) WARN_ROWS + ":" + rowsRead + "/" + rowsTotal else null
        return Outcome.Ok(Doc(name, KIND_XLSX, 0, text, warning))
    }

    private fun parseSharedStrings(input: InputStream, into: MutableList<String>) {
        val parser = newParser(input)
        val current = StringBuilder()
        var inSi = false
        var inT = false
        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "si" -> { inSi = true; current.setLength(0) }
                    "t" -> inT = true
                }
                XmlPullParser.TEXT -> if (inSi && inT) current.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "t" -> inT = false
                    // Runs (<r><t>bold</t></r><r><t> rest</t></r>) are one string, concatenated.
                    "si" -> { into.add(current.toString()); inSi = false }
                }
            }
        }
    }

    private fun parseWorkbook(input: InputStream, into: MutableMap<String, String>) {
        val parser = newParser(input)
        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            if (event == XmlPullParser.START_TAG && parser.name == "sheet") {
                val sheetName = parser.getAttributeValue(null, "name") ?: continue
                val rid = parser.getAttributeValue(null, "r:id")
                    ?: parser.getAttributeValue(null, "id") ?: continue
                into[rid] = sheetName
            }
        }
    }

    private fun parseRels(input: InputStream, into: MutableMap<String, String>) {
        val parser = newParser(input)
        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            if (event == XmlPullParser.START_TAG && parser.name == "Relationship") {
                val id = parser.getAttributeValue(null, "Id") ?: continue
                val target = parser.getAttributeValue(null, "Target") ?: continue
                into[id] = target
            }
        }
    }

    /**
     * Stream one sheet, handing each non-empty row to [emit] until [budget] is used up.
     *
     * Returns how many rows the sheet has WITH SOMETHING IN THEM, not how many it declares.
     * The difference is what the user is shown when a spreadsheet is bigger than the ceiling -
     * and counting declared rows made that sentence a lie: a sheet whose used range runs to row
     * 2001 with 102 filled rows reported "read 102 of 2001 rows", as though 1,899 rows of data
     * had been dropped. Nothing was dropped; they were empty. (Device, session 4.)
     */
    private fun parseSheet(
        input: InputStream,
        shared: List<String>,
        budget: Int,
        emit: (String) -> Unit
    ): Int {
        val parser = newParser(input)
        val row = StringBuilder()
        val value = StringBuilder()
        var rowsWithText = 0
        var emitted = 0
        var column = 0
        var cellType: String? = null
        var inValue = false
        var rowHasText = false

        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> { row.setLength(0); column = 0; rowHasText = false }
                    "c" -> {
                        cellType = parser.getAttributeValue(null, "t")
                        val ref = parser.getAttributeValue(null, "r")
                        val target = if (ref != null) columnOf(ref) else column
                        // Pad, so the third column stays the third column when the second is
                        // empty: a table read as text is only useful while it lines up.
                        while (column < target) {
                            row.append('\t')
                            column++
                        }
                        value.setLength(0)
                    }
                    "v", "t" -> inValue = true
                }
                XmlPullParser.TEXT -> if (inValue) value.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "v", "t" -> inValue = false
                    "c" -> {
                        val raw = value.toString()
                        val text = if (cellType == "s") {
                            raw.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                        } else {
                            raw
                        }
                        if (text.isNotEmpty()) rowHasText = true
                        row.append(text)
                        column++
                    }
                    "row" -> {
                        if (rowHasText) {
                            rowsWithText++
                            if (emitted < budget) {
                                emit(row.toString().trimEnd('\t'))
                                emitted++
                            }
                        }
                    }
                }
            }
        }
        return rowsWithText
    }

    /** "AB12" -> 27. The zero-based column index a cell reference names. */
    private fun columnOf(ref: String): Int {
        var n = 0
        for (c in ref) {
            if (c !in 'A'..'Z') break
            n = n * 26 + (c - 'A' + 1)
        }
        return (n - 1).coerceAtLeast(0)
    }

    // Plain text

    private fun readText(context: Context, uri: Uri, name: String, mime: String?): Outcome {
        val raw = context.contentResolver.openInputStream(uri)?.use {
            it.bufferedReader().readText()
        } ?: return Outcome.Refused(REFUSED_UNREADABLE)

        val isHtml = mime == "text/html" ||
            name.endsWith(".html", ignoreCase = true) ||
            name.endsWith(".htm", ignoreCase = true)
        val text = normalise(if (isHtml) stripHtml(raw) else raw).trim()
        if (text.isEmpty()) return Outcome.Refused(REFUSED_EMPTY)
        return Outcome.Ok(Doc(name, KIND_TEXT, 0, text, null))
    }

    /** The 2.5 tag-stripper, moved here unchanged so both callers strip identically. */
    fun stripHtml(raw: String): String =
        raw.replace(Regex("<[^>]*>"), " ")
            .replace(Regex("&nbsp;"), " ")
            .replace(Regex("&amp;"), "&")
            .replace(Regex("&lt;"), "<")
            .replace(Regex("&gt;"), ">")
            .replace(Regex("&quot;"), "\"")
            .replace(Regex("&#39;"), "'")
            .replace(Regex("\\s+"), " ")
            .trim()

    // Shared

    /**
     * NFKC, then tidy the whitespace a converter leaves behind.
     *
     * NFKC is what turns a PDF's Arabic presentation forms back into letters, and what makes the
     * full-width digits some Asian exports use comparable with ordinary ones. The whitespace
     * pass matters for chunking: paragraphs are the boundary the chunker cuts on, so a file with
     * five blank lines between paragraphs must not chunk differently from one with two.
     */
    private fun normalise(raw: String): String {
        val nfkc = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        return nfkc
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace(' ', ' ')
            .replace(Regex("[ \\t]+\\n"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
    }

    private fun newParser(input: InputStream): XmlPullParser {
        val parser = Xml.newPullParser()
        // Names arrive as written ("w:t"), which is what the readers above match on.
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, "UTF-8")
        return parser
    }

    /**
     * Walk the zip, handing each entry's stream to [onEntry]; stop when it returns false.
     *
     * The stream is wrapped so the XML parser's own close() does not close the ZipInputStream
     * underneath it and end the walk after the first part it reads.
     */
    private fun forEachZipEntry(
        context: Context,
        uri: Uri,
        onEntry: (String, InputStream) -> Boolean
    ) {
        val stream = context.contentResolver.openInputStream(uri) ?: return
        ZipInputStream(stream.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val keepGoing = onEntry(entry.name, NonClosing(zip))
                if (!keepGoing) break
            }
        }
    }

    private class NonClosing(stream: InputStream) : FilterInputStream(stream) {
        override fun close() { /* the ZipInputStream owns the lifetime, not the parser */ }
    }
}
