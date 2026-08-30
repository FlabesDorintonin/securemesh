package dev.securemesh.commander.core.map

import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class PmTilesHeader(
    val minZoom: Int,
    val maxZoom: Int,
    val bounds: OfflineMapBounds,
    val centerLatitude: Double,
    val centerLongitude: Double,
)

/** Minimal PMTiles v3 validation used before a downloaded/imported file becomes selectable. */
internal object PmTilesHeaderParser {
    const val HEADER_BYTES = 127
    private val magic = "PMTiles".encodeToByteArray()

    fun read(file: File): PmTilesHeader {
        require(file.isFile && file.length() >= HEADER_BYTES) { "Файл карты повреждён или пуст" }
        val header = ByteArray(HEADER_BYTES)
        FileInputStream(file).use { input ->
            var offset = 0
            while (offset < header.size) {
                val read = input.read(header, offset, header.size - offset)
                if (read < 0) error("Файл карты слишком короткий")
                offset += read
            }
        }
        return parse(header)
    }

    fun parse(header: ByteArray): PmTilesHeader {
        require(header.size >= HEADER_BYTES) { "Файл карты слишком короткий" }
        require(header.copyOfRange(0, 7).contentEquals(magic)) { "Выбранный файл не является картой PMTiles" }
        require(header[7].toInt() and 0xFF == 3) { "Эта версия карты пока не поддерживается" }

        // SecureMesh local style renders MVT vector packs. Raster archives are rejected explicitly
        // rather than being installed and then showing a blank map.
        val tileType = header[99].toInt() and 0xFF
        require(tileType == 1) { "Нужна векторная карта PMTiles" }

        val minZoom = header[100].toInt() and 0xFF
        val maxZoom = header[101].toInt() and 0xFF
        require(minZoom <= maxZoom && maxZoom <= 24) { "Повреждены уровни масштаба карты" }

        val minLon = coordinate(header, 102)
        val minLat = coordinate(header, 106)
        val maxLon = coordinate(header, 110)
        val maxLat = coordinate(header, 114)
        val centerLon = coordinate(header, 119)
        val centerLat = coordinate(header, 123)
        require(validLatitude(minLat) && validLatitude(maxLat) && validLatitude(centerLat)) { "Повреждены координаты карты" }
        require(validLongitude(minLon) && validLongitude(maxLon) && validLongitude(centerLon)) { "Повреждены координаты карты" }
        require(minLat <= maxLat && minLon <= maxLon) { "Повреждены границы карты" }

        return PmTilesHeader(
            minZoom = minZoom,
            maxZoom = maxZoom,
            bounds = OfflineMapBounds(minLat, minLon, maxLat, maxLon),
            centerLatitude = centerLat,
            centerLongitude = centerLon,
        )
    }

    private fun coordinate(bytes: ByteArray, offset: Int): Double =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int / 10_000_000.0

    private fun validLatitude(value: Double): Boolean = value.isFinite() && value in -90.0..90.0
    private fun validLongitude(value: Double): Boolean = value.isFinite() && value in -180.0..180.0
}
