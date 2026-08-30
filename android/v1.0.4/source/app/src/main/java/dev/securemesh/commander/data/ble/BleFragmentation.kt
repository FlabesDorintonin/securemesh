package dev.securemesh.commander.data.ble

object SecureMeshBleFragmentation {
    const val MAGIC = 0x4653
    const val VERSION = 1
    const val HEADER_SIZE = 12
    const val MAX_FRAGMENT_DATA = 180
    const val MAX_FRAGMENT_COUNT = 48
    const val MAX_APPLICATION_PACKET = 384
    const val REASSEMBLY_TIMEOUT_MS = 3_000L

    fun fragment(packet: ByteArray, mtu: Int, transportId: Int): Result<List<ByteArray>> = runCatching {
        require(packet.size in SecureMeshBleProtocolV02Codec.HEADER_SIZE..MAX_APPLICATION_PACKET) { "application packet must be 10..384 bytes" }
        require(transportId in 1..0xFFFF) { "transportId must be non-zero u16" }
        val negotiatedMtu = mtu.coerceAtLeast(23)
        val dataPerFragment = minOf(MAX_FRAGMENT_DATA, negotiatedMtu - 3 - HEADER_SIZE)
        require(dataPerFragment > 0) { "MTU leaves no fragment payload" }
        val count = (packet.size + dataPerFragment - 1) / dataPerFragment
        require(count in 1..MAX_FRAGMENT_COUNT) { "fragment count $count exceeds protocol limit" }

        buildList(count) {
            var offset = 0
            repeat(count) { index ->
                val length = minOf(dataPerFragment, packet.size - offset)
                val out = ByteArray(HEADER_SIZE + length)
                putU16(out, 0, MAGIC)
                out[2] = VERSION.toByte()
                putU16(out, 3, transportId)
                out[5] = index.toByte()
                out[6] = count.toByte()
                putU16(out, 7, packet.size)
                putU16(out, 9, offset)
                out[11] = length.toByte()
                packet.copyInto(out, HEADER_SIZE, offset, offset + length)
                add(out)
                offset += length
            }
        }
    }

    sealed interface AcceptResult {
        data object Incomplete : AcceptResult
        data class Complete(val packet: ByteArray) : AcceptResult
        data class Rejected(val reason: String) : AcceptResult
    }

    class Reassembler(private val timeoutMs: Long = REASSEMBLY_TIMEOUT_MS) {
        private val buffer = ByteArray(MAX_APPLICATION_PACKET)
        private var active = false
        private var transportId = 0
        private var fragmentCount = 0
        private var totalLength = 0
        private var nextIndex = 0
        private var received = 0
        private var startedAtMs = 0L

        fun accept(fragment: ByteArray, nowMs: Long): AcceptResult {
            if (active && nowMs - startedAtMs > timeoutMs) reset()
            val decoded = decodeHeader(fragment) ?: return AcceptResult.Rejected("malformed fragment header")
            if (decoded.fragmentLength != fragment.size - HEADER_SIZE) return reject("fragmentLength mismatch")
            if (decoded.fragmentLength > MAX_FRAGMENT_DATA) return reject("fragmentLength exceeds 180")
            if (decoded.fragmentCount !in 1..MAX_FRAGMENT_COUNT) return reject("invalid fragmentCount")
            if (decoded.totalLength !in SecureMeshBleProtocolV02Codec.HEADER_SIZE..MAX_APPLICATION_PACKET) return reject("invalid totalLength")
            if (decoded.transportId == 0) return reject("transportId must be non-zero")
            if (decoded.fragmentIndex !in 0 until decoded.fragmentCount) return reject("fragmentIndex out of range")
            if (decoded.offset + decoded.fragmentLength > decoded.totalLength) return reject("fragment out of bounds")

            if (!active) {
                if (decoded.fragmentIndex != 0 || decoded.offset != 0) return AcceptResult.Rejected("first fragment must be index 0 offset 0")
                active = true
                transportId = decoded.transportId
                fragmentCount = decoded.fragmentCount
                totalLength = decoded.totalLength
                nextIndex = 0
                received = 0
                startedAtMs = nowMs
            }

            if (decoded.transportId != transportId || decoded.fragmentCount != fragmentCount || decoded.totalLength != totalLength) {
                return reject("assembly identity changed")
            }
            if (decoded.fragmentIndex != nextIndex) return reject("out-of-order fragment")
            if (decoded.offset != received) return reject("overlap/gap detected")

            fragment.copyInto(buffer, received, HEADER_SIZE, HEADER_SIZE + decoded.fragmentLength)
            received += decoded.fragmentLength
            nextIndex++

            if (nextIndex == fragmentCount) {
                if (received != totalLength) return reject("final assembled length mismatch")
                val packet = buffer.copyOf(totalLength)
                reset()
                return AcceptResult.Complete(packet)
            }
            return AcceptResult.Incomplete
        }

        fun expire(nowMs: Long): Boolean {
            if (!active || nowMs - startedAtMs <= timeoutMs) return false
            reset()
            return true
        }

        fun reset() {
            active = false
            transportId = 0
            fragmentCount = 0
            totalLength = 0
            nextIndex = 0
            received = 0
            startedAtMs = 0L
        }

        private fun reject(reason: String): AcceptResult.Rejected {
            reset()
            return AcceptResult.Rejected(reason)
        }
    }

    private data class Header(
        val transportId: Int,
        val fragmentIndex: Int,
        val fragmentCount: Int,
        val totalLength: Int,
        val offset: Int,
        val fragmentLength: Int,
    )

    private fun decodeHeader(bytes: ByteArray): Header? {
        if (bytes.size < HEADER_SIZE) return null
        if (u16(bytes, 0) != MAGIC) return null
        if ((bytes[2].toInt() and 0xFF) != VERSION) return null
        return Header(
            transportId = u16(bytes, 3),
            fragmentIndex = bytes[5].toInt() and 0xFF,
            fragmentCount = bytes[6].toInt() and 0xFF,
            totalLength = u16(bytes, 7),
            offset = u16(bytes, 9),
            fragmentLength = bytes[11].toInt() and 0xFF,
        )
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }
}
