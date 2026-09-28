package app.juiz.core.voice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow

/** 流式合成：逐块输出 16 位单声道 PCM。协程取消时必须立即停止网络请求。 */
interface TextToSpeech {
    val id: String
    val sampleRate: Int
    fun synthesize(text: String): Flow<ShortArray>
}

/** 实时转写会话：持续追加音频，由客户端 VAD 决定何时 commit 一段话。 */
interface SttSession {
    val sampleRate: Int
    val partials: SharedFlow<String>
    fun append(pcm: ShortArray)
    /** 提交当前缓冲并等待这段话的最终转写。 */
    suspend fun commit(timeoutMs: Long = 4000): String
    /** 丢弃当前缓冲（咳嗽、噪声等太短的片段）。 */
    fun clear()
    fun close()
}

interface StreamingStt {
    val id: String
    suspend fun connect(): SttSession
}

/** 平台提供的通话音频端口：L1 下由特权音频桥实现，仿真台由麦克风/扬声器或测试桩实现。 */
interface CallAudioPort {
    /** 对方声音（下行）的采样率。 */
    val captureRate: Int
    /** 送入通话（上行）的采样率。 */
    val playbackRate: Int
    fun captured(): Flow<ShortArray>
    /** 写入上行，可能挂起直到缓冲区可写。 */
    suspend fun play(pcm: ShortArray)
    /** 立即清空尚未播出的音频（打断、接管）。 */
    fun flushPlayback()
}
