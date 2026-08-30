package dev.securemesh.commander.data.ble

import dev.securemesh.commander.domain.model.DeviceClassification
import org.junit.Assert.assertEquals
import org.junit.Test

class SecureMeshDeviceMatcherTest {
    @Test fun `protocol service uuid marks only a SecureMesh candidate`() {
        val config = BleProtocolConfig.ProtocolV02
        val matcher = SecureMeshDeviceMatcher(config)
        val result = matcher.match(AdvertisementSnapshot("Other", setOf(config.serviceUuid), emptyMap()))
        assertEquals(DeviceClassification.SECUREMESH_CANDIDATE, result.classification)
    }

    @Test fun `SecureMesh name without service uuid is not identity evidence`() {
        val matcher = SecureMeshDeviceMatcher(BleProtocolConfig.ProtocolV02)
        val result = matcher.match(AdvertisementSnapshot("SecureMesh Field", emptySet(), emptyMap()))
        assertEquals(DeviceClassification.UNKNOWN_BLE, result.classification)
    }

    @Test fun `random device remains unknown`() {
        val matcher = SecureMeshDeviceMatcher(BleProtocolConfig.ProtocolV02)
        assertEquals(
            DeviceClassification.UNKNOWN_BLE,
            matcher.match(AdvertisementSnapshot("Headphones", emptySet(), emptyMap())).classification,
        )
    }
}
