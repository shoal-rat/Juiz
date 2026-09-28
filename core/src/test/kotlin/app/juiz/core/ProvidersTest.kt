package app.juiz.core

import app.juiz.core.conversation.ChatEvent
import app.juiz.core.conversation.ChatItem
import app.juiz.core.conversation.ChatRequest
import app.juiz.core.conversation.Tools
import app.juiz.core.providers.openai.CompatChatModel
import app.juiz.core.providers.openai.OpenAiRealtimeStt
import app.juiz.core.providers.openai.OpenAiResponsesModel
import app.juiz.core.providers.openai.OpenAiTts
import app.juiz.core.util.JuizJson
import app.juiz.core.voice.Pcm
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProvidersTest {
    private val history = listOf(
        ChatItem.User("〔来电方说〕帮我查下"),
        ChatItem.Assistant("好的，我查一下。"),
        ChatItem.ToolCall("call_1", Tools.CHECK_TASK_STATUS, "{}"),
        ChatItem.ToolResult("call_1", """{"ok":true}"""),
    )

    @Test
    fun responsesStreamingParsesTextAndToolCalls(): Unit = runBlocking {
        val sse = listOf(
            """{"type":"response.created","response":{"id":"r1"}}""",
            """{"type":"response.output_text.delta","item_id":"m1","output_index":0,"content_index":0,"delta":"您好，"}""",
            """{"type":"response.output_text.delta","item_id":"m1","output_index":0,"content_index":0,"delta":"请稍等。"}""",
            """{"type":"response.function_call_arguments.delta","item_id":"f1","output_index":1,"delta":"{}"}""",
            """{"type":"response.output_item.done","output_index":1,"item":{"type":"function_call","id":"f1","call_id":"call_9","name":"check_task_status","arguments":"{}"}}""",
            """{"type":"response.completed","response":{"id":"r1","usage":{"input_tokens":120,"output_tokens":9}}}""",
        ).joinToString("") { "event: x\ndata: $it\n\n" }
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse))
        server.start()
        val model = OpenAiResponsesModel("sk-test", "gpt-6-luna", "none", server.url("/v1").toString().trimEnd('/'))
        val events = model.stream(ChatRequest("系统", history, Tools.all)).toList()
        assertEquals("您好，请稍等。", events.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text })
        val call = events.filterIsInstance<ChatEvent.ToolCallDone>().single()
        assertEquals("call_9" to "check_task_status", call.callId to call.name)
        assertEquals(120, events.filterIsInstance<ChatEvent.Completed>().single().inputTokens)

        val body = JuizJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("gpt-6-luna", body["model"]!!.jsonPrimitive.content)
        assertEquals("false", body["store"]!!.jsonPrimitive.content)
        assertEquals("none", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        val input = body["input"]!!.jsonArray
        assertEquals("function_call", input[2].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("function_call_output", input[3].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("function", body["tools"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
        server.shutdown()
    }

    @Test
    fun compatGroupsToolCallsAndStripsThinking(): Unit = runBlocking {
        val chunks = listOf(
            """{"choices":[{"delta":{"content":"<thi"}}]}""",
            """{"choices":[{"delta":{"content":"nk>让我想想</think>好的，"}}]}""",
            """{"choices":[{"delta":{"content":"我查一下。"}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","type":"function","function":{"name":"check_task_status","arguments":""}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{}"}}]}}]}""",
            """{"choices":[{"delta":{},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":50,"completion_tokens":5}}""",
        ).joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(chunks))
        server.start()
        val model = CompatChatModel(server.url("/v1").toString(), "qwen3:4b")
        val events = model.stream(ChatRequest("系统", history, Tools.all)).toList()
        assertEquals("好的，我查一下。", events.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text })
        assertEquals("c1", events.filterIsInstance<ChatEvent.ToolCallDone>().single().callId)

        val body = JuizJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val messages = body["messages"]!!.jsonArray
        assertEquals(listOf("system", "user", "assistant", "tool"), messages.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        val assistant = messages[2].jsonObject
        assertEquals("好的，我查一下。", assistant["content"]!!.jsonPrimitive.content)
        assertEquals(1, (assistant["tool_calls"] as JsonArray).size)
        server.shutdown()
    }

    @Test
    fun openAiTtsHandlesOddChunkBoundaries(): Unit = runBlocking {
        val samples = ShortArray(1001) { (it * 31).toShort() }
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(Buffer().write(Pcm.shortsToBytes(samples))).throttleBody(333, 0, java.util.concurrent.TimeUnit.MILLISECONDS))
        server.start()
        val tts = OpenAiTts("sk-test", baseUrl = server.url("/v1").toString().trimEnd('/'))
        val out = tts.synthesize("你好").toList().flatMap { it.toList() }
        assertEquals(samples.toList(), out)
        val body = JuizJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("pcm", body["response_format"]!!.jsonPrimitive.content)
        server.shutdown()
    }

    @Test
    fun realtimeTranscriptionSessionShape() {
        val update = OpenAiRealtimeStt("sk", keywords = listOf("Juiz")).sessionUpdate()
        val session = update["session"]!!.jsonObject
        assertEquals("transcription", session["type"]!!.jsonPrimitive.content)
        val input = session["audio"]!!.jsonObject["input"]!!.jsonObject
        assertEquals(24000, input["format"]!!.jsonObject["rate"]!!.jsonPrimitive.content.toInt())
        assertEquals("gpt-live-transcribe", input["transcription"]!!.jsonObject["model"]!!.jsonPrimitive.content)
        assertTrue(input["turn_detection"] is JsonNull, "gpt-live-transcribe 要求 turn_detection 为 null")
    }
}
