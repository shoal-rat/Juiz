package app.juiz.core.voice

import app.juiz.core.detox.DetoxLine
import app.juiz.core.detox.DetoxRewriter
import app.juiz.core.detox.LexiconFilter
import app.juiz.core.detox.ReplyOption
import app.juiz.core.detox.ReplySuggester
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    /** 针对某句字幕准备好的回复选项（选句代答）。 */
    fun onSuggestions(captionId: Int, options: List<ReplyOption>) {}
    /** 选定的回复已经说给对方。 */
    fun onSpoken(text: String) {}
}

data class ShieldConfig(
    val quietGain: Double = 0.35,
    /** 吼叫压缩：短时 RMS 超过这个值就按比例压下去。 */
    val limiterTargetRms: Double = 1800.0,
    val transcribeOwner: Boolean = true,
    val vad: VadConfig = VadConfig(endSilenceMs = 700),
    /**
     * 第一次用合成声音替本人说话前的一句说明。说的内容都是本人选定的，但声音是合成的：
     * 语音供应商条款与《人工智能生成合成内容标识办法》都要求标识合成语音。
     */
    val voiceAssistNotice: String = "我这边现在不方便说话，用语音助手回复您。",
)

/**
 * 情绪滤网：本人亲自接听（对方确实在和本人说话，不涉及任何冒充），
 * Juiz 只改变"本人听到什么"：
 *   对方声音 → 压低/削峰/静音 → 本人听筒
 *            → 转写 → 词表过滤（立即字幕）→ 模型改写（替换字幕，可选平静复述）
 *   本人麦克风 → 原样送入通话
 * 选句代答：本人不开口，从 Juiz 准备的回复里选一句（或自己输入），由合成声音说给对方；
 *   第一次使用时先说一句"用语音助手回复"的说明。每句话都是本人选的，Juiz 不替本人决定。
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
    /** 替本人说话用的声音（授权后可以是本人克隆音色）。 */
    private val replyTts: TextToSpeech? = null,
    private val suggester: ReplySuggester? = null,
) {
    /** false 时本人麦克风不送入通话（选句代答模式）。 */
    @Volatile var ownerMicLive: Boolean = true
    @Volatile private var speakingForOwner = false
    @Volatile private var noticeGiven = false
    private val sayLock = Mutex()

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
            val pcm = toCall.process(frame)
            if (ownerMicLive && !speakingForOwner) call.play(pcm)
            if (ownerMicLive && ownerStt != null && toStt != null) {
                when (vad.process(frame)) {
                    VadEvent.SPEECH_START, null -> if (vad.inSpeech) ownerStt.append(toStt.process(frame))
                    VadEvent.SPEECH_END -> if (vad.lastWasMeaningful) {
                        val pending = ownerStt.commit()
                        launch {
                        val text = runCatching { kotlinx.coroutines.withTimeout(4000) { pending.await() } }.getOrDefault("").trim()
                        if (text.isNotEmpty()) {
                            transcript += "owner" to text
                            listener.onOwnerLine(text)
                        }
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
        // 片段拼成整句再去情绪：半句话既不好转述，也容易被误判
        val joiner = UtteranceJoiner(this, { vad.inSpeech }) { text, _ -> processCaption(text, ear) }
        val agc = Agc()
        call.captured().collect { raw ->
            when (mode) {
                ShieldMode.PASSTHROUGH -> ear.trySend(toEar.process(raw))
                ShieldMode.QUIET_WITH_CAPTIONS -> ear.trySend(toEar.process(limit(raw, config.quietGain)))
                ShieldMode.CALM_VOICE -> Unit
            }
            val frame = agc.process(raw)
            val was = vad.inSpeech
            when (vad.process(frame)) {
                VadEvent.SPEECH_START -> {
                    joiner.callerResumed()
                    preRoll.drain().forEach { session.append(toStt.process(it)) }
                    session.append(toStt.process(frame))
                }
                VadEvent.SPEECH_END -> {
                    val endedAt = System.currentTimeMillis()
                    if (vad.lastWasMeaningful) {
                        val pending = session.commit() // 此刻同步封口
                        launch {
                            val raw = try {
                                kotlinx.coroutines.withTimeout(6000) { pending.await() }.trim()
                            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                                ""
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                ""
                            }
                            if (raw.isNotEmpty()) joiner.add(raw, endedAt) else joiner.resume()
                        }
                    } else {
                        session.clear()
                        joiner.resume()
                    }
                    toStt.reset()
                }
                null -> if (vad.inSpeech) session.append(toStt.process(frame)) else if (!was) preRoll.add(frame)
            }
        }
    }

    private suspend fun processCaption(raw: String, ear: Channel<ShortArray>) {
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
        if (suggester != null) {
            val options = suggester.suggest(line, transcriptSnapshot())
            listener.onSuggestions(id, options)
        }
        val tts = calmTts
        if (mode == ShieldMode.CALM_VOICE && tts != null && line.calm.isNotBlank()) {
            val toEar = StreamingResampler(tts.sampleRate, owner.earRate)
            runCatching { tts.synthesize(line.calm).collect { ear.send(toEar.process(it)) } }
        }
    }

    /**
     * 用合成声音把本人选定的一句话说给对方。第一次调用时先说明"用语音助手回复"。
     * 说话期间本人麦克风暂不送入通话，避免两路声音叠在一起。
     */
    suspend fun say(text: String) {
        val tts = replyTts ?: calmTts ?: throw IllegalStateException("没有可用的语音合成")
        val line = text.trim()
        if (line.isEmpty()) return
        sayLock.withLock {
            speakingForOwner = true
            try {
                val toCall = StreamingResampler(tts.sampleRate, call.playbackRate)
                if (!noticeGiven) {
                    noticeGiven = true
                    tts.synthesize(config.voiceAssistNotice).collect { call.play(toCall.process(it)) }
                }
                tts.synthesize(line).collect { call.play(toCall.process(it)) }
                transcript += "owner" to line
                listener.onSpoken(line)
            } finally {
                speakingForOwner = false
            }
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
