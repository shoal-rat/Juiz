package app.juiz.core

import app.juiz.core.conversation.ScriptedChatModel
import app.juiz.core.conversation.ScriptedReply
import app.juiz.core.model.CallerInfo
import app.juiz.core.model.Channel
import app.juiz.core.voice.CallAudioPort
import app.juiz.core.voice.EndReason
import app.juiz.core.voice.EnergyVad
import app.juiz.core.voice.StreamingResampler
import app.juiz.core.voice.StreamingStt
import app.juiz.core.voice.SttSession
import app.juiz.core.voice.TextToSpeech
import app.juiz.core.voice.VadEvent
import app.juiz.core.voice.VoiceConfig
import app.juiz.core.voice.VoiceSession
import app.juiz.core.voice.VoiceSessionListener
import app.juiz.core.voice.VoiceState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel as KChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoiceTest {
    private val rate = 16_000
    private fun tone(ms: Int, amp: Double = 6000.0) = ShortArray(rate * ms / 1000) { (amp * sin(2 * PI * 220 * it / rate)).toInt().toShort() }
    private fun silence(ms: Int) = ShortArray(rate * ms / 1000)
    private fun frames(pcm: ShortArray) = pcm.toList().chunked(rate / 50).map { it.toShortArray() }

    @Test
    fun vadDetectsUtteranceBoundaries() {
        val vad = EnergyVad(rate)
        val events = (frames(silence(500)) + frames(tone(800)) + frames(silence(900))).mapNotNull { vad.process(it) }
        assertEquals(listOf(VadEvent.SPEECH_START, VadEvent.SPEECH_END), events)
        assertTrue(vad.lastWasMeaningful)
        val strict = EnergyVad(rate)
        val short = (frames(silence(300)) + frames(tone(150)) + frames(silence(300))).mapNotNull { strict.process(it, strict = true) }
        assertTrue(short.isEmpty(), "AI 说话时，150ms 的短促声音不算打断")
    }

    @Test
    fun resamplerKeepsDurationAcrossChunks() {
        val r = StreamingResampler(8000, 24000)
        val total = (0 until 10).sumOf { r.process(ShortArray(160) { (it * 10).toShort() }).size }
        assertTrue(total in 4790..4800, "20ms×10 @8k → ~4800 @24k，实际 $total")
    }

    /** 假通话：按脚本推送"对方声音"，记录"送入通话"的音频。 */
    private class FakeCall(private val script: KChannel<ShortArray>) : CallAudioPort {
        override val captureRate = 16_000
        override val playbackRate = 16_000
        val played = AtomicInteger()
        val flushes = AtomicInteger()
        override fun captured(): Flow<ShortArray> = script.receiveAsFlow()
        override suspend fun play(pcm: ShortArray) {
            delay(pcm.size * 1000L / playbackRate / 4) // 以 4 倍速"播放"
            played.addAndGet(pcm.size)
        }
        override fun flushPlayback() { flushes.incrementAndGet() }
    }

    private class FakeStt(private val transcripts: ArrayDeque<String>) : StreamingStt {
        override val id = "fake"
        override suspend fun connect(): SttSession = object : SttSession {
            override val sampleRate = 24_000
            override val partials: SharedFlow<String> = MutableSharedFlow()
            override fun append(pcm: ShortArray) {}
            override suspend fun commit(timeoutMs: Long) = transcripts.removeFirstOrNull() ?: ""
            override fun clear() {}
            override fun close() {}
        }
    }

    private class FakeTts : TextToSpeech {
        override val id = "fake"
        override val sampleRate = 16_000
        val spoken = CopyOnWriteArrayList<String>()
        override fun synthesize(text: String): Flow<ShortArray> = flow {
            spoken += text
            repeat(text.length) { emit(ShortArray(1600)) } // 每字 100ms
        }
    }

    @Test
    fun fullTurnThenBargeInThenGoodbye(): Unit = runBlocking {
        val (core, _) = testCore()
        val script = KChannel<ShortArray>(KChannel.UNLIMITED)
        val call = FakeCall(script)
        val tts = FakeTts()
        val model = ScriptedChatModel.sequence(
            ScriptedReply("好的，我帮您记下留言。请问还有别的事吗？这是一句故意很长的话，用来测试对方中途打断时，助理会立即停下来。"),
            ScriptedReply("好的，再见。", listOf("end_call" to """{"reason":"对方道别"}""")),
        )
        val engine = core.conversations.start(CallerInfo("17000000000"), Channel.VOICE, "test", model)
        val states = CopyOnWriteArrayList<VoiceState>()
        val latency = CompletableDeferred<Long>()
        val session = VoiceSession(
            call, FakeStt(ArrayDeque(listOf("请转告他明天开会", "没有了，再见"))), tts, engine, null,
            object : VoiceSessionListener {
                override fun onState(state: VoiceState) { states += state }
                override fun onLatency(firstAudioMs: Long) { latency.complete(firstAudioMs) }
            },
            VoiceConfig(stillThereAfterMs = 60_000),
        )
        val result = async { session.run("您好，我是 AI 助理。") }

        suspend fun say(ms: Int) { frames(tone(ms)).forEach { script.send(it) } }
        suspend fun quiet(ms: Int) { frames(silence(ms)).forEach { script.send(it); delay(5) } }

        quiet(400)
        withTimeout(5000) { while (tts.spoken.size < 1) delay(20) }
        delay(600)
        say(900); quiet(800)
        withTimeout(5000) { while (tts.spoken.size < 2) delay(20) }
        assertTrue(latency.await() < 2000)
        // 长回复播放中对方开口 → 必须打断
        delay(300)
        val flushesBefore = call.flushes.get()
        say(600)
        withTimeout(3000) { while (call.flushes.get() == flushesBefore) delay(20) }
        quiet(800)
        val reason = withTimeout(8000) { result.await() }
        assertEquals(EndReason.CALLER_GOODBYE, reason)
        assertTrue(VoiceState.SPEAKING in states && VoiceState.THINKING in states)
        assertTrue(tts.spoken.any { "再见" in it })
    }

    @Test
    fun takeoverEndsImmediately(): Unit = runBlocking {
        val (core, _) = testCore()
        val script = KChannel<ShortArray>(KChannel.UNLIMITED)
        val call = FakeCall(script)
        val engine = core.conversations.start(CallerInfo("17000000000"), Channel.VOICE, "test", ScriptedChatModel.sequence())
        val session = VoiceSession(call, FakeStt(ArrayDeque()), FakeTts(), engine, null, object : VoiceSessionListener {})
        val result = async { session.run("您好，我是 AI 助理，经本人授权代接电话，请问有什么可以帮您？") }
        delay(200)
        session.takeover()
        assertEquals(EndReason.TAKEOVER, withTimeout(2000) { result.await() })
        assertTrue(call.flushes.get() >= 1)
    }
}
