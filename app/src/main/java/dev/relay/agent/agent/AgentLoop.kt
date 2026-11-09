package dev.relay.agent.agent

import dev.relay.agent.tools.Risk
import dev.relay.agent.tools.ToolCall
import dev.relay.agent.tools.ToolResult
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

data class TargetReply(
    val text: String,
    val codeBlocks: List<String> = emptyList(),
    val timedOut: Boolean = false,
)

/** A chat on a website or inside an installed app that can receive a prompt and return the AI reply. */
interface ChatTarget {
    suspend fun send(text: String, onPartial: (String) -> Unit): TargetReply
}

enum class Decision { ONCE, ALWAYS, DENY }

enum class ApprovalMode { ASK, AUTO_READ, AUTO_ALL }

class ApprovalPolicy(var mode: ApprovalMode = ApprovalMode.ASK) {
    private val always = mutableSetOf<String>()

    fun isAutoApproved(tool: String, risk: Risk): Boolean = when (mode) {
        ApprovalMode.AUTO_ALL -> true
        ApprovalMode.AUTO_READ -> risk == Risk.READ || tool in always
        ApprovalMode.ASK -> tool in always
    }

    /** "Always" is never remembered for dangerous tools (delete, shell). */
    fun allowAlways(tool: String, risk: Risk) {
        if (risk != Risk.DANGER) always += tool
    }

    fun reset() = always.clear()
}

interface AgentListener {
    fun onPartial(text: String)
    fun onAssistant(text: String, step: Int)
    fun onToolStatus(call: ToolCall, status: String, result: ToolResult? = null)
    fun onInfo(text: String) {}
    suspend fun requestApproval(call: ToolCall, risk: Risk): Decision
}

/** Claude-Code-style loop: send → parse tool calls → approve → execute → send results → repeat. */
class AgentLoop(
    private val target: ChatTarget,
    private val execute: suspend (ToolCall) -> ToolResult,
    private val riskOf: (ToolCall) -> Risk,
    private val listener: AgentListener,
    private val approvals: ApprovalPolicy,
    private val maxSteps: Int = 12,
    private val runId: String = System.currentTimeMillis().toString(36),
) {
    /** @return number of messages sent to the target. */
    suspend fun run(firstMessage: String, agentMode: Boolean): Int {
        var message = firstMessage
        var step = 0
        while (true) {
            step++
            val reply = target.send(message) { listener.onPartial(it) }
            listener.onAssistant(reply.text, step)
            if (reply.timedOut) listener.onInfo("Ответ не завершился вовремя — показана полученная часть.")
            if (!agentMode) return step

            val calls = ToolProtocol.parse(reply.text, reply.codeBlocks, "r$runId-s$step-c")
            if (calls.isEmpty()) return step
            if (step >= maxSteps) {
                listener.onInfo("Достигнут лимит шагов ($maxSteps) — агент остановлен.")
                return step
            }

            val results = mutableListOf<Pair<ToolCall, ToolResult>>()
            for (call in calls) {
                coroutineContext.ensureActive()
                val risk = riskOf(call)
                val allowed = if (approvals.isAutoApproved(call.tool, risk)) {
                    true
                } else {
                    listener.onToolStatus(call, "pending")
                    when (listener.requestApproval(call, risk)) {
                        Decision.ONCE -> true
                        Decision.ALWAYS -> {
                            approvals.allowAlways(call.tool, risk)
                            true
                        }
                        Decision.DENY -> false
                    }
                }
                if (!allowed) {
                    val r = ToolResult(false, "Пользователь отклонил это действие.")
                    listener.onToolStatus(call, "denied", r)
                    results += call to r
                    continue
                }
                listener.onToolStatus(call, "running")
                val r = execute(call)
                listener.onToolStatus(call, if (r.ok) "done" else "error", r)
                results += call to r
            }
            message = ToolProtocol.formatResults(results)
        }
    }
}
