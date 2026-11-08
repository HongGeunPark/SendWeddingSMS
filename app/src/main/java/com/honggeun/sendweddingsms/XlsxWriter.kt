package com.honggeun.sendweddingsms

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 외부 라이브러리 없이 엑셀(.xlsx) 파일을 만든다.
 * 첫 줄은 굵은 머리글, 틀 고정, 자동 필터를 넣는다.
 * 문자열은 그대로 저장해서 전화번호 앞의 0이 사라지지 않는다.
 */
object XlsxWriter {

    fun write(
        out: OutputStream,
        sheetName: String,
        headers: List<String>,
        rows: List<List<Any?>>,
        columnWidths: List<Double> = emptyList(),
    ) {
        val zip = ZipOutputStream(out)
        fun put(path: String, content: String) {
            zip.putNextEntry(ZipEntry(path))
            zip.write(content.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }

        val lastCol = column(headers.size - 1)
        val lastRow = rows.size + 1
        val range = "\$A\$1:\$$lastCol\$$lastRow"

        put("[Content_Types].xml", CONTENT_TYPES)
        put("_rels/.rels", ROOT_RELS)
        put("xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
        put("xl/styles.xml", STYLES)
        put(
            "xl/workbook.xml",
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets><sheet name="${escape(sheetName)}" sheetId="1" r:id="rId1"/></sheets>
<definedNames><definedName name="_xlnm._FilterDatabase" localSheetId="0" hidden="1">'${escape(sheetName.replace("'", "''"))}'!$range</definedName></definedNames>
</workbook>"""
        )
        put("xl/worksheets/sheet1.xml", sheetXml(headers, rows, columnWidths, "A1:$lastCol$lastRow"))
        zip.finish()
        zip.flush()
    }

    private fun sheetXml(
        headers: List<String>,
        rows: List<List<Any?>>,
        widths: List<Double>,
        filterRef: String,
    ): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""")
        sb.append("""<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
        if (widths.isNotEmpty()) {
            sb.append("<cols>")
            widths.forEachIndexed { i, w ->
                sb.append("""<col min="${i + 1}" max="${i + 1}" width="$w" customWidth="1"/>""")
            }
            sb.append("</cols>")
        }
        sb.append("<sheetData>")
        appendRow(sb, 1, headers, style = 1)
        rows.forEachIndexed { i, row -> appendRow(sb, i + 2, row, style = 0) }
        sb.append("</sheetData>")
        sb.append("""<autoFilter ref="$filterRef"/>""")
        sb.append("</worksheet>")
        return sb.toString()
    }

    private fun appendRow(sb: StringBuilder, r: Int, values: List<Any?>, style: Int) {
        sb.append("""<row r="$r">""")
        values.forEachIndexed { i, v ->
            val ref = "${column(i)}$r"
            val s = if (style != 0) """ s="$style"""" else ""
            when (v) {
                null -> sb.append("""<c r="$ref"$s/>""")
                is Number -> sb.append("""<c r="$ref"$s><v>$v</v></c>""")
                else -> sb.append("""<c r="$ref"$s t="inlineStr"><is><t xml:space="preserve">${escape(v.toString())}</t></is></c>""")
            }
        }
        sb.append("</row>")
    }

    fun column(index: Int): String {
        var n = index + 1
        val sb = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            sb.insert(0, 'A' + rem)
            n = (n - 1) / 26
        }
        return sb.toString()
    }

    fun escape(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            when {
                c == '&' -> sb.append("&amp;")
                c == '<' -> sb.append("&lt;")
                c == '>' -> sb.append("&gt;")
                c == '"' -> sb.append("&quot;")
                c == '\t' || c == '\n' || c == '\r' -> sb.append(c)
                c < ' ' || c == '￾' || c == '￿' -> {} // XML에 넣을 수 없는 문자는 버린다.
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>"""

    private const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""

    private const val WORKBOOK_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""

    private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<fonts count="2"><font><sz val="11"/><name val="맑은 고딕"/></font><font><b/><sz val="11"/><name val="맑은 고딕"/></font></fonts>
<fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFFCE4EC"/><bgColor indexed="64"/></patternFill></fill></fills>
<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/></cellXfs>
<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
</styleSheet>"""
}
