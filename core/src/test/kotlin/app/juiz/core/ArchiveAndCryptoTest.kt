package app.juiz.core

import app.juiz.core.archive.ArchiveLog
import app.juiz.core.archive.BackupCrypto
import app.juiz.core.archive.BundleFile
import app.juiz.core.archive.ExportBundle
import app.juiz.core.util.sha256Hex
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArchiveAndCryptoTest {

    @Test
    fun chainVerifiesAndDetectsTampering() {
        val (core, clock) = testCore()
        repeat(5) { i ->
            clock.advanceSeconds(1)
            core.archive.append("test", "R$i") { put("i", i); put("text", "第 $i 条") }
        }
        assertTrue(core.archive.verify().ok)
        val anchor = core.archive.head()!!
        assertEquals(5, anchor.seq)
        assertTrue(core.archive.verifyAnchor(anchor))

        // 绕过 ArchiveLog 直接往库里插一条伪造事件：哈希链必须发现
        core.db.juizQueries.insertEvent(6, 0, "forged", null, "{}", "bad", "bad")
        val report = core.archive.verify()
        assertFalse(report.ok)
        assertEquals(6, report.firstBadSeq)
    }

    @Test
    fun rewrittenPayloadBreaksHash() {
        val (core, _) = testCore()
        core.archive.append("a", null) { put("v", 1) }
        val e = core.archive.all().single()
        val forged = e.copy(payload = kotlinx.serialization.json.buildJsonObject { put("v", 2) })
        assertFalse(ArchiveLog.verifyEvents(listOf(forged)).ok)
    }

    @Test
    fun backupRoundTripAndWrongPassphrase() {
        val data = "账本 ✓ ledger".repeat(100).toByteArray()
        val blob = BackupCrypto.encrypt(data, "正确口令".toCharArray(), iterations = 20_000)
        assertContentEquals(data, BackupCrypto.decrypt(blob, "正确口令".toCharArray()))
        assertFailsWith<BackupCrypto.WrongPassphrase> { BackupCrypto.decrypt(blob, "错误口令".toCharArray()) }
        val tampered = blob.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertFailsWith<BackupCrypto.WrongPassphrase> { BackupCrypto.decrypt(tampered, "正确口令".toCharArray()) }
    }

    @Test
    fun exportBundleIsSelfVerifying() {
        val (core, _) = testCore()
        core.archive.append("task.created", "T-1") { put("title", "做 Slides <script>") }
        val out = ByteArrayOutputStream()
        ExportBundle.write(out, core.archive.all(), listOf(BundleFile("a.txt", "hello".toByteArray())), "张三", ZoneId.of("Asia/Shanghai"), Instant.EPOCH)
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                entries[e.name] = z.readBytes()
            }
        }
        assertEquals(setOf("events.jsonl", "timeline.html", "files/a.txt", "README.txt", "MANIFEST.sha256"), entries.keys)
        val html = String(entries.getValue("timeline.html"))
        assertTrue("&lt;script&gt;" in html, "HTML 必须转义")
        String(entries.getValue("MANIFEST.sha256")).lines().filter { it.isNotBlank() }.forEach { line ->
            val (hash, name) = line.split("  ")
            assertEquals(sha256Hex(entries.getValue(name)), hash, name)
        }
    }
}
