package com.phoneform.ai.core.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Test

class XlsxWriterTest {
    @Test fun writesSafeFourSheetWorkbookAndIndependentXmlValidation() {
        val file = File("build/validation/xlsx-synthetic.xlsx").apply { parentFile.mkdirs() }
        val headers = listOf("编号", "繁體文字", "公式形状文字", "数字", "精确大数", "日期", "缺失", "多选A", "多选B")
        val sheets = listOf(
            XlsxSheet("Responses", headers) { sequenceOf(listOf(XlsxCell.Text("0007"), XlsxCell.Text("中文／繁體\n第二行 & < > 😀"),
                XlsxCell.Text("=HYPERLINK(\"https://invalid.example\",\"data\")"), XlsxCell.Number("42.50"), XlsxCell.Number("12345678901234567"),
                XlsxCell.Text("2026-10-18"), XlsxCell.Empty, XlsxCell.Number("1"), XlsxCell.Number("0"))) },
            XlsxSheet("Codebook", listOf("key", "code")) { sequenceOf(listOf(XlsxCell.Text("question_a"), XlsxCell.Text("01"))) },
            XlsxSheet("Review_Flags", listOf("record_id", "state")) { sequenceOf(listOf(XlsxCell.Text("synthetic-record"), XlsxCell.Text("NOT_CAPTURED"))) },
            XlsxSheet("Summary", listOf("key", "value")) { sequenceOf(listOf(XlsxCell.Text("included"), XlsxCell.Number("1"))) },
        )
        val counts = file.outputStream().use { XlsxWriter.write(it, sheets) }
        assertEquals(listOf(2, 2, 2, 2), counts)
        XlsxWriter.validate(file, counts)
        ZipFile(file).use { zip ->
            val xml = zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).bufferedReader().readText()
            assertTrue(xml.contains("t=\"inlineStr\""))
            assertTrue(xml.contains("12345678901234567"))
            assertFalse(xml.contains("<f>"))
            assertFalse(xml.contains("<c r=\"G2\""))
        }
    }
    @Test fun rejectsOversizedCellInsteadOfTruncating() {
        try { XlsxWriter.write(ByteArrayOutputStream(), listOf(XlsxSheet("Responses", listOf("title")) { sequenceOf(listOf(XlsxCell.Text("a".repeat(32768)))) })); fail() }
        catch (failure: IllegalArgumentException) { assertEquals("EXCEL_CELL_TEXT_LIMIT", failure.message) }
    }
    @Test fun rejectsInvalidColumnCountAndMalformedUnicode() {
        assertThrows(IllegalArgumentException::class.java) { XlsxWriter.write(ByteArrayOutputStream(), listOf(XlsxSheet("Responses", List(16385) { "x" }) { emptySequence() })) }
        assertThrows(IllegalArgumentException::class.java) { XlsxWriter.write(ByteArrayOutputStream(), listOf(XlsxSheet("Responses", listOf("title")) { sequenceOf(listOf(XlsxCell.Text("\uD800"))) })) }
    }
    @Test fun precisionAndColumnAddressesRespectExcelLimits() {
        assertEquals("A", XlsxWriter.column(0)); assertEquals("Z", XlsxWriter.column(25))
        assertEquals("AA", XlsxWriter.column(26)); assertEquals("XFD", XlsxWriter.column(16383))
        assertTrue(XlsxWriter.canBeNumber("123456789012345".toBigDecimal()))
        assertFalse(XlsxWriter.canBeNumber("1234567890123456".toBigDecimal()))
        assertFalse(XlsxWriter.canBeNumber("1E1000".toBigDecimal()))
        assertFalse(XlsxWriter.canBeNumber("1E-1000".toBigDecimal()))
    }
    @Test fun mismatchedRowsFailValidation() {
        val file = File.createTempFile("xlsx-validation-", ".xlsx")
        try {
            file.outputStream().use { XlsxWriter.write(it, listOf(XlsxSheet("Responses", listOf("x")) { emptySequence() })) }
            assertThrows(IllegalArgumentException::class.java) { XlsxWriter.validate(file, listOf(9)) }
        } finally { file.delete() }
    }
    @Test fun streamingValidationRejectsTruncatedFooterAndReadsAuthenticatedSourceToEof() {
        val buffer = ByteArrayOutputStream()
        val counts = XlsxWriter.write(buffer, listOf(XlsxSheet("Responses", listOf("private")) { sequenceOf(listOf(XlsxCell.Text("00007"))) }))
        val content = buffer.toByteArray()
        var reachedEnd = false
        val input = object : java.io.ByteArrayInputStream(content) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = super.read(bytes, offset, length).also { if (it < 0) reachedEnd = true }
        }
        XlsxWriter.validate(input, counts)
        assertTrue(reachedEnd)
        assertThrows(IllegalStateException::class.java) { XlsxWriter.validate(content.copyOf(content.size - 22).inputStream(), counts) }
        val changed = content.copyOf().also { it[it.size - 22 + 10] = 99 }
        assertThrows(IllegalArgumentException::class.java) { XlsxWriter.validate(changed.inputStream(), counts) }
    }
}
