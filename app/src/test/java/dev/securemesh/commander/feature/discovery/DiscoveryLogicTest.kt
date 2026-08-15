package dev.securemesh.commander.feature.discovery

import dev.securemesh.commander.domain.model.*
import org.junit.Assert.assertEquals
import org.junit.Test

class DiscoveryLogicTest {
    private fun d(name:String?, address:String, rssi:Int, type:DeviceClassification, reasons:Set<String> = emptySet()) =
        DiscoveredDevice(address,name,rssi,1,type,BondStatus.NOT_BONDED, matchReasons = reasons)

    @Test fun `production discovery hides unknown BLE and keeps protocol candidates`() {
        val input=listOf(
            d(null,"AA:00",-25,DeviceClassification.UNKNOWN_BLE),
            d("Headphones","AA:01",-35,DeviceClassification.UNKNOWN_BLE),
            d("SecureMesh","AA:02",-55,DeviceClassification.SECUREMESH_CANDIDATE),
            d("Trusted","AA:03",-60,DeviceClassification.TRUSTED_SECUREMESH),
        )
        val out=filterDevices(input,DiscoveryFilter(),canShowUnknown=false)
        assertEquals(listOf("AA:02","AA:03"),out.map{it.address})
        assertEquals(false, isVisibleDuringDiscovery(input[0], canShowUnknown = false))
        assertEquals(true, isVisibleDuringDiscovery(input[2], canShowUnknown = false))
    }

    @Test fun `developer discovery can show raw unknown BLE without granting identity`() {
        val hinted = d("SecureMesh", "AA:01", -45, DeviceClassification.UNKNOWN_BLE, setOf("name-only-not-identity"))
        val unrelated = d("Headphones", "AA:02", -30, DeviceClassification.UNKNOWN_BLE)
        val candidate = d("Node", "AA:03", -55, DeviceClassification.SECUREMESH_CANDIDATE)
        assertEquals(true, isVisibleDuringDiscovery(hinted, canShowUnknown = true))
        assertEquals(listOf("AA:02", "AA:01", "AA:03"), filterDevices(listOf(hinted, unrelated, candidate), DiscoveryFilter(), canShowUnknown = true).map { it.address })
        assertEquals(DeviceClassification.UNKNOWN_BLE, hinted.classification)
    }

    @Test fun `securemesh-only never upgrades a name hint into protocol evidence`() {
        val input=listOf(
            d("SecureMesh Fake","1",-20,DeviceClassification.UNKNOWN_BLE,setOf("name-only-not-identity")),
            d("Candidate","2",-70,DeviceClassification.SECUREMESH_CANDIDATE),
            d("Known","3",-50,DeviceClassification.KNOWN_SECUREMESH),
            d("Trusted","4",-60,DeviceClassification.TRUSTED_SECUREMESH),
        )
        val out=filterDevices(input,DiscoveryFilter(secureMeshOnly=true,sort=DeviceSort.RSSI),canShowUnknown=true)
        assertEquals(listOf("3","4","2"),out.map{it.address})
    }

    @Test fun `developer search may use transport metadata without treating address as identity`() {
        val input=listOf(
            d("SecureMesh Alpha","AA:BB",-50,DeviceClassification.SECUREMESH_CANDIDATE),
            d("Other","CC:DD",-40,DeviceClassification.UNKNOWN_BLE),
        )
        assertEquals(1,filterDevices(input,DiscoveryFilter(query="alpha"),canShowUnknown=false).size)
        assertEquals("CC:DD",filterDevices(input,DiscoveryFilter(query="cc:dd"),canShowUnknown=true).single().address)
    }

    @Test fun `permission result requires every requested permission`() {
        assertEquals(
            true,
            permissionResultGranted(mapOf("android.permission.BLUETOOTH_SCAN" to true, "android.permission.BLUETOOTH_CONNECT" to true)),
        )
        assertEquals(
            false,
            permissionResultGranted(mapOf("android.permission.BLUETOOTH_SCAN" to true, "android.permission.BLUETOOTH_CONNECT" to false)),
        )
        assertEquals(false, permissionResultGranted(emptyMap()))
    }
}
