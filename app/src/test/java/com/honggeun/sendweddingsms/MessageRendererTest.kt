package com.honggeun.sendweddingsms

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageRendererTest {
    private val link = "https://card.example/abc"
    private val template = "{이름}님 안녕하세요."

    private fun entry() = ContactEntry("01012345678", "김철수", "010-1234-5678", mutableListOf())

    @Test fun vocative() {
        assertEquals("철수야", MessageRenderer.vocative("철수"))
        assertEquals("길동아", MessageRenderer.vocative("길동"))
        assertEquals("Tom야", MessageRenderer.vocative("Tom"))
    }

    @Test fun commonMessageWithLinkAfterBlankLine() {
        assertEquals("김철수님 안녕하세요.\n\n$link", MessageRenderer.messageFor(entry(), template, null, link))
    }

    @Test fun callNameUsed() {
        val e = entry().apply { callName = "철수" }
        assertEquals("철수야 오랜만이다.\n\n$link", MessageRenderer.messageFor(e, "{이름아} 오랜만이다.", null, link))
    }

    @Test fun customMessageWins() {
        assertEquals(
            "김철수형님, 꼭 와 주세요\n\n$link",
            MessageRenderer.messageFor(entry(), template, "{이름}형님, 꼭 와 주세요 {링크}", link),
        )
    }

    @Test fun blankCustomFallsBackAndNoLink() {
        assertEquals("김철수님 안녕하세요.", MessageRenderer.messageFor(entry(), template, "  ", ""))
    }
}
