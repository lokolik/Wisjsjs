package dev.relay.agent.agent

import java.io.File
import java.util.Locale
import java.util.UUID

/** A device file sent to the AI together with a message. */
data class Attachment(
    val file: File,
    val name: String = file.name,
    val mime: String = Mime.of(name),
    val id: String = UUID.randomUUID().toString().take(8),
) {
    val size: Long get() = file.length()
    val isText: Boolean get() = Mime.isText(mime, name)
}

object Mime {
    private val MAP = mapOf(
        "pdf" to "application/pdf", "txt" to "text/plain", "md" to "text/markdown", "csv" to "text/csv",
        "tsv" to "text/tab-separated-values", "json" to "application/json", "xml" to "application/xml",
        "yml" to "text/yaml", "yaml" to "text/yaml", "html" to "text/html", "htm" to "text/html", "css" to "text/css",
        "js" to "text/javascript", "ts" to "text/plain", "kt" to "text/plain", "java" to "text/plain", "py" to "text/x-python",
        "sh" to "text/x-shellscript", "sql" to "text/plain", "log" to "text/plain", "ini" to "text/plain",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "rtf" to "application/rtf", "odt" to "application/vnd.oasis.opendocument.text", "epub" to "application/epub+zip",
        "zip" to "application/zip", "rar" to "application/vnd.rar", "7z" to "application/x-7z-compressed",
        "apk" to "application/vnd.android.package-archive",
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "webp" to "image/webp", "gif" to "image/gif",
        "svg" to "image/svg+xml", "heic" to "image/heic",
        "mp3" to "audio/mpeg", "wav" to "audio/wav", "m4a" to "audio/mp4", "ogg" to "audio/ogg",
        "mp4" to "video/mp4", "mov" to "video/quicktime", "webm" to "video/webm",
    )

    fun of(name: String): String = MAP[name.substringAfterLast('.', "").lowercase(Locale.ROOT)] ?: "application/octet-stream"

    fun ext(mime: String?): String? = mime?.substringBefore(';')?.trim()?.let { m -> MAP.entries.firstOrNull { it.value == m }?.key }

    fun isText(mime: String, name: String = ""): Boolean =
        mime.startsWith("text/") || mime in setOf("application/json", "application/xml") ||
            name.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("md", "csv", "json", "xml", "yml", "yaml", "log", "ini", "kt", "ts", "sql")
}
