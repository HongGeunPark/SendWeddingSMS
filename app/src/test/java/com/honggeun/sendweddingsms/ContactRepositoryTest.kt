package com.honggeun.sendweddingsms

import org.junit.Assert.assertEquals
import org.junit.Test
import com.honggeun.sendweddingsms.ContactRepository.RawPhone

class ContactRepositoryTest {
    @Test fun mergesAndPrefersMobile() {
        val rows = listOf(
            RawPhone(1, "홍길동", "02-123-4567"),
            RawPhone(1, "홍길동", "+82 10-1111-2222"),
            RawPhone(2, "홍길동(SIM)", "01011112222"),   // 같은 번호의 중복 연락처
            RawPhone(3, "가나다", "010-3333-4444"),
            RawPhone(4, "", "010-5555-6666"),
            RawPhone(5, "번호없음", "없음"),
        )
        val list = ContactRepository.build(rows)
        assertEquals(listOf("(이름 없음)", "가나다", "홍길동"), list.map { it.name })
        val hong = list.last()
        assertEquals("010-1111-2222", hong.phone)
        assertEquals(listOf("02-123-4567"), hong.otherPhones)
    }
}
