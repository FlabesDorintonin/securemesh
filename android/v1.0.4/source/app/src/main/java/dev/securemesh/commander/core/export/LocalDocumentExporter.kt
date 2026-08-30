package dev.securemesh.commander.core.export

import android.content.Context
import android.net.Uri

object LocalDocumentExporter {
    fun write(context: Context, uri: Uri, content: String) {
        context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(content) }
            ?: error("Unable to open export destination")
    }
}
