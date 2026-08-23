package dev.securemesh.commander.data.ble

import dev.securemesh.commander.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class SecureMeshBleV104ExtendedTest {
    private val codec = SecureMeshBleProtocolV01Codec()

    @Test fun `extended operational payload sizes decode exactly`() {
        val health = ByteArray(17).also {
            it[0]=1; it[1]=88.toByte(); it[2]=3; putU16(it,3,0x0012)
            it[5]=90.toByte(); it[6]=80.toByte(); it[7]=85.toByte(); it[8]=95.toByte(); it[9]=99.toByte(); it[10]=70; it[11]=100.toByte()
            it[12]=2; it[13]=3; it[14]=1; it[15]=0; it[16]=16
        }
        val h = codec.parseOperationalHealth(response(BleOpcode.GET_OPERATIONAL_HEALTH, health)).getOrThrow()
        assertEquals(88, h.score)
        assertEquals(OperationalLevel.EXCELLENT, h.level)
        assertEquals(1, h.backupRouteCount)

        val self = ByteArray(43).also {
            it[0]=1; it[1]=91.toByte(); it[2]=3; putU16(it,3,0)
            it[5]=1; it[6]=1; it[7]=1; it[8]=2; it[9]=1; it[10]=2; it[11]=3; it[12]=1; it[13]=0; it[14]=16
            putU32(it,15,150000); putU32(it,19,80000); putU32(it,23,50); putU32(it,27,1); putU32(it,31,0); putU32(it,35,2); putU32(it,39,0)
        }
        val d = codec.parseSelfDiagnostics(response(BleOpcode.GET_SELF_DIAGNOSTICS, self)).getOrThrow()
        assertTrue(d.radioReady)
        assertTrue(d.protectionReady)
        assertEquals(150000, d.freeHeapBytes)

        assertTrue(codec.parseSelfDiagnostics(response(BleOpcode.GET_SELF_DIAGNOSTICS, self.copyOf(42))).isFailure)
        assertTrue(codec.parseOperationalHealth(response(BleOpcode.GET_OPERATIONAL_HEALTH, health.copyOf(16))).isFailure)
    }

    @Test fun `position radar and sos payloads decode with current firmware layout`() {
        val positions = ByteArray(36)
        positions[0]=1
        putU32(positions,1,0xAABBCCDDL)
        positions[5]=1; positions[6]=0x1D; putU16(positions,7,7); putU32(positions,9,1_700_000_000L)
        putI32(positions,13,539000000); putI32(positions,17,254000000); putI32(positions,21,12345)
        putU16(positions,25,250); putU16(positions,27,135); positions[29]=9; putU16(positions,30,500); putU32(positions,32,120)
        val p = codec.parsePositions(response(BleOpcode.GET_POSITIONS, positions)).getOrThrow().single()
        assertEquals("AABBCCDD", p.nodeId)
        assertTrue(p.hasFix)
        assertEquals(9, p.satellites)

        val radar = ByteArray(12).also { it[0]=1; it[1]=1; it[2]=1; it[3]=0; putU32(it,4,42); putU32(it,8,100) }
        val r = codec.parseBleRadar(response(BleOpcode.GET_BLE_RADAR, radar)).getOrThrow()
        assertTrue(r.configured)
        assertTrue(r.scanning)
        assertTrue(r.devices.isEmpty())

        val sos = ByteArray(29)
        putU32(sos,0,0xAABBCCDDL); sos[4]=1; sos[5]=2; sos[6]=1; sos[7]=0; putU32(sos,8,0x01020304)
        putU32(sos,12,1_700_000_001L); putI32(sos,16,539000000); putI32(sos,20,254000000); putU32(sos,24,1000); sos[28]=77
        val event = codec.decodeApplicationPacket(event(BleEventType.SOS_RAISED, sos)).getOrThrow() as SecureMeshBleFrame.Event
        val decoded = codec.parseEvent(event).getOrThrow() as BleDecodedEvent.SosRaised
        assertEquals("AABBCCDD", decoded.origin)
        assertEquals(77, decoded.batteryPercent)
    }

    @Test fun `v1_0_4 capability and route source mapping is current`() {
        val caps = SecureMeshBleV01DomainMapping.capabilities(
            (1L shl 0) or (1L shl 6) or (1L shl 9) or (1L shl 10) or (1L shl 13) or (1L shl 14)
        )
        assertTrue(DeviceCapability.MESSAGING in caps)
        assertTrue(DeviceCapability.ROUTING in caps)
        assertTrue(DeviceCapability.GPS in caps)
        assertTrue(DeviceCapability.SOS in caps)
        assertTrue(DeviceCapability.NETWORK_DIAGNOSTICS in caps)
        assertEquals(RouteType.DYNAMIC, SecureMeshBleV01DomainMapping.route(BleRoutePayload("00000002","00000003",2), 1)?.type)
        assertEquals(RouteType.STATIC, SecureMeshBleV01DomainMapping.route(BleRoutePayload("00000002","00000003",4), 1)?.type)
    }

    private fun response(opcode: BleOpcode, payload: ByteArray): SecureMeshBleFrame.Response {
        val bytes=applicationPacket(2,1,opcode.wire,0,payload)
        return codec.decodeApplicationPacket(bytes).getOrThrow() as SecureMeshBleFrame.Response
    }
    private fun event(type: BleEventType, payload: ByteArray)=applicationPacket(3,0,type.wire,0,payload)
    private fun applicationPacket(type:Int,requestId:Int,opcode:Int,status:Int,payload:ByteArray)=ByteArray(10+payload.size).also {
        putU16(it,0,0x4D53); it[2]=2; it[3]=type.toByte(); putU16(it,4,requestId); it[6]=opcode.toByte(); it[7]=status.toByte(); putU16(it,8,payload.size); payload.copyInto(it,10)
    }
    private fun putU16(out:ByteArray,o:Int,v:Int){out[o]=(v and 255).toByte();out[o+1]=((v ushr 8) and 255).toByte()}
    private fun putU32(out:ByteArray,o:Int,v:Long){repeat(4){out[o+it]=((v ushr (8*it)) and 255).toByte()}}
    private fun putI32(out:ByteArray,o:Int,v:Int)=putU32(out,o,v.toLong() and 0xFFFF_FFFFL)
}
