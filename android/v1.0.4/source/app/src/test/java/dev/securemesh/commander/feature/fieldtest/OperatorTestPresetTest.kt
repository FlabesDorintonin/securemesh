package dev.securemesh.commander.feature.fieldtest

import dev.securemesh.commander.domain.model.FieldTestMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OperatorTestPresetTest {
    @Test
    fun presetsKeepExpectedTestParameters() {
        assertEquals(listOf(30, 100, 200), OperatorTestPreset.entries.map { it.packetCount })
        assertEquals(listOf(500L, 1000L, 1500L), OperatorTestPreset.entries.map { it.intervalMs })
        assertEquals(listOf(16, 32, 48), OperatorTestPreset.entries.map { it.payloadBytes })
    }

    @Test
    fun operatorPresetTextContainsNoProtocolJargon() {
        val forbidden = listOf("ack", "pdr", "rssi", "snr", "gatt", "payload", "ble", "mtu")
        OperatorTestPreset.entries.forEach { preset ->
            val visibleText = "${preset.title} ${preset.description}".lowercase()
            forbidden.forEach { token ->
                assertFalse("Unexpected operator token '$token' in '$visibleText'", visibleText.contains(token))
            }
        }
    }

    @Test
    fun presetBuildsExistingFieldTestContractWithoutChangingRouteSemantics() {
        val config = OperatorTestPreset.STANDARD.toConfig(
            sourceNodeId = "NODE-A",
            targetNodeId = "NODE-B",
            mode = FieldTestMode.ROUTED,
        )

        assertEquals("NODE-A", config.source)
        assertEquals("NODE-B", config.target)
        assertEquals(FieldTestMode.ROUTED, config.mode)
        assertEquals(100, config.packetCount)
        assertEquals(1000L, config.intervalMs)
        assertEquals(32, config.payloadBytes)
    }
}
