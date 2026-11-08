package com.honggeun.sendweddingsms

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactEntryTest {
    private val e = ContactEntry("01012345678", "홍길동", "010-1234-5678", mutableListOf("02-123-4567"))

    @Test fun byName() {
        assertTrue(e.matches("길동"))
        assertFalse(e.matches("철수"))
    }

    @Test fun byChosung() {
        assertTrue(e.matches("ㅎㄱㄷ"))
        assertTrue(e.matches("ㄱㄷ"))
        assertFalse(e.matches("ㅊㅅ"))
    }

    @Test fun byNumber() {
        assertTrue(e.matches("5678"))
        assertTrue(e.matches("1234-5678"))
        assertTrue(e.matches("1234567"))
        assertFalse(e.matches("9999"))
    }
}
