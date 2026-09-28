package app.juiz.core.voice

import app.juiz.core.util.sha256Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import java.io.File

/** 预合成的固定用语。开场白零等待，也省去重复合成的费用。 */
object Phrases {
    const val FILLER = "嗯，好的，我看一下。"
    const val HANDOVER = "好的，我这就请本人来接听，请您稍候。"
    const val STILL_THERE = "您好，请问还在听吗？"
    const val GOODBYE_TIMEOUT = "那我先挂断了，您的来电我会转告本人，再见。"
    const val MAX_DURATION = "为了不耽误您的时间，我先把您的需求转告本人，稍后由本人联系您。再见。"
    const val NETWORK = "抱歉，我这边线路不太稳定，我请本人来接听，请稍候。"

    val all = listOf(FILLER, HANDOVER, STILL_THERE, GOODBYE_TIMEOUT, MAX_DURATION, NETWORK)
}

/**
 * 按"音色 ID + 文本"缓存 PCM。音色授权撤销时调用 [clear] 删除全部缓存。
 */
class PhraseCache(private val dir: File, private val tts: TextToSpeech) {
    val sampleRate: Int get() = tts.sampleRate

    private fun file(text: String) = File(dir, sha256Hex(tts.id + "|" + text) + ".pcm")

    suspend fun get(text: String): ShortArray? = withContext(Dispatchers.IO) {
        val f = file(text)
        if (f.exists()) Pcm.bytesToShorts(f.readBytes()) else null
    }

    suspend fun getOrSynthesize(text: String): ShortArray {
        get(text)?.let { return it }
        val chunks = tts.synthesize(text).toList()
        val all = ShortArray(chunks.sumOf { it.size })
        var o = 0
        chunks.forEach { c -> c.copyInto(all, o); o += c.size }
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            val tmp = File(dir, file(text).name + ".tmp")
            tmp.writeBytes(Pcm.shortsToBytes(all))
            tmp.renameTo(file(text))
        }
        return all
    }

    suspend fun warm(texts: List<String>) {
        texts.forEach { runCatching { getOrSynthesize(it) } }
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }
}
