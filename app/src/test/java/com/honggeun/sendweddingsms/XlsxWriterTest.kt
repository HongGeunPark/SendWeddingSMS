package com.honggeun.sendweddingsms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class XlsxWriterTest {
    @Test fun columns() {
        assertEquals("A", XlsxWriter.column(0))
        assertEquals("G", XlsxWriter.column(6))
        assertEquals("AA", XlsxWriter.column(26))
    }

    @Test fun escapes() {
        assertEquals("a&amp;b&lt;c&gt;", XlsxWriter.escape("a&b<c>\u0001"))
    }

    @Test fun writesAllParts() {
        val out = ByteArrayOutputStream()
        XlsxWriter.write(out, "연락처", listOf("이름", "전화번호"), listOf(listOf("홍길동", "010-1234-5678")))
        val names = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                names[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertTrue(names.keys.containsAll(listOf("[Content_Types].xml", "xl/workbook.xml", "xl/worksheets/sheet1.xml", "xl/styles.xml")))
        assertTrue(names["xl/worksheets/sheet1.xml"]!!.contains("010-1234-5678"))
    }
}
