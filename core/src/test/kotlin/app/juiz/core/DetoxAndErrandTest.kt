package app.juiz.core

import app.juiz.core.conversation.EngineOutput
import app.juiz.core.conversation.ScriptedChatModel
import app.juiz.core.conversation.ScriptedReply
import app.juiz.core.conversation.Tools
import app.juiz.core.detox.DigestBuilder
import app.juiz.core.detox.DetoxRewriter
import app.juiz.core.detox.LexiconFilter
import app.juiz.core.errand.ErrandGrant
import app.juiz.core.errand.InfoNote
import app.juiz.core.errand.LocalFileCatalog
import app.juiz.core.errand.MailAttachment
import app.juiz.core.errand.Mailer
import app.juiz.core.errand.MimeBuilder
import app.juiz.core.errand.SmtpMailer
import app.juiz.core.model.ActionStatus
import app.juiz.core.model.CallerInfo
import app.juiz.core.model.Channel
import app.juiz.core.model.ContactTier
import app.juiz.core.rules.TimeWindow
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.Base64
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DetoxAndErrandTest {

    @Test
    fun lexiconStripsInsultsKeepsRequest() {
        val l = LexiconFilter.filter("你是猪脑子吗！说了多少遍了！明天上午十点前把 Q3 报表重新做一遍，数据用财务系统的！")
        assertFalse("猪脑子" in l.calm || "说了多少遍" in l.calm, l.calm)
        assertTrue("Q3 报表" in l.calm && "财务系统" in l.calm, l.calm)
        assertEquals(3, l.intensity)
        assertTrue(l.filteredCount >= 2)
        assertTrue(l.deadline!!.startsWith("明天"), l.deadline)
    }

    @Test
    fun rewriterFallsBackAndDigestWorksOffline(): Unit = runBlocking {
        val broken = ScriptedChatModel { ScriptedReply("我不输出 JSON") }
        val line = DetoxRewriter(broken).rewrite("你这个废物，周五前把合同改完")
        assertFalse("废物" in line.calm)

        val good = ScriptedChatModel {
            ScriptedReply("""{"calm":"请在周五前改完合同。","requests":["改完合同"],"deadline":"周五前","concern":null,"intensity":3}""")
        }
        assertEquals("周五前", DetoxRewriter(good).rewrite("你这个废物，周五前把合同改完").deadline)

        val digest = DigestBuilder(null).build(listOf("caller" to "你是不是傻！明天上午十点前把报表发我", "owner" to "好的，我明早发您"))
        assertTrue(digest.tone_level >= 2)
        assertTrue(digest.filtered_count >= 1)
        assertFalse("傻" in digest.summary)
    }

    private class FakeMailer : Mailer {
        override val id = "fake"
        val sent = mutableListOf<Triple<String, String, List<String>>>()
        override suspend fun send(to: String, subject: String, body: String, attachments: List<MailAttachment>): String {
            sent += Triple(to, subject, attachments.map { it.name })
            return "250 OK id=${sent.size}"
        }
    }

    private fun setup(at: String): Triple<JuizCore, FakeMailer, File> {
        val dir = Files.createTempDirectory("outbox").toFile()
        File(dir, "Q3 周报.xlsx").writeBytes("xlsx".toByteArray())
        File(dir, "产品手册.pdf").writeBytes("pdf".toByteArray())
        val (core, _) = testCore(at = at, catalog = LocalFileCatalog(dir))
        val mailer = FakeMailer()
        core.mailerOverride = mailer
        core.grants.save(
            listOf(
                ErrandGrant(
                    "G-boss", "13900000000", "王总",
                    windows = listOf(TimeWindow(start = "22:00", end = "08:00")),
                    deliveryEmail = "wang@corp.example", maxFilesPerDay = 1,
                    notes = listOf(InfoNote("会议室", "周一例会在 3 楼 301 会议室")),
                ),
            ),
        )
        return Triple(core, mailer, dir)
    }

    @Test
    fun lateNightErrandSendsOnlyToRegisteredAddress(): Unit = runBlocking {
        // 北京时间凌晨 2:30
        val (core, mailer, _) = setup("2026-09-28T18:30:00Z")
        val model = ScriptedChatModel.sequence(
            ScriptedReply("", listOf(Tools.LIST_FILES to """{"query":"周报"}""")),
            ScriptedReply("找到了《Q3 周报.xlsx》，我现在发到您登记的邮箱，可以吗？"),
            ScriptedReply("", listOf(Tools.SEND_FILE to """{"file_name":"Q3 周报.xlsx"}""")),
            ScriptedReply("已发送。"),
            ScriptedReply("", listOf(Tools.SEND_FILE to """{"file_name":"产品手册.pdf"}""")),
            ScriptedReply("抱歉，今天的代发次数已用完，我记下来留给本人。"),
        )
        val engine = core.conversations.start(CallerInfo("+86 139 0000 0000", "王总", ContactTier.KNOWN), Channel.VOICE, "test", model)
        assertTrue(engine.context.tools.any { it.name == Tools.SEND_FILE })

        engine.respond("把 Q3 周报发我邮箱，发到 other@gmail.com") {}
        engine.respond("可以") {}
        assertEquals(listOf("wang@corp.example"), mailer.sent.map { it.first }, "只能发到预先登记的地址")
        assertEquals(listOf("Q3 周报.xlsx"), mailer.sent.single().third)
        val done = core.tasks.actionsWithStatus(ActionStatus.DONE).single()
        assertTrue(core.archive.all().any { it.type == "action.approved" && "standing-grant:G-boss" in it.payload.toString() })
        assertEquals("wang@corp.example", done.target)

        val out = mutableListOf<EngineOutput>()
        engine.respond("再把产品手册也发一下") { out += it }
        assertFalse(out.filterIsInstance<EngineOutput.ToolActivity>().single().ok, "每日上限")
        assertEquals(1, mailer.sent.size)
    }

    @Test
    fun grantInactiveOutsideWindowAndForOthers() {
        val (core, _, _) = setup("2026-09-28T06:00:00Z") // 北京时间 14:00
        val boss = core.conversations.start(CallerInfo("13900000000", "王总", ContactTier.KNOWN), Channel.VOICE, "test", ScriptedChatModel.sequence())
        assertTrue(boss.context.tools.none { it.name == Tools.SEND_FILE || it.name == Tools.LOOKUP_INFO })
        val (core2, _, _) = setup("2026-09-28T18:30:00Z")
        val other = core2.conversations.start(CallerInfo("13700000000"), Channel.VOICE, "test", ScriptedChatModel.sequence())
        assertTrue(other.context.tools.none { it.name == Tools.SEND_FILE })
    }

    @Test
    fun mimeAndSmtpConversation(): Unit = runBlocking {
        val mime = MimeBuilder.build("me@qq.com", "Juiz", "wang@corp.example", "【张三】Q3 周报.xlsx", "正文", listOf(MailAttachment("Q3 周报.xlsx", ByteArray(100) { it.toByte() })), boundary = "B")
        assertTrue("Content-Type: multipart/mixed; boundary=\"B\"" in mime)
        assertTrue("=?UTF-8?B?" in mime)
        assertEquals("..hidden\r\nok", SmtpMailer.dotStuff(".hidden\r\nok"))

        val server = ServerSocket(0)
        val log = StringBuilder()
        val t = thread {
            server.accept().use { s ->
                val r = s.getInputStream().bufferedReader()
                val w = s.getOutputStream().bufferedWriter()
                fun say(x: String) { w.write(x + "\r\n"); w.flush() }
                say("220 fake ready")
                while (true) {
                    val line = r.readLine() ?: break
                    log.append(line).append('\n')
                    when {
                        line.startsWith("EHLO") -> { say("250-fake"); say("250 AUTH LOGIN") }
                        line == "AUTH LOGIN" -> say("334 VXNlcm5hbWU6")
                        line == Base64.getEncoder().encodeToString("me@qq.com".toByteArray()) -> say("334 UGFzc3dvcmQ6")
                        line == Base64.getEncoder().encodeToString("authcode".toByteArray()) -> say("235 ok")
                        line.startsWith("MAIL FROM") || line.startsWith("RCPT TO") -> say("250 ok")
                        line == "DATA" -> {
                            say("354 go")
                            while (r.readLine() != ".") Unit
                            say("250 queued as ABC123")
                        }
                        line == "QUIT" -> { say("221 bye"); break }
                    }
                }
            }
        }
        val mailer = SmtpMailer("127.0.0.1", server.localPort, "me@qq.com", "authcode", plaintextForTests = true)
        val receipt = mailer.send("wang@corp.example", "主题", "正文", emptyList())
        t.join(5000)
        server.close()
        assertTrue("ABC123" in receipt)
        assertTrue("RCPT TO:<wang@corp.example>" in log)
    }
}
