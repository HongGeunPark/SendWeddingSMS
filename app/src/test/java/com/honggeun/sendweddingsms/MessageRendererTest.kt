package com.honggeun.sendweddingsms

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageRendererTest {
    private val formats = listOf(
        MessageFormat("기본", "{이름}님 안녕하세요.\n{링크}"),
        MessageFormat("친구", "{이름아} 오랜만이다."),
    )
    private val link = "https://card.example/abc"

    private fun entry() = ContactEntry("01012345678", "김철수", "010-1234-5678", mutableListOf())

    @Test fun vocative() {
        assertEquals("철수야", MessageRenderer.vocative("철수"))
        assertEquals("길동아", MessageRenderer.vocative("길동"))
        assertEquals("Tom야", MessageRenderer.vocative("Tom"))
    }

    @Test fun defaultFormatWhenUnset() {
        assertEquals("김철수님 안녕하세요.\n$link", MessageRenderer.messageFor(entry(), formats, link))
    }

    @Test fun callNameAndLinkAppended() {
        val e = entry().apply { format = "친구"; callName = "철수" }
        assertEquals("철수야 오랜만이다.\n$link", MessageRenderer.messageFor(e, formats, link))
    }

    @Test fun customMessageWins() {
        val e = entry().apply { format = "친구"; customMessage = "{이름}형님, 꼭 와 주세요 {링크}" }
        assertEquals("김철수형님, 꼭 와 주세요\n$link", MessageRenderer.messageFor(e, formats, link))
    }

    @Test fun unknownFormatFallsBack() {
        val e = entry().apply { format = "지워진형식" }
        assertEquals("김철수님 안녕하세요.", MessageRenderer.messageFor(e, formats, ""))
    }
}
