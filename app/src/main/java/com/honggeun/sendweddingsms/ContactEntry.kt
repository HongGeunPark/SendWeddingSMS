package com.honggeun.sendweddingsms

class ContactEntry(
    /** 대표 번호의 숫자만 모은 값. 저장할 때 키로 쓴다. */
    val key: String,
    val name: String,
    val phone: String,
    val otherPhones: MutableList<String>,
) {
    /** 문자에서 부를 이름. 비어 있으면 연락처 이름을 쓴다. 한 번 정하면 다음에도 기억한다. */
    var callName: String = ""
    /** 마지막으로 보낸 시각(밀리초). 0이면 아직 한 번도 안 보냄. */
    var sentAt: Long = 0L
    /** 마지막으로 보낸 문자 내용. */
    var lastMessage: String = ""

    val displayCallName: String get() = callName.trim().ifEmpty { name }

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
        if (callName.isNotEmpty() && callName.lowercase().contains(q.lowercase())) return true
        val qDigits = PhoneUtil.digits(q)
        return qDigits.length >= 2 && qDigits.length == q.replace("-", "").replace(" ", "").length &&
            allDigits.contains(qDigits)
    }
}
