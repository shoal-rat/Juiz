package app.juiz.core.providers.openai

import app.juiz.core.conversation.ChatEvent
import app.juiz.core.conversation.ChatItem
import app.juiz.core.conversation.ChatModel
import app.juiz.core.conversation.ChatRequest
import app.juiz.core.conversation.ModelException
import app.juiz.core.providers.Http
import app.juiz.core.providers.executeCancellable
import app.juiz.core.providers.readSse
import app.juiz.core.util.JuizJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OpenAI Responses API（POST /v1/responses，stream=true）。
 * store=false：对话历史由手机保存、逐轮发送，不在服务端留存会话。
 */
class OpenAiResponsesModel(
    private val apiKey: String,
    private val model: String = "gpt-6-luna",
    private val reasoningEffort: String? = "none",
    private val baseUrl: String = "https://api.openai.com/v1",
    private val client: OkHttpClient = Http.client,
) : ChatModel {
    override val id: String get() = "openai:$model"

    fun buildBody(req: ChatRequest): JsonObject = buildJsonObject {
        put("model", model)
        put("instructions", req.system)
        put("stream", true)
        put("store", false)
        put("max_output_tokens", req.maxOutputTokens)
        reasoningEffort?.let { putJsonObject("reasoning") { put("effort", it) } }
        putJsonArray("input") {
            for (item in req.history) {
                when (item) {
                    is ChatItem.User -> addJsonObject { put("role", "user"); put("content", item.text) }
                    is ChatItem.Assistant -> addJsonObject { put("role", "assistant"); put("content", item.text) }
                    is ChatItem.ToolCall -> addJsonObject {
                        put("type", "function_call")
                        put("call_id", item.callId)
                        put("name", item.name)
                        put("arguments", item.arguments)
                    }
                    is ChatItem.ToolResult -> addJsonObject {
                        put("type", "function_call_output")
                        put("call_id", item.callId)
                        put("output", item.output)
                    }
                }
            }
        }
        if (req.tools.isNotEmpty()) {
            putJsonArray("tools") {
                req.tools.forEach { t ->
                    addJsonObject {
                        put("type", "function")
                        put("name", t.name)
                        put("description", t.description)
                        put("parameters", t.parameters)
                    }
                }
            }
        }
    }

    override fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
        val http = Request.Builder()
            .url("$baseUrl/responses")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .post(buildBody(request).toString().toRequestBody(Http.JSON))
            .build()
        client.newCall(http).executeCancellable { resp ->
            readSse(resp.body!!.source()) { _, data ->
                if (data == "[DONE]") return@readSse false
                val ev = JuizJson.parseToJsonElement(data).jsonObject
                when (ev["type"]?.jsonPrimitive?.contentOrNull) {
                    "response.output_text.delta" ->
                        ev["delta"]?.jsonPrimitive?.contentOrNull?.let { emit(ChatEvent.TextDelta(it)) }
                    "response.output_item.done" -> {
                        val item = ev["item"]?.jsonObject
                        if (item?.get("type")?.jsonPrimitive?.contentOrNull == "function_call") {
                            emit(
                                ChatEvent.ToolCallDone(
                                    callId = item["call_id"]!!.jsonPrimitive.content,
                                    name = item["name"]!!.jsonPrimitive.content,
                                    arguments = item["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}",
                                ),
                            )
                        }
                    }
                    "response.completed" -> {
                        val usage = ev["response"]?.jsonObject?.get("usage")?.jsonObject
                        emit(ChatEvent.Completed(usage?.get("input_tokens")?.jsonPrimitive?.intOrNull, usage?.get("output_tokens")?.jsonPrimitive?.intOrNull))
                        return@readSse false
                    }
                    "response.failed", "response.incomplete", "error" -> throw ModelException("模型返回错误：${data.take(400)}")
                }
                true
            }
        }
    }.flowOn(Dispatchers.IO)
}
