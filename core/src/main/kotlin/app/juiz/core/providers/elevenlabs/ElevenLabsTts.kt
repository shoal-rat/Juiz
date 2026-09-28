package app.juiz.core.providers.elevenlabs

import app.juiz.core.providers.Http
import app.juiz.core.providers.executeCancellable
import app.juiz.core.providers.openai.emitPcm
import app.juiz.core.voice.TextToSpeech
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * ElevenLabs 流式合成：POST /v1/text-to-speech/{voice_id}/stream?output_format=pcm_16000。
 * 用于本人授权的克隆音色；音色的克隆与验证由主人在 ElevenLabs 完成，这里只使用 voice_id。
 * 每句一次请求，打断时直接取消该请求。
 */
class ElevenLabsTts(
    private val apiKey: String,
    private val voiceId: String,
    private val model: String = "eleven_flash_v2_5",
    override val sampleRate: Int = 16_000,
    private val languageCode: String? = null,
    private val zeroRetention: Boolean = false,
    private val baseUrl: String = "https://api.elevenlabs.io/v1",
    private val client: OkHttpClient = Http.client,
) : TextToSpeech {
    override val id: String get() = "elevenlabs:$model:$voiceId"

    override fun synthesize(text: String): Flow<ShortArray> = flow {
        val body = buildJsonObject {
            put("text", text)
            put("model_id", model)
            languageCode?.let { put("language_code", it) }
        }
        val url = buildString {
            append("$baseUrl/text-to-speech/$voiceId/stream?output_format=pcm_$sampleRate")
            if (zeroRetention) append("&enable_logging=false")
        }
        val req = Request.Builder()
            .url(url)
            .header("xi-api-key", apiKey)
            .post(body.toString().toRequestBody(Http.JSON))
            .build()
        client.newCall(req).executeCancellable { resp -> emitPcm(resp, chunkBytes = 3200) }
    }.flowOn(Dispatchers.IO)
}
