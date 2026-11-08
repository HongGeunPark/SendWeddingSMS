package com.honggeun.sendweddingsms

class ContactEntry(
    /** 대표 번호의 숫자만 모은 값. 체크 상태를 저장할 때 키로 쓴다. */
    val key: String,
    val name: String,
    val phone: String,
    val otherPhones: MutableList<String>,
) {
    var selected: Boolean = false
    var relation: String = ""

    private val lowerName: String by lazy { name.lowercase() }
    private val nameChosung: String by lazy { Hangul.chosung(name) }
    private val allDigits: String by lazy {
        (listOf(phone) + otherPhones).joinToString(" ") { PhoneUtil.digits(it) }
    }

    fun matches(query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        if (Hangul.isChosungOnly(q)) {
            return nameChosung.replace(" ", "").contains(q.replace(" ", ""))
        }
        if (lowerName.contains(q.lowercase())) return true
        val qDigits = PhoneUtil.digits(q)
        return qDigits.length >= 2 && qDigits.length == q.replace("-", "").replace(" ", "").length &&
            allDigits.contains(qDigits)
    }
}
