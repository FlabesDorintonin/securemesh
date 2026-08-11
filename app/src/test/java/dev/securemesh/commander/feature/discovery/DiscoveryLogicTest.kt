package dev.securemesh.commander.feature.discovery

import dev.securemesh.commander.domain.model.*
import org.junit.Assert.assertEquals
import org.junit.Test

class DiscoveryLogicTest {
    private fun d(name:String?, address:String, rssi:Int, type:DeviceClassification)=
        DiscoveredDevice(address,name,rssi,1,type,BondStatus.NOT_BONDED)

    @Test fun `securemesh filter accepts candidate known and trusted but rejects unknown`() {
        val input=listOf(
            d("Other","2",-30,DeviceClassification.UNKNOWN_BLE),
            d("Candidate","1",-70,DeviceClassification.SECUREMESH_CANDIDATE),
            d("Known","3",-50,DeviceClassification.KNOWN_SECUREMESH),
            d("Trusted","4",-60,DeviceClassification.TRUSTED_SECUREMESH),
        )
        val out=filterDevices(input,DiscoveryFilter(secureMeshOnly=true,sort=DeviceSort.RSSI))
        assertEquals(listOf("3","4","1"),out.map{it.address})
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
