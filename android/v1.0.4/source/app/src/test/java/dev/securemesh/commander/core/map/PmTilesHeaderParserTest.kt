package dev.securemesh.commander.core.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PmTilesHeaderParserTest {
    @Test
    fun `valid v3 vector header is accepted`() {
        val bytes = validHeader()
        val parsed = PmTilesHeaderParser.parse(bytes)
        assertEquals(5, parsed.minZoom)
        assertEquals(16, parsed.maxZoom)
        assertEquals(53.0, parsed.bounds.minLatitude, 0.0000001)
        assertEquals(23.0, parsed.bounds.minLongitude, 0.0000001)
        assertEquals(56.0, parsed.bounds.maxLatitude, 0.0000001)
        assertEquals(27.0, parsed.bounds.maxLongitude, 0.0000001)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `partial archive is rejected`() {
        PmTilesHeaderParser.parse(ByteArray(64))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `raster archive is rejected`() {
        val bytes = validHeader()
        bytes[99] = 2
        PmTilesHeaderParser.parse(bytes)
    }

    @Test
    fun `downloaded filenames are sanitized without exposing extension`() {
        assertEquals("Belarus West", OfflineMapManager.cleanDisplayName("Belarus West.pmtiles"))
        assertEquals("region 2026", OfflineMapManager.cleanDisplayName("region@2026.PMTILES"))
        assertTrue(OfflineMapManager.cleanDisplayName("***").isNotBlank())
    }

    private fun validHeader(): ByteArray = ByteArray(PmTilesHeaderParser.HEADER_BYTES).apply {
        "PMTiles".encodeToByteArray().copyInto(this, 0)
        this[7] = 3
        this[99] = 1
        this[100] = 5
        this[101] = 16
        putCoordinate(102, 23.0)
        putCoordinate(106, 53.0)
        putCoordinate(110, 27.0)
        putCoordinate(114, 56.0)
        this[118] = 9
        putCoordinate(119, 25.0)
        putCoordinate(123, 54.5)
    }

    private fun ByteArray.putCoordinate(offset: Int, value: Double) {
        ByteBuffer.wrap(this, offset, 4).order(ByteOrder.LITTLE_ENDIAN).putInt((value * 10_000_000).toInt())
    }
}
