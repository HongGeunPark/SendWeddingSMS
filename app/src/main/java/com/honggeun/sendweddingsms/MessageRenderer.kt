package com.honggeun.sendweddingsms

/** 쓴 문구에 이름과 청첩장 주소를 넣어 실제로 보낼 문자를 만든다. */
object MessageRenderer {
    const val NAME = "{이름}"
    const val NAME_CALL = "{이름아}"
    const val LINK = "{링크}"

    /** "철수" → "철수야", "길동" → "길동아" (받침에 따라). */
    fun vocative(name: String): String {
        val n = name.trim()
        val last = n.lastOrNull() ?: return n
        val hasBatchim = last in '가'..'힣' && (last - '가') % 28 != 0
        return n + if (hasBatchim) "아" else "야"
    }

    /** 청첩장 주소는 본문 아래에 빈 줄 하나를 두고 붙인다. */
    fun render(template: String, callName: String, link: String): String {
        val body = template
            .replace(NAME_CALL, vocative(callName))
            .replace(NAME, callName.trim())
            .replace(LINK, "")
            .lines()
            .joinToString("\n") { it.trimEnd() }
            .trim()
        val l = link.trim()
        return if (l.isEmpty()) body else "$body\n\n$l".trim()
    }

    /** 이 사람만 따로 고친 문구가 있으면 그걸, 없으면 공통 문구를 쓴다. */
    fun messageFor(entry: ContactEntry, template: String, custom: String?, link: String): String =
        render(custom?.takeIf { it.isNotBlank() } ?: template, entry.displayCallName, link)
}
