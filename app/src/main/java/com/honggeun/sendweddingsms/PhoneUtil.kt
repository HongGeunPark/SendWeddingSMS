package com.honggeun.sendweddingsms

/** 전화번호를 010-1234-5678 같은 한국식 형식으로 맞춘다. */
object PhoneUtil {

    fun digits(s: String): String = s.filter { it in '0'..'9' }

    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        var d = digits(trimmed)
        if (d.isEmpty()) return ""

        // +82 10-1234-5678, 0082-10-..., +82 (0)10-... 같은 국제 표기를 국내 번호로 바꾼다.
        if (d.startsWith("0082")) {
            d = "0" + d.drop(4)
        } else if (trimmed.startsWith("+") && d.startsWith("82")) {
            d = "0" + d.drop(2)
        }
        if (d.startsWith("00") && d.length >= 11) d = d.drop(1)

        return when {
            d.startsWith("02") && d.length == 9 -> "${d.substring(0, 2)}-${d.substring(2, 5)}-${d.substring(5)}"
            d.startsWith("02") && d.length == 10 -> "${d.substring(0, 2)}-${d.substring(2, 6)}-${d.substring(6)}"
            d.startsWith("0") && d.length == 11 -> "${d.substring(0, 3)}-${d.substring(3, 7)}-${d.substring(7)}"
            d.startsWith("0") && d.length == 10 -> "${d.substring(0, 3)}-${d.substring(3, 6)}-${d.substring(6)}"
            d.length == 8 && (d.startsWith("15") || d.startsWith("16") || d.startsWith("18")) ->
                "${d.substring(0, 4)}-${d.substring(4)}"
            else -> d
        }
    }

    fun isMobile(number: String): Boolean = digits(number).startsWith("01")
}
