package dev.securemesh.commander.domain.model

object MessageStateMachine {
    private val allowed = mapOf(
        MessageDeliveryState.QUEUED to setOf(MessageDeliveryState.ROUTING, MessageDeliveryState.FAILED, MessageDeliveryState.EXPIRED),
        MessageDeliveryState.ROUTING to setOf(MessageDeliveryState.SENDING, MessageDeliveryState.FAILED, MessageDeliveryState.EXPIRED),
        MessageDeliveryState.SENDING to setOf(MessageDeliveryState.HOP_PROGRESS, MessageDeliveryState.FINAL_CONFIRMATION_PENDING, MessageDeliveryState.DELIVERED, MessageDeliveryState.FAILED, MessageDeliveryState.EXPIRED),
        MessageDeliveryState.HOP_PROGRESS to setOf(MessageDeliveryState.SENDING, MessageDeliveryState.HOP_PROGRESS, MessageDeliveryState.FINAL_CONFIRMATION_PENDING, MessageDeliveryState.FAILED, MessageDeliveryState.EXPIRED),
        MessageDeliveryState.FINAL_CONFIRMATION_PENDING to setOf(MessageDeliveryState.DELIVERED, MessageDeliveryState.FAILED, MessageDeliveryState.EXPIRED),
        MessageDeliveryState.DELIVERED to emptySet(),
        MessageDeliveryState.FAILED to emptySet(),
        MessageDeliveryState.EXPIRED to emptySet(),
    )

    fun canTransition(from: MessageDeliveryState, to: MessageDeliveryState): Boolean = to in allowed.getValue(from)

    /** Hop ACK is link-layer progress only and must never manufacture end-to-end delivery. */
    fun finalStateAfterHopAck(): MessageFinalState = MessageFinalState.UNKNOWN
}
