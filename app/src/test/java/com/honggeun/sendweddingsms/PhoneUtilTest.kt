package com.honggeun.sendweddingsms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneUtilTest {
    @Test fun mobile() {
        assertEquals("010-1234-5678", PhoneUtil.normalize("01012345678"))
        assertEquals("010-1234-5678", PhoneUtil.normalize("010 1234 5678"))
        assertEquals("010-1234-5678", PhoneUtil.normalize("+82 10-1234-5678"))
        assertEquals("010-1234-5678", PhoneUtil.normalize("+82 (0)10-1234-5678"))
        assertEquals("010-1234-5678", PhoneUtil.normalize("0082-10-1234-5678"))
        assertEquals("011-123-4567", PhoneUtil.normalize("0111234567"))
    }

    @Test fun landline() {
        assertEquals("02-123-4567", PhoneUtil.normalize("021234567"))
        assertEquals("02-1234-5678", PhoneUtil.normalize("02-1234-5678"))
        assertEquals("031-123-4567", PhoneUtil.normalize("0311234567"))
        assertEquals("1588-1234", PhoneUtil.normalize("15881234"))
    }

    @Test fun oddInput() {
        assertEquals("", PhoneUtil.normalize("없음"))
        assertEquals("114", PhoneUtil.normalize("114"))
    }

    @Test fun isMobile() {
        assertTrue(PhoneUtil.isMobile("010-1234-5678"))
        assertFalse(PhoneUtil.isMobile("02-123-4567"))
    }
}
