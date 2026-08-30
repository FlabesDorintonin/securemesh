package dev.securemesh.commander.data.transport

import dev.securemesh.commander.data.mock.MockTransport
import dev.securemesh.commander.domain.model.TransportMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TransportRouterTest {
    @Test fun `router switches transport without UI knowledge`() = runBlocking {
        val a=MockTransport(); val b=MockTransport(); val router=TransportRouter(a,b)
        assertEquals(TransportMode.BLE,router.mode.value)
        router.switchTo(TransportMode.MOCK)
        assertSame(a,router.current())
        assertEquals(TransportMode.MOCK,router.mode.value)
    }
}
