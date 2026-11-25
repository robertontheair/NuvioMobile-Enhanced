package com.nuvio.app.core.logging

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val DEFAULT_MAX_LOG_LINES = 3_000

internal expect fun currentInAppLogTimestamp(): String

enum class InAppLogLevel(val label: String) {
    Debug("Debug"),
    Info("Info"),
    Warn("Warn"),
    Error("Error"),
}

data class InAppLogEntry(
    val timestamp: String,
    val level: InAppLogLevel,
    val tag: String,
    val category: String,
    val message: String,
    val line: String,
)

/**
 * In-memory app log buffer shown from Settings -> Advanced -> Debugging.
 *
 * This is intentionally process-local and non-persistent so playback URLs,
 * headers, or account state are not written to disk.
 */
object InAppLogger {
    val maxRetainedEntries: Int = DEFAULT_MAX_LOG_LINES

    private val maxLines = DEFAULT_MAX_LOG_LINES
    private val _entries = MutableStateFlow<List<InAppLogEntry>>(emptyList())
    private val _lines = MutableStateFlow<List<String>>(emptyList())

    val entries: StateFlow<List<InAppLogEntry>> = _entries.asStateFlow()

    /**
     * Backward-compatible text-only view for existing callers.
     */
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun debug(tag: String, message: String) = log(InAppLogLevel.Debug, tag, message)
    fun info(tag: String, message: String) = log(InAppLogLevel.Info, tag, message)
    fun warn(tag: String, message: String) = log(InAppLogLevel.Warn, tag, message)
    fun error(tag: String, message: String) = log(InAppLogLevel.Error, tag, message)

    fun log(level: InAppLogLevel, tag: String, message: String) {
        val trimmedMessage = message.trim()
        if (trimmedMessage.isEmpty()) return

        val timestamp = currentInAppLogTimestamp()
        val safeTag = tag.trim().ifEmpty { "App" }
        val line = buildString {
            append(timestamp)
            append(" [")
            append(level.label)
            append("] [")
            append(safeTag)
            append("] ")
            append(trimmedMessage)
        }
        appendEntry(
            InAppLogEntry(
                timestamp = timestamp,
                level = level,
                tag = safeTag,
                category = deriveCategory(safeTag),
                message = trimmedMessage,
                line = line,
            ),
        )
    }

    fun logMpv(platform: String, prefix: String, level: String, message: String) {
        val normalizedLevel = when (level.trim().lowercase()) {
            "fatal", "error" -> InAppLogLevel.Error
            "warn", "warning" -> InAppLogLevel.Warn
            "info", "status" -> InAppLogLevel.Info
            else -> InAppLogLevel.Debug
        }
        val tag = buildString {
            append("MPV")
            val safePlatform = platform.trim()
            if (safePlatform.isNotEmpty()) {
                append('/')
                append(safePlatform)
            }
            val safePrefix = prefix.trim()
            if (safePrefix.isNotEmpty()) {
                append('/')
                append(safePrefix)
            }
        }
        log(normalizedLevel, tag, message)
    }

    fun redactUrl(url: String?, maxLength: Int = 180): String {
        val raw = url?.trim().orEmpty()
        if (raw.isEmpty()) return "<empty>"

        val withoutQuery = raw.substringBefore('?').substringBefore('#')
        val schemeSeparator = withoutQuery.indexOf("://")
        val withoutCredentials = if (schemeSeparator >= 0) {
            val prefix = withoutQuery.take(schemeSeparator + 3)
            val rest = withoutQuery.drop(schemeSeparator + 3)
            val slashIndex = rest.indexOf('/')
            val authority = if (slashIndex >= 0) rest.take(slashIndex) else rest
            val path = if (slashIndex >= 0) rest.drop(slashIndex) else ""
            prefix + authority.substringAfter('@') + totalEclipseMaskPath(path)
        } else {
            withoutQuery
        }
        return if (withoutCredentials.length <= maxLength) {
            withoutCredentials
        } else {
            withoutCredentials.take(maxLength) + "…"
        }
    }

    fun headerKeys(headers: Map<String, String>?): String {
        val keys = headers.orEmpty().keys
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .sortedBy { it.lowercase() }
        return keys.joinToString(separator = ",").ifBlank { "none" }
    }

    fun throwableSummary(error: Throwable): String {
        val type = error::class.simpleName ?: "Throwable"
        val message = error.message?.trim().orEmpty()
        return if (message.isBlank()) type else "$type: $message"
    }

    fun dump(): String = entries.value.joinToString(separator = "\n") { it.line }

    fun clear() {
        _entries.value = emptyList()
        syncLines(emptyList())
    }

    // Total Eclipse: scrubbed copy on disk, and the previous session's lines first (see
    // TotalEclipseLogPersistence; this buffer itself stays in memory as upstream intends).
    private var restoredPrevious = false

    private fun appendEntry(entry: InAppLogEntry) {
        if (!restoredPrevious) {
            restoredPrevious = true
            val previous = com.nuvio.app.totaleclipse.TotalEclipseLogPersistence.previousSession()
            if (previous.isNotEmpty()) _entries.update { (previous + it).takeLast(maxLines) }
        }
        com.nuvio.app.totaleclipse.TotalEclipseLogPersistence.onEntry(entry)
        _entries.update { current ->
            val updated = if (current.size < maxLines) {
                current + entry
            } else {
                current.drop(current.size - maxLines + 1) + entry
            }
            syncLines(updated)
            updated
        }
    }

    private fun syncLines(entries: List<InAppLogEntry>) {
        _lines.value = entries.map { it.line }
    }

    private fun deriveCategory(tag: String): String {
        val root = tag.substringBefore('/').substringBefore(':').trim()
        val normalized = root.lowercase()
        return when (normalized) {
            "mpv", "exoplayer", "player", "p2p", "pip" -> "Player"
            "streams", "stream", "debrid" -> "Streams"
            "addons", "addon" -> "Addon"
            "plugins", "plugin", "pluginruntime" -> "Plugin"
            "metadata", "tmdb", "mdblist", "trakt" -> "Metadata"
            "network", "http", "datasource", "supabase" -> "Network"
            "app", "application", "deeplink", "sync", "auth", "profile" -> "App"
            else -> root.toTitleCaseOrDefault("Other")
        }
    }

    private fun String.toTitleCaseOrDefault(default: String): String {
        val value = trim()
        if (value.isEmpty()) return default
        return value.take(1).uppercase() + value.drop(1).lowercase()
    }
}

/**
 * Small Swift/Java friendly facade. Swift calls this as InAppLogBridge.shared.*
 * from MPVPlayerBridge.swift.
 */
object InAppLogBridge {
    fun debug(tag: String, message: String) = InAppLogger.debug(tag, message)
    fun info(tag: String, message: String) = InAppLogger.info(tag, message)
    fun warn(tag: String, message: String) = InAppLogger.warn(tag, message)
    fun error(tag: String, message: String) = InAppLogger.error(tag, message)

    fun mpv(platform: String, prefix: String, level: String, message: String) {
        InAppLogger.logMpv(platform = platform, prefix = prefix, level = level, message = message)
    }
}

/**
 * Total Eclipse: paths that carry secrets. Xtream links put the username and password in the
 * path (/live/<user>/<pass>/123.ts, also /movie, /series, /timeshift and /<user>/<pass>/123), and
 * the household's remote Live TV link puts its key there (/k/<key>/). Long opaque segments
 * (tokens, debrid links) are masked too.
 */
internal fun totalEclipseMaskPath(path: String): String {
    if (path.isEmpty()) return path
    val segments = path.split('/').toMutableList()
    // segments[0] is "" (the path starts with '/').
    val first = segments.getOrNull(1).orEmpty().lowercase()
    when {
        first == "k" && segments.size > 2 -> segments[2] = "<key>"
        first in setOf("live", "movie", "series", "timeshift") && segments.size > 4 -> {
            segments[2] = "<user>"
            segments[3] = "<pass>"
        }
        // Bare Xtream form: /<user>/<pass>/<stream id>
        segments.size == 4 && segments[3].substringBefore('.').all(Char::isDigit) && segments[3].isNotEmpty() -> {
            segments[1] = "<user>"
            segments[2] = "<pass>"
        }
    }
    return segments.joinToString("/") { segment ->
        if (segment.length >= 32 && segment.all { it.isLetterOrDigit() || it == '-' || it == '_' }) "<token>" else segment
    }
}
