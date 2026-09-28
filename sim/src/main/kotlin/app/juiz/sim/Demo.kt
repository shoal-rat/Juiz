package app.juiz.sim

import app.juiz.core.conversation.ScriptedChatModel
import app.juiz.core.conversation.ScriptedReply
import app.juiz.core.conversation.Tools
import app.juiz.core.detox.DigestBuilder
import app.juiz.core.errand.ErrandGrant
import app.juiz.core.errand.InfoNote
import app.juiz.core.model.CallerInfo
import app.juiz.core.model.CapabilityLevel
import app.juiz.core.model.Channel
import app.juiz.core.model.ConsentKind
import app.juiz.core.model.ContactTier
import app.juiz.core.model.OwnerProfile
import app.juiz.core.policy.Disclosure
import app.juiz.core.rules.TimeWindow
import app.juiz.core.util.FixedClock
import app.juiz.core.util.sha256Hex
import app.juiz.core.work.HandoffResult
import app.juiz.core.work.HandoffRoute
import app.juiz.core.work.LocalExchangeFolder
import java.io.File
import java.time.OffsetDateTime

/** 离线演示：脚本模型 + 本地目录，展示一条完整的业务闭环。不联网，不代表真实模型或真机表现。 */
object Demo {
    private fun step(n: Int, title: String) {
        println()
        println(Ansi.bold(Ansi.cyan("── $n · $title ")) + Ansi.dim("─".repeat(maxOf(0, 40 - title.length * 2))))
    }

    suspend fun run() {
        val root = File(SimEnv.dataDir, "demo").apply { deleteRecursively(); mkdirs() }
        val files = File(root, "可外发文件").apply { mkdirs() }
        File(files, "Q3 周报.xlsx").writeText("（演示用的周报内容）")
        val clock = FixedClock(OffsetDateTime.parse("2026-09-28T14:05:00+08:00").toInstant())
        val core = SimEnv.core(File(root, "juiz.db"), clock, File(root, "outbox"), files)
        core.settings.saveOwnerProfile(OwnerProfile(ownerName = "张三", publicStatus = "下午在开会，五点后方便。"))
        core.consents.set(ConsentKind.SMS_SCREENING, true)

        step(1, "来电决策")
        val li = CallerInfo("13822223333", "李女士", ContactTier.KNOWN)
        val now = clock.now().atZone(clock.zone())
        for (level in CapabilityLevel.entries) {
            val d = core.ruleEngine().decide(li, now, level, true)
            println("  ${level.name.padEnd(20)} → ${d.action.zh}（${d.delaySeconds}s）${d.downgradeReason?.let { Ansi.dim(" · $it") } ?: ""}")
        }

        step(2, "AI 代接：复述确认后创建任务")
        val create = """{"title":"Q3 合作方案 PPT","request":"李女士请张三做一份 Q3 合作方案 PPT","kind":"DOCUMENT","deliverable":"10 页以内 PPT","due":"10月8日18点","fields":[]}"""
        val model = ScriptedChatModel.sequence(
            ScriptedReply("我跟您核对一下：十月八号下午六点前要，对吗？", listOf(Tools.CONFIRM_DETAILS to """{"fields":[{"name":"截止时间","value":"10月8日18点"}]}""")),
            ScriptedReply(""),
            ScriptedReply("", listOf(Tools.CREATE_TASK to create)),
            ScriptedReply("好的，已经记下，张三确认后会安排。"),
        )
        val engine = core.conversations.start(li, Channel.TEXT_SIM, "demo", model)
        val greeting = Disclosure.greeting(core.settings.ownerProfile(), recording = false, clonedVoice = true)
        engine.opening(greeting)
        println(Ansi.cyan("  Juiz ▸ ") + greeting)
        for (line in listOf("帮我跟张三说，要一份 Q3 合作方案的 PPT，十月八号下午六点前要。", "对。")) {
            println(Ansi.bold("  来电 ▸ ") + line)
            speakTurn(engine, line, "  ")
        }
        core.conversations.end(engine.context.conversationId, "demo", "创建任务")
        val task = core.tasks.all().first()

        step(3, "本人确认 → 交给 ChatGPT Work（分享任务卡路径）")
        val r = core.handoff().handoff(task.id, HandoffRoute.MANUAL_SHARE) as HandoffResult.ShareNeeded
        println(Ansi.dim(r.cardText.lines().take(14).joinToString("\n") { "  │ $it" }))
        println(Ansi.dim("  │ …"))
        core.handoff().confirmManualHandoff(task.id)
        println("  任务状态：${core.tasks.get(task.id)!!.status.zh}")

        step(4, "Work 写回结果清单 → 手机核验交付物")
        val exchange = File(root, "exchange")
        val tdir = File(exchange, task.id).apply { mkdirs() }
        val deck = "（演示用的 PPT 字节）".toByteArray()
        File(tdir, "q3-plan.pptx").writeBytes(deck)
        File(tdir, "result.json").writeText("""{"schema":"juiz.result/v1","task_id":"${task.id}","status":"completed","summary":"已生成 8 页方案","deliverables":[{"path":"q3-plan.pptx","sha256":"${sha256Hex(deck)}","bytes":${deck.size}}]}""")
        val report = core.handoff().verify(task.id, LocalExchangeFolder(exchange))
        println("  核验：${if (report.ok) Ansi.green("通过") else Ansi.red("未通过")} ${report.verified} ${report.issues}")
        println("  任务状态：${core.tasks.get(task.id)!!.status.zh}")

        step(5, "凌晨 2:30，领导来电要文件（按授权代办，表明 AI 身份）")
        clock.instant = OffsetDateTime.parse("2026-09-29T02:30:00+08:00").toInstant()
        core.grants.save(listOf(ErrandGrant("G-boss", "13900000000", "王总", windows = listOf(TimeWindow(start = "22:00", end = "08:00")), deliveryEmail = "wang@corp.example", notes = listOf(InfoNote("例会", "周一例会在 301 会议室")))))
        val boss = CallerInfo("13900000000", "王总", ContactTier.KNOWN)
        println("  规则：${core.ruleEngine().decide(boss, clock.now().atZone(clock.zone()), CapabilityLevel.L1_PRIVILEGED_VOICE, true).action.zh}")
        val errandModel = ScriptedChatModel.sequence(
            ScriptedReply("", listOf(Tools.LIST_FILES to """{"query":"周报"}""")),
            ScriptedReply("王总您好，我是张三的 AI 助理，他已经休息了。找到《Q3 周报.xlsx》，按他的授权发到您登记的邮箱，可以吗？"),
            ScriptedReply("", listOf(Tools.SEND_FILE to """{"file_name":"Q3 周报.xlsx"}""")),
            ScriptedReply("已经发到 w***@corp.example，张三早上会看到记录。"),
        )
        val e2 = core.conversations.start(boss, Channel.TEXT_SIM, "demo", errandModel)
        e2.opening(Disclosure.greeting(core.settings.ownerProfile(), false, true))
        for (line in listOf("小张，把 Q3 周报发我一下，早上开会要用。", "对。")) {
            println(Ansi.bold("  来电 ▸ ") + line)
            speakTurn(e2, line, "  ")
        }
        core.conversations.end(e2.context.conversationId, "demo", "代办：发送 Q3 周报")
        File(root, "outbox").listFiles()?.forEach { println(Ansi.dim("  outbox/${it.name}")) }

        step(6, "工作时间领导来电：本人接听 + 情绪滤网 → 去情绪摘要")
        val transcript = listOf(
            "caller" to "你是猪脑子吗！说了多少遍了！周报里的 Q3 数据跟财务对不上！",
            "caller" to "明天上午十点前重新做一版发我，数据全部用财务系统导出的口径！",
            "owner" to "好的王总，我明天十点前按财务口径重做发您。",
        )
        transcript.filter { it.first == "caller" }.forEach { (_, t) ->
            println(Ansi.dim("  原话（折叠，仅本人可展开）"))
            println("  字幕 ▸ ${app.juiz.core.detox.LexiconFilter.filter(t).calm}")
        }
        val digest = DigestBuilder(null).build(transcript)
        println("  摘要 ▸ ${digest.summary}")
        digest.action_items.forEach { println("   ☐ ${it.what} · ${it.due}") }
        println(Ansi.dim("  语气强度 ${digest.tone_level}/3 · 已过滤 ${digest.filtered_count} 处"))

        step(7, "档案：哈希链校验与离线档案包")
        println("  ${core.archive.verify().message}")
        println("  链头锚点：${core.archive.head()}")
        val zip = File(root, "juiz-archive.zip")
        exportArchive(core, zip)
        println("  已导出：${zip.path}（含 timeline.html，无需本应用即可阅读和校验）")
        println()
        println(Ansi.dim("以上为离线脚本演示：用于展示流程与代码层规则，不代表真实模型或真机电话链路的表现。"))
    }
}
