package com.phoneform.ai.core.data

import java.io.File
import java.io.OutputStream
import java.math.BigDecimal
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/** Small, streaming SpreadsheetML writer. User strings never become formulas. */
sealed class XlsxCell {
    data class Text(val value: String) : XlsxCell()
    data class Number(val value: String) : XlsxCell()
    data object Empty : XlsxCell()
}

data class XlsxSheet(val name: String, val headers: List<String>, val rows: () -> Sequence<List<XlsxCell>>)

object XlsxWriter {
    const val MAX_ROWS = 1_048_576
    const val MAX_COLUMNS = 16_384
    const val MAX_TEXT = 32_767
    private const val NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    fun write(output: OutputStream, sheets: List<XlsxSheet>): List<Int> {
        require(sheets.isNotEmpty() && sheets.map { it.name }.distinct().size == sheets.size)
        sheets.forEach {
            require(it.name.length in 1..31 && it.name.none { c -> c in "[]:*?/\\" }) { "INVALID_SHEET_NAME" }
            require(it.headers.size in 1..MAX_COLUMNS) { "EXCEL_COLUMN_LIMIT" }
        }
        val counts = mutableListOf<Int>()
        ZipOutputStream(output).use { zip ->
            fun xml(name: String, body: String) {
                zip.putNextEntry(ZipEntry(name).apply { time = 0 })
                zip.write(("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" + body).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            xml("[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
                "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
                sheets.indices.joinToString("") { "<Override PartName=\"/xl/worksheets/sheet${it + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" } + "</Types>")
            xml("_rels/.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"$REL/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>")
            xml("xl/workbook.xml", "<workbook xmlns=\"$NS\" xmlns:r=\"$REL\"><bookViews><workbookView/></bookViews><sheets>" +
                sheets.mapIndexed { i, sheet -> "<sheet name=\"${escape(sheet.name)}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>" }.joinToString("") + "</sheets></workbook>")
            xml("xl/_rels/workbook.xml.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                sheets.indices.joinToString("") { "<Relationship Id=\"rId${it + 1}\" Type=\"$REL/worksheet\" Target=\"worksheets/sheet${it + 1}.xml\"/>" } +
                "<Relationship Id=\"rId${sheets.size + 1}\" Type=\"$REL/styles\" Target=\"styles.xml\"/></Relationships>")
            xml("xl/styles.xml", "<styleSheet xmlns=\"$NS\"><fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><color rgb=\"FFFFFFFF\"/><name val=\"Calibri\"/></font></fonts>" +
                "<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill><fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF12695E\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>" +
                "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
                "<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"><alignment vertical=\"top\" wrapText=\"1\"/></xf><xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"><alignment vertical=\"top\" wrapText=\"1\"/></xf></cellXfs>" +
                "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>")
            sheets.forEachIndexed { index, sheet ->
                zip.putNextEntry(ZipEntry("xl/worksheets/sheet${index + 1}.xml").apply { time = 0 })
                val writer = zip.writer(Charsets.UTF_8).buffered()
                writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"$NS\"><sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews><sheetFormatPr defaultRowHeight=\"15\"/><cols><col min=\"1\" max=\"${sheet.headers.size}\" width=\"24\" customWidth=\"1\"/></cols><sheetData>")
                var rowNumber = 0
                fun row(cells: List<XlsxCell>, header: Boolean) {
                    require(++rowNumber <= MAX_ROWS) { "EXCEL_ROW_LIMIT" }
                    require(cells.size == sheet.headers.size) { "COLUMN_COUNT_MISMATCH" }
                    writer.write("<row r=\"$rowNumber\">")
                    cells.forEachIndexed { col, cell ->
                        val ref = "${column(col)}$rowNumber"
                        val style = if (header) " s=\"1\"" else ""
                        when (cell) {
                            XlsxCell.Empty -> Unit
                            is XlsxCell.Text -> writer.write("<c r=\"$ref\" t=\"inlineStr\"$style><is><t xml:space=\"preserve\">${text(cell.value)}</t></is></c>")
                            is XlsxCell.Number -> {
                                val number = cell.value.toBigDecimalOrNull() ?: error("INVALID_NUMBER")
                                if (canBeNumber(number)) writer.write("<c r=\"$ref\"$style><v>${number.toPlainString()}</v></c>")
                                else writer.write("<c r=\"$ref\" t=\"inlineStr\"$style><is><t xml:space=\"preserve\">${text(cell.value)}</t></is></c>")
                            }
                        }
                    }
                    writer.write("</row>")
                }
                row(sheet.headers.map { XlsxCell.Text(it) }, true)
                sheet.rows().forEach { row(it, false) }
                writer.write("</sheetData><autoFilter ref=\"A1:${column(sheet.headers.lastIndex)}$rowNumber\"/></worksheet>")
                writer.flush() // Closing would close the entire ZIP stream.
                zip.closeEntry()
                counts += rowNumber
            }
        }
        return counts
    }

    fun canBeNumber(value: BigDecimal): Boolean = value.stripTrailingZeros().precision() <= 15 &&
        value.toDouble().isFinite() && (value.signum() == 0 || value.toDouble() != 0.0) && kotlin.math.abs(value.scale()) < 1000

    /** Validate written XML and ZIP CRCs independently of writer counters, before exposing a file. */
    fun validate(file: File, expectedRows: List<Int>) = file.inputStream().use { validate(it, expectedRows) }

    fun validate(input: java.io.InputStream, expectedRows: List<Int>) {
        val seen = mutableSetOf<String>()
        val authenticated = ZipTailInput(input)
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            // Android's SAX provider does not implement every Apache feature. External entity
            // loading above remains disabled; the writer never emits a DTD.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        java.util.zip.ZipInputStream(authenticated).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(seen.add(entry.name)) { "DUPLICATE_ZIP_ENTRY" }
                require(!entry.isDirectory && !entry.name.startsWith("/") && ".." !in entry.name.split('/'))
                var count = 0
                if (entry.name.endsWith(".xml") || entry.name.endsWith(".rels")) {
                    val currentEntry = object : java.io.FilterInputStream(zip) { override fun close() {} }
                    factory.newSAXParser().parse(currentEntry, object : DefaultHandler() {
                        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                            require(localName != "f") { "UNEXPECTED_FORMULA" }
                            if (localName == "row") count++
                        }
                    })
                }
                // Drain to verify each ZIP CRC and the authenticated stream's final segment.
                val buffer = ByteArray(8192)
                while (zip.read(buffer) >= 0) { }
                zip.closeEntry()
                val sheet = Regex("xl/worksheets/sheet(\\d+)\\.xml").matchEntire(entry.name)
                if (sheet != null) {
                    val index = sheet.groupValues[1].toInt() - 1
                    require(index in expectedRows.indices && count == expectedRows[index]) { "ROW_COUNT_MISMATCH" }
                }
            }
            require("[Content_Types].xml" in seen && "xl/workbook.xml" in seen && "_rels/.rels" in seen && "xl/_rels/workbook.xml.rels" in seen)
            expectedRows.indices.forEach { require("xl/worksheets/sheet${it + 1}.xml" in seen) }
            // ZipInputStream stops at the central directory; consume the underlying cipher to EOF
            // and verify the ZIP footer too. Closing a cipher stream alone is not authentication.
            val remaining = ByteArray(8192)
            while (authenticated.read(remaining) >= 0) { }
            authenticated.verifyFooter(seen.size)
        }
    }

    private class ZipTailInput(input: java.io.InputStream) : java.io.FilterInputStream(input) {
        private val tail = ByteArray(65_557)
        private var cursor = 0
        private var total = 0L
        private fun remember(bytes: ByteArray, offset: Int, length: Int) {
            total += length
            if (length >= tail.size) {
                System.arraycopy(bytes, offset + length - tail.size, tail, 0, tail.size); cursor = 0
            } else {
                val first = minOf(length, tail.size - cursor)
                System.arraycopy(bytes, offset, tail, cursor, first)
                if (first < length) System.arraycopy(bytes, offset + first, tail, 0, length - first)
                cursor = (cursor + length) % tail.size
            }
        }
        override fun read(): Int = `in`.read().also { if (it >= 0) remember(byteArrayOf(it.toByte()), 0, 1) }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = `in`.read(bytes, offset, length).also {
            if (it > 0) remember(bytes, offset, it)
        }
        fun verifyFooter(entries: Int) {
            val count = minOf(total, tail.size.toLong()).toInt()
            val data = ByteArray(count) { tail[(if (total >= tail.size) cursor + it else it) % tail.size] }
            fun u16(index: Int) = (data[index].toInt() and 255) or ((data[index + 1].toInt() and 255) shl 8)
            fun u32(index: Int) = (0..3).sumOf { (data[index + it].toLong() and 255) shl (8 * it) }
            val end = (count - 22 downTo 0).firstOrNull { i ->
                data[i] == 0x50.toByte() && data[i + 1] == 0x4b.toByte() && data[i + 2] == 5.toByte() && data[i + 3] == 6.toByte() &&
                    i + 22 + u16(i + 20) == count
            } ?: error("ZIP_FOOTER_MISSING")
            require(u16(end + 4) == 0 && u16(end + 6) == 0 && u16(end + 8) == entries && u16(end + 10) == entries) { "ZIP_DIRECTORY_MISMATCH" }
            val directorySize = u32(end + 12)
            val directoryOffset = u32(end + 16)
            require(directorySize != 0xffffffffL && directoryOffset != 0xffffffffL) { "EXCEL_FILE_SIZE_LIMIT" }
            require(directoryOffset + directorySize == total - (count - end)) { "ZIP_DIRECTORY_MISMATCH" }
        }
    }

    internal fun column(index: Int): String {
        require(index in 0 until MAX_COLUMNS)
        var value = index + 1
        return buildString { while (value > 0) { insert(0, ('A'.code + (value - 1) % 26).toChar()); value = (value - 1) / 26 } }
    }

    private fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    private fun text(value: String): String {
        require(value.length <= MAX_TEXT) { "EXCEL_CELL_TEXT_LIMIT" }
        val literalSafe = value.replace(Regex("_x[0-9A-Fa-f]{4}_")) { "_x005F_" + it.value.drop(1) }
        val xmlSafe = buildString {
            var i = 0
            while (i < literalSafe.length) {
                val c = literalSafe[i]
                when {
                    c.isHighSurrogate() -> {
                        require(i + 1 < literalSafe.length && literalSafe[i + 1].isLowSurrogate()) { "INVALID_UNICODE" }
                        append(c); append(literalSafe[++i])
                    }
                    c.isLowSurrogate() -> error("INVALID_UNICODE")
                    (c.code < 32 && c !in "\t\n\r") || c.code in 0xFFFE..0xFFFF -> append("_x${c.code.toString(16).padStart(4, '0')}_")
                    else -> append(c)
                }
                i++
            }
        }
        return escape(xmlSafe)
    }
}
