package dev.relay.agent.agent

import dev.relay.agent.tools.ToolCall
import dev.relay.agent.tools.ToolExecutor
import dev.relay.agent.tools.ToolResult
import dev.relay.agent.tools.ToolSpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * Text protocol that lets any chat AI (without native tool calling) drive device tools:
 * the model answers with ```relay {"tool": ..., "args": {...}}``` blocks and receives results
 * as the next user message.
 */
object ToolProtocol {
    const val RESULT_HEADER = "[Relay • результат]"
    const val PREAMBLE_HEADER = "[Relay • инструкция для ИИ]"

    data class Context(
        val storageRoot: String,
        val rootAvailable: Boolean,
        val device: String,
    )

    fun preamble(ctx: Context, specs: List<ToolSpec> = ToolExecutor.SPECS): String = buildString {
        appendLine(PREAMBLE_HEADER)
        appendLine("Ты работаешь как агент (аналог Claude Code) на устройстве пользователя (${ctx.device}) через приложение Relay и можешь выполнять действия на нём с помощью инструментов.")
        appendLine()
        appendLine("Чтобы вызвать инструмент, напиши блок кода с языком relay, внутри — один JSON-объект:")
        appendLine("```relay")
        appendLine("""{"tool": "mkdir", "args": {"path": "~/Download/пример"}}""")
        appendLine("```")
        appendLine()
        appendLine("Правила:")
        appendLine("• Можно несколько блоков relay в одном ответе — они выполнятся по порядку.")
        appendLine("• Результаты придут следующим сообщением, начинающимся с «$RESULT_HEADER». Не выдумывай результаты — дождись их.")
        appendLine("• Когда задача выполнена, ответь обычным текстом без блоков relay.")
        appendLine("• Пути: ~ = ${ctx.storageRoot} (общая память). Загрузки — ~/Download, документы — ~/Documents, фото — ~/DCIM и ~/Pictures.")
        appendLine("• Root: ${if (ctx.rootAvailable) "доступен (shell с \"root\": true)" else "недоступен"}.")
        appendLine("• Удаление и shell пользователь подтверждает вручную.")
        appendLine()
        appendLine("Файлы:")
        appendLine("• Файлы, которые пользователь прикрепил, приходят вместе с сообщением. Нужен файл с устройства — вызови attach_file, он придёт со следующим сообщением.")
        appendLine("• Текстовый файл (код, md, csv, html, json…) создавай через write_file. Если файл доступен по ссылке — download_file.")
        appendLine("• Если ты создал файл у себя (PDF, таблица, картинка) — дай кнопку/ссылку «Скачать» в ответе: Relay сам скачает его в ~/Download и сообщит путь.")
        appendLine()
        appendLine("Инструменты:")
        for (s in specs) {
            val params = s.params.joinToString(", ") { p -> "\"${p.name}\": ${p.type}${if (p.required) "" else "?"}" }
            appendLine("• ${s.name} {$params} — ${s.description}")
        }
        appendLine()
        append("Задача пользователя:")
    }

    fun withPreamble(ctx: Context, userText: String): String = preamble(ctx) + "\n" + userText

    /** Code blocks are preferred (their text is not mangled by markdown); falls back to scanning the whole reply. */
    fun parse(text: String, codeBlocks: List<String> = emptyList(), idPrefix: String = "c"): List<ToolCall> {
        val fromBlocks = codeBlocks.flatMap { extract(it).map { e -> e.call } }
        val raw = fromBlocks.ifEmpty { extract(text).map { it.call } }
        return raw.mapIndexed { i, (tool, args) -> ToolCall("$idPrefix${i + 1}", tool, args) }
    }

    /** Reply text without tool-call JSON and code-block chrome, for display. */
    fun stripCalls(text: String): String {
        val spans = extract(text)
        if (spans.isEmpty()) return text.trim()
        val sb = StringBuilder()
        var pos = 0
        for (s in spans) {
            sb.append(text, pos, s.start)
            pos = s.end
            val ownLine = s.start == 0 || text[s.start - 1] == '\n'
            if (ownLine && pos < text.length && text[pos] == '\n') pos++
        }
        sb.append(text.substring(pos))
        return sb.toString()
            .lines()
            .filterNot { CHROME_LINES.contains(it.trim().lowercase()) }
            .joinToString("\n")
            .replace(Regex("```\\s*relay\\s*```"), "")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    fun formatResults(
        items: List<Pair<ToolCall, ToolResult>>,
        maxEach: Int = 4000,
        maxTotal: Int = 12_000,
        extra: String? = null,
    ): String {
        val body = buildString {
            appendLine(RESULT_HEADER)
            items.forEachIndexed { i, (call, res) ->
                appendLine("${i + 1}) ${call.tool} ${compactArgs(call.args)} → ${if (res.ok) "OK" else "ОШИБКА"}")
                appendLine(truncate(res.output.trim(), maxEach))
                appendLine()
            }
            if (!extra.isNullOrBlank()) {
                appendLine(extra.trim())
                appendLine()
            }
        }
        val tail = "Продолжай выполнение задачи. Если всё готово — кратко ответь пользователю без блоков relay."
        return truncate(body, maxTotal - tail.length - 1) + "\n" + tail
    }

    // region parsing

    private data class Span(val start: Int, val end: Int, val call: Pair<String, JSONObject>)

    private val CHROME_LINES = setOf("relay", "json", "copy", "copy code", "копировать", "копировать код", "code", "код", "скопировать")

    private fun extract(source: String): List<Span> {
        val out = mutableListOf<Span>()
        var i = 0
        val limit = minOf(source.length, 400_000)
        while (i < limit) {
            if (source[i] != '{') {
                i++
                continue
            }
            val end = matchBrace(source, i)
            if (end < 0) {
                i++
                continue
            }
            val cand = source.substring(i, end + 1)
            val call = (tryParse(cand) ?: tryParse(normalizeQuotes(cand)))?.let(::toCall)
            if (call != null) {
                out += Span(i, end + 1, call)
                i = end + 1
            } else {
                i++
            }
        }
        return out
    }

    private fun matchBrace(s: String, start: Int): Int {
        var depth = 0
        var inStr = false
        var esc = false
        var i = start
        while (i < s.length) {
            val c = s[i]
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
            } else {
                when (c) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            i++
        }
        return -1
    }

    private fun tryParse(s: String): JSONObject? = try {
        JSONObject(s)
    } catch (e: Exception) {
        null
    }

    /** Markdown renderers outside code blocks may turn quotes into typographic ones. */
    private fun normalizeQuotes(s: String) = s
        .replace('“', '"').replace('”', '"').replace('„', '"').replace('«', '"').replace('»', '"')
        .replace('\u00A0', ' ')

    private fun toCall(obj: JSONObject): Pair<String, JSONObject>? {
        val explicit = obj.optString("tool").trim()
        val named = obj.optString("name").trim()
        val tool = when {
            explicit.isNotEmpty() -> explicit
            named.isNotEmpty() && obj.has("arguments") && ToolExecutor.spec(named) != null -> named
            else -> return null
        }
        val args = obj.optJSONObject("args")
            ?: obj.optJSONObject("arguments")
            ?: obj.optJSONObject("parameters")
            ?: obj.optString("arguments").takeIf { it.startsWith("{") }?.let(::tryParse)
            ?: JSONObject().also { flat ->
                obj.keys().forEach { k -> if (k != "tool" && k != "name") flat.put(k, obj.get(k)) }
            }
        return tool to args
    }

    // endregion

    private fun compactArgs(args: JSONObject): String {
        val copy = JSONObject()
        args.keys().forEach { k ->
            val v = args.get(k)
            copy.put(k, if (v is String && v.length > 80) v.take(77) + "…" else v)
        }
        return copy.toString()
    }

    private fun truncate(s: String, max: Int): String =
        if (s.length <= max) s else s.take(maxOf(0, max - 20)) + "\n… (обрезано)"

    fun callsToJson(calls: List<ToolCall>): JSONArray = JSONArray().also { arr ->
        calls.forEach { arr.put(JSONObject().put("id", it.id).put("tool", it.tool).put("args", it.args)) }
    }

    /** Contents of ``` fenced blocks in markdown copied from an app's "Copy" button. */
    fun fencedBlocks(markdown: String): List<String> =
        Regex("```[^\\n`]*\\n([\\s\\S]*?)```").findAll(markdown).map { it.groupValues[1].trimEnd() }.toList()
}
