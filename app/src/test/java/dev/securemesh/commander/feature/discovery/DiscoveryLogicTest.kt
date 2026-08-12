package dev.securemesh.commander.feature.discovery

import dev.securemesh.commander.domain.model.*
import org.junit.Assert.assertEquals
import org.junit.Test

class DiscoveryLogicTest {
    private fun d(name:String?, address:String, rssi:Int, type:DeviceClassification, reasons:Set<String> = emptySet())=
        DiscoveredDevice(address,name,rssi,1,type,BondStatus.NOT_BONDED, matchReasons = reasons)

    @Test fun `default discovery filter keeps every raw BLE device`() {
        val input=listOf(
            d(null,"AA:00",-25,DeviceClassification.UNKNOWN_BLE),
            d("Headphones","AA:01",-35,DeviceClassification.UNKNOWN_BLE),
            d("SecureMesh","AA:02",-55,DeviceClassification.SECUREMESH_CANDIDATE),
        )
        val out=filterDevices(input,DiscoveryFilter())
        assertEquals(listOf("AA:00","AA:01","AA:02"),out.map{it.address})
    }

    @Test fun `securemesh filter accepts candidate known and trusted but rejects unrelated unknown`() {
        val input=listOf(
            d("Other","2",-30,DeviceClassification.UNKNOWN_BLE),
            d("Candidate","1",-70,DeviceClassification.SECUREMESH_CANDIDATE),
            d("Known","3",-50,DeviceClassification.KNOWN_SECUREMESH),
            d("Trusted","4",-60,DeviceClassification.TRUSTED_SECUREMESH),
        )
        val out=filterDevices(input,DiscoveryFilter(secureMeshOnly=true,sort=DeviceSort.RSSI))
        assertEquals(listOf("3","4","1"),out.map{it.address})
    }

    @Test fun `name only SecureMesh hint stays filter relevant but is not identity`() {
        val hinted = d("SecureMesh", "AA:01", -45, DeviceClassification.UNKNOWN_BLE, setOf("name-only-not-identity"))
        val unrelated = d("Headphones", "AA:02", -30, DeviceClassification.UNKNOWN_BLE)
        assertEquals(true, isVisibleDuringDiscovery(hinted, canShowUnknown = false))
        assertEquals(false, isVisibleDuringDiscovery(unrelated, canShowUnknown = false))
        assertEquals(listOf("AA:01"), filterDevices(listOf(hinted, unrelated), DiscoveryFilter(secureMeshOnly = true)).map { it.address })
        assertEquals(DeviceClassification.UNKNOWN_BLE, hinted.classification)
    }

    @Test fun `searches transport metadata without treating address as SecureMesh identity`() {
        val input=listOf(
            d("SecureMesh Alpha","AA:BB",-50,DeviceClassification.SECUREMESH_CANDIDATE),
            d("Other","CC:DD",-40,DeviceClassification.UNKNOWN_BLE),
        )
        assertEquals(1,filterDevices(input,DiscoveryFilter(query="alpha")).size)
        assertEquals("CC:DD",filterDevices(input,DiscoveryFilter(query="cc:dd")).single().address)
    }
}
