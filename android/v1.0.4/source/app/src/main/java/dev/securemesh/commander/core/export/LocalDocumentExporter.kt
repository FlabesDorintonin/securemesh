package dev.securemesh.commander.core.export

import android.content.Context
import android.net.Uri

object LocalDocumentExporter {
    fun write(context: Context, uri: Uri, content: String): Result<Unit> = runCatching {
        val output = context.contentResolver.openOutputStream(uri)
            ?: error("Не удалось открыть выбранное место сохранения")
        output.bufferedWriter().use { writer ->
            writer.write(content)
            writer.flush()
        }
    }
}
