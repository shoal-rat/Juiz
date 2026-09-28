package app.juiz.core.recording

import app.juiz.core.archive.ArchiveLog
import app.juiz.core.util.Clock
import app.juiz.core.util.sha256Hex
import app.juiz.core.voice.CallAudioPort
import app.juiz.core.voice.StreamingResampler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 录音加密。Android 用 Keystore 里不可导出的 AES 密钥；仿真台和测试用 [AesGcmCipher]。 */
interface RecordingCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}

/** AES-256-GCM：输出 = 12 字节 IV + 密文（含 16 字节认证标签）。 */
class AesGcmCipher(private val key: ByteArray) : RecordingCipher {
    init { require(key.size == 32) { "需要 256 位密钥" } }

    override fun encrypt(plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return iv + c.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, blob, 0, 12))
        return c.doFinal(blob, 12, blob.size - 12)
    }
}

/**
 * 通话录音器：左声道是对方（下行），右声道是 Juiz（上行）。
 *
 * 时间线按真实经过的时间对齐：下行一块音频到达时，它结束于"现在"；下行中断（系统卡顿、测试台里对方不说话时不送帧）
 * 超过 0.3 秒，就用静音补齐，不会把后面的话挤到前面。Juiz 的语音是成段写进播放缓冲的，从"现在"起排；
 * 被打断、清空播放缓冲时，还没播出去的那部分也从录音里截掉——录下来的就是对方实际听到的。
 * 通话中只写应用私有目录里的临时文件，结束时混成立体声 WAV 交给 [RecordingStore] 加密保存，临时文件随即删除。
 */
class CallRecorder(
    tmpDir: File,
    val sampleRate: Int = 16_000,
    /** 单调时钟（纳秒），测试里可以替换。 */
    private val clock: () -> Long = System::nanoTime,
) {
    private val t0 = clock()
    private fun now(): Long = (clock() - t0) * sampleRate / 1_000_000_000L
    private val dir = File(tmpDir, "rec-" + System.nanoTime()).apply { mkdirs() }
    private val callerFile = RandomAccessFile(File(dir, "caller.pcm"), "rw")
    private val juizFile = RandomAccessFile(File(dir, "juiz.pcm"), "rw")
    private var callerPos = 0L
    private var juizPos = 0L
    private var callerRs: StreamingResampler? = null
    private var callerRate = 0
    private var juizRs: StreamingResampler? = null
    private var juizRate = 0
    private var closed = false

    val seconds: Double @Synchronized get() = callerPos.toDouble() / sampleRate

    @Synchronized
    fun caller(pcm: ShortArray, rate: Int) {
        if (closed || pcm.isEmpty()) return
        if (rate != callerRate) { callerRate = rate; callerRs = StreamingResampler(rate, sampleRate) }
        val s = callerRs!!.process(pcm)
        val start = now() - s.size
        if (start - callerPos > sampleRate * 3 / 10) {   // 下行断了一阵：补静音，对齐到真实时间
            callerFile.seek(callerPos * 2); callerFile.write(ByteArray(((start - callerPos) * 2).toInt()))
            callerPos = start
        }
        callerFile.seek(callerPos * 2); callerFile.write(le(s))
        callerPos += s.size
    }

    @Synchronized
    fun assistant(pcm: ShortArray, rate: Int) {
        if (closed || pcm.isEmpty()) return
        if (rate != juizRate) { juizRate = rate; juizRs = StreamingResampler(rate, sampleRate) }
        val s = juizRs!!.process(pcm)
        val start = maxOf(juizPos, now())
        if (start > juizPos) { juizFile.seek(juizPos * 2); juizFile.write(ByteArray(((start - juizPos) * 2).toInt())) }
        juizFile.seek(start * 2); juizFile.write(le(s))
        juizPos = start + s.size
    }

    /** 播放缓冲被清空（打断、接管）：还没播出去的部分不算数。 */
    @Synchronized
    fun flushAssistant() {
        val n = now()
        if (closed || juizPos <= n) return
        juizPos = n
        juizFile.setLength(juizPos * 2)
    }

    /** 结束录音，返回立体声 16 位 WAV；不到一秒返回 null。临时文件一律删除。 */
    @Synchronized
    fun finish(): ByteArray? {
        if (closed) return null
        closed = true
        try {
            val n = maxOf(callerPos, minOf(juizPos, maxOf(callerPos, now()) + sampleRate * 2L)).toInt()
            if (n < sampleRate) return null
            val left = readAll(callerFile, n)
            val right = readAll(juizFile, n)
            val data = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until n) { data.putShort(left[i]); data.putShort(right[i]) }
            return wav(data.array(), sampleRate, 2)
        } finally {
            callerFile.close(); juizFile.close()
            dir.deleteRecursively()
        }
    }

    /** 放弃录音（例如通话中撤销了录音同意）。 */
    @Synchronized
    fun discard() {
        if (closed) return
        closed = true
        callerFile.close(); juizFile.close()
        dir.deleteRecursively()
    }

    private fun readAll(f: RandomAccessFile, n: Int): ShortArray {
        val out = ShortArray(n)
        val len = minOf(f.length() / 2, n.toLong()).toInt()
        if (len == 0) return out
        val b = ByteArray(len * 2); f.seek(0); f.readFully(b)
        ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out, 0, len)
        return out
    }

    private fun le(s: ShortArray): ByteArray {
        val b = ByteBuffer.allocate(s.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.asShortBuffer().put(s)
        return b.array()
    }

    companion object {
        fun wav(pcm: ByteArray, rate: Int, channels: Int): ByteArray {
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()); h.putInt(36 + pcm.size); h.put("WAVE".toByteArray())
            h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(channels.toShort())
            h.putInt(rate); h.putInt(rate * channels * 2); h.putShort((channels * 2).toShort()); h.putShort(16)
            h.put("data".toByteArray()); h.putInt(pcm.size)
            return h.array() + pcm
        }
    }
}

/** 把录音器挂在通话音频端口上：下行进左声道，Juiz 的上行进右声道。VoiceSession 不需要知道录音的存在。 */
class RecordingAudioPort(private val inner: CallAudioPort, private val recorder: CallRecorder) : CallAudioPort {
    override val captureRate: Int get() = inner.captureRate
    override val playbackRate: Int get() = inner.playbackRate
    override fun captured(): Flow<ShortArray> = inner.captured().onEach { recorder.caller(it, inner.captureRate) }
    override suspend fun play(pcm: ShortArray) {
        recorder.assistant(pcm, inner.playbackRate)
        inner.play(pcm)
    }
    override fun flushPlayback() {
        inner.flushPlayback()
        recorder.flushAssistant()
    }
}

/** 回放时的核验结果。 */
enum class RecordingCheck(val zh: String) {
    VERIFIED("与档案记录的哈希一致，录音未被改动"),
    HASH_MISMATCH("与档案记录的哈希不一致，录音可能被改动"),
    NOT_IN_ARCHIVE("档案里没有这段录音的记录"),
    CHAIN_BROKEN("档案哈希链校验失败"),
}

class OpenedRecording(val wav: ByteArray, val seconds: Double, val check: RecordingCheck) {
    /** 16 位交错 PCM（去掉 44 字节 WAV 头）。 */
    val pcm: ByteArray get() = wav.copyOfRange(44, wav.size)
    val sampleRate: Int get() = ByteBuffer.wrap(wav, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int
    val channels: Int get() = ByteBuffer.wrap(wav, 22, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
}

/**
 * 加密录音库：每通电话一个文件，明文的 SHA-256 写进哈希链档案（"recording.saved"）。
 * 回放时解密并重算哈希，与档案比对——所以本人随时可以核对"这就是当时的原话"。
 * 过了保留期自动删除；撤销录音同意时全部删除。删除同样记入档案。
 */
class RecordingStore(
    private val dir: File,
    private val cipher: RecordingCipher,
    private val archive: ArchiveLog,
    private val clock: Clock,
) {
    init { dir.mkdirs() }

    private fun file(conversationId: String) = File(dir, conversationId.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".jrec")

    fun exists(conversationId: String?): Boolean = conversationId != null && file(conversationId).isFile

    fun save(conversationId: String, wav: ByteArray) {
        val f = file(conversationId)
        val tmp = File(dir, f.name + ".part")
        tmp.writeBytes(cipher.encrypt(wav))
        check(tmp.renameTo(f)) { "录音保存失败" }
        val seconds = (wav.size - 44).toDouble() / (ByteBuffer.wrap(wav, 28, 4).order(ByteOrder.LITTLE_ENDIAN).int)
        archive.append("recording.saved", conversationId) {
            put("sha256", sha256Hex(wav))
            put("seconds", Math.round(seconds * 10) / 10.0)
            put("bytes", wav.size)
        }
    }

    fun open(conversationId: String): OpenedRecording? {
        val f = file(conversationId)
        if (!f.isFile) return null
        val wav = cipher.decrypt(f.readBytes())
        val saved = archive.forRef(conversationId).lastOrNull { it.type == "recording.saved" }
        val check = when {
            saved == null -> RecordingCheck.NOT_IN_ARCHIVE
            saved.payload["sha256"]?.jsonPrimitive?.contentOrNull != sha256Hex(wav) -> RecordingCheck.HASH_MISMATCH
            !archive.verify().ok -> RecordingCheck.CHAIN_BROKEN
            else -> RecordingCheck.VERIFIED
        }
        val bytesPerSec = ByteBuffer.wrap(wav, 28, 4).order(ByteOrder.LITTLE_ENDIAN).int
        archive.append("recording.played", conversationId) { put("check", check.name) }
        return OpenedRecording(wav, (wav.size - 44).toDouble() / bytesPerSec, check)
    }

    fun delete(conversationId: String, reason: String) {
        if (file(conversationId).delete()) archive.append("recording.deleted", conversationId) { put("reason", reason) }
    }

    fun purgeOlderThan(days: Int) {
        val cutoff = clock.millis() - days * 86_400_000L
        dir.listFiles { f -> f.name.endsWith(".jrec") && f.lastModified() < cutoff }?.forEach { f ->
            val id = f.name.removeSuffix(".jrec")
            if (f.delete()) archive.append("recording.deleted", id) { put("reason", "超过保留期（$days 天）") }
        }
    }

    fun deleteAll(reason: String) {
        dir.listFiles { f -> f.name.endsWith(".jrec") }?.forEach { f ->
            val id = f.name.removeSuffix(".jrec")
            if (f.delete()) archive.append("recording.deleted", id) { put("reason", reason) }
        }
    }
}
