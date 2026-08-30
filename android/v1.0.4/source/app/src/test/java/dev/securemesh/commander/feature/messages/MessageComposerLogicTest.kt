package dev.securemesh.commander.feature.messages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageComposerLogicTest {
    @Test fun `ascii draft is capped to firmware byte limit`() {
        val fitted = fitMessageDraftToProtocol("x".repeat(100))
        assertEquals(70, messageUtf8Bytes(fitted))
        assertEquals(70, fitted.length)
    }

    @Test fun `unicode draft never splits an emoji code point`() {
        val fitted = fitMessageDraftToProtocol("🚀".repeat(30))
        assertTrue(messageUtf8Bytes(fitted) <= SECUREMESH_MESSAGE_MAX_UTF8_BYTES)
        assertTrue(fitted.isEmpty() || !Character.isHighSurrogate(fitted.last()))
        assertEquals(17, fitted.codePointCount(0, fitted.length))
    }

    @Test fun `cyrillic byte limit matches UTF8 not character count`() {
        val fitted = fitMessageDraftToProtocol("я".repeat(50))
        assertEquals(70, messageUtf8Bytes(fitted))
        assertEquals(35, fitted.length)
    }
}
