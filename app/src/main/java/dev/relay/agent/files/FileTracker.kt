package dev.relay.agent.files

import java.io.File

/**
 * Finds files that appeared in shared storage while the agent was working — e.g. a PDF the AI app
 * saved to Download — so Relay can show them and tell the AI about them.
 */
class FileTracker(
    private val roots: () -> List<File>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var since = Long.MAX_VALUE
    private val baseline = HashMap<String, Long>()
    private val reported = HashSet<String>()

    fun start() {
        since = clock() - 1500
        baseline.clear()
        reported.clear()
        // Files already being written before the task started are not "new".
        scan(since - 30_000).forEach { baseline[it.path] = it.lastModified() }
    }

    fun collect(exclude: (String) -> Boolean = { false }): List<File> {
        if (since == Long.MAX_VALUE) return emptyList()
        val out = scan(since).filter { f ->
            val prev = baseline[f.path]
            (prev == null || f.lastModified() > prev) && f.path !in reported && !exclude(f.path) && f.length() > 0
        }
        out.forEach { reported += it.path }
        return out.sortedBy { it.lastModified() }
    }

    private fun scan(minTime: Long): List<File> {
        val out = ArrayList<File>()
        var visited = 0
        fun walk(dir: File, depth: Int) {
            val list = dir.listFiles() ?: return
            for (f in list) {
                if (++visited > 8000) return
                if (f.name.startsWith(".") || isTemp(f.name)) continue
                if (f.isDirectory) {
                    if (depth < 3 && f.name != "Android") walk(f, depth + 1)
                } else if (f.lastModified() >= minTime) {
                    out += f
                }
            }
        }
        roots().filter { it.isDirectory }.forEach { walk(it, 0) }
        return out
    }

    companion object {
        private val TEMP = Regex("""\.(tmp|temp|crdownload|part|partial|download)$""", RegexOption.IGNORE_CASE)

        fun isTemp(name: String) = TEMP.containsMatchIn(name) || name.startsWith(".pending-") || name.startsWith(".trashed-")
    }
}
