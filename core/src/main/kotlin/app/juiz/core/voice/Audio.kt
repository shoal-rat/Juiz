package app.juiz.core.voice

import kotlin.math.max
import kotlin.math.sqrt

object Pcm {
    /** 16 位小端 PCM 字节 → 采样。奇数字节由调用方保留到下一块。 */
    fun bytesToShorts(bytes: ByteArray, length: Int = bytes.size): ShortArray {
        val n = length / 2
        val out = ShortArray(n)
        for (i in 0 until n) {
            out[i] = ((bytes[i * 2].toInt() and 0xff) or (bytes[i * 2 + 1].toInt() shl 8)).toShort()
        }
        return out
    }

    fun shortsToBytes(s: ShortArray): ByteArray {
        val out = ByteArray(s.size * 2)
        for (i in s.indices) {
            val v = s[i].toInt()
            out[i * 2] = (v and 0xff).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
        }
        return out
    }

    fun rms(s: ShortArray): Double {
        if (s.isEmpty()) return 0.0
        var sum = 0.0
        for (v in s) sum += v.toDouble() * v
        return sqrt(sum / s.size)
    }

    fun durationMs(samples: Int, rate: Int): Long = samples * 1000L / rate
}

/**
 * 流式线性插值重采样。电话下行一般是 8k/16k，转写需要 24k；TTS 输出 16k/24k，注入通话按设备要求。
 * 对语音频段足够，且逐块调用时相位连续。
 */
class StreamingResampler(private val from: Int, private val to: Int) {
    private var pos = 0.0
    private var last: Short = 0
    private var hasLast = false

    fun process(input: ShortArray): ShortArray {
        if (from == to) return input
        if (input.isEmpty()) return input
        val step = from.toDouble() / to
        // 把上一块最后一个采样放在索引 -1 处，保证块之间平滑
        val out = ArrayList<Short>(((input.size + 1) / step).toInt() + 2)
        val offset = if (hasLast) 1 else 0
        val total = input.size + offset
        fun at(i: Int): Short = if (hasLast) (if (i == 0) last else input[i - 1]) else input[i]
        while (pos + 1 < total) {
            val i = pos.toInt()
            val frac = pos - i
            val a = at(i)
            val b = at(i + 1)
            out += (a + (b - a) * frac).toInt().toShort()
            pos += step
        }
        pos -= (total - 1)
        last = input.last()
        hasLast = true
        return out.toShortArray()
    }

    fun reset() { pos = 0.0; hasLast = false }
}

fun resampleOnce(input: ShortArray, from: Int, to: Int): ShortArray = StreamingResampler(from, to).process(input)

data class VadConfig(
    /** 判定为说话所需的最低 RMS（16 位满幅 32767）。 */
    val minSpeechRms: Double = 450.0,
    /** 相对噪声底的倍数。 */
    val speechRatio: Double = 3.0,
    /** AI 说话期间用更高倍数，避免残余回声触发打断。 */
    val bargeInRatio: Double = 4.5,
    val startMs: Long = 90,
    val bargeInMs: Long = 250,
    /** 静音持续这么久算说完。中文停顿较多，太短会抢话。 */
    val endSilenceMs: Long = 600,
    val minUtteranceMs: Long = 250,
)

enum class VadEvent { SPEECH_START, SPEECH_END }

/**
 * 能量 VAD：自适应噪声底 + 起止挂起时间。刻意简单、可解释，参数在真机验证时按线路调整。
 * strict=true（AI 正在说话）时需要更高能量和更长时间才判定为对方开口，即"打断"。
 */
class EnergyVad(private val rate: Int, private val cfg: VadConfig = VadConfig()) {
    private var noiseFloor = 150.0
    private var voicedMs = 0L
    private var silentMs = 0L
    var inSpeech = false
        private set
    var speechMs = 0L
        private set

    /** 上一段话去掉结尾静音后的长度；太短的（咳嗽、噪声）由调用方丢弃。 */
    var lastUtteranceMs = 0L
        private set

    val lastWasMeaningful: Boolean get() = lastUtteranceMs >= cfg.minUtteranceMs

    fun process(frame: ShortArray, strict: Boolean = false): VadEvent? {
        val ms = Pcm.durationMs(frame.size, rate)
        val r = Pcm.rms(frame)
        val ratio = if (strict) cfg.bargeInRatio else cfg.speechRatio
        val voiced = r >= max(cfg.minSpeechRms, noiseFloor * ratio)
        if (!voiced && !inSpeech) {
            // 只在非说话段更新噪声底，慢速跟随
            noiseFloor = noiseFloor * 0.95 + r * 0.05
        }
        if (!inSpeech) {
            voicedMs = if (voiced) voicedMs + ms else 0
            val need = if (strict) cfg.bargeInMs else cfg.startMs
            if (voicedMs >= need) {
                inSpeech = true
                speechMs = voicedMs
                silentMs = 0
                return VadEvent.SPEECH_START
            }
        } else {
            speechMs += ms
            silentMs = if (voiced) 0 else silentMs + ms
            if (silentMs >= cfg.endSilenceMs) {
                inSpeech = false
                voicedMs = 0
                lastUtteranceMs = speechMs - silentMs
                speechMs = 0
                return VadEvent.SPEECH_END
            }
        }
        return null
    }

    fun reset() {
        inSpeech = false
        voicedMs = 0
        silentMs = 0
        speechMs = 0
    }
}

/** 保留最近一段音频，说话起点判定有延迟，把开头补给转写。 */
class PreRoll(private val maxSamples: Int) {
    private val frames = ArrayDeque<ShortArray>()
    private var size = 0

    fun add(frame: ShortArray) {
        frames.addLast(frame)
        size += frame.size
        while (size > maxSamples && frames.size > 1) size -= frames.removeFirst().size
    }

    fun drain(): List<ShortArray> = frames.toList().also { frames.clear(); size = 0 }
}
