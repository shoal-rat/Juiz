package app.juiz.platform

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.telecom.Call
import app.juiz.core.voice.CallAudioPort
import app.juiz.core.voice.OwnerAudioPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.IOException

/**
 * AOSP 的系统接口（@SystemApi，已在 frameworks/base 主干源码核对）：
 *   Call.enterBackgroundAudioProcessing() / exitBackgroundAudioProcessing(shouldRing) —— 仅默认拨号应用
 *   AudioManager.isPstnCallAudioInterceptable()                    —— CALL_AUDIO_INTERCEPTION
 *   AudioManager.getCallDownlinkExtractionAudioRecord(AudioFormat)  —— 对方声音
 *   AudioManager.getCallUplinkInjectionAudioTrack(AudioFormat)      —— 送入通话
 * 这些接口需要系统特权安装；普通安装调用会抛 SecurityException。能不能用、在哪台机器上能用，
 * 一律以能力探针和真机验证向导的结果为准。
 */
object SystemCallApi {
    const val STATE_AUDIO_PROCESSING = 12
    const val STATE_SIMULATED_RINGING = 13

    private fun invoke(clazz: Class<*>, target: Any, name: String, vararg args: Any?): Any? =
        try {
            HiddenApiBypass.invoke(clazz, target, name, *args)
        } catch (e: NoSuchMethodException) {
            // 某些 ROM 上 HiddenApiBypass 找不到时退回普通反射
            clazz.methods.first { it.name == name && it.parameterTypes.size == args.size }.invoke(target, *args)
        }

    fun methodExists(clazz: Class<*>, name: String): Boolean =
        runCatching { HiddenApiBypass.getDeclaredMethods(clazz).any { (it as java.lang.reflect.Method).name == name } }.getOrDefault(false) ||
            clazz.methods.any { it.name == name }

    fun enterBackgroundAudioProcessing(call: Call) {
        invoke(Call::class.java, call, "enterBackgroundAudioProcessing")
    }

    fun exitBackgroundAudioProcessing(call: Call, ring: Boolean) {
        invoke(Call::class.java, call, "exitBackgroundAudioProcessing", ring)
    }

    fun isPstnCallAudioInterceptable(am: AudioManager): Boolean =
        invoke(AudioManager::class.java, am, "isPstnCallAudioInterceptable") as Boolean

    fun downlinkRecord(am: AudioManager, rate: Int): AudioRecord {
        val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
        return invoke(AudioManager::class.java, am, "getCallDownlinkExtractionAudioRecord", fmt) as AudioRecord
    }

    fun uplinkTrack(am: AudioManager, rate: Int): AudioTrack {
        val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
        return invoke(AudioManager::class.java, am, "getCallUplinkInjectionAudioTrack", fmt) as AudioTrack
    }

    /** 退路：老式的 VOICE_DOWNLINK 采集（需要 CAPTURE_AUDIO_OUTPUT）。 */
    @SuppressLint("MissingPermission")
    fun legacyDownlinkRecord(rate: Int): AudioRecord =
        AudioRecord(MediaRecorder.AudioSource.VOICE_DOWNLINK, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 4)
}

/** L1 的通话音频端口：下行提取 + 上行注入。 */
class PrivilegedCallAudioPort private constructor(
    private val record: AudioRecord,
    private val track: AudioTrack,
) : CallAudioPort {
    override val captureRate: Int = record.sampleRate
    override val playbackRate: Int = track.sampleRate

    override fun captured(): Flow<ShortArray> = flow {
        val buf = ShortArray(captureRate / 50)
        record.startRecording()
        try {
            while (currentCoroutineContext().isActive) {
                val n = record.read(buf, 0, buf.size)
                when {
                    n > 0 -> emit(buf.copyOf(n))
                    n < 0 -> throw IOException("下行音频读取失败：$n")
                }
            }
        } finally {
            runCatching { record.stop() }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun play(pcm: ShortArray) = withContext(Dispatchers.IO) {
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
        var off = 0
        while (off < pcm.size) {
            val n = track.write(pcm, off, pcm.size - off)
            if (n < 0) throw IOException("上行音频写入失败：$n")
            if (n == 0) break // 被 flush 打断
            off += n
        }
    }

    override fun flushPlayback() {
        runCatching {
            track.pause()
            track.flush()
            track.play()
        }
    }

    fun release() {
        runCatching { record.release() }
        runCatching { track.release() }
    }

    companion object {
        /** 优先用 Android 13 的通话音频拦截接口；不可用时尝试旧的 VOICE_DOWNLINK 采集（上行仍需注入接口）。 */
        fun open(context: Context, rate: Int = 16_000): PrivilegedCallAudioPort {
            val am = context.getSystemService(AudioManager::class.java)
            check(Build.VERSION.SDK_INT >= 33) { "通话音频拦截接口需要 Android 13 及以上" }
            check(SystemCallApi.isPstnCallAudioInterceptable(am)) { "本机音频 HAL 不支持拦截普通电话音频（isPstnCallAudioInterceptable=false）" }
            val record = runCatching { SystemCallApi.downlinkRecord(am, rate) }.getOrElse { SystemCallApi.legacyDownlinkRecord(rate) }
            val track = SystemCallApi.uplinkTrack(am, rate)
            return PrivilegedCallAudioPort(record, track)
        }
    }
}

/**
 * 情绪滤网里"本人的听筒和麦克风"。通话处于后台音频处理状态时，本机声学设备不直连通话，
 * 由这里转接。路由是否正确（听筒而不是外放）取决于机型，属于真机验证项。
 */
class PhoneOwnerAudioPort(context: Context, rate: Int = 16_000) : OwnerAudioPort {
    override val micRate = rate
    override val earRate = rate

    @SuppressLint("MissingPermission")
    private val mic = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 4)

    private val ear = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 4)
        .build()

    init {
        val am = context.getSystemService(AudioManager::class.java)
        if (Build.VERSION.SDK_INT >= 31) {
            am.availableCommunicationDevices.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                ?.let { runCatching { am.setCommunicationDevice(it) } }
        }
    }

    override fun mic(): Flow<ShortArray> = flow {
        val buf = ShortArray(micRate / 50)
        mic.startRecording()
        try {
            while (currentCoroutineContext().isActive) {
                val n = mic.read(buf, 0, buf.size)
                if (n > 0) emit(buf.copyOf(n)) else if (n < 0) throw IOException("麦克风读取失败：$n")
            }
        } finally {
            runCatching { mic.stop() }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun playToEar(pcm: ShortArray) = withContext(Dispatchers.IO) {
        if (ear.playState != AudioTrack.PLAYSTATE_PLAYING) ear.play()
        ear.write(pcm, 0, pcm.size)
        Unit
    }

    override fun flushEar() {
        runCatching { ear.pause(); ear.flush(); ear.play() }
    }

    fun release() {
        runCatching { mic.release() }
        runCatching { ear.release() }
    }
}
