package com.honggeun.sendweddingsms

data class MessageFormat(val name: String, val text: String)

/** 형식 문구에 이름과 청첩장 주소를 넣어 실제로 보낼 문자를 만든다. */
object MessageRenderer {
    const val NAME = "{이름}"
    const val NAME_CALL = "{이름아}"
    const val LINK = "{링크}"

    val DEFAULT_FORMATS = listOf(
        MessageFormat(
            "기본",
            "안녕하세요, {이름}님. 저희 아들이 결혼하게 되어 소식 전합니다. " +
                "직접 찾아뵙고 인사드려야 하는데 모바일 청첩장으로 대신하는 점 양해 부탁드립니다.\n{링크}"
        ),
        MessageFormat(
            "어르신",
            "{이름}님, 그간 평안하셨습니까. 저희 아들이 결혼을 하게 되어 연락드립니다. " +
                "찾아뵙고 청첩장을 드려야 마땅하나 모바일 청첩장으로 대신 인사드리는 점 너그러이 양해해 주십시오.\n{링크}"
        ),
        MessageFormat(
            "친구",
            "{이름아} 오랜만에 연락한다. 우리 아들이 결혼을 한다고 해서 연락한다. " +
                "청첩장을 만나서 줘야 하는데 만나지 못해서 모바일 청첩장으로 보낸다.\n{링크}"
        ),
        MessageFormat(
            "직원",
            "{이름}님, 저희 아들이 결혼하게 되어 소식 전합니다. 바쁘시겠지만 시간 되시면 함께 축하해 주세요.\n{링크}"
        ),
        MessageFormat(
            "거래처",
            "{이름}님 안녕하십니까. 늘 감사드립니다. 저희 아들이 결혼을 하게 되어 연락드립니다. " +
                "직접 찾아뵙고 전해드려야 하나 모바일 청첩장으로 인사드리는 점 양해 부탁드립니다.\n{링크}"
        ),
    )

    /** "철수" → "철수야", "길동" → "길동아" (받침에 따라). */
    fun vocative(name: String): String {
        val n = name.trim()
        val last = n.lastOrNull() ?: return n
        val hasBatchim = last in '가'..'힣' && (last - '가') % 28 != 0
        return n + if (hasBatchim) "아" else "야"
    }

    /** 문구에 {링크}가 없고 주소가 있으면 맨 끝에 주소를 붙인다. */
    fun render(template: String, callName: String, link: String): String {
        var t = template.replace(NAME_CALL, vocative(callName)).replace(NAME, callName.trim())
        val l = link.trim()
        t = when {
            t.contains(LINK) -> t.replace(LINK, l)
            l.isNotEmpty() -> t.trimEnd() + "\n" + l
            else -> t
        }
        return t.trim()
    }

    fun formatFor(entry: ContactEntry, formats: List<MessageFormat>): MessageFormat =
        formats.firstOrNull { it.name == entry.format } ?: formats.firstOrNull() ?: DEFAULT_FORMATS.first()

    fun messageFor(entry: ContactEntry, formats: List<MessageFormat>, link: String): String =
        render(entry.customMessage.ifBlank { formatFor(entry, formats).text }, entry.displayCallName, link)
}
