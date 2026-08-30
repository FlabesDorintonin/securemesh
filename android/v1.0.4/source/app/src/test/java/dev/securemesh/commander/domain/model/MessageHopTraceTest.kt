package dev.securemesh.commander.domain.model

import org.junit.Assert.*
import org.junit.Test

class MessageHopTraceTest {
    @Test fun `all hop acks still allow unknown final delivery`() {
        val hops = listOf(
            TransmissionHop("SM-LOCAL","SM-RELAY","F1",HopAckState.ACKED,0,-72,7.0,2L),
            TransmissionHop("SM-RELAY","SM-DEST","F2",HopAckState.ACKED,1,-81,4.0,3L),
        )
        val message = MeshMessage(
            id="M1", origin="SM-LOCAL", destination="SM-DEST", payload="hello", createdAtEpochMs=1L,
            progressState=MessageDeliveryState.FINAL_CONFIRMATION_PENDING,
            finalState=MessageFinalState.UNKNOWN,
            hopTrace=hops,
        )
        assertEquals(listOf("SM-LOCAL","SM-RELAY","SM-DEST"), message.observedRoute())
        assertEquals(MessageFinalState.UNKNOWN, message.finalState)
        assertNull(message.deliveredAtEpochMs)
        assertEquals(1, message.totalRetries())
    }
}
