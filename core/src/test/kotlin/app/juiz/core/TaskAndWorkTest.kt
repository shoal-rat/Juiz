package app.juiz.core

import app.juiz.core.model.ActionContent
import app.juiz.core.model.ActionKind
import app.juiz.core.model.ActionStatus
import app.juiz.core.model.TaskKind
import app.juiz.core.model.TaskStatus
import app.juiz.core.tasks.DefinitelyNotSent
import app.juiz.core.tasks.ExecutionOutcome
import app.juiz.core.tasks.IllegalTransition
import app.juiz.core.tasks.NewTask
import app.juiz.core.util.sha256Hex
import app.juiz.core.work.HandoffResult
import app.juiz.core.work.HandoffRoute
import app.juiz.core.work.HandoffService
import app.juiz.core.work.LocalExchangeFolder
import app.juiz.core.work.WorkspaceAgentsClient
import app.juiz.core.work.WorkspaceAgentsError
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TaskAndWorkTest {

    private fun newTask(core: JuizCore) = core.tasks.create(
        NewTask("做 Q3 方案 Slides", "李女士需要一份 Q3 合作方案", TaskKind.DOCUMENT, "13800000000", "李女士", "C-1",
            mapOf("截止时间" to "10月8日18点"), "10 页以内", "10月8日18点"),
    )

    @Test
    fun taskIdsAndIllegalTransitions() {
        val (core, _) = testCore()
        val t = newTask(core)
        assertEquals("T-20260928-0001", t.id)
        assertEquals("T-20260928-0002", newTask(core).id)
        assertEquals(TaskStatus.PENDING_CONFIRMATION, t.status)
        assertFailsWith<IllegalTransition> { core.tasks.transition(t.id, TaskStatus.COMPLETED) }
        core.tasks.transition(t.id, TaskStatus.HANDED_OFF)
        core.tasks.transition(t.id, TaskStatus.NEEDS_VERIFICATION)
        core.tasks.transition(t.id, TaskStatus.COMPLETED)
        assertFailsWith<IllegalTransition> { core.tasks.transition(t.id, TaskStatus.IN_PROGRESS) }
    }

    @Test
    fun approvalBindsContentAndExecutionHappensOnce(): Unit = runBlocking {
        val (core, _) = testCore()
        val content = ActionContent("方案", "您好，附件是方案。")
        val a = core.tasks.proposeAction(null, ActionKind.EMAIL, "li@example.com", content)
        assertFailsWith<IllegalStateException> { core.tasks.approve(a.id, "not-the-hash") }
        core.tasks.approve(a.id, a.contentHash)

        var sends = 0
        val first = core.tasks.execute(a.id) { sends++; "250 OK queued" }
        assertIs<ExecutionOutcome.Done>(first)
        val second = core.tasks.execute(a.id) { sends++; "250 OK" }
        assertIs<ExecutionOutcome.Refused>(second)
        assertEquals(1, sends, "重试不能导致重复发信")
    }

    @Test
    fun failureSemantics(): Unit = runBlocking {
        val (core, _) = testCore()
        val c = ActionContent(body = "x")
        val a1 = core.tasks.proposeAction(null, ActionKind.EMAIL, "a@b.c", c).also { core.tasks.approve(it.id, it.contentHash) }
        core.tasks.execute(a1.id) { throw DefinitelyNotSent("连接被拒绝") }
        assertEquals(ActionStatus.FAILED, core.tasks.action(a1.id)!!.status)

        val a2 = core.tasks.proposeAction(null, ActionKind.EMAIL, "a@b.c", c.copy(body = "y")).also { core.tasks.approve(it.id, it.contentHash) }
        core.tasks.execute(a2.id) { throw java.io.IOException("DATA 之后断线") }
        assertEquals(ActionStatus.UNKNOWN, core.tasks.action(a2.id)!!.status, "不确定是否发出时必须标为 UNKNOWN")
        assertIs<ExecutionOutcome.Refused>(core.tasks.execute(a2.id) { "again" })
    }

    @Test
    fun workspaceAgentsHandoffPollAndVerify(): Unit = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"conversation_url":"https://chatgpt.com/c/x","agent_trigger_run_id":"apirun_1"}"""))
        server.enqueue(MockResponse().setBody("""{"object":"workspace_agent.trigger_run","id":"apirun_1","status":"in_progress","created_at":1,"agent_id":"a","api_trigger_id":"agtch_1","conversation_url":"u","error":null}"""))
        server.enqueue(MockResponse().setBody("""{"object":"workspace_agent.trigger_run","id":"apirun_1","status":"completed","created_at":1,"agent_id":"a","api_trigger_id":"agtch_1","conversation_url":"u","error":null}"""))
        server.start()
        val (core, _) = testCore()
        val t = newTask(core)
        val handoff = HandoffService(core.tasks, WorkspaceAgentsClient("tok", "agtch_1", server.url("/v1").toString().trimEnd('/')), "Juiz")

        val r = handoff.handoff(t.id, HandoffRoute.WORKSPACE_AGENT)
        assertIs<HandoffResult.Triggered>(r)
        val req = server.takeRequest()
        assertEquals("/v1/workspace_agents/agtch_1/trigger", req.path)
        assertEquals("Bearer tok", req.getHeader("Authorization"))
        assertEquals("workspace_agent_runs=v1", req.getHeader("OpenAI-Beta"))
        assertEquals("${t.id}#1", req.getHeader("Idempotency-Key"))
        assertTrue(req.body.readUtf8().contains("juiz.taskcard/v1"))

        assertEquals(TaskStatus.IN_PROGRESS, handoff.poll(t.id))
        assertEquals(TaskStatus.NEEDS_VERIFICATION, handoff.poll(t.id), "API 报告完成只能进入待核实")
        assertEquals(TaskStatus.NEEDS_VERIFICATION, core.tasks.get(t.id)!!.status)

        val dir = Files.createTempDirectory("exchange").toFile()
        val taskDir = java.io.File(dir, t.id).apply { mkdirs() }
        val deck = "fake pptx bytes".toByteArray()
        java.io.File(taskDir, "q3.pptx").writeBytes(deck)
        java.io.File(taskDir, "result.json").writeText(
            """{"schema":"juiz.result/v1","task_id":"${t.id}","status":"completed","summary":"8 页","deliverables":[{"path":"q3.pptx","sha256":"${sha256Hex("tampered".toByteArray())}","bytes":${deck.size}}]}""",
        )
        val bad = handoff.verify(t.id, LocalExchangeFolder(dir))
        assertTrue(!bad.ok && bad.issues.any { "哈希" in it })
        assertEquals(TaskStatus.NEEDS_VERIFICATION, core.tasks.get(t.id)!!.status)

        java.io.File(taskDir, "result.json").writeText(
            """{"schema":"juiz.result/v1","task_id":"${t.id}","status":"completed","summary":"8 页","deliverables":[{"path":"q3.pptx","sha256":"${sha256Hex(deck)}","bytes":${deck.size}}]}""",
        )
        assertTrue(handoff.verify(t.id, LocalExchangeFolder(dir)).ok)
        assertEquals(TaskStatus.COMPLETED, core.tasks.get(t.id)!!.status)
        server.shutdown()
    }

    @Test
    fun workspaceAgentsErrorsAreTyped(): Unit = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"not runnable"}"""))
        server.start()
        val client = WorkspaceAgentsClient("tok", "agtch_1", server.url("/v1").toString().trimEnd('/'))
        assertFailsWith<WorkspaceAgentsError.NotRunnable> { client.trigger("x", null, "k") }
        server.shutdown()
    }

    @Test
    fun manualShareProducesCard(): Unit = runBlocking {
        val (core, _) = testCore()
        val t = newTask(core)
        val r = core.handoff().handoff(t.id, HandoffRoute.MANUAL_SHARE)
        assertIs<HandoffResult.ShareNeeded>(r)
        assertTrue("【Juiz 任务卡】${t.id}" in r.cardText)
        assertTrue("10月8日18点" in r.cardText)
        core.handoff().confirmManualHandoff(t.id)
        assertEquals(TaskStatus.HANDED_OFF, core.tasks.get(t.id)!!.status)
    }
}
