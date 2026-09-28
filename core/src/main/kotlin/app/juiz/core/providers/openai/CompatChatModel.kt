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
import kotlinx.serialization.json.JsonArray
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
 * 兼容 OpenAI Chat Completions 的端点：国内兼容服务、本机 ollama（http://127.0.0.1:11434/v1）、
 * llama.cpp 的 llama-server 等。为"完全离线"留的路，也方便在没有 OpenAI 账号时做仿真。
 */
class CompatChatModel(
    private val baseUrl: String,
    private val model: String,
    private val apiKey: String? = null,
    private val client: OkHttpClient = Http.client,
    private val extraBody: JsonObject = JsonObject(emptyMap()),
) : ChatModel {
    override val id: String get() = "compat:$model"

    fun buildBody(req: ChatRequest): JsonObject = buildJsonObject {
        put("model", model)
        put("stream", true)
        put("max_tokens", req.maxOutputTokens)
        putJsonArray("messages") {
            addJsonObject { put("role", "system"); put("content", req.system) }
            // Chat Completions 要求同一轮的文字与工具调用放在同一条 assistant 消息里
            var i = 0
            val h = req.history
            while (i < h.size) {
                when (val item = h[i]) {
                    is ChatItem.User -> { addJsonObject { put("role", "user"); put("content", item.text) }; i++ }
                    is ChatItem.ToolResult -> {
                        addJsonObject { put("role", "tool"); put("tool_call_id", item.callId); put("content", item.output) }
                        i++
                    }
                    is ChatItem.Assistant, is ChatItem.ToolCall -> {
                        var text: String? = null
                        val calls = mutableListOf<ChatItem.ToolCall>()
                        while (i < h.size && (h[i] is ChatItem.Assistant || h[i] is ChatItem.ToolCall)) {
                            val x = h[i]
                            if (x is ChatItem.Assistant) {
                                if (calls.isNotEmpty()) break
                                text = listOfNotNull(text, x.text).joinToString("")
                            } else if (x is ChatItem.ToolCall) calls += x
                            i++
                        }
                        addJsonObject {
                            put("role", "assistant")
                            put("content", text ?: "")
                            if (calls.isNotEmpty()) putJsonArray("tool_calls") {
                                calls.forEach { c ->
                                    addJsonObject {
                                        put("id", c.callId)
                                        put("type", "function")
                                        putJsonObject("function") { put("name", c.name); put("arguments", c.arguments) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (req.tools.isNotEmpty()) putJsonArray("tools") {
            req.tools.forEach { t ->
                addJsonObject {
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", t.name)
                        put("description", t.description)
                        put("parameters", t.parameters)
                    }
                }
            }
        }
        extraBody.forEach { (k, v) -> put(k, v) }
    }

    private class PartialCall(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder())

    override fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
        val builder = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .header("Accept", "text/event-stream")
            .post(buildBody(request).toString().toRequestBody(Http.JSON))
        apiKey?.takeIf { it.isNotBlank() }?.let { builder.header("Authorization", "Bearer $it") }
        val calls = sortedMapOf<Int, PartialCall>()
        val think = ThinkFilter()
        var usageIn: Int? = null
        var usageOut: Int? = null
        client.newCall(builder.build()).executeCancellable { resp ->
            readSse(resp.body!!.source()) { _, data ->
                if (data == "[DONE]") return@readSse false
                val ev = JuizJson.parseToJsonElement(data).jsonObject
                ev["error"]?.let { throw ModelException("模型返回错误：${it.toString().take(300)}") }
                ev["usage"]?.let { u ->
                    if (u is JsonObject) {
                        usageIn = u["prompt_tokens"]?.jsonPrimitive?.intOrNull
                        usageOut = u["completion_tokens"]?.jsonPrimitive?.intOrNull
                    }
                }
                val choice = (ev["choices"] as? JsonArray)?.firstOrNull()?.jsonObject ?: return@readSse true
                val delta = choice["delta"]?.jsonObject
                delta?.get("content")?.jsonPrimitive?.contentOrNull?.let { raw ->
                    think.feed(raw).takeIf { it.isNotEmpty() }?.let { emit(ChatEvent.TextDelta(it)) }
                }
                (delta?.get("tool_calls") as? JsonArray)?.forEach { tc ->
                    val o = tc.jsonObject
                    val idx = o["index"]?.jsonPrimitive?.intOrNull ?: 0
                    val pc = calls.getOrPut(idx) { PartialCall() }
                    o["id"]?.jsonPrimitive?.contentOrNull?.let { if (it.isNotEmpty()) pc.id = it }
                    o["function"]?.jsonObject?.let { f ->
                        f["name"]?.jsonPrimitive?.contentOrNull?.let { if (it.isNotEmpty()) pc.name = it }
                        f["arguments"]?.jsonPrimitive?.contentOrNull?.let { pc.args.append(it) }
                    }
                }
                true
            }
        }
        calls.values.forEachIndexed { i, pc ->
            if (pc.name.isNotEmpty()) emit(ChatEvent.ToolCallDone(pc.id.ifEmpty { "call_$i" }, pc.name, pc.args.toString().ifBlank { "{}" }))
        }
        emit(ChatEvent.Completed(usageIn, usageOut))
    }.flowOn(Dispatchers.IO)

    /** 一些本地推理模型会把思考过程放在 <think>…</think> 里，这部分不能读给来电方听。 */
    class ThinkFilter {
        private var inThink = false
        private val pending = StringBuilder()

        fun feed(s: String): String {
            pending.append(s)
            val out = StringBuilder()
            while (true) {
                val text = pending.toString()
                if (inThink) {
                    val end = text.indexOf("</think>")
                    if (end < 0) { keepTail(text, "</think>".length); return out.toString() }
                    pending.setLength(0); pending.append(text.substring(end + "</think>".length))
                    inThink = false
                } else {
                    val start = text.indexOf("<think>")
                    if (start < 0) {
                        // 可能是被切开的 "<thi"，保留尾部等下一段
                        val safe = safePrefix(text)
                        out.append(text.substring(0, safe))
                        pending.setLength(0); pending.append(text.substring(safe))
                        return out.toString()
                    }
                    out.append(text.substring(0, start))
                    pending.setLength(0); pending.append(text.substring(start + "<think>".length))
                    inThink = true
                }
            }
        }

        private fun keepTail(text: String, n: Int) {
            pending.setLength(0)
            pending.append(if (text.length > n) text.substring(text.length - n) else text)
        }

        private fun safePrefix(text: String): Int {
            val tag = "<think>"
            for (k in minOf(tag.length - 1, text.length) downTo 1) {
                if (text.endsWith(tag.substring(0, k))) return text.length - k
            }
            return text.length
        }
    }
}
