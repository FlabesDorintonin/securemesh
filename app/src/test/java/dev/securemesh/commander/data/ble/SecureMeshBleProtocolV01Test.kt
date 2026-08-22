package dev.securemesh.commander.data.ble

import dev.securemesh.commander.domain.model.DeviceCapability
import dev.securemesh.commander.domain.model.DeviceClassification
import dev.securemesh.commander.domain.model.FieldTestConfig
import dev.securemesh.commander.domain.model.FieldTestMode
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SecureMeshBleProtocolV01Test {
    private val codec = SecureMeshBleProtocolV01Codec()

    @Test fun `GET_INFO command matches firmware v1_0_4 application protocol v2`() {
        val packet = codec.encodeCommand(0x1234, SecureMeshBleCommand.GetInfo).getOrThrow()
        assertArrayEquals(
            byteArrayOf(0x53, 0x4D, 0x02, 0x01, 0x34, 0x12, 0x01, 0x00, 0x00, 0x00),
            packet,
        )
    }

    @Test fun `wrong magic is rejected`() {
        val packet = response(1, BleOpcode.GET_STATUS, byteArrayOf())
        packet[0] = 0
        assertTrue(codec.decodeApplicationPacket(packet).isFailure)
    }

    @Test fun `old application protocol version is rejected`() {
        val packet = response(1, BleOpcode.GET_STATUS, byteArrayOf())
        packet[2] = 1
        assertTrue(codec.decodeApplicationPacket(packet).isFailure)
    }

    @Test fun `payload length mismatch is rejected`() {
        val packet = response(1, BleOpcode.GET_STATUS, byteArrayOf(1, 2))
        packet[8] = 1
        packet[9] = 0
        assertTrue(codec.decodeApplicationPacket(packet).isFailure)
    }

    @Test fun `unknown response opcode is decoded safely but not invented`() {
        val packet = responseRaw(7, 0x7F, 0, byteArrayOf())
        val frame = codec.decodeApplicationPacket(packet).getOrThrow() as SecureMeshBleFrame.Response
        assertNull(frame.opcode)
        assertEquals(0x7F, frame.rawOpcode)
    }

    @Test fun `INFO parses little endian identity capabilities and security`() {
        val payload = ByteArray(23)
        payload[0] = 2
        payload[1] = 3
        payload[2] = 2
        payload[3] = 1
        payload[4] = 0
        payload[5] = 4
        putU32(payload, 6, 0xA1B2C3D4L)
        payload[10] = 1
        putU32(payload, 11, 0b1_1111)
        putU16(payload, 15, 0x1234)
        payload[17] = 5
        payload[18] = 0b111
        putU32(payload, 19, 0b1111)
        val frame = codec.decodeApplicationPacket(response(0, BleOpcode.GET_INFO, payload)).getOrThrow() as SecureMeshBleFrame.Response
        val info = codec.parseInfo(frame).getOrThrow()
        assertEquals("A1B2C3D4", info.localNodeId)
        assertEquals("1.0.4", info.firmwareVersion)
        assertEquals(2, info.bleProtocolVersion)
        assertTrue(info.authenticated)
        assertTrue(info.bonded)
        assertEquals(5, SecureMeshBleV01DomainMapping.capabilities(info.capabilityMask).size)
        assertFalse(DeviceCapability.GPS in SecureMeshBleV01DomainMapping.capabilities(info.capabilityMask))
        assertFalse(DeviceCapability.SOS in SecureMeshBleV01DomainMapping.capabilities(info.capabilityMask))
        assertFalse(DeviceCapability.OTA in SecureMeshBleV01DomainMapping.capabilities(info.capabilityMask))
    }

    @Test fun `name alone is not SecureMesh identity`() {
        val matcher = SecureMeshDeviceMatcher(BleProtocolConfig.FirmwareV104)
        val nameOnly = matcher.match(AdvertisementSnapshot("SecureMesh", emptySet(), emptyMap()))
        assertEquals(DeviceClassification.UNKNOWN_BLE, nameOnly.classification)
        val byService = matcher.match(AdvertisementSnapshot("anything", setOf(BleProtocolConfig.FirmwareV104.serviceUuid), emptyMap()))
        assertEquals(DeviceClassification.SECUREMESH_CANDIDATE, byService.classification)
    }

    @Test fun `MTU 23 keeps fragment transport v1 while carrying application v2`() {
        val packet = codec.encodeCommand(0x1234, SecureMeshBleCommand.GetInfo).getOrThrow()
        val fragments = SecureMeshBleFragmentation.fragment(packet, 23, 1).getOrThrow()
        assertEquals(2, fragments.size)
        assertArrayEquals(
            byteArrayOf(0x53,0x46,0x01,0x01,0x00,0x00,0x02,0x0A,0x00,0x00,0x00,0x08,0x53,0x4D,0x02,0x01,0x34,0x12,0x01,0x00),
            fragments[0],
        )
        assertArrayEquals(
            byteArrayOf(0x53,0x46,0x01,0x01,0x00,0x01,0x02,0x0A,0x00,0x08,0x00,0x02,0x00,0x00),
            fragments[1],
        )
        val r = SecureMeshBleFragmentation.Reassembler()
        assertEquals(SecureMeshBleFragmentation.AcceptResult.Incomplete, r.accept(fragments[0], 0))
        val complete = r.accept(fragments[1], 1) as SecureMeshBleFragmentation.AcceptResult.Complete
        assertArrayEquals(packet, complete.packet)
    }

    @Test fun `out of order fragment is rejected because transport requires sequential order`() {
        val packet = ByteArray(100) { it.toByte() }.also {
            it[0] = 0x53; it[1] = 0x4D; it[2] = 2; it[3] = 2
        }
        val fragments = SecureMeshBleFragmentation.fragment(packet, 23, 9).getOrThrow()
        val r = SecureMeshBleFragmentation.Reassembler()
        val result = r.accept(fragments[1], 0)
        assertTrue(result is SecureMeshBleFragmentation.AcceptResult.Rejected)
    }

    @Test fun `overlap or impossible fragment bounds are rejected`() {
        val packet = codec.encodeCommand(1, SecureMeshBleCommand.GetInfo).getOrThrow()
        val fragment = SecureMeshBleFragmentation.fragment(packet, 185, 2).getOrThrow().single().clone()
        fragment[9] = 10
        fragment[10] = 0
        val result = SecureMeshBleFragmentation.Reassembler().accept(fragment, 0)
        assertTrue(result is SecureMeshBleFragmentation.AcceptResult.Rejected)
    }

    @Test fun `incomplete reassembly expires`() {
        val packet = ByteArray(100) { 0 }.also { it[0]=0x53; it[1]=0x4D; it[2]=2; it[3]=2 }
        val fragments = SecureMeshBleFragmentation.fragment(packet, 23, 3).getOrThrow()
        val r = SecureMeshBleFragmentation.Reassembler(3_000)
        r.accept(fragments[0], 100)
        assertTrue(r.expire(3_101))
    }

    @Test fun `response requestId completes only matching request`() = runTest {
        val manager = BleRequestManager(timeoutMs = 100)
        val handle = manager.allocate(BleOpcode.GET_STATUS).getOrThrow()
        val frame = SecureMeshBleFrame.Response(handle.requestId, BleOpcode.GET_STATUS, BleOpcode.GET_STATUS.wire, BleCommandStatus.OK, 0, byteArrayOf())
        assertTrue(manager.accept(frame))
        assertSame(frame, manager.await(handle).getOrThrow())
    }

    @Test fun `EVENT never completes pending request and request times out`() = runTest {
        val manager = BleRequestManager(timeoutMs = 50)
        val handle = manager.allocate(BleOpcode.GET_STATUS).getOrThrow()
        val waiting = async { manager.await(handle) }
        val event = SecureMeshBleFrame.Event(BleEventType.BLE_STATE, BleEventType.BLE_STATE.wire, BleCommandStatus.OK, 0, byteArrayOf(5))
        assertFalse(manager.accept(event))
        advanceTimeBy(60)
        assertTrue(waiting.await().isFailure)
    }

    @Test fun `field test first hop ACK is not end to end success`() {
        val status = BleFieldTestStatusPayload(
            state=1, mode=0, testId=7, target="AABBCCDD", elapsedMs=1000, requestedPackets=10,
            sentProbes=10, firstHopAcked=10, firstHopFinalFailures=0, firstHopRetryTimeouts=2,
            endToEndReplies=3, endToEndTimeouts=7, currentSequence=10, firstNextHop="11223344", routeSource=2,
            averageRttMs=90, minimumRttMs=70, maximumRttMs=120, endToEndPdr=.3,
            averageFirstHopRssiDbm=-71.2, averageFirstHopSnrDb=5.4,
        )
        val mapped = SecureMeshBleV01DomainMapping.fieldTest(status, "01020304", null, 10_000)!!
        assertEquals(10, mapped.firstHopAcked)
        assertEquals(3, mapped.confirmedReceived)
        assertEquals(.3, mapped.pdr!!, .0001)
        assertEquals("11223344", mapped.currentNextHop)
    }

    @Test fun `START_FIELD_TEST validates firmware v1_0_4 bounds`() {
        assertTrue(codec.encodeCommand(1, SecureMeshBleCommand.StartFieldTest("AABBCCDD", 500, 60_000, 70, false)).isSuccess)
        assertTrue(codec.encodeCommand(1, SecureMeshBleCommand.StartFieldTest("AABBCCDD", 501, 1_000, 32, false)).isFailure)
    }

    private fun response(requestId: Int, opcode: BleOpcode, payload: ByteArray, status: Int = 0) = responseRaw(requestId, opcode.wire, status, payload)

    private fun responseRaw(requestId: Int, opcode: Int, status: Int, payload: ByteArray): ByteArray = ByteArray(10 + payload.size).also { out ->
        putU16(out, 0, 0x4D53)
        out[2] = 2
        out[3] = 2
        putU16(out, 4, requestId)
        out[6] = opcode.toByte()
        out[7] = status.toByte()
        putU16(out, 8, payload.size)
        payload.copyInto(out, 10)
    }

    private fun putU16(out: ByteArray, offset: Int, value: Int) {
        out[offset] = (value and 0xFF).toByte(); out[offset+1] = ((value ushr 8) and 0xFF).toByte()
    }
    private fun putU32(out: ByteArray, offset: Int, value: Long) {
        repeat(4) { out[offset+it] = ((value ushr (8*it)) and 0xFF).toByte() }
    }
}
