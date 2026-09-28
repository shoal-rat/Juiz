package app.juiz.core

import app.juiz.core.model.ActionStatus
import app.juiz.core.model.TaskKind
import app.juiz.core.model.TaskStatus
import app.juiz.core.tasks.NewTask
import app.juiz.core.util.JuizJson
import app.juiz.core.work.CloudWorker
import app.juiz.core.work.HandoffResult
import app.juiz.core.work.HandoffRoute
import app.juiz.core.work.HandoffService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CloudWorkerTest {
    @Test
    fun cloudRouteRunsInBackgroundDownloadsVerifiesAndProposesDraft(): Unit = runBlocking {
        val polls = AtomicInteger()
        val docx = "fake docx bytes".toByteArray()
        val draft = """{"to":"li@example.com","subject":"邀请函","body":"您好，附件是邀请函。","attachments":["invite.docx"]}"""
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.method == "POST" && request.path == "/v1/responses" -> MockResponse().setBody("""{"id":"resp_1","status":"queued"}""")
                request.path == "/v1/responses/resp_1" -> if (polls.incrementAndGet() < 2) MockResponse().setBody("""{"id":"resp_1","status":"in_progress"}""") else MockResponse().setBody(
                    """{"id":"resp_1","status":"completed","output":[{"type":"code_interpreter_call","id":"ci_1","container_id":"cntr_1"},{"type":"message","content":[{"type":"output_text","text":"已写好邀请函。","annotations":[{"type":"container_file_citation","container_id":"cntr_1","file_id":"cfile_1","filename":"invite.docx"}]}]}]}""",
                )
                request.path!!.startsWith("/v1/containers/cntr_1/files?") -> MockResponse().setBody(
                    """{"object":"list","data":[{"id":"cfile_0","path":"/mnt/data/input.txt","source":"user","bytes":1},{"id":"cfile_1","path":"/mnt/data/out/invite.docx","source":"assistant","bytes":${docx.size}},{"id":"cfile_2","path":"/mnt/data/drafts/mail.json","source":"assistant","bytes":10}],"has_more":false}""",
                )
                request.path == "/v1/containers/cntr_1/files/cfile_1/content" -> MockResponse().setBody(Buffer().write(docx))
                request.path == "/v1/containers/cntr_1/files/cfile_2/content" -> MockResponse().setBody(draft)
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val (core, _) = testCore()
        val t = core.tasks.create(NewTask("写邀请函", "新品发布会邀请函", TaskKind.DOCUMENT, "13822223333", "李女士", null, mapOf("邮箱" to "li@example.com"), brief = "写一封邀请函"))
        val dir = Files.createTempDirectory("cloud").toFile()
        val worker = CloudWorker("sk-test", baseUrl = server.url("/v1").toString().trimEnd('/'))
        val h = HandoffService(core.tasks, null, "Juiz", null, worker, dir)
        assertEquals(HandoffRoute.OPENAI_CLOUD, h.availableRoutes.first())

        assertIs<HandoffResult.Triggered>(h.handoff(t.id, HandoffRoute.OPENAI_CLOUD))
        val start = JuizJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("true", start["background"]!!.jsonPrimitive.content)
        assertEquals("gpt-6-sol", start["model"]!!.jsonPrimitive.content)
        assertEquals("code_interpreter", start["tools"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(start["input"]!!.jsonPrimitive.content.contains("## 工作说明"))

        assertEquals(TaskStatus.IN_PROGRESS, h.poll(t.id))
        assertEquals(TaskStatus.COMPLETED, h.poll(t.id))
        val action = core.tasks.actionsForTask(t.id).single()
        assertEquals(ActionStatus.PROPOSED, action.status)
        assertEquals("li@example.com", action.target)
        assertEquals(listOf("out/invite.docx"), action.content.attachments.map { it.name })
        assertTrue(java.io.File(dir, "${t.id}/out/invite.docx").readBytes().contentEquals(docx))
        server.shutdown()
    }
}
