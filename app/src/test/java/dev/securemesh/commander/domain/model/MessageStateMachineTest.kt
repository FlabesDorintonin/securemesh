package dev.securemesh.commander.domain.model

import org.junit.Assert.*
import org.junit.Test

class MessageStateMachineTest {
    @Test fun `hop progress can end in final confirmation pending`() {
        assertTrue(MessageStateMachine.canTransition(MessageDeliveryState.QUEUED, MessageDeliveryState.ROUTING))
        assertTrue(MessageStateMachine.canTransition(MessageDeliveryState.ROUTING, MessageDeliveryState.SENDING))
        assertTrue(MessageStateMachine.canTransition(MessageDeliveryState.SENDING, MessageDeliveryState.HOP_PROGRESS))
        assertTrue(MessageStateMachine.canTransition(MessageDeliveryState.HOP_PROGRESS, MessageDeliveryState.FINAL_CONFIRMATION_PENDING))
    }

    @Test fun `hop ack alone never manufactures e2e delivered`() {
        assertEquals(MessageFinalState.UNKNOWN, MessageStateMachine.finalStateAfterHopAck())
    }

    @Test fun `terminal states do not transition`() {
        assertFalse(MessageStateMachine.canTransition(MessageDeliveryState.DELIVERED, MessageDeliveryState.SENDING))
        assertFalse(MessageStateMachine.canTransition(MessageDeliveryState.FAILED, MessageDeliveryState.QUEUED))
    }
}
