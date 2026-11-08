package com.honggeun.sendweddingsms

/** 초성 검색(예: "ㅎㄱㄷ" → "홍길동")을 위한 도우미. */
object Hangul {
    private val CHOSUNG = charArrayOf(
        'ㄱ', 'ㄲ', 'ㄴ', 'ㄷ', 'ㄸ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅃ', 'ㅅ',
        'ㅆ', 'ㅇ', 'ㅈ', 'ㅉ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ'
    )

    fun chosung(text: String): String = buildString {
        for (c in text) {
            if (c in '가'..'힣') append(CHOSUNG[(c - '가') / 588]) else append(c)
        }
    }

    fun isChosungOnly(text: String): Boolean =
        text.isNotEmpty() && text.all { it in CHOSUNG || it == ' ' }
}
