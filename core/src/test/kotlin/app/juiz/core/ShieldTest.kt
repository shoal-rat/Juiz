package app.juiz.core

import app.juiz.core.detox.ReplyOption
import app.juiz.core.detox.ReplySuggester
import app.juiz.core.voice.CallAudioPort
import app.juiz.core.voice.Caption
import app.juiz.core.voice.OwnerAudioPort
import app.juiz.core.voice.ShieldListener
import app.juiz.core.voice.ShieldSession
import app.juiz.core.voice.StreamingStt
import app.juiz.core.voice.SttSession
import app.juiz.core.voice.TextToSpeech
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShieldTest {
    private val rate = 16_000
    private fun tone(ms: Int, amp: Double = 7000.0) = ShortArray(rate * ms / 1000) { (amp * sin(2 * PI * 200 * it / rate)).toInt().toShort() }
    private fun frames(pcm: ShortArray) = pcm.toList().chunked(rate / 50).map { it.toShortArray() }

    private class Call(val down: Channel<ShortArray>) : CallAudioPort {
        override val captureRate = 16_000
        override val playbackRate = 16_000
        val uplinkSamples = AtomicInteger()
        override fun captured(): Flow<ShortArray> = down.receiveAsFlow()
        override suspend fun play(pcm: ShortArray) { uplinkSamples.addAndGet(pcm.size) }
        override fun flushPlayback() {}
    }

    private class Owner(val mic: Channel<ShortArray>) : OwnerAudioPort {
        override val micRate = 16_000
        override val earRate = 16_000
        val earSamples = AtomicInteger()
        override fun mic(): Flow<ShortArray> = mic.receiveAsFlow()
        override suspend fun playToEar(pcm: ShortArray) { earSamples.addAndGet(pcm.size) }
        override fun flushEar() {}
    }

    private class Stt(private val lines: ArrayDeque<String>) : StreamingStt {
        override val id = "fake"
        override suspend fun connect() = object : SttSession {
            override val sampleRate = 24_000
            override val partials: SharedFlow<String> = MutableSharedFlow()
            override fun append(pcm: ShortArray) {}
            override fun commit() = kotlinx.coroutines.CompletableDeferred(synchronized(lines) { lines.removeFirstOrNull() } ?: "")
            override fun clear() {}
            override fun close() {}
        }
    }

    private class Tts : TextToSpeech {
        override val id = "fake"
        override val sampleRate = 16_000
        val said = CopyOnWriteArrayList<String>()
        override fun synthesize(text: String): Flow<ShortArray> = flow { said += text; emit(ShortArray(1600 * text.length)) }
    }

    @Test
    fun replyPickerSpeaksChosenLineWithOneTimeNoticeAndMutesMic(): Unit = runBlocking {
        val down = Channel<ShortArray>(Channel.UNLIMITED)
        val mic = Channel<ShortArray>(Channel.UNLIMITED)
        val call = Call(down)
        val owner = Owner(mic)
        val tts = Tts()
        val captions = CopyOnWriteArrayList<Caption>()
        val suggestions = CopyOnWriteArrayList<List<ReplyOption>>()
        val session = ShieldSession(
            call, owner, Stt(ArrayDeque(listOf("你是猪脑子吗！明天上午十点前把报表重做"))), null, tts,
            object : ShieldListener {
                override fun onCaption(caption: Caption) { captions += caption }
                override fun onSuggestions(captionId: Int, options: List<ReplyOption>) { suggestions += options }
            },
            replyTts = tts, suggester = ReplySuggester(null),
        )
        session.ownerMicLive = false
        val job = launch { session.run() }
        frames(ShortArray(8000)).forEach { down.send(it) }
        frames(tone(900)).forEach { down.send(it) }
        frames(ShortArray(16000)).forEach { down.send(it) }
        frames(tone(500)).forEach { mic.send(it) }
        withTimeout(5000) { while (suggestions.isEmpty()) delay(20) }
        assertTrue(captions.last().line.calm.contains("报表") && !captions.last().line.calm.contains("猪脑子"))
        assertEquals(0, call.uplinkSamples.get(), "选句代答时本人麦克风不送入通话")

        session.say(suggestions.first().first().text)
        session.say("收到，我明早十点前发您。")
        assertEquals(1, tts.said.count { it.contains("语音助手") }, "语音助手说明只说一次")
        assertTrue(call.uplinkSamples.get() > 0)
        assertEquals(listOf("owner"), session.transcriptSnapshot().filter { it.second == "收到，我明早十点前发您。" }.map { it.first })
        job.cancel()
    }
}
