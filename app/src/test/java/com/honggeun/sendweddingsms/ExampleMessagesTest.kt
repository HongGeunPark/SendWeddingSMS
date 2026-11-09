package com.honggeun.sendweddingsms

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleMessagesTest {
    @Test
    fun everyExampleUsesANameAndRendersCleanly() {
        for (ex in ExampleMessages.all) {
            assertTrue(ex.title, ex.text.contains(MessageRenderer.NAME) || ex.text.contains(MessageRenderer.NAME_CALL))
            val out = MessageRenderer.render(ex.text, "철수", "https://x")
            assertFalse(ex.title, out.contains("{"))
            assertTrue(ex.title, out.endsWith("\n\nhttps://x"))
        }
    }
}
