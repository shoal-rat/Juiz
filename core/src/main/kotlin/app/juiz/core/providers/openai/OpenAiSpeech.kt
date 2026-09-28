package app.juiz.core.providers.openai

import app.juiz.core.providers.Http
import app.juiz.core.providers.executeCancellable
import app.juiz.core.util.JuizJson
import app.juiz.core.voice.Pcm
import app.juiz.core.voice.StreamingStt
import app.juiz.core.voice.SttSession
import app.juiz.core.voice.TextToSpeech
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * OpenAI 语音合成（POST /v1/audio/speech，response_format=pcm：24kHz 16 位小端单声道，分块传输）。
 * 用作"普通合成音色"：克隆音色撤销或不可用时的备用。
 */
class OpenAiTts(
    private val apiKey: String,
    private val model: String = "gpt-4o-mini-tts",
    private val voice: String = "marin",
    private val instructions: String? = "用自然、平和、礼貌的普通话说，语速适中，像专业的礼宾助理。",
    private val baseUrl: String = "https://api.openai.com/v1",
    private val client: OkHttpClient = Http.client,
) : TextToSpeech {
    override val id: String get() = "openai:$model:$voice"
    override val sampleRate: Int = 24_000

    override fun synthesize(text: String): Flow<ShortArray> = flow {
        val body = buildJsonObject {
            put("model", model)
            put("voice", voice)
            put("input", text)
            put("response_format", "pcm")
            instructions?.let { put("instructions", it) }
        }
        val req = Request.Builder()
            .url("$baseUrl/audio/speech")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody(Http.JSON))
            .build()
        client.newCall(req).executeCancellable { resp -> emitPcm(resp) }
    }.flowOn(Dispatchers.IO)
}

/** 按 100ms 左右的块读取 PCM 流，处理奇数字节边界。 */
internal suspend fun kotlinx.coroutines.flow.FlowCollector<ShortArray>.emitPcm(resp: Response, chunkBytes: Int = 4800) {
    val source = resp.body!!.source()
    val buf = ByteArray(chunkBytes)
    var carry: Byte? = null
    while (true) {
        val n = source.read(buf, 0, buf.size)
        if (n <= 0) break
        val merged = if (carry != null) byteArrayOf(carry) + buf.copyOf(n) else buf.copyOf(n)
        val even = merged.size - (merged.size % 2)
        carry = if (even < merged.size) merged[merged.size - 1] else null
        if (even > 0) emit(Pcm.bytesToShorts(merged, even))
    }
}

/**
 * OpenAI 实时转写：wss://api.openai.com/v1/realtime?intent=transcription，
 * session.type = "transcription"，模型 gpt-live-transcribe，输入 24kHz PCM。
 * 该模型不支持服务端 VAD（turn_detection 必须为 null），由客户端 VAD 决定何时 commit。
 */
class OpenAiRealtimeStt(
    private val apiKey: String,
    private val model: String = "gpt-live-transcribe",
    private val languages: List<String> = listOf("zh", "en"),
    private val keywords: List<String> = emptyList(),
    private val prompt: String? = null,
    private val delay: String = "low",
    private val url: String = "wss://api.openai.com/v1/realtime?intent=transcription",
    private val client: OkHttpClient = Http.client,
) : StreamingStt {
    override val id: String get() = "openai:$model"

    fun sessionUpdate() = buildJsonObject {
        put("type", "session.update")
        putJsonObject("session") {
            put("type", "transcription")
            putJsonObject("audio") {
                putJsonObject("input") {
                    putJsonObject("format") {
                        put("type", "audio/pcm")
                        put("rate", 24_000)
                    }
                    putJsonObject("transcription") {
                        put("model", model)
                        if (languages.isNotEmpty()) putJsonArray("languages") { languages.forEach { add(it) } }
                        if (keywords.isNotEmpty()) putJsonArray("keywords") { keywords.forEach { add(it) } }
                        prompt?.let { put("prompt", it) }
                        put("delay", delay)
                    }
                    put("turn_detection", JsonNull)
                }
            }
        }
    }

    override suspend fun connect(): SttSession {
        val session = Session()
        val req = Request.Builder().url(url).header("Authorization", "Bearer $apiKey").build()
        val ws = client.newWebSocket(req, session.listener)
        session.ws = ws
        withTimeout(8000) { session.opened.await() }
        ws.send(sessionUpdate().toString())
        return session
    }

    private class Session : SttSession {
        lateinit var ws: WebSocket
        val opened = CompletableDeferred<Unit>()
        private val waiting = ConcurrentLinkedQueue<CompletableDeferred<String>>()
        private val _partials = MutableSharedFlow<String>(extraBufferCapacity = 64)
        override val partials: SharedFlow<String> = _partials
        override val sampleRate: Int = 24_000
        @Volatile private var failure: Throwable? = null

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val ev = runCatching { JuizJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                when (ev["type"]?.jsonPrimitive?.contentOrNull) {
                    "conversation.item.input_audio_transcription.delta" ->
                        ev["delta"]?.jsonPrimitive?.contentOrNull?.let { _partials.tryEmit(it) }
                    "conversation.item.input_audio_transcription.completed" ->
                        waiting.poll()?.complete(ev["transcript"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    "error" -> {
                        val msg = ev["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull ?: text
                        // 空缓冲提交之类的错误只影响当前这一段
                        waiting.poll()?.complete("")
                        if ("session" in msg && "invalid" in msg) failure = IllegalStateException(msg)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                failure = t
                opened.completeExceptionally(t)
                while (true) waiting.poll()?.completeExceptionally(t) ?: break
            }
        }

        override fun append(pcm: ShortArray) {
            failure?.let { throw it }
            val b64 = Base64.getEncoder().encodeToString(Pcm.shortsToBytes(pcm))
            ws.send("""{"type":"input_audio_buffer.append","audio":"$b64"}""")
        }

        override suspend fun commit(timeoutMs: Long): String {
            failure?.let { throw it }
            val d = CompletableDeferred<String>()
            waiting.add(d)
            ws.send("""{"type":"input_audio_buffer.commit"}""")
            return withTimeout(timeoutMs) { d.await() }
        }

        override fun clear() {
            ws.send("""{"type":"input_audio_buffer.clear"}""")
        }

        override fun close() {
            ws.close(1000, "done")
        }
    }
}
