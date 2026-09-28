package app.juiz.core.voice

import app.juiz.core.detox.DetoxLine
import app.juiz.core.detox.DetoxRewriter
import app.juiz.core.detox.LexiconFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.Collections

/** 主人自己的听筒和麦克风（L1 下通话音频不直连本机，由 Juiz 转接）。 */
interface OwnerAudioPort {
    val micRate: Int
    val earRate: Int
    fun mic(): Flow<ShortArray>
    suspend fun playToEar(pcm: ShortArray)
    fun flushEar()
}

enum class ShieldMode(val zh: String) {
    /** 原声压低音量、削掉吼叫的峰值，同时看去情绪字幕。 */
    QUIET_WITH_CAPTIONS("原声调低 + 字幕"),
    /** 原声静音，只听平静的合成复述（有几秒延迟）。 */
    CALM_VOICE("只听平静复述"),
    /** 恢复正常原声（字幕照常）。 */
    PASSTHROUGH("正常原声"),
}

data class Caption(val id: Int, val line: DetoxLine, val final: Boolean)

interface ShieldListener {
    fun onCaption(caption: Caption) {}
    fun onOwnerLine(text: String) {}
    fun onIntensity(level: Int) {}
}

data class ShieldConfig(
    val quietGain: Double = 0.35,
    /** 吼叫压缩：短时 RMS 超过这个值就按比例压下去。 */
    val limiterTargetRms: Double = 1800.0,
    val transcribeOwner: Boolean = true,
    val vad: VadConfig = VadConfig(endSilenceMs = 700),
)

/**
 * 情绪滤网：本人亲自接听（对方确实在和本人说话，不涉及任何冒充），
 * Juiz 只改变"本人听到什么"：
 *   对方声音 → 压低/削峰/静音 → 本人听筒
 *            → 转写 → 词表过滤（立即字幕）→ 模型改写（替换字幕，可选平静复述）
 *   本人麦克风 → 原样送入通话
 * 原始转写只留在会话内存里，只有本人主动点"查看原话"才显示。
 */
class ShieldSession(
    private val call: CallAudioPort,
    private val owner: OwnerAudioPort,
    private val stt: StreamingStt,
    private val rewriter: DetoxRewriter?,
    private val calmTts: TextToSpeech?,
    private val listener: ShieldListener,
    private val config: ShieldConfig = ShieldConfig(),
) {
    @Volatile var mode: ShieldMode = ShieldMode.QUIET_WITH_CAPTIONS
        set(value) {
            field = value
            if (value != ShieldMode.CALM_VOICE) owner.flushEar()
        }

    private val transcript = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
    private val rawById = Collections.synchronizedMap(mutableMapOf<Int, String>())
    private var nextId = 0

    /** 本人主动要求查看某句原话。 */
    fun revealRaw(captionId: Int): String? = rawById[captionId]

    fun transcriptSnapshot(): List<Pair<String, String>> = synchronized(transcript) { transcript.toList() }

    suspend fun run(): Unit = coroutineScope {
        val callerStt = stt.connect()
        val ownerStt = if (config.transcribeOwner) runCatching { stt.connect() }.getOrNull() else null
        val ear = Channel<ShortArray>(capacity = 64)
        try {
            launch { for (pcm in ear) owner.playToEar(pcm) }
            launch { uplink(ownerStt) }
            launch { downlink(callerStt, ear) }
            // 两个方向都持续运行，直到通话结束时外部取消本协程
            kotlinx.coroutines.awaitCancellation()
        } finally {
            callerStt.close()
            ownerStt?.close()
            ear.close()
        }
    }

    private suspend fun CoroutineScope.uplink(ownerStt: SttSession?) {
        val toCall = StreamingResampler(owner.micRate, call.playbackRate)
        val toStt = ownerStt?.let { StreamingResampler(owner.micRate, it.sampleRate) }
        val vad = EnergyVad(owner.micRate, config.vad)
        owner.mic().collect { frame ->
            call.play(toCall.process(frame))
            if (ownerStt != null && toStt != null) {
                when (vad.process(frame)) {
                    VadEvent.SPEECH_START, null -> if (vad.inSpeech) ownerStt.append(toStt.process(frame))
                    VadEvent.SPEECH_END -> if (vad.lastWasMeaningful) launch {
                        val text = runCatching { ownerStt.commit() }.getOrDefault("").trim()
                        if (text.isNotEmpty()) {
                            transcript += "owner" to text
                            listener.onOwnerLine(text)
                        }
                    } else ownerStt.clear()
                }
            }
        }
    }

    private suspend fun CoroutineScope.downlink(session: SttSession, ear: Channel<ShortArray>) {
        val toEar = StreamingResampler(call.captureRate, owner.earRate)
        val toStt = StreamingResampler(call.captureRate, session.sampleRate)
        val vad = EnergyVad(call.captureRate, config.vad)
        val preRoll = PreRoll(call.captureRate * 300 / 1000)
        call.captured().collect { frame ->
            when (mode) {
                ShieldMode.PASSTHROUGH -> ear.trySend(toEar.process(frame))
                ShieldMode.QUIET_WITH_CAPTIONS -> ear.trySend(toEar.process(limit(frame, config.quietGain)))
                ShieldMode.CALM_VOICE -> Unit
            }
            val was = vad.inSpeech
            when (vad.process(frame)) {
                VadEvent.SPEECH_START -> {
                    preRoll.drain().forEach { session.append(toStt.process(it)) }
                    session.append(toStt.process(frame))
                }
                VadEvent.SPEECH_END -> {
                    if (vad.lastWasMeaningful) launch { finishUtterance(session, ear) } else session.clear()
                    toStt.reset()
                }
                null -> if (vad.inSpeech) session.append(toStt.process(frame)) else if (!was) preRoll.add(frame)
            }
        }
    }

    private suspend fun finishUtterance(session: SttSession, ear: Channel<ShortArray>) {
        val raw = try {
            session.commit().trim()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ""
        }
        if (raw.isEmpty()) return
        val id = synchronized(this) { nextId++ }
        rawById[id] = raw
        transcript += "caller" to raw
        val quick = LexiconFilter.filter(raw)
        listener.onCaption(Caption(id, quick, final = rewriter == null))
        listener.onIntensity(quick.intensity)
        val line = rewriter?.rewrite(raw)?.also {
            listener.onCaption(Caption(id, it, final = true))
            listener.onIntensity(it.intensity)
        } ?: quick
        val tts = calmTts
        if (mode == ShieldMode.CALM_VOICE && tts != null && line.calm.isNotBlank()) {
            val toEar = StreamingResampler(tts.sampleRate, owner.earRate)
            runCatching { tts.synthesize(line.calm).collect { ear.send(toEar.process(it)) } }
        }
    }

    /** 增益 + 简单限幅：吼叫时按比例压到目标响度，平常说话只做固定衰减。 */
    private fun limit(frame: ShortArray, gain: Double): ShortArray {
        val r = Pcm.rms(frame) * gain
        val extra = if (r > config.limiterTargetRms) config.limiterTargetRms / r else 1.0
        val g = gain * extra
        return ShortArray(frame.size) { i -> (frame[i] * g).toInt().coerceIn(-32768, 32767).toShort() }
    }
}
