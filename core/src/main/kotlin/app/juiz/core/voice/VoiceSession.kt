package app.juiz.core.voice

import app.juiz.core.conversation.ConversationEngine
import app.juiz.core.conversation.EngineOutput
import app.juiz.core.policy.EscalationSignal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

enum class VoiceState { GREETING, LISTENING, THINKING, SPEAKING, ENDED }

enum class EndReason(val zh: String) {
    CALLER_GOODBYE("对话结束"),
    TAKEOVER("本人接管"),
    ESCALATED("转交本人"),
    SILENCE_TIMEOUT("对方长时间无应答"),
    MAX_DURATION("达到通话时长上限"),
    ERROR("语音链路故障"),
    REMOTE_HANGUP("对方挂断"),
}

data class VoiceConfig(
    val vad: VadConfig = VadConfig(),
    val preRollMs: Int = 300,
    /** 说完后这么久还没有任何声音播出，先播一句预合成的应答。 */
    val fillerAfterMs: Long = 1200,
    val stillThereAfterMs: Long = 15_000,
    val goodbyeAfterMs: Long = 30_000,
    val maxDurationMs: Long = 10 * 60_000,
    val sttCommitTimeoutMs: Long = 4000,
)

interface VoiceSessionListener {
    fun onState(state: VoiceState) {}
    fun onCallerPartial(text: String) {}
    fun onCallerFinal(text: String) {}
    fun onAssistant(text: String) {}
    fun onOutput(output: EngineOutput) {}
    /** 返回 true 表示把来电交给主人（L1 下会让手机重新响铃），false 只发通知。 */
    fun onEscalation(signal: EscalationSignal): Boolean = false
    fun onLatency(firstAudioMs: Long) {}
}

/**
 * 一通 AI 代接电话的实时循环：
 *   下行音频 → VAD → 转写 → 对话引擎 → 逐句合成 → 上行音频
 * 对方开口（高门限）立即打断；说完后迟迟没有声音先播垫话；长时间无应答礼貌结束；
 * 任何链路故障都结束会话并交还主人，而不是让对方对着一条无声线路。
 */
class VoiceSession(
    private val audio: CallAudioPort,
    private val stt: StreamingStt,
    private val tts: TextToSpeech,
    private val engine: ConversationEngine,
    private val phrases: PhraseCache?,
    private val listener: VoiceSessionListener,
    private val config: VoiceConfig = VoiceConfig(),
) {
    private val _state = MutableStateFlow(VoiceState.GREETING)
    val state: StateFlow<VoiceState> = _state
    private val end = CompletableDeferred<EndReason>()
    private val lastActivity = AtomicLong(System.currentTimeMillis())
    private val startedAt = System.currentTimeMillis()
    private val turnLock = Mutex()
    private var turnJob: Job? = null
    @Volatile private var pendingHandover = false
    @Volatile private var endAfterTurn = false

    fun takeover() {
        audio.flushPlayback()
        end.complete(EndReason.TAKEOVER)
    }

    fun remoteHangup() {
        end.complete(EndReason.REMOTE_HANGUP)
    }

    private fun setState(s: VoiceState) {
        _state.value = s
        listener.onState(s)
    }

    suspend fun run(greeting: String): EndReason = coroutineScope {
        val session = try {
            stt.connect()
        } catch (e: Exception) {
            speakPhrase(Phrases.NETWORK)
            return@coroutineScope EndReason.ERROR
        }
        val jobs = mutableListOf<Job>()
        try {
            engine.opening(greeting).forEach { handleOutput(it) }
            turnJob = launch {
                setState(VoiceState.GREETING)
                speakCachedOrLive(greeting)
                setState(VoiceState.LISTENING)
                lastActivity.set(System.currentTimeMillis())
            }
            jobs += launch { session.partials.collect { listener.onCallerPartial(it) } }
            jobs += launch { captureLoop(session) }
            jobs += launch { watchdog() }
            val reason = end.await()
            jobs.forEach { it.cancel() }
            turnJob?.cancelAndJoin()
            audio.flushPlayback()
            setState(VoiceState.ENDED)
            reason
        } finally {
            jobs.forEach { it.cancel() }
            session.close()
        }
    }

    private suspend fun CoroutineScope.captureLoop(session: SttSession) {
        // 两个 VAD：vad 负责断句和送转写（始终用正常门限，AI 说话时对方的话也不丢）；
        // barge 只负责判断"要不要打断 Juiz"（高门限、更长时间，避免残余回声误触发）。
        val vad = EnergyVad(audio.captureRate, config.vad)
        val barge = EnergyVad(audio.captureRate, config.vad)
        val toStt = StreamingResampler(audio.captureRate, session.sampleRate)
        val preRoll = PreRoll(audio.captureRate * config.preRollMs / 1000)
        val joiner = UtteranceJoiner(this, { vad.inSpeech }) { text, endedAt -> startTurn(text, endedAt) }
        val agc = Agc()
        try {
            audio.captured().collect { raw ->
                val frame = agc.process(raw)
                val speaking = _state.value == VoiceState.SPEAKING || _state.value == VoiceState.GREETING
                if (speaking) {
                    if (barge.process(frame, strict = true) == VadEvent.SPEECH_START) bargeIn()
                } else {
                    barge.reset()
                }
                val wasInSpeech = vad.inSpeech
                val ev = vad.process(frame)
                if (!wasInSpeech && ev != VadEvent.SPEECH_START) preRoll.add(frame)
                when (ev) {
                    VadEvent.SPEECH_START -> {
                        lastActivity.set(System.currentTimeMillis())
                        joiner.callerResumed()
                        // 对方还在说，而我们在"想"：别回答半句话
                        if (_state.value == VoiceState.THINKING) bargeIn()
                        preRoll.drain().forEach { session.append(toStt.process(it)) }
                        session.append(toStt.process(frame))
                    }
                    VadEvent.SPEECH_END -> {
                        lastActivity.set(System.currentTimeMillis())
                        if (vad.lastWasMeaningful) {
                            val endedAt = System.currentTimeMillis()
                            val pending = session.commit() // 此刻同步封口
                            launch {
                                val text = try {
                                    kotlinx.coroutines.withTimeout(config.sttCommitTimeoutMs) { pending.await() }.trim()
                                } catch (e: Exception) {
                                    if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                                    ""
                                }
                                if (text.isNotEmpty()) joiner.add(text, endedAt) else joiner.resume()
                            }
                        } else {
                            session.clear()
                            joiner.resume()
                        }
                        toStt.reset()
                    }
                    null -> if (vad.inSpeech) session.append(toStt.process(frame))
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            speakPhrase(Phrases.NETWORK)
            end.complete(EndReason.ERROR)
        }
    }

    private suspend fun bargeIn() {
        val j = turnJob
        if (j != null && j.isActive) {
            j.cancel()
            audio.flushPlayback()
            setState(VoiceState.LISTENING)
        }
    }

    private fun CoroutineScope.startTurn(callerText: String, speechEndedAt: Long) {
        listener.onCallerFinal(callerText)
        val prev = turnJob
        turnJob = launch {
            prev?.cancelAndJoin()
            turnLock.withLock {
                setState(VoiceState.THINKING)
                val sentences = Channel<String>(Channel.UNLIMITED)
                var firstAudio = false
                val speaker = launch {
                    for (s in sentences) {
                        setState(VoiceState.SPEAKING)
                        tts.synthesize(s).collect { chunk ->
                            if (!firstAudio) {
                                firstAudio = true
                                listener.onLatency(System.currentTimeMillis() - speechEndedAt)
                            }
                            playResampled(chunk, tts.sampleRate)
                        }
                    }
                }
                val filler = launch {
                    delay(config.fillerAfterMs)
                    if (!firstAudio && phrases != null) {
                        phrases.get(Phrases.FILLER)?.let {
                            firstAudio = true
                            setState(VoiceState.SPEAKING)
                            playResampled(it, phrases.sampleRate)
                        }
                    }
                }
                engine.respond(callerText) { out ->
                    when (out) {
                        is EngineOutput.Speech -> {
                            listener.onAssistant(out.text)
                            sentences.send(out.text)
                        }
                        else -> handleOutput(out)
                    }
                }
                sentences.close()
                speaker.join()
                filler.cancel()
                lastActivity.set(System.currentTimeMillis())
                when {
                    pendingHandover -> {
                        speakPhrase(Phrases.HANDOVER)
                        end.complete(EndReason.ESCALATED)
                    }
                    endAfterTurn -> end.complete(EndReason.CALLER_GOODBYE)
                    else -> setState(VoiceState.LISTENING)
                }
            }
        }
    }

    private fun handleOutput(out: EngineOutput) {
        listener.onOutput(out)
        when (out) {
            is EngineOutput.Escalation -> if (listener.onEscalation(out.signal)) pendingHandover = true
            is EngineOutput.EndRequested -> endAfterTurn = true
            is EngineOutput.SpamMarked -> endAfterTurn = true
            else -> Unit
        }
    }

    private suspend fun watchdog() {
        var prompted = false
        while (currentCoroutineContextActive()) {
            delay(250)
            val now = System.currentTimeMillis()
            if (now - startedAt > config.maxDurationMs) {
                turnJob?.cancelAndJoin()
                speakPhrase(Phrases.MAX_DURATION)
                end.complete(EndReason.MAX_DURATION)
                return
            }
            if (_state.value != VoiceState.LISTENING) {
                prompted = false
                continue
            }
            val idle = now - lastActivity.get()
            if (!prompted && idle > config.stillThereAfterMs) {
                prompted = true
                speakPhrase(Phrases.STILL_THERE)
                setState(VoiceState.LISTENING)
            } else if (prompted && idle > config.stillThereAfterMs + config.goodbyeAfterMs) {
                speakPhrase(Phrases.GOODBYE_TIMEOUT)
                end.complete(EndReason.SILENCE_TIMEOUT)
                return
            }
        }
    }

    private suspend fun currentCoroutineContextActive(): Boolean = kotlin.coroutines.coroutineContext.isActive

    private suspend fun speakPhrase(text: String) {
        listener.onAssistant(text)
        runCatching { speakCachedOrLive(text) }
    }

    private suspend fun speakCachedOrLive(text: String) {
        val cached = phrases?.get(text)
        if (cached != null) {
            playResampled(cached, phrases.sampleRate)
        } else {
            tts.synthesize(text).collect { playResampled(it, tts.sampleRate) }
        }
    }

    private suspend fun playResampled(pcm: ShortArray, rate: Int) {
        audio.play(if (rate == audio.playbackRate) pcm else resampleOnce(pcm, rate, audio.playbackRate))
    }
}
