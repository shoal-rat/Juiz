package app.juiz.core

import app.juiz.core.model.ConsentKind
import app.juiz.core.recording.CallRecorder
import app.juiz.core.recording.RecordingCheck
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordingTest {
    private val sr = 16_000
    private fun tone(sec: Double, f: Double = 440.0, rate: Int = sr) = ShortArray((sec * rate).toInt()) { i -> (8000 * sin(2 * Math.PI * f * i / rate)).toInt().toShort() }
    private fun silence(sec: Double, rate: Int = sr) = ShortArray((sec * rate).toInt())

    /** 取某声道在 [a, b) 秒的平均幅度。 */
    private fun level(wav: ByteArray, ch: Int, a: Double, b: Double): Double {
        val pcm = ByteBuffer.wrap(wav, 44, wav.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var sum = 0.0; var n = 0
        for (i in (a * sr).toInt() until minOf((b * sr).toInt(), pcm.limit() / 2)) { sum += abs(pcm.get(i * 2 + ch).toInt()); n++ }
        return if (n == 0) 0.0 else sum / n
    }

    /** 假时钟：音频块"到达"时把时间推进到这块结束的时刻。 */
    private class FakeClock { var ns = 0L; fun at(sec: Double) { ns = (sec * 1e9).toLong() } }

    @Test
    fun juizSpeechIsPlacedAtTheMomentItStartsAndBargeInTrimsTheUnplayedTail() {
        val tmp = Files.createTempDirectory("rec").toFile()
        val c = FakeClock()
        val r = CallRecorder(tmp, sr) { c.ns }
        c.at(1.0); r.caller(tone(1.0, rate = 8000), 8000)          // 对方先说 1 秒（8 kHz 下行，重采样到 16 kHz）
        r.assistant(tone(1.0, 660.0, 24000), 24000)                 // Juiz 在第 1 秒开口
        c.at(2.0); r.caller(silence(1.0, 8000), 8000)               // 第 1–2 秒对方没说话
        r.assistant(tone(3.0, 660.0, 24000), 24000)                 // 又写进 3 秒，但……
        c.at(2.5); r.caller(silence(0.5, 8000), 8000)
        r.flushAssistant()                                           // ……第 2.5 秒被打断，没播出去的 2.5 秒不算
        c.at(3.5); r.caller(silence(1.0, 8000), 8000)
        val wav = assertNotNull(r.finish())
        assertEquals(2, ByteBuffer.wrap(wav, 22, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt())
        assertTrue(level(wav, 0, 0.1, 0.9) > 3000)       // 左声道：对方
        assertTrue(level(wav, 1, 0.1, 0.9) < 50)         // 右声道：Juiz 那时还没说话
        assertTrue(level(wav, 1, 1.1, 2.4) > 3000)       // Juiz 从第 1 秒说到被打断
        assertTrue(level(wav, 1, 2.6, 3.4) < 50)         // 打断之后是空的
        assertFalse(tmp.listFiles()!!.any { it.isDirectory }, "临时文件要删掉")
    }

    @Test
    fun downlinkGapsArePaddedWithSilenceInsteadOfSquashingTime() {
        // 音频测试台实测：对方不说话时测试台不送下行帧，按采样数计时会把 60 秒的通话压成 14 秒
        val c = FakeClock()
        val r = CallRecorder(Files.createTempDirectory("rec").toFile(), sr) { c.ns }
        c.at(1.0); r.caller(tone(1.0), sr)                 // 0–1 秒对方说话
        c.at(1.5); r.assistant(tone(2.0, 660.0), sr)       // 1.5 秒 Juiz 开口，说到 3.5 秒；这期间下行没有帧
        c.at(5.0); r.caller(tone(1.0), sr)                 // 4–5 秒对方再说
        val wav = r.finish()!!
        assertEquals(5.0, (wav.size - 44) / 4.0 / sr, 0.05)
        assertTrue(level(wav, 0, 4.1, 4.9) > 3000)         // 第二句在第 4 秒，不是紧接着第 1 秒
        assertTrue(level(wav, 0, 1.2, 3.8) < 50)
        assertTrue(level(wav, 1, 1.6, 3.4) > 3000)
    }

    @Test
    fun shortRecordingsAreDropped() {
        val r = CallRecorder(Files.createTempDirectory("rec").toFile(), sr)
        r.caller(tone(0.4), sr)
        assertNull(r.finish())
    }

    @Test
    fun savedRecordingIsEncryptedAndVerifiedAgainstTheArchive() {
        val dir = Files.createTempDirectory("recs").toFile()
        val (core, _) = testCore(recordingDir = dir)
        val store = core.recordings!!
        val c = FakeClock()
        val r = CallRecorder(Files.createTempDirectory("rec").toFile(), sr) { c.ns }
        c.at(2.0); r.caller(tone(2.0), sr); r.assistant(tone(1.0, 660.0), sr)
        val wav = r.finish()!!
        store.save("C-test1", wav)
        val file = File(dir, "C-test1.jrec")
        assertTrue(file.isFile)
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("RIFF"), "磁盘上不能是明文 WAV")
        val opened = store.open("C-test1")!!
        assertEquals(RecordingCheck.VERIFIED, opened.check)
        assertEquals(3.0, opened.seconds, 0.05)  // 对方说完 2 秒后 Juiz 才开口，她说的最后 1 秒也要录上
        assertTrue(core.archive.forRef("C-test1").any { it.type == "recording.saved" })

        // 有人换掉了录音（用同一把钥匙加密了一段改过的音频）→ 回放时哈希对不上
        val cipher = app.juiz.core.recording.AesGcmCipher(ByteArray(32) { i -> i.toByte() })
        val forged = wav.copyOf().also { it[1000] = (it[1000] + 1).toByte() }
        file.writeBytes(cipher.encrypt(forged))
        assertEquals(RecordingCheck.HASH_MISMATCH, store.open("C-test1")!!.check)
    }

    @Test
    fun revokingRecordingConsentDeletesAllRecordings() {
        val dir = Files.createTempDirectory("recs").toFile()
        val (core, _) = testCore(recordingDir = dir)
        core.consents.set(ConsentKind.CALL_RECORDING, true)
        assertTrue(core.shouldRecordCalls())
        val r = CallRecorder(Files.createTempDirectory("rec").toFile(), sr)
        r.caller(tone(1.5), sr)
        core.recordings!!.save("C-x", r.finish()!!)
        assertTrue(core.recordings!!.exists("C-x"))
        core.consents.set(ConsentKind.CALL_RECORDING, false)
        assertFalse(core.recordings!!.exists("C-x"))
        assertTrue(core.archive.forRef("C-x").any { it.type == "recording.deleted" })
        assertFalse(core.shouldRecordCalls())
    }
}
