package dev.securemesh.commander.core.map

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.roundToInt

data class OfflineMapBounds(
    val minLatitude: Double,
    val minLongitude: Double,
    val maxLatitude: Double,
    val maxLongitude: Double,
)

data class OfflineMapPack(
    val id: String,
    val displayName: String,
    val filePath: String,
    val sizeBytes: Long,
    val minZoom: Int,
    val maxZoom: Int,
    val bounds: OfflineMapBounds,
    val centerLatitude: Double,
    val centerLongitude: Double,
) {
    val mapLibreUri: String
        get() = "pmtiles://${Uri.fromFile(File(filePath))}"
}

enum class OfflineMapTransferKind { DOWNLOAD, IMPORT }

data class OfflineMapTransfer(
    val kind: OfflineMapTransferKind,
    val name: String,
    val bytesDone: Long = 0L,
    val bytesTotal: Long? = null,
) {
    val progress: Float?
        get() = bytesTotal?.takeIf { it > 0L }?.let { (bytesDone.toDouble() / it.toDouble()).coerceIn(0.0, 1.0).toFloat() }
}

data class OfflineMapState(
    val packs: List<OfflineMapPack> = emptyList(),
    val activePackId: String? = null,
    val transfer: OfflineMapTransfer? = null,
    val notice: String? = null,
) {
    val activePack: OfflineMapPack?
        get() = packs.firstOrNull { it.id == activePackId }
}

/**
 * Owns map files inside app storage. Downloads are delegated to Android DownloadManager so a
 * large map can continue even if the Activity is recreated; on the next app start a pending
 * DownloadManager id is recovered, validated and installed atomically.
 */
class OfflineMapManager(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val downloadManager = appContext.getSystemService(DownloadManager::class.java)
    private val mapsDir = File(appContext.filesDir, "offline_maps").apply { mkdirs() }
    private val downloadDir = File(
        appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: appContext.cacheDir,
        DOWNLOAD_SUBDIR,
    ).apply { mkdirs() }

    private val _state = MutableStateFlow(OfflineMapState(activePackId = prefs.getString(KEY_ACTIVE_PACK, null)))
    val state = _state.asStateFlow()

    private var monitorJob: Job? = null
    private var importJob: Job? = null

    init {
        scope.launch {
            refreshInstalledInternal()
            resumePendingDownload()
        }
    }

    fun clearNotice() {
        _state.value = _state.value.copy(notice = null)
    }

    fun refresh() {
        scope.launch { refreshInstalledInternal() }
    }

    fun select(packId: String) {
        val pack = _state.value.packs.firstOrNull { it.id == packId } ?: return
        prefs.edit().putString(KEY_ACTIVE_PACK, pack.id).apply()
        _state.value = _state.value.copy(activePackId = pack.id, notice = "Карта «${pack.displayName}» выбрана")
    }

    fun delete(packId: String) {
        scope.launch {
            val pack = _state.value.packs.firstOrNull { it.id == packId } ?: return@launch
            val deleted = runCatching { File(pack.filePath).delete() }.getOrDefault(false)
            if (!deleted && File(pack.filePath).exists()) {
                postNotice("Не удалось удалить карту «${pack.displayName}»")
                return@launch
            }
            if (prefs.getString(KEY_ACTIVE_PACK, null) == packId) {
                prefs.edit().remove(KEY_ACTIVE_PACK).apply()
            }
            refreshInstalledInternal("Карта «${pack.displayName}» удалена")
        }
    }

    fun startDownload(urlText: String) {
        if (_state.value.transfer != null) {
            postNotice("Сначала заверши текущее скачивание")
            return
        }
        val uri = runCatching { Uri.parse(urlText.trim()) }.getOrNull()
        if (uri == null || uri.scheme != "https" || uri.host.isNullOrBlank()) {
            postNotice("Нужна обычная HTTPS-ссылка на файл карты")
            return
        }

        val guessedName = uri.lastPathSegment?.substringBefore('?')?.substringBefore('#')
            ?.takeIf { it.isNotBlank() }
            ?: "offline-map.pmtiles"
        val displayName = cleanDisplayName(guessedName)
        val tempName = "download-${System.currentTimeMillis()}.pmtiles.part"
        val tempFile = File(downloadDir, tempName)

        runCatching {
            val request = DownloadManager.Request(uri)
                .setTitle("SecureMesh · $displayName")
                .setDescription("Скачивание офлайн-карты")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(appContext, Environment.DIRECTORY_DOWNLOADS, "$DOWNLOAD_SUBDIR/$tempName")
            val id = downloadManager.enqueue(request)
            prefs.edit()
                .putLong(KEY_PENDING_ID, id)
                .putString(KEY_PENDING_TEMP, tempFile.absolutePath)
                .putString(KEY_PENDING_NAME, displayName)
                .apply()
            _state.value = _state.value.copy(
                transfer = OfflineMapTransfer(OfflineMapTransferKind.DOWNLOAD, displayName),
                notice = null,
            )
            monitorDownload(id, tempFile, displayName)
        }.onFailure {
            postNotice("Не удалось начать скачивание: ${it.message ?: "ошибка Android"}")
        }
    }

    fun cancelTransfer() {
        importJob?.cancel()
        importJob = null
        val pendingId = prefs.getLong(KEY_PENDING_ID, NO_DOWNLOAD_ID)
        if (pendingId != NO_DOWNLOAD_ID) runCatching { downloadManager.remove(pendingId) }
        pendingTempFile()?.delete()
        clearPendingDownload()
        _state.value = _state.value.copy(transfer = null, notice = "Операция отменена")
    }

    fun importFromUri(uri: Uri) {
        if (_state.value.transfer != null) {
            postNotice("Сначала заверши текущую операцию")
            return
        }
        importJob = scope.launch {
            val metadata = queryDocument(uri)
            val displayName = cleanDisplayName(metadata.first ?: "offline-map.pmtiles")
            val expectedBytes = metadata.second?.takeIf { it > 0L }
            if (expectedBytes != null && expectedBytes > MAX_PACK_BYTES) {
                postNotice("Файл слишком большой для SecureMesh")
                return@launch
            }
            if (expectedBytes != null && expectedBytes + FREE_SPACE_RESERVE_BYTES > mapsDir.usableSpace) {
                postNotice("Недостаточно свободной памяти для этой карты")
                return@launch
            }

            _state.value = _state.value.copy(
                transfer = OfflineMapTransfer(OfflineMapTransferKind.IMPORT, displayName, bytesTotal = expectedBytes),
                notice = null,
            )

            val temp = File(mapsDir, ".import-${System.currentTimeMillis()}.part")
            runCatching {
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(temp).use { output ->
                        copyWithProgress(input, output, displayName, OfflineMapTransferKind.IMPORT, expectedBytes)
                        output.fd.sync()
                    }
                } ?: error("Не удалось открыть выбранный файл")
                installValidated(temp, displayName)
            }.onFailure {
                temp.delete()
                _state.value = _state.value.copy(transfer = null, notice = friendlyInstallError(it))
            }
        }
    }

    private suspend fun resumePendingDownload() {
        val id = prefs.getLong(KEY_PENDING_ID, NO_DOWNLOAD_ID)
        val temp = pendingTempFile()
        val name = prefs.getString(KEY_PENDING_NAME, null)
        if (id == NO_DOWNLOAD_ID || temp == null || name.isNullOrBlank()) return
        _state.value = _state.value.copy(transfer = OfflineMapTransfer(OfflineMapTransferKind.DOWNLOAD, name))
        monitorDownload(id, temp, name)
    }

    private fun monitorDownload(id: Long, tempFile: File, displayName: String) {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            while (isActive) {
                val snapshot = queryDownload(id)
                if (snapshot == null) {
                    clearPendingDownload()
                    tempFile.delete()
                    _state.value = _state.value.copy(transfer = null, notice = "Скачивание карты не найдено")
                    return@launch
                }

                when (snapshot.status) {
                    DownloadManager.STATUS_PENDING,
                    DownloadManager.STATUS_RUNNING,
                    DownloadManager.STATUS_PAUSED -> {
                        _state.value = _state.value.copy(
                            transfer = OfflineMapTransfer(
                                OfflineMapTransferKind.DOWNLOAD,
                                displayName,
                                snapshot.downloaded.coerceAtLeast(0L),
                                snapshot.total.takeIf { it > 0L },
                            ),
                            notice = null,
                        )
                    }
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        clearPendingDownload()
                        runCatching { installValidated(tempFile, displayName) }
                            .onFailure {
                                tempFile.delete()
                                _state.value = _state.value.copy(transfer = null, notice = friendlyInstallError(it))
                            }
                        return@launch
                    }
                    DownloadManager.STATUS_FAILED -> {
                        clearPendingDownload()
                        tempFile.delete()
                        _state.value = _state.value.copy(
                            transfer = null,
                            notice = "Скачивание не завершено (код ${snapshot.reason})",
                        )
                        return@launch
                    }
                }
                delay(800L)
            }
        }
    }

    private suspend fun installValidated(source: File, requestedName: String) = withContext(Dispatchers.IO) {
        if (!source.exists() || source.length() < PmTilesHeaderParser.HEADER_BYTES) error("Файл карты повреждён или пуст")
        if (source.length() > MAX_PACK_BYTES) error("Файл карты превышает допустимый размер")
        if (source.length() + FREE_SPACE_RESERVE_BYTES > mapsDir.usableSpace) error("Недостаточно свободной памяти")

        PmTilesHeaderParser.read(source)
        val destination = uniqueDestination(requestedName)
        val staging = File(mapsDir, ".${destination.name}.installing")
        staging.delete()

        FileInputStream(source).use { input ->
            FileOutputStream(staging).use { output ->
                input.copyTo(output, DEFAULT_BUFFER_SIZE)
                output.fd.sync()
            }
        }
        PmTilesHeaderParser.read(staging)
        if (!staging.renameTo(destination)) {
            staging.delete()
            error("Не удалось сохранить карту")
        }
        source.delete()
        prefs.edit().putString(KEY_ACTIVE_PACK, destination.name).apply()
        refreshInstalledInternal("Карта «${destination.nameWithoutExtension}» готова")
    }

    private suspend fun refreshInstalledInternal(notice: String? = null) = withContext(Dispatchers.IO) {
        mapsDir.mkdirs()
        mapsDir.listFiles()
            ?.filter { it.isFile && it.extension.equals("pmtiles", ignoreCase = true) }
            ?.forEach { file ->
                // Corrupt files are never exposed as selectable maps.
                runCatching { PmTilesHeaderParser.read(file) }.onFailure { file.delete() }
            }

        val packs = mapsDir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.extension.equals("pmtiles", ignoreCase = true) }
            ?.mapNotNull { file ->
                runCatching {
                    val header = PmTilesHeaderParser.read(file)
                    OfflineMapPack(
                        id = file.name,
                        displayName = file.nameWithoutExtension,
                        filePath = file.absolutePath,
                        sizeBytes = file.length(),
                        minZoom = header.minZoom,
                        maxZoom = header.maxZoom,
                        bounds = header.bounds,
                        centerLatitude = header.centerLatitude,
                        centerLongitude = header.centerLongitude,
                    )
                }.getOrNull()
            }
            ?.sortedBy { it.displayName.lowercase() }
            ?.toList()
            .orEmpty()

        var active = prefs.getString(KEY_ACTIVE_PACK, null)
        if (active != null && packs.none { it.id == active }) {
            active = null
            prefs.edit().remove(KEY_ACTIVE_PACK).apply()
        }
        if (active == null && packs.isNotEmpty()) {
            active = packs.first().id
            prefs.edit().putString(KEY_ACTIVE_PACK, active).apply()
        }
        _state.value = _state.value.copy(packs = packs, activePackId = active, transfer = null, notice = notice ?: _state.value.notice)
    }

    private suspend fun copyWithProgress(
        input: java.io.InputStream,
        output: FileOutputStream,
        displayName: String,
        kind: OfflineMapTransferKind,
        totalBytes: Long?,
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            copied += read
            if (copied > MAX_PACK_BYTES) error("Файл карты превышает допустимый размер")
            if (mapsDir.usableSpace < FREE_SPACE_RESERVE_BYTES) error("Заканчивается свободная память")
            _state.value = _state.value.copy(
                transfer = OfflineMapTransfer(kind, displayName, copied, totalBytes),
                notice = null,
            )
        }
    }

    private fun queryDocument(uri: Uri): Pair<String?, Long?> {
        var name: String? = null
        var size: Long? = null
        appContext.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) name = cursor.getString(nameIndex)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
        return name to size
    }

    private data class DownloadSnapshot(val status: Int, val reason: Int, val downloaded: Long, val total: Long)

    private fun queryDownload(id: Long): DownloadSnapshot? {
        val query = DownloadManager.Query().setFilterById(id)
        downloadManager.query(query)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            return DownloadSnapshot(
                status = cursor.int(DownloadManager.COLUMN_STATUS),
                reason = cursor.int(DownloadManager.COLUMN_REASON),
                downloaded = cursor.long(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                total = cursor.long(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
            )
        }
        return null
    }

    private fun android.database.Cursor.int(column: String): Int = getInt(getColumnIndexOrThrow(column))
    private fun android.database.Cursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))

    private fun pendingTempFile(): File? = prefs.getString(KEY_PENDING_TEMP, null)?.let(::File)

    private fun clearPendingDownload() {
        prefs.edit().remove(KEY_PENDING_ID).remove(KEY_PENDING_TEMP).remove(KEY_PENDING_NAME).apply()
    }

    private fun uniqueDestination(requestedName: String): File {
        val base = cleanDisplayName(requestedName).ifBlank { "offline-map" }
        var candidate = File(mapsDir, "$base.pmtiles")
        var index = 2
        while (candidate.exists()) {
            candidate = File(mapsDir, "$base ($index).pmtiles")
            index++
        }
        return candidate
    }

    private fun postNotice(message: String) {
        _state.value = _state.value.copy(notice = message)
    }

    private fun friendlyInstallError(error: Throwable): String = when {
        error is kotlinx.coroutines.CancellationException -> "Операция отменена"
        error.message.isNullOrBlank() -> "Не удалось добавить карту"
        else -> requireNotNull(error.message)
    }

    companion object {
        private const val PREFS_NAME = "securemesh_offline_maps"
        private const val KEY_ACTIVE_PACK = "active_pack"
        private const val KEY_PENDING_ID = "pending_download_id"
        private const val KEY_PENDING_TEMP = "pending_download_temp"
        private const val KEY_PENDING_NAME = "pending_download_name"
        private const val DOWNLOAD_SUBDIR = "securemesh-maps"
        private const val NO_DOWNLOAD_ID = -1L
        private const val MAX_PACK_BYTES = 4L * 1024L * 1024L * 1024L
        private const val FREE_SPACE_RESERVE_BYTES = 64L * 1024L * 1024L

        internal fun cleanDisplayName(raw: String): String {
            val trimmed = raw.trim()
            val withoutExtension = if (trimmed.endsWith(".pmtiles", ignoreCase = true)) trimmed.dropLast(8) else trimmed
            val cleaned = buildString {
                withoutExtension.forEach { ch ->
                    when {
                        ch.isLetterOrDigit() -> append(ch)
                        ch == ' ' || ch == '-' || ch == '_' || ch == '.' -> append(ch)
                        else -> append(' ')
                    }
                }
            }.replace(Regex("\\s+"), " ").trim().take(64)
            return cleaned.ifBlank { "offline-map" }
        }
    }
}

internal fun Long.humanFileSize(): String {
    if (this < 1024L) return "$this Б"
    val kb = this / 1024.0
    if (kb < 1024.0) return "${kb.roundToInt()} КБ"
    val mb = kb / 1024.0
    if (mb < 1024.0) return if (mb < 10) "%.1f МБ".format(mb) else "${mb.roundToInt()} МБ"
    val gb = mb / 1024.0
    return "%.1f ГБ".format(gb)
}
