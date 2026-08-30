package dev.securemesh.commander.data.ble

import dev.securemesh.commander.domain.model.DeviceCapability
import dev.securemesh.commander.domain.model.DeviceClassification
import dev.securemesh.commander.domain.model.FieldTestConfig
import dev.securemesh.commander.domain.model.FieldTestMode
import dev.securemesh.commander.domain.model.DeviceUiAction
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SecureMeshBleProtocolV02Test {
    private val codec = SecureMeshBleProtocolV02Codec()

    @Test fun `GET_INFO command matches protocol example`() {
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

    @Test fun `wrong version is rejected`() {
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
        payload[1] = 8
        payload[2] = 2
        payload[3] = 0
        payload[4] = 9
        payload[5] = 0
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
        assertEquals("0.9.0", info.firmwareVersion)
        assertTrue(info.authenticated)
        assertTrue(info.bonded)
        assertEquals(5, SecureMeshBleV02DomainMapping.capabilities(info.capabilityMask).size)
        assertFalse(DeviceCapability.GPS in SecureMeshBleV02DomainMapping.capabilities(info.capabilityMask))
        assertFalse(DeviceCapability.SOS in SecureMeshBleV02DomainMapping.capabilities(info.capabilityMask))
        assertFalse(DeviceCapability.OTA in SecureMeshBleV02DomainMapping.capabilities(info.capabilityMask))
    }

    @Test fun `name alone is not SecureMesh identity`() {
        val matcher = SecureMeshDeviceMatcher(BleProtocolConfig.ProtocolV02)
        val nameOnly = matcher.match(AdvertisementSnapshot("SecureMesh", emptySet(), emptyMap()))
        assertEquals(DeviceClassification.UNKNOWN_BLE, nameOnly.classification)
        val byService = matcher.match(AdvertisementSnapshot("anything", setOf(BleProtocolConfig.ProtocolV02.serviceUuid), emptyMap()))
        assertEquals(DeviceClassification.SECUREMESH_CANDIDATE, byService.classification)
    }

    @Test fun `MTU 23 fragments protocol example sequentially`() {
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

    @Test fun `out of order fragment is rejected because v0_2 requires sequential order`() {
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
        // offset = totalLength, while fragmentLength is still non-zero.
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
        val mapped = SecureMeshBleV02DomainMapping.fieldTest(status, "01020304", null, 10_000)!!
        assertEquals(10, mapped.firstHopAcked)
        assertEquals(3, mapped.confirmedReceived)
        assertEquals(.3, mapped.pdr!!, .0001)
        assertEquals("11223344", mapped.currentNextHop)
    }

    @Test fun `START_FIELD_TEST validates documented bounds`() {
        assertTrue(codec.encodeCommand(1, SecureMeshBleCommand.StartFieldTest("AABBCCDD", 500, 60_000, 70, false)).isSuccess)
        assertTrue(codec.encodeCommand(1, SecureMeshBleCommand.StartFieldTest("AABBCCDD", 501, 1_000, 32, false)).isFailure)
    }

    @Test fun `firmware 0_9 capability mask exposes command map GPS SOS and VANGUARD`() {
        val mapped = SecureMeshBleV02DomainMapping.capabilities((1L shl 12) - 1L)
        assertTrue(DeviceCapability.UI_OS in mapped)
        assertTrue(DeviceCapability.VANGUARD in mapped)
        assertTrue(DeviceCapability.MANIFEST in mapped)
        assertTrue(DeviceCapability.FAULT_LAB in mapped)
        assertTrue(DeviceCapability.ROUTING in mapped)
        assertTrue(DeviceCapability.NETWORK_DIAGNOSTICS in mapped)
        assertTrue(DeviceCapability.GPS in mapped)
        assertTrue(DeviceCapability.SOS in mapped)
    }

    @Test fun `UI OS v4 state is exactly 29 bytes and preserves node identity`() {
        val payload = ByteArray(29)
        payload[0] = 4
        payload[1] = 2
        payload[2] = 9
        payload[3] = 2; payload[4] = 1; payload[5] = 3
        payload[6] = 39
        payload[7] = 0b0010_1111
        payload[8] = 3; payload[9] = 2; payload[10] = 2; payload[11] = 4
        payload[12] = 1; payload[13] = 5; payload[14] = 0; payload[15] = 1; payload[16] = 2
        putU32(payload, 17, 0xA1B2C3D4L)
        putU32(payload, 21, 0x01020304L)
        putU32(payload, 25, 0x11223344L)
        val frame = codec.decodeApplicationPacket(response(7, BleOpcode.GET_UI_STATE, payload)).getOrThrow() as SecureMeshBleFrame.Response
        val ui = codec.parseUiState(frame).getOrThrow()
        assertEquals(4, ui.modelVersion)
        assertEquals("A1B2C3D4", ui.localNodeId)
        assertEquals(9, ui.menu)
        assertEquals(39, ui.feature)
        assertEquals(0x01020304L, ui.fieldTestId)
        assertEquals("11223344", ui.fieldTestTarget)
        val mapped = SecureMeshBleV02DomainMapping.deviceUiState(ui, 1234)
        assertEquals(dev.securemesh.commander.domain.model.DeviceUiMenu.QUICK, mapped.menu)
        assertTrue(mapped.oledReady)
        assertTrue(mapped.bleProtocolReady)
    }

    @Test fun `VANGUARD commands 15 through 23 encode exact opcode and payload shapes`() {
        fun opcodeOf(command: SecureMeshBleCommand): Int = codec.encodeCommand(0x44, command).getOrThrow()[6].toInt() and 0xFF
        assertEquals(15, opcodeOf(SecureMeshBleCommand.GetKnownNodes))
        assertEquals(16, opcodeOf(SecureMeshBleCommand.GetManifest))
        assertEquals(17, opcodeOf(SecureMeshBleCommand.SetManifest(7, listOf("00000001", "00000002", "00000003"))))
        assertEquals(18, opcodeOf(SecureMeshBleCommand.DiscoverRoute("00000003", true)))
        assertEquals(19, opcodeOf(SecureMeshBleCommand.GetRoutingDiagnostics))
        assertEquals(20, opcodeOf(SecureMeshBleCommand.InjectLinkFailure("00000002", 5_000)))
        assertEquals(21, opcodeOf(SecureMeshBleCommand.ClearDynamicRoutes))
        assertEquals(22, opcodeOf(SecureMeshBleCommand.SetLabLinkPolicy("00000002", 0b10, 30_000, 23592, 183501)))
        assertEquals(23, opcodeOf(SecureMeshBleCommand.GetLabLinkPolicies))

        val manifest = codec.encodeCommand(1, SecureMeshBleCommand.SetManifest(0x11223344, listOf("AABBCCDD", "01020304"))).getOrThrow()
        assertEquals(13, (manifest[8].toInt() and 0xFF) or ((manifest[9].toInt() and 0xFF) shl 8))
        assertArrayEquals(byteArrayOf(0x44,0x33,0x22,0x11,0x02,0xDD.toByte(),0xCC.toByte(),0xBB.toByte(),0xAA.toByte(),0x04,0x03,0x02,0x01), manifest.copyOfRange(10, 23))

        val lab = codec.encodeCommand(1, SecureMeshBleCommand.SetLabLinkPolicy("11223344", 0b11, 0x01020304, 0x5678, 0x11223344)).getOrThrow()
        assertEquals(15, (lab[8].toInt() and 0xFF) or ((lab[9].toInt() and 0xFF) shl 8))
        assertArrayEquals(byteArrayOf(0x44,0x33,0x22,0x11,0x03,0x04,0x03,0x02,0x01,0x78,0x56,0x44,0x33,0x22,0x11), lab.copyOfRange(10, 25))
    }

    @Test fun `known registry and manifest decode firmware 0_8_2 payloads exactly`() {
        val knownPayload = byteArrayOf(3, 0x01,0,0,0, 0x02,0,0,0, 0x03,0,0,0)
        val knownFrame = codec.decodeApplicationPacket(response(8, BleOpcode.GET_KNOWN_NODES, knownPayload)).getOrThrow() as SecureMeshBleFrame.Response
        assertEquals(listOf("00000001", "00000002", "00000003"), codec.parseKnownNodes(knownFrame).getOrThrow())

        val manifestPayload = ByteArray(25)
        manifestPayload[0] = 1
        putU32(manifestPayload, 1, 0x01020304)
        putU32(manifestPayload, 5, 0xAABBCCDDL)
        manifestPayload[9] = 3
        var o = 10
        listOf(0x11111111L, 0x22222222L, 0x33333333L).forEachIndexed { slot, id ->
            manifestPayload[o++] = slot.toByte(); putU32(manifestPayload, o, id); o += 4
        }
        val mf = codec.decodeApplicationPacket(response(9, BleOpcode.GET_MANIFEST, manifestPayload)).getOrThrow() as SecureMeshBleFrame.Response
        val parsed = codec.parseManifest(mf).getOrThrow()
        assertTrue(parsed.valid)
        assertEquals(0x01020304L, parsed.networkEpoch)
        assertEquals(0xAABBCCDDL, parsed.digest)
        assertEquals(listOf("11111111", "22222222", "33333333"), parsed.entries.sortedBy { it.slot }.map { it.nodeId })
    }

    @Test fun `routing diagnostics v2 consumes exact 89 byte header and 56 byte route record`() {
        val payload = ByteArray(89 + 56)
        var o = 0
        fun u8(v: Int) { payload[o++] = v.toByte() }
        fun u16(v: Int) { payload[o++] = (v and 0xFF).toByte(); payload[o++] = ((v ushr 8) and 0xFF).toByte() }
        fun u32(v: Long) { putU32(payload, o, v); o += 4 }
        u8(2); u8(1); u32(7); u32(0x12345678); u32(99)
        repeat(3) { u32((it + 1).toLong()) }
        repeat(5) { u32((it + 10).toLong()) }
        repeat(4) { u32((it + 20).toLong()) }
        repeat(4) { u32((it + 30).toLong()) }
        u8(2); u32(40); u32(41); u8(1); u8(1)
        u32(0x33333333); u32(0x22222222); u32(0x11111111); u32(0)
        u32(4); u32(5); u32(6); u32(7); u32(0x3); u32(0x4); u32(0xA1); u32(0xB2)
        u32(2L shl 16); u16(30000); u8(0b1_1111); u8(9)
        assertEquals(payload.size, o)
        val frame = codec.decodeApplicationPacket(response(10, BleOpcode.GET_ROUTING_DIAGNOSTICS, payload)).getOrThrow() as SecureMeshBleFrame.Response
        val d = codec.parseRoutingDiagnostics(frame).getOrThrow()
        assertEquals(2, d.version)
        assertEquals(99, d.localRouteSeq)
        assertEquals(1, d.routes.size)
        assertEquals("33333333", d.routes.single().destination)
        assertEquals(0b1_1111, d.routes.single().flags)
        assertEquals(9, d.routes.single().backupLease)
    }

    @Test fun `lab policies decode 15 byte records including manual duration sentinel`() {
        val payload = ByteArray(1 + 15)
        payload[0] = 1
        putU32(payload, 1, 0x11223344)
        payload[5] = 0b11
        putU32(payload, 6, 0xFFFF_FFFFL)
        payload[10] = 0x00; payload[11] = 0x60
        putU32(payload, 12, 3L shl 16)
        val frame = codec.decodeApplicationPacket(response(11, BleOpcode.GET_LAB_LINK_POLICIES, payload)).getOrThrow() as SecureMeshBleFrame.Response
        val policy = codec.parseLabLinkPolicies(frame).getOrThrow().single()
        assertEquals("11223344", policy.peer)
        assertEquals(0b11, policy.flags)
        assertEquals(0xFFFF_FFFFL, policy.remainingMs)
        assertEquals(0x6000, policy.reliabilityQ15)
        assertEquals(3L shl 16, policy.ecaQ16)
    }


    @Test fun `GPS position list v0_2 parses exact compact record`() {
        val payload = ByteArray(1 + 35)
        payload[0] = 1
        var o = 1
        putU32(payload, o, 0xA1B2C3D4L); o += 4
        payload[o++] = 1
        payload[o++] = 0x1F
        putU16(payload, o, 77); o += 2
        putU32(payload, o, 1_800_000_000L); o += 4
        putI32(payload, o, 541469790); o += 4
        putI32(payload, o, 253244880); o += 4
        putI32(payload, o, 18340); o += 4
        putU16(payload, o, 245); o += 2
        putU16(payload, o, 135); o += 2
        payload[o++] = 10
        putU16(payload, o, 850); o += 2
        putU32(payload, o, 1200); o += 4
        assertEquals(payload.size, o)
        val frame = codec.decodeApplicationPacket(response(40, BleOpcode.GET_POSITIONS, payload)).getOrThrow() as SecureMeshBleFrame.Response
        val pos = codec.parsePositions(frame).getOrThrow().single()
        assertEquals("A1B2C3D4", pos.nodeId)
        assertTrue(pos.hasFix)
        assertEquals(54.146979, pos.latitudeE7 / 1e7, 1e-7)
        assertEquals(25.324488, pos.longitudeE7 / 1e7, 1e-7)
        assertEquals(10, pos.satellites)
        assertEquals(135, pos.hdopX100)
        assertEquals(1200L, pos.receivedAgeMs)
    }

    @Test fun `SOS and command map commands use frozen v0_2 wire sizes`() {
        val sos = codec.encodeCommand(41, SecureMeshBleCommand.RaiseSos(2)).getOrThrow()
        assertEquals(1, payloadLength(sos))
        assertEquals(BleOpcode.RAISE_SOS.wire, sos[6].toInt() and 0xFF)
        assertEquals(2, sos[10].toInt() and 0xFF)

        val ack = codec.encodeCommand(42, SecureMeshBleCommand.AckSos("A1B2C3D4", 0x11223344)).getOrThrow()
        assertEquals(8, payloadLength(ack))
        assertEquals(BleOpcode.ACK_SOS.wire, ack[6].toInt() and 0xFF)

        val cmd = codec.encodeCommand(
            43,
            SecureMeshBleCommand.SendCommandNotice("01020304", 4, 541469790, 253244880),
        ).getOrThrow()
        assertEquals(13, payloadLength(cmd))
        assertEquals(BleOpcode.SEND_COMMAND_NOTICE.wire, cmd[6].toInt() and 0xFF)
        assertEquals(4, cmd[14].toInt() and 0xFF)
    }

    @Test fun `POSITION event v0_2 maps to real NodePosition`() {
        val payload = ByteArray(35)
        var o = 0
        putU32(payload, o, 0x01020304); o += 4
        payload[o++] = 1
        payload[o++] = 0x0D
        putU16(payload, o, 9); o += 2
        putU32(payload, o, 1_800_000_100L); o += 4
        putI32(payload, o, 541469790); o += 4
        putI32(payload, o, 253244880); o += 4
        putI32(payload, o, 0); o += 4
        putU16(payload, o, 125); o += 2
        putU16(payload, o, 111); o += 2
        payload[o++] = 8
        putU16(payload, o, 300); o += 2
        putU32(payload, o, 400); o += 4
        val frame = codec.decodeApplicationPacket(event(BleEventType.POSITION_UPDATED, payload)).getOrThrow() as SecureMeshBleFrame.Event
        val decoded = codec.parseEvent(frame).getOrThrow() as BleDecodedEvent.PositionUpdated
        val mapped = SecureMeshBleV02DomainMapping.position(decoded.position, 1_800_000_101_000L)
        assertTrue(mapped.valid)
        assertEquals("01020304", mapped.nodeId)
        assertEquals(1.11, mapped.hdop!!, 0.001)
        assertEquals(1.25, mapped.speedMps!!, 0.001)
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

    private fun event(type: BleEventType, payload: ByteArray, status: Int = 0): ByteArray = ByteArray(10 + payload.size).also { out ->
        putU16(out, 0, 0x4D53)
        out[2] = 2
        out[3] = 3
        putU16(out, 4, 0)
        out[6] = type.wire.toByte()
        out[7] = status.toByte()
        putU16(out, 8, payload.size)
        payload.copyInto(out, 10)
    }

    private fun payloadLength(packet: ByteArray): Int = (packet[8].toInt() and 0xFF) or ((packet[9].toInt() and 0xFF) shl 8)

    private fun putI32(out: ByteArray, offset: Int, value: Int) = putU32(out, offset, value.toLong() and 0xFFFF_FFFFL)

    private fun putU16(out: ByteArray, offset: Int, value: Int) {
        out[offset] = (value and 0xFF).toByte(); out[offset+1] = ((value ushr 8) and 0xFF).toByte()
    }
    private fun putU32(out: ByteArray, offset: Int, value: Long) {
        repeat(4) { out[offset+it] = ((value ushr (8*it)) and 0xFF).toByte() }
    }
}
