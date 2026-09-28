package app.juiz.sim

import app.juiz.core.model.ActionStatus
import app.juiz.core.model.CallerInfo
import app.juiz.core.model.Channel
import app.juiz.core.model.ContactTier
import app.juiz.core.model.OwnerProfile
import app.juiz.core.policy.Disclosure
import app.juiz.core.tasks.ExecutionOutcome
import app.juiz.core.work.LocalExchangeFolder
import app.juiz.desk.Desk
import app.juiz.desk.DeskConfig
import java.io.File

/**
 * 真实链路演示（会真的调用模型和 Codex，不是脚本）：
 *   来电方（脚本台词）↔ 小模型接活 → 自动交给 juiz-desk → Codex 干活 → 手机核验 → 草稿 → 本人批准 → 发出（仿真发件箱）
 */
object Pipeline {
    suspend fun run(args: Args) {
        val root = File(SimEnv.dataDir, "pipeline").apply { deleteRecursively(); mkdirs() }
        val exchange = File(root, "exchange").apply { mkdirs() }
        val folder = LocalExchangeFolder(exchange)
        val clock = SimEnv.clock(null)
        val core = app.juiz.core.JuizCore(
            app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver("jdbc:sqlite:${File(root, "juiz.db").absolutePath}").also { app.juiz.core.JuizCore.createSchema(it) },
            clock, EnvSecrets, exchangeFolder = { folder },
        )
        core.mailerOverride = OutboxMailer(File(root, "outbox"))
        core.settings.saveOwnerProfile(OwnerProfile(ownerName = "林夏"))
        core.settings.saveWork(core.settings.work().copy(cloudEnabled = false, deskEnabled = true, autoHandoff = true, autoRoute = "CODEX_DESK", exchangeFolderHint = "Juiz"))

        println(Ansi.bold(Ansi.cyan("① 电话侧：小模型接活")))
        val caller = CallerInfo("13822223333", "李女士", ContactTier.KNOWN)
        val engine = core.conversations.start(caller, Channel.TEXT_SIM, "pipeline", SimEnv.model(args.modelChoice()))
        val greeting = Disclosure.greeting(core.settings.ownerProfile(), false, false)
        engine.opening(greeting)
        println(Ansi.cyan("  Juiz ▸ ") + greeting)
        val lines = listOf(
            "你好，我是李女士。想请林夏帮我写一封新品发布会的邀请函，发布会是 10 月 18 日下午两点在上海，写好后发到 li@example.com。",
            "对，没错。",
            "好的，谢谢。",
        )
        for (l in lines) {
            println(Ansi.bold("  来电 ▸ ") + l)
            speakTurn(engine, l, "  ")
            if (core.tasks.all().isNotEmpty()) break
        }
        val task = core.tasks.all().firstOrNull()
        if (task == null) {
            println(Ansi.red("  小模型没有创建任务，链路在这里中断（如实报告）。"))
            return
        }
        println(Ansi.dim("  工作说明（给大模型）：${task.brief ?: "（模型没写）"}"))
        println("  任务 ${task.id} 状态：${core.tasks.get(task.id)!!.status.zh}")

        println(Ansi.bold(Ansi.cyan("② 电脑侧：juiz-desk 调用 Codex")))
        val dir = File(exchange, task.id)
        if (!File(dir, "card.json").exists()) {
            println(Ansi.red("  任务卡没有进入交换目录"))
            return
        }
        val cfg = DeskConfig(exchange, "/Applications/ChatGPT.app/Contents/Resources/codex-cli/bin/codex", args["codex-model"] ?: "gpt-6-sol", false, 30, args.flag("dry-run"))
        val t0 = System.currentTimeMillis()
        Desk.process(dir, cfg) { println("  $it") }
        println(Ansi.dim("  耗时 ${(System.currentTimeMillis() - t0) / 1000}s · 交付物：${File(dir, "out").list()?.joinToString()}"))

        println(Ansi.bold(Ansi.cyan("③ 手机侧：领取状态 → 核验交付物 → 草稿变成待审批")))
        val h = core.handoff()
        h.poll(task.id); h.poll(task.id)
        val report = h.verify(task.id, folder)
        println("  核验：${if (report.ok) Ansi.green("通过") else Ansi.red("未通过")} ${report.verified} ${report.issues}")
        println("  任务状态：${core.tasks.get(task.id)!!.status.zh}")
        val proposed = core.tasks.actionsForTask(task.id)
        proposed.forEach { a ->
            println("  待审批：发给 ${a.target} · ${a.content.subject} · 附件 ${a.content.attachments.map { it.name }}")
            println(Ansi.dim("    " + a.content.body.take(160).replace("\n", " ")))
        }

        println(Ansi.bold(Ansi.cyan("④ 本人最终批准")))
        if (!args.flag("approve")) {
            println(Ansi.dim("  未加 --approve：停在待审批。真实手机上这一步需要指纹/锁屏确认。"))
            return
        }
        for (a in proposed.filter { it.status == ActionStatus.PROPOSED }) {
            core.tasks.approve(a.id, a.contentHash, approver = "owner(sim)")
            val r = core.tasks.execute(a.id) { act ->
                val atts = act.content.attachments.map { att -> app.juiz.core.errand.MailAttachment(att.name.substringAfterLast('/'), File(dir, att.name).readBytes()) }
                core.mailer()!!.send(act.target, act.content.subject.orEmpty(), act.content.body, atts)
            }
            println("  ${if (r is ExecutionOutcome.Done) Ansi.green("已发出") else Ansi.red("未发出")}：$r")
        }
        println(Ansi.dim("  档案：${core.archive.verify().message}"))
    }
}
