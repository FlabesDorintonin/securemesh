package dev.securemesh.commander.feature.messages

const val SECUREMESH_MESSAGE_MAX_UTF8_BYTES = 70

fun messageUtf8Bytes(value: String): Int = value.toByteArray(Charsets.UTF_8).size

/** Keeps complete Unicode code points while enforcing the firmware byte limit. */
fun fitMessageDraftToProtocol(value: String, maxBytes: Int = SECUREMESH_MESSAGE_MAX_UTF8_BYTES): String {
    require(maxBytes >= 0)
    if (messageUtf8Bytes(value) <= maxBytes) return value

    val out = StringBuilder(value.length)
    var used = 0
    var index = 0
    while (index < value.length) {
        val codePoint = value.codePointAt(index)
        val piece = String(Character.toChars(codePoint))
        val pieceBytes = messageUtf8Bytes(piece)
        if (used + pieceBytes > maxBytes) break
        out.append(piece)
        used += pieceBytes
        index += Character.charCount(codePoint)
    }
    return out.toString()
}
