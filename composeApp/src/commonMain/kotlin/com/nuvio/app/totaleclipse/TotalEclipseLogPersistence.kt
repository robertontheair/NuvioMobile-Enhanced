package com.nuvio.app.totaleclipse

import com.nuvio.app.core.logging.InAppLogEntry
import com.nuvio.app.core.logging.InAppLogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Total Eclipse: keeps the debug log across a crash, like HomeTV's Playback Logs. The last
 * [MAX_LINES] lines are written to the app's Caches folder, scrubbed first: every URL becomes its
 * host only, and anything that looks like a key, token or password is removed. On the next launch
 * the previous session's lines come back first in Settings › Debug logs, marked "previous".
 * Upstream keeps this log in memory only because raw lines can hold stream URLs and headers.
 */
internal object TotalEclipseLogPersistence {
    private const val MAX_LINES = 500
    private const val FLUSH_EVERY = 40
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val buffer = ArrayDeque<String>()
    private var sinceFlush = 0

    private val urlPattern = Regex("""\b[a-zA-Z][a-zA-Z0-9+.-]*://([^/\s"'?#]+)[^\s"']*""")
    private val secretPattern = Regex(
        """(?i)\b(api[_-]?key|apikey|token|access[_-]?token|refresh[_-]?token|password|passwd|secret|authorization|bearer|client[_-]?secret|username|user)\b(\s*[:=]\s*|\s+)("[^"]*"|[^\s,;&]+)""",
    )
    private val longOpaque = Regex("""\b[A-Za-z0-9_\-]{32,}\b""")

    fun scrub(line: String): String = line
        .replace(urlPattern) { m -> "<${m.groupValues[1].substringAfterLast('@')}>" }
        .replace(secretPattern) { m -> "${m.groupValues[1]}${m.groupValues[2]}<removed>" }
        .replace(longOpaque, "<removed>")

    /** The previous session's scrubbed lines, as log entries (empty when there are none). */
    fun previousSession(): List<InAppLogEntry> {
        val text = runCatching { TotalEclipseLogFile.read() }.getOrNull().orEmpty()
        if (text.isBlank()) return emptyList()
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        return buildList {
            add(marker("— previous session (${lines.size} lines, scrubbed) —"))
            lines.forEach { add(InAppLogEntry("", InAppLogLevel.Debug, "Previous", "Previous", it, "[previous] $it")) }
            add(marker("— this session —"))
        }
    }

    private fun marker(text: String) = InAppLogEntry("", InAppLogLevel.Info, "Previous", "Previous", text, text)

    fun onEntry(entry: InAppLogEntry) {
        if (entry.tag == "Previous") return
        val urgent = entry.level == InAppLogLevel.Warn || entry.level == InAppLogLevel.Error
        scope.launch {
            mutex.withLock {
                buffer.addLast(scrub(entry.line))
                while (buffer.size > MAX_LINES) buffer.removeFirst()
                sinceFlush++
                if (urgent || sinceFlush >= FLUSH_EVERY) {
                    sinceFlush = 0
                    runCatching { TotalEclipseLogFile.write(buffer.joinToString("\n")) }
                }
            }
        }
    }
}

/** The scrubbed log file in the app's Caches folder. */
internal expect object TotalEclipseLogFile {
    fun read(): String?
    fun write(text: String)
}
