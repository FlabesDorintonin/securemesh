package dev.securemesh.commander.data.ble

import dev.securemesh.commander.domain.model.DeviceClassification
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class SecureMeshDeviceMatcherTest {
    @Test fun `configured service uuid marks only a SecureMesh candidate`() {
        val uuid = UUID.fromString("00000000-0000-0000-0000-000000000123")
        val matcher = SecureMeshDeviceMatcher(BleProtocolConfig(serviceUuid = uuid))
        val result = matcher.match(AdvertisementSnapshot("Other", setOf(uuid), emptyMap()))
        assertEquals(DeviceClassification.SECUREMESH_CANDIDATE, result.classification)
    }

    @Test fun `development name is candidate evidence not trust`() {
        val matcher = SecureMeshDeviceMatcher(BleProtocolConfig.Development)
        val result = matcher.match(AdvertisementSnapshot("SecureMesh Field", emptySet(), emptyMap()))
        assertEquals(DeviceClassification.SECUREMESH_CANDIDATE, result.classification)
    }

    @Test fun `random device remains unknown`() {
        val matcher = SecureMeshDeviceMatcher(BleProtocolConfig.Development)
        assertEquals(
            DeviceClassification.UNKNOWN_BLE,
            matcher.match(AdvertisementSnapshot("Headphones", emptySet(), emptyMap())).classification,
        )
    }
}
