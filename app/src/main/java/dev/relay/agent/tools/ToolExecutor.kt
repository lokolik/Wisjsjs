package dev.relay.agent.tools

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.Base64
import java.util.Locale

enum class Risk { READ, WRITE, DANGER }

data class ToolParam(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = true,
)

data class ToolSpec(
    val name: String,
    val description: String,
    val params: List<ToolParam>,
    val risk: Risk,
)

data class ToolCall(val id: String, val tool: String, val args: JSONObject)

data class ToolResult(val ok: Boolean, val output: String)

/** Device capabilities that need Android APIs; faked in JVM tests. */
interface Platform {
    val storageRoot: File
    fun openUrl(url: String): String
    fun launchApp(nameOrPackage: String): String
    fun deviceInfo(): Map<String, Any?>
    fun openFile(file: File): String
    /** Queues a device file for the next message to the AI chat. */
    fun attachFile(file: File): String
    fun onFileCreated(file: File, source: String) {}
}

class ToolExecutor(
    private val platform: Platform,
    private val shell: Shell,
    private val rootAllowed: () -> Boolean,
) {
    private class ArgError(message: String) : Exception(message)

    /** Paths written by tools, so the new-file watcher does not report them twice. */
    val touched: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    fun execute(call: ToolCall): ToolResult = try {
        val a = call.args
        when (call.tool) {
            "download_file" -> download(str(a, listOf("url", "link", "href")), a.optString("path").takeIf { it.isNotBlank() })
            "open_file" -> openFile(resolve(str(a, PATH_KEYS)))
            "attach_file" -> attach(resolve(str(a, PATH_KEYS)))
            "list_dir" -> listDir(resolve(str(a, PATH_KEYS, default = "~")))
            "read_file" -> readFile(resolve(str(a, PATH_KEYS)), a.optInt("max_chars", 20_000).coerceIn(100, 200_000))
            "search_files" -> searchFiles(resolve(str(a, PATH_KEYS, default = "~")), str(a, listOf("pattern", "name", "query")))
            "write_file" -> writeFile(resolve(str(a, PATH_KEYS)), str(a, listOf("content", "text", "data"), default = ""), a.optBoolean("append", false))
            "mkdir" -> mkdir(resolve(str(a, PATH_KEYS)))
            "move" -> move(resolve(str(a, FROM_KEYS)), resolve(str(a, TO_KEYS)))
            "copy" -> copy(resolve(str(a, FROM_KEYS)), resolve(str(a, TO_KEYS)))
            "delete" -> delete(resolve(str(a, PATH_KEYS)), a.optBoolean("recursive", false))
            "shell" -> runShell(str(a, listOf("command", "cmd")), a.optBoolean("root", false), a.optLong("timeout_sec", 60))
            "open_url" -> ToolResult(true, platform.openUrl(str(a, listOf("url", "link"))))
            "launch_app" -> ToolResult(true, platform.launchApp(str(a, listOf("package", "package_name", "app", "name"))))
            "device_info" -> deviceInfo()
            else -> fail("Неизвестный инструмент «${call.tool}». Доступные: " + SPECS.joinToString { it.name })
        }
    } catch (e: ArgError) {
        fail(e.message ?: "Неверные аргументы")
    } catch (e: Exception) {
        fail("${e.javaClass.simpleName}: ${e.message}")
    }

    /** `~`, `/sdcard` and relative paths map to shared storage; common folder names are aliased. */
    fun resolve(raw: String): File {
        val root = platform.storageRoot
        val p = raw.trim().replace('\\', '/')
        val relative = when {
            p.isEmpty() || p == "~" || p == "/sdcard" || p == "/storage/self/primary" -> return root
            p.startsWith("~/") -> p.substring(2)
            p.startsWith("/sdcard/") -> p.removePrefix("/sdcard/")
            p.startsWith("/storage/self/primary/") -> p.removePrefix("/storage/self/primary/")
            p.startsWith("/") -> return File(p)
            else -> p
        }
        val segs = relative.split('/').filter { it.isNotEmpty() }.toMutableList()
        if (segs.isNotEmpty()) ALIASES[segs[0].lowercase(Locale.ROOT)]?.let { segs[0] = it }
        return if (segs.isEmpty()) root else File(root, segs.joinToString("/"))
    }

    // region tools

    private fun listDir(dir: File): ToolResult {
        if (dir.isFile) return fail("Это файл, а не папка: ${dir.path}")
        val files = dir.listFiles()
        if (files == null) {
            viaRoot("ls -la -- ${q(dir.path)}")?.let { return it }
            return fail(if (!dir.exists()) "Папка не найдена: ${dir.path}" else "Нет доступа к ${dir.path}. $ACCESS_HINT")
        }
        val sorted = files.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }))
        val sb = StringBuilder("${dir.path} — ${files.size} элем.\n")
        sorted.take(MAX_LIST).forEach { f ->
            if (f.isDirectory) sb.append("📁 ").append(f.name).append("/\n")
            else sb.append("📄 ").append(f.name).append(" (").append(human(f.length())).append(")\n")
        }
        if (files.size > MAX_LIST) sb.append("… и ещё ${files.size - MAX_LIST}\n")
        return ok(sb.toString().trimEnd())
    }

    private fun readFile(f: File, maxChars: Int): ToolResult {
        if (f.isDirectory) return fail("Это папка: ${f.path}. Используй list_dir.")
        if (!f.exists() || !f.canRead()) {
            viaRoot("head -c ${maxChars * 4} -- ${q(f.path)}")?.let { return it }
            return fail(if (!f.exists()) "Файл не найден: ${f.path}" else "Нет доступа к ${f.path}. $ACCESS_HINT")
        }
        val bytes = readPrefix(f, maxChars * 4)
        if (bytes.any { it == 0.toByte() }) return ok("Бинарный файл ${f.path}, размер ${human(f.length())}")
        var text = String(bytes, Charsets.UTF_8)
        if (text.length > maxChars) text = text.take(maxChars) + "\n… (обрезано)"
        else if (f.length() > bytes.size) text += "\n… (обрезано, полный размер ${human(f.length())})"
        return ok(text)
    }

    private fun searchFiles(dir: File, pattern: String): ToolResult {
        val matcher = nameMatcher(pattern)
        val found = mutableListOf<String>()
        var visited = 0
        fun walk(d: File, depth: Int) {
            if (depth > 8 || found.size >= MAX_SEARCH || visited > 20_000) return
            val list = d.listFiles() ?: return
            for (f in list) {
                visited++
                if (matcher(f.name)) found += (if (f.isDirectory) "📁 " else "📄 ") + f.path
                if (found.size >= MAX_SEARCH) return
                // Android/ holds other apps' private data: huge and mostly inaccessible.
                if (f.isDirectory && !f.name.startsWith(".") && f.name != "Android") walk(f, depth + 1)
            }
        }
        if (!dir.isDirectory) return fail("Папка не найдена: ${dir.path}")
        walk(dir, 0)
        return ok(
            if (found.isEmpty()) "Ничего не найдено по «$pattern» в ${dir.path}"
            else "Найдено ${found.size}${if (found.size >= MAX_SEARCH) "+" else ""}:\n" + found.joinToString("\n"),
        )
    }

    private fun writeFile(f: File, content: String, append: Boolean): ToolResult {
        val bytes = content.toByteArray(Charsets.UTF_8)
        touched += f.path
        return try {
            f.parentFile?.mkdirs()
            FileOutputStream(f, append).use { it.write(bytes) }
            ok("${if (append) "Дописано в файл" else "Записан файл"} ${f.path} (${human(bytes.size.toLong())})")
        } catch (e: IOException) {
            val b64 = Base64.getEncoder().encodeToString(bytes)
            val redirect = if (append) ">>" else ">"
            viaRoot("mkdir -p -- ${q(f.parent ?: "/")} && printf '%s' ${q(b64)} | base64 -d $redirect ${q(f.path)}") {
                "Записан файл (root): ${f.path}"
            } ?: fail("Не удалось записать ${f.path}: ${e.message}. $ACCESS_HINT")
        }
    }

    private fun mkdir(dir: File): ToolResult {
        if (dir.isDirectory) return ok("Папка уже существует: ${dir.path}")
        if (dir.exists()) return fail("Уже есть файл с таким именем: ${dir.path}")
        if (dir.mkdirs() || dir.isDirectory) return ok("Создана папка: ${dir.path}")
        return viaRoot("mkdir -p -- ${q(dir.path)}") { "Создана папка (root): ${dir.path}" }
            ?: fail("Не удалось создать папку ${dir.path}. $ACCESS_HINT")
    }

    private fun move(from: File, to: File): ToolResult {
        if (!from.exists()) {
            return viaRoot("mv -- ${q(from.path)} ${q(to.path)}") { "Перемещено (root): ${from.path} → ${to.path}" }
                ?: fail("Не найдено: ${from.path}")
        }
        val dest = if (to.isDirectory) File(to, from.name) else to
        if (dest.exists()) return fail("Назначение уже существует: ${dest.path}")
        touched += dest.path
        dest.parentFile?.mkdirs()
        if (from.renameTo(dest)) return ok("Перемещено: ${from.path} → ${dest.path}")
        try {
            if (from.isDirectory) from.copyRecursively(dest, overwrite = false) else from.copyTo(dest, overwrite = false)
            if (from.deleteRecursively()) return ok("Перемещено: ${from.path} → ${dest.path}")
        } catch (_: Exception) {
        }
        return viaRoot("mv -- ${q(from.path)} ${q(dest.path)}") { "Перемещено (root): ${from.path} → ${dest.path}" }
            ?: fail("Не удалось переместить ${from.path} → ${dest.path}. $ACCESS_HINT")
    }

    private fun copy(from: File, to: File): ToolResult {
        if (!from.exists()) {
            return viaRoot("cp -r -- ${q(from.path)} ${q(to.path)}") { "Скопировано (root): ${from.path} → ${to.path}" }
                ?: fail("Не найдено: ${from.path}")
        }
        val dest = if (to.isDirectory) File(to, from.name) else to
        touched += dest.path
        return try {
            dest.parentFile?.mkdirs()
            if (from.isDirectory) from.copyRecursively(dest, overwrite = true) else from.copyTo(dest, overwrite = true)
            ok("Скопировано: ${from.path} → ${dest.path}")
        } catch (e: Exception) {
            viaRoot("cp -r -- ${q(from.path)} ${q(dest.path)}") { "Скопировано (root): ${from.path} → ${dest.path}" }
                ?: fail("Не удалось скопировать: ${e.message}. $ACCESS_HINT")
        }
    }

    private fun delete(f: File, recursive: Boolean): ToolResult {
        if (isProtected(f)) return fail("Отказано: ${f.path} — защищённый путь, удалять нельзя.")
        val flags = if (recursive) "-rf" else "-f"
        if (!f.exists()) {
            return viaRoot("rm $flags -- ${q(f.path)}") { "Удалено (root): ${f.path}" } ?: fail("Не найдено: ${f.path}")
        }
        if (f.isDirectory && !recursive && f.list()?.isNotEmpty() == true) {
            return fail("Папка не пуста. Чтобы удалить вместе с содержимым, передай \"recursive\": true.")
        }
        val deleted = if (f.isDirectory) f.deleteRecursively() else f.delete()
        if (deleted) return ok("Удалено: ${f.path}")
        return viaRoot("rm ${if (f.isDirectory) "-rf" else "-f"} -- ${q(f.path)}") { "Удалено (root): ${f.path}" }
            ?: fail("Не удалось удалить ${f.path}. $ACCESS_HINT")
    }

    private fun download(url: String, dest: String?): ToolResult {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return fail("Скачивать можно только http(s)-ссылки. Если файл есть только в приложении ИИ — Relay скачает его кнопкой «Скачать» в ответе.")
        }
        var u = URL(url)
        var conn: HttpURLConnection? = null
        for (hop in 0 until 6) {
            val c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Relay/0.2")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: return fail("Перенаправление без адреса ($code)")
                c.disconnect()
                u = URL(u, loc)
                continue
            }
            if (code !in 200..299) {
                c.disconnect()
                return fail("Сервер ответил $code для $u")
            }
            conn = c
            break
        }
        val c = conn ?: return fail("Слишком много перенаправлений")
        try {
            var name = fileNameFrom(c.getHeaderField("Content-Disposition")) ?: URLDecoder.decode(u.path.substringAfterLast('/'), "UTF-8")
            name = name.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").trim().trim('.').take(120).ifBlank { "download" }
            if (!name.contains('.')) dev.relay.agent.agent.Mime.ext(c.contentType)?.let { name += ".$it" }
            val target = when {
                dest == null -> resolve("~/Download")
                dest.endsWith("/") -> resolve(dest)
                else -> resolve(dest)
            }
            val file = if (dest == null || dest.endsWith("/") || target.isDirectory) unique(target, name) else target
            file.parentFile?.mkdirs()
            touched += file.path
            var total = 0L
            c.inputStream.use { input ->
                FileOutputStream(file).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_DOWNLOAD) {
                            out.close()
                            file.delete()
                            return fail("Файл больше ${human(MAX_DOWNLOAD)} — скачивание прервано")
                        }
                        out.write(buf, 0, n)
                    }
                }
            }
            platform.onFileCreated(file, "download")
            return ok("Скачано: ${file.path} (${human(total)})")
        } finally {
            c.disconnect()
        }
    }

    private fun openFile(f: File): ToolResult {
        if (!f.isFile) return fail("Файл не найден: ${f.path}")
        return ok(platform.openFile(f))
    }

    private fun attach(f: File): ToolResult {
        if (!f.isFile) return fail(if (f.isDirectory) "Это папка, прикрепить можно только файл: ${f.path}" else "Файл не найден: ${f.path}")
        if (!f.canRead()) return fail("Нет доступа к ${f.path}. $ACCESS_HINT")
        if (f.length() > MAX_ATTACH) return fail("Файл слишком большой (${human(f.length())}), максимум ${human(MAX_ATTACH)}")
        return ok(platform.attachFile(f))
    }

    private fun runShell(command: String, root: Boolean, timeoutSec: Long): ToolResult {
        if (root) {
            if (!rootAllowed()) return fail("Root отключён в настройках Relay.")
            if (!shell.hasRoot()) return fail("Root недоступен (su не найден или доступ не выдан).")
        }
        val r = shell.run(command, root, timeoutSec.coerceIn(1, 600), workDir = platform.storageRoot)
        var out = r.combined()
        if (out.length > 16_000) out = out.take(16_000) + "\n… (обрезано)"
        return ToolResult(r.ok, "exit=${r.exitCode}${if (root) " (root)" else ""}\n" + out.ifEmpty { "(нет вывода)" })
    }

    private fun deviceInfo(): ToolResult {
        val info = LinkedHashMap<String, Any?>(platform.deviceInfo())
        val root = platform.storageRoot
        info["Общая память"] = "${human(root.usableSpace)} свободно из ${human(root.totalSpace)}"
        info["Папка ~"] = root.path
        info["Root"] = when {
            !rootAllowed() -> "отключён в настройках"
            shell.cachedRoot == true -> "есть"
            shell.cachedRoot == false -> "нет"
            shell.suPresent() -> "su найден (не проверен)"
            else -> "нет"
        }
        return ok(info.entries.joinToString("\n") { "${it.key}: ${it.value}" })
    }

    // endregion

    private fun viaRoot(cmd: String, success: ((ShellResult) -> String)? = null): ToolResult? {
        if (!rootAllowed() || !shell.suPresent() || !shell.hasRoot()) return null
        val r = shell.run(cmd, root = true, timeoutSec = 60)
        return if (r.ok) ok(success?.invoke(r) ?: r.stdout.trimEnd().ifEmpty { "OK (root)" })
        else fail("root: " + r.combined().ifEmpty { "exit ${r.exitCode}" })
    }

    private fun isProtected(f: File): Boolean {
        val p = canonical(f)
        if (p in setOf("/", canonical(platform.storageRoot), "/sdcard", "/storage", "/storage/emulated", "/data", "/system", "/vendor", "/product", "/apex", "/proc", "/sys", "/dev")) return true
        return listOf("/system/", "/vendor/", "/product/", "/apex/", "/proc/", "/sys/", "/dev/").any { p.startsWith(it) }
    }

    private fun canonical(f: File): String = try {
        f.canonicalPath
    } catch (e: IOException) {
        f.absolutePath
    }

    companion object {
        private const val MAX_LIST = 300
        private const val MAX_SEARCH = 200
        private const val MAX_DOWNLOAD = 1024L * 1024 * 1024
        const val MAX_ATTACH = 100L * 1024 * 1024

        fun unique(dir: File, name: String): File {
            var f = File(dir, name)
            if (!f.exists()) return f
            val base = name.substringBeforeLast('.', name)
            val ext = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
            var i = 1
            while (f.exists()) f = File(dir, "$base (${i++})$ext")
            return f
        }

        fun fileNameFrom(disposition: String?): String? {
            if (disposition.isNullOrBlank()) return null
            Regex("""filename\*\s*=\s*(?:UTF-8|utf-8)''([^;]+)""").find(disposition)?.let {
                return URLDecoder.decode(it.groupValues[1].trim('"', ' '), "UTF-8")
            }
            Regex("""filename\s*=\s*"?([^";]+)"?""").find(disposition)?.let { return it.groupValues[1].trim() }
            return null
        }
        private const val ACCESS_HINT = "Выдай Relay «Доступ ко всем файлам» (Настройки → Разрешения) или разреши root."

        private val PATH_KEYS = listOf("path", "dir", "folder", "directory", "file")
        private val FROM_KEYS = listOf("from", "src", "source")
        private val TO_KEYS = listOf("to", "dst", "dest", "destination", "target")

        private val ALIASES = mapOf(
            "download" to "Download", "downloads" to "Download", "загрузки" to "Download", "загрузка" to "Download",
            "documents" to "Documents", "документы" to "Documents",
            "pictures" to "Pictures", "картинки" to "Pictures", "изображения" to "Pictures",
            "dcim" to "DCIM", "фото" to "DCIM", "камера" to "DCIM",
            "music" to "Music", "музыка" to "Music",
            "movies" to "Movies", "видео" to "Movies",
        )

        val SPECS: List<ToolSpec> = listOf(
            ToolSpec("list_dir", "показать содержимое папки", listOf(ToolParam("path", "string", "путь к папке")), Risk.READ),
            ToolSpec(
                "read_file", "прочитать текстовый файл",
                listOf(ToolParam("path", "string", "путь к файлу"), ToolParam("max_chars", "integer", "лимит символов", required = false)),
                Risk.READ,
            ),
            ToolSpec(
                "search_files", "найти файлы по имени (подстрока или маска * ?)",
                listOf(ToolParam("path", "string", "где искать"), ToolParam("pattern", "string", "имя или маска")),
                Risk.READ,
            ),
            ToolSpec(
                "write_file", "создать/перезаписать текстовый файл",
                listOf(
                    ToolParam("path", "string", "путь к файлу"),
                    ToolParam("content", "string", "содержимое"),
                    ToolParam("append", "boolean", "дописать в конец", required = false),
                ),
                Risk.WRITE,
            ),
            ToolSpec("mkdir", "создать папку (вместе с родительскими)", listOf(ToolParam("path", "string", "путь к папке")), Risk.WRITE),
            ToolSpec(
                "move", "переместить или переименовать",
                listOf(ToolParam("from", "string", "откуда"), ToolParam("to", "string", "куда")), Risk.WRITE,
            ),
            ToolSpec(
                "copy", "скопировать файл или папку",
                listOf(ToolParam("from", "string", "откуда"), ToolParam("to", "string", "куда")), Risk.WRITE,
            ),
            ToolSpec(
                "delete", "удалить файл или папку",
                listOf(ToolParam("path", "string", "что удалить"), ToolParam("recursive", "boolean", "вместе с содержимым", required = false)),
                Risk.DANGER,
            ),
            ToolSpec(
                "shell", "выполнить shell-команду Android",
                listOf(ToolParam("command", "string", "команда"), ToolParam("root", "boolean", "выполнить через su", required = false)),
                Risk.DANGER,
            ),
            ToolSpec(
                "download_file", "скачать файл по http(s)-ссылке (по умолчанию в ~/Download)",
                listOf(ToolParam("url", "string", "ссылка на файл"), ToolParam("path", "string", "папка или путь назначения", required = false)),
                Risk.WRITE,
            ),
            ToolSpec(
                "attach_file", "прикрепить файл с устройства к следующему сообщению в этот чат (чтобы ты его увидел)",
                listOf(ToolParam("path", "string", "путь к файлу")), Risk.WRITE,
            ),
            ToolSpec("open_file", "открыть файл в подходящем приложении на устройстве", listOf(ToolParam("path", "string", "путь к файлу")), Risk.WRITE),
            ToolSpec("open_url", "открыть ссылку в браузере", listOf(ToolParam("url", "string", "адрес")), Risk.WRITE),
            ToolSpec("launch_app", "запустить приложение", listOf(ToolParam("package", "string", "имя пакета или название приложения")), Risk.WRITE),
            ToolSpec("device_info", "сведения об устройстве, памяти и root", emptyList(), Risk.READ),
        )

        fun spec(name: String): ToolSpec? = SPECS.firstOrNull { it.name == name }

        /** Unknown tools are treated as dangerous so they always need approval. */
        fun riskOf(call: ToolCall): Risk = spec(call.tool)?.risk ?: Risk.DANGER

        private fun q(s: String) = Shell.quote(s)

        private fun ok(text: String) = ToolResult(true, text)
        private fun fail(text: String) = ToolResult(false, text)

        private fun str(a: JSONObject, keys: List<String>, default: String? = null): String {
            for (k in keys) {
                val v = a.opt(k)
                if (v != null && v != JSONObject.NULL) {
                    val s = v.toString()
                    if (s.isNotBlank() || default == "") return s
                }
            }
            return default ?: throw ArgError("Не указан параметр «${keys.first()}»")
        }

        private fun readPrefix(f: File, max: Int): ByteArray = f.inputStream().use { input ->
            val buf = ByteArray(max)
            var total = 0
            while (total < max) {
                val n = input.read(buf, total, max - total)
                if (n < 0) break
                total += n
            }
            buf.copyOf(total)
        }

        private fun nameMatcher(pattern: String): (String) -> Boolean {
            val p = pattern.trim()
            if (p.contains('*') || p.contains('?')) {
                val rx = Regex(
                    p.split('*').joinToString(".*") { part -> part.split('?').joinToString(".") { Regex.escape(it) } },
                    RegexOption.IGNORE_CASE,
                )
                return { rx.matches(it) }
            }
            return { it.contains(p, ignoreCase = true) }
        }

        fun human(bytes: Long): String {
            if (bytes < 1024) return "$bytes Б"
            val units = listOf("КБ", "МБ", "ГБ", "ТБ")
            var v = bytes / 1024.0
            var i = 0
            while (v >= 1024 && i < units.lastIndex) {
                v /= 1024
                i++
            }
            return String.format(Locale.US, "%.1f %s", v, units[i])
        }
    }
}
