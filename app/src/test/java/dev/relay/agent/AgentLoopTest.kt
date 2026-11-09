package dev.relay.agent

import dev.relay.agent.agent.AgentListener
import dev.relay.agent.agent.AgentLoop
import dev.relay.agent.agent.ApprovalMode
import dev.relay.agent.agent.ApprovalPolicy
import dev.relay.agent.agent.ChatTarget
import dev.relay.agent.agent.Decision
import dev.relay.agent.agent.TargetReply
import dev.relay.agent.agent.ToolProtocol
import dev.relay.agent.tools.Risk
import dev.relay.agent.tools.ToolCall
import dev.relay.agent.tools.ToolExecutor
import dev.relay.agent.tools.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AgentLoopTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class ScriptedTarget(private val replies: List<String>) : ChatTarget {
        val sent = mutableListOf<String>()
        override suspend fun send(text: String, onPartial: (String) -> Unit): TargetReply {
            sent += text
            val reply = replies[sent.size - 1]
            onPartial(reply.take(5))
            return TargetReply(reply)
        }
    }

    private class RecordingListener(private val decision: Decision) : AgentListener {
        val events = mutableListOf<String>()
        override fun onPartial(text: String) {}
        override fun onAssistant(text: String, step: Int) { events += "assistant:$step" }
        override fun onToolStatus(call: ToolCall, status: String, result: ToolResult?) { events += "${call.tool}:$status" }
        override fun onInfo(text: String) { events += "info" }
        override suspend fun requestApproval(call: ToolCall, risk: Risk): Decision {
            events += "ask:${call.tool}:$risk"
            return decision
        }
    }

    private fun executor(): Pair<ToolExecutor, File> {
        val root = tmp.newFolder("storage")
        return ToolExecutor(FakePlatform(root), FakeRootShell(false)) { false } to root
    }

    @Test
    fun createsFolderThenFinishes() = runBlocking {
        val (ex, root) = executor()
        val target = ScriptedTarget(
            listOf(
                "Создаю.\nrelay\nCopy code\n{\"tool\":\"mkdir\",\"args\":{\"path\":\"~/Download/привет\"}}",
                "Готово! Папка создана.",
            ),
        )
        val listener = RecordingListener(Decision.ONCE)
        val steps = AgentLoop(target, { ex.execute(it) }, ToolExecutor::riskOf, listener, ApprovalPolicy(ApprovalMode.ASK)).run("prompt", agentMode = true)

        assertEquals(2, steps)
        assertTrue(File(root, "Download/привет").isDirectory)
        assertEquals(listOf("assistant:1", "mkdir:pending", "ask:mkdir:WRITE", "mkdir:running", "mkdir:done", "assistant:2"), listener.events)
        assertTrue(target.sent[1].startsWith(ToolProtocol.RESULT_HEADER))
        assertTrue(target.sent[1].contains("Создана папка"))
    }

    @Test
    fun deniedCallIsReportedBackToTheModel() = runBlocking {
        val (ex, root) = executor()
        val target = ScriptedTarget(listOf("{\"tool\":\"delete\",\"args\":{\"path\":\"~/Download\"}}", "Хорошо, не удаляю."))
        File(root, "Download").mkdirs()
        val listener = RecordingListener(Decision.DENY)
        AgentLoop(target, { ex.execute(it) }, ToolExecutor::riskOf, listener, ApprovalPolicy(ApprovalMode.AUTO_READ)).run("x", true)
        assertTrue(File(root, "Download").isDirectory)
        assertTrue(listener.events.contains("ask:delete:DANGER"))
        assertTrue(listener.events.contains("delete:denied"))
        assertTrue(target.sent[1].contains("отклонил"))
    }

    @Test
    fun readOnlyCallsAreAutoApprovedInAutoReadMode() = runBlocking {
        val (ex, _) = executor()
        val target = ScriptedTarget(listOf("{\"tool\":\"list_dir\",\"args\":{\"path\":\"~\"}}", "Вот список."))
        val listener = RecordingListener(Decision.DENY)
        AgentLoop(target, { ex.execute(it) }, ToolExecutor::riskOf, listener, ApprovalPolicy(ApprovalMode.AUTO_READ)).run("x", true)
        assertTrue(listener.events.none { it.startsWith("ask:") })
        assertTrue(listener.events.contains("list_dir:done"))
    }

    @Test
    fun alwaysIsNotRememberedForDangerousTools() {
        val p = ApprovalPolicy(ApprovalMode.ASK)
        p.allowAlways("shell", Risk.DANGER)
        p.allowAlways("mkdir", Risk.WRITE)
        assertTrue(p.isAutoApproved("mkdir", Risk.WRITE))
        assertTrue(!p.isAutoApproved("shell", Risk.DANGER))
    }

    @Test
    fun chatModeNeverExecutesTools() = runBlocking {
        val (ex, root) = executor()
        val target = ScriptedTarget(listOf("{\"tool\":\"mkdir\",\"args\":{\"path\":\"~/x\"}}"))
        val listener = RecordingListener(Decision.ONCE)
        val steps = AgentLoop(target, { ex.execute(it) }, ToolExecutor::riskOf, listener, ApprovalPolicy()).run("x", agentMode = false)
        assertEquals(1, steps)
        assertTrue(!File(root, "x").exists())
    }

    @Test
    fun stopsAtStepLimit() = runBlocking {
        val (ex, _) = executor()
        val loopReply = "{\"tool\":\"device_info\",\"args\":{}}"
        val target = ScriptedTarget(List(10) { loopReply })
        val listener = RecordingListener(Decision.ONCE)
        val steps = AgentLoop(target, { ex.execute(it) }, ToolExecutor::riskOf, listener, ApprovalPolicy(ApprovalMode.AUTO_ALL), maxSteps = 3).run("x", true)
        assertEquals(3, steps)
        assertEquals("info", listener.events.last())
    }
}
