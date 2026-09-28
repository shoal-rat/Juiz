package app.juiz.sim

import app.juiz.core.conversation.ConversationService
import app.juiz.core.conversation.EngineOutput
import app.juiz.core.conversation.ScriptedChatModel
import app.juiz.core.conversation.ScriptedReply
import app.juiz.core.errand.ErrandGrant
import app.juiz.core.errand.InfoNote
import app.juiz.core.model.CallerInfo
import app.juiz.core.model.Channel
import app.juiz.core.model.ContactTier
import app.juiz.core.model.FactSource
import app.juiz.core.model.OwnerProfile
import app.juiz.core.model.TaskKind
import app.juiz.core.model.TaskStatus
import app.juiz.core.policy.Disclosure
import app.juiz.core.rules.TimeWindow
import app.juiz.core.tasks.NewTask
import app.juiz.core.util.JuizJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.nio.file.Files

@Serializable
data class ScriptStep(val text: String = "", val tools: List<List<String>> = emptyList())

@Serializable
data class Expectation(
    val tools_called: List<String> = emptyList(),
    val tools_not_called: List<String> = emptyList(),
    val tool_order: List<String> = emptyList(),
    val tasks_created: Int? = null,
    val escalations_any: List<String> = emptyList(),
    val speech_must_not_contain: List<String> = emptyList(),
    val speech_must_contain_any: List<String> = emptyList(),
    /** 所有发出的邮件都必须在这个名单里（可以一封都不发）。 */
    val mail_to: List<String>? = null,
    val mail_count: Int? = null,
    /** 第 n 轮结束时还不能有任务（关键信息要等对方确认后才能落成任务）。 */
    val no_task_after_turn: Int? = null,
)

@Serializable
data class Scenario(
    val id: String,
    val title: String,
    val number: String = "17000000000",
    val name: String? = null,
    val tier: String = "UNKNOWN",
    val at: String? = null,
    /** 预置：给该号码开启代办授权。 */
    val boss_grant: Boolean = false,
    /** 预置：该号码有一个"Work 已报告完成、待核实"的任务。 */
    val pending_verification_task: Boolean = false,
    /** 预置：一条已确认但不允许在通话中引用的私人记录。 */
    val private_fact: String? = null,
    val turns: List<String>,
    val expect: Expectation,
    val script: List<ScriptStep> = emptyList(),
)

data class CheckResult(val scenario: String, val check: String, val ok: Boolean, val detail: String)

/**
 * 场景评测。scripted 模式下模型回复来自场景里写好的脚本，只能检验代码层的闸门和状态机；
 * 用真实模型（openai / compat）时才是在检验"模型 + 规则"的整体行为。两者都不是真机验收。
 */
object Scenarios {
    /**
     * dev：调提示词时反复使用的场景；test：留出的测试集，措辞和情境都不同，
     * 只在调好之后跑一次用来报告，不根据它的失败项去改提示词。
     */
    fun load(set: String = "dev"): List<Scenario> {
        val file = if (set == "test") "/scenarios/test_heldout.json" else "/scenarios/scenarios.json"
        val text = Scenarios::class.java.getResourceAsStream(file)!!.bufferedReader().readText()
        return JuizJson.decodeFromString(ListSerializer(Scenario.serializer()), text)
    }

    suspend fun run(args: Args): Int {
        val choice = args.modelChoice()
        val only = args["only"]
        val set = args["set"] ?: "dev"
        val scenarios = load(set).filter { only == null || it.id == only }
        println(Ansi.bold("Juiz 场景评测 · ${if (set == "test") "留出测试集" else "开发集"} · 模型：${choice.kind}${choice.model?.let { " ($it)" } ?: ""} · ${scenarios.size} 个场景"))
        if (choice.kind == "scripted") println(Ansi.dim("scripted 模式：模型回复由脚本给出，只检验代码层规则，不代表真实模型表现。"))
        val repeat = (args["repeat"]?.toIntOrNull() ?: 1).coerceIn(1, 10)
        val results = mutableListOf<CheckResult>()
        val perRun = mutableListOf<Pair<Int, Int>>()
        repeat(repeat) { r ->
            if (repeat > 1) println(Ansi.bold("\n════ 第 ${r + 1}/$repeat 轮 ════"))
            val before = results.size
            for (s in scenarios) results += runOne(s, args)
            val run = results.subList(before, results.size)
            perRun += run.count { it.ok } to run.size
        }
        val passed = results.count { it.ok }
        println()
        if (repeat > 1) println(Ansi.bold("各轮：" + perRun.joinToString("  ") { "${it.first}/${it.second}" }))
        println(Ansi.bold("合计：$passed/${results.size} 项检查通过（${"%.1f".format(passed * 100.0 / results.size)}%）"))
        // 按检查项汇总不稳定项
        val flaky = results.groupBy { it.scenario + " · " + it.check }.filter { (_, v) -> v.any { !it.ok } }
        if (flaky.isNotEmpty()) {
            println(Ansi.bold("未全部通过的检查："))
            flaky.forEach { (k, v) -> println("  ${v.count { it.ok }}/${v.size}  $k") }
        }
        val report = File(SimEnv.dataDir, "eval-$set-${choice.kind}-${System.currentTimeMillis()}.json")
        report.parentFile.mkdirs()
        report.writeText(results.joinToString(",\n", "[\n", "\n]") {
            """  {"scenario":"${it.scenario}","check":${JuizJson.encodeToString(kotlinx.serialization.serializer<String>(), it.check)},"ok":${it.ok},"detail":${JuizJson.encodeToString(kotlinx.serialization.serializer<String>(), it.detail)}}"""
        })
        println(Ansi.dim("报告：${report.path}"))
        return if (passed == results.size) 0 else 1
    }

    private suspend fun runOne(s: Scenario, args: Args): List<CheckResult> {
        val outbox = Files.createTempDirectory("juiz-outbox").toFile()
        val files = Files.createTempDirectory("juiz-files").toFile().also {
            File(it, "Q3 周报.xlsx").writeBytes("xlsx".toByteArray())
            File(it, "产品手册.pdf").writeBytes("pdf".toByteArray())
        }
        val core = SimEnv.core(null, SimEnv.clock(s.at ?: "2026-09-28T14:00:00+08:00"), outbox, files)
        core.settings.saveOwnerProfile(OwnerProfile(ownerName = "张三", publicStatus = "今天在外地出差，晚上八点后方便回电。"))
        if (s.boss_grant) {
            core.grants.save(
                listOf(
                    ErrandGrant(
                        "G-boss", s.number, s.name ?: "领导", windows = listOf(TimeWindow(start = "22:00", end = "08:00")),
                        deliveryEmail = "wang@corp.example", notes = listOf(InfoNote("例会", "周一例会在 3 楼 301 会议室，上午九点半开始。")),
                    ),
                ),
            )
        }
        if (s.pending_verification_task) {
            val t = core.tasks.create(NewTask("Q3 合作方案", "做一份 Q3 合作方案", TaskKind.DOCUMENT, s.number, s.name, null))
            core.tasks.transition(t.id, TaskStatus.HANDED_OFF)
            core.tasks.transition(t.id, TaskStatus.NEEDS_VERIFICATION, "Work 报告完成")
        }
        s.private_fact?.let { core.memory.add("张三的日程", it, FactSource.OWNER, null, shareable = false) }

        val scripted = ScriptedChatModel.sequence(*s.script.map { st -> ScriptedReply(st.text, st.tools.map { it[0] to it[1] }) }.toTypedArray())
        val model = SimEnv.model(args.modelChoice()) { scripted }
        val caller = CallerInfo(s.number, s.name, ContactTier.valueOf(s.tier))
        val engine = core.conversations.start(caller, Channel.VOICE, "eval", model)
        val greeting = Disclosure.greeting(core.settings.ownerProfile(), recording = false, clonedVoice = false)
        val outputs = mutableListOf<EngineOutput>()
        outputs += engine.opening(greeting)

        println()
        println(Ansi.bold("▌${s.id} · ${s.title}"))
        println(Ansi.cyan("  Juiz ▸ ") + greeting)
        val speech = StringBuilder()
        val tasksAfterTurn = mutableListOf<Int>()
        for (turn in s.turns) {
            println(Ansi.bold("  来电 ▸ ") + turn)
            val said = StringBuilder()
            engine.respond(turn) { o ->
                outputs += o
                if (o is EngineOutput.Speech) said.append(o.text)
            }
            speech.append(said).append('\n')
            tasksAfterTurn += core.tasks.all().count { it.kind != TaskKind.CALLBACK && it.contactNumber == s.number && !s.pending_verification_task }
            println(Ansi.cyan("  Juiz ▸ ") + said)
            if (engine.context.ended) break
        }
        outputs.filter { it !is EngineOutput.Speech }.forEach { print("  "); printOutput(it) }
        core.conversations.end(engine.context.conversationId, "eval", ConversationService.SummaryBuilder().also { b -> outputs.forEach(b::add) }.build())

        val calls = outputs.filterIsInstance<EngineOutput.ToolActivity>()
        val okCalls = calls.filter { it.ok }.map { it.name }
        val allCalls = calls.map { it.name }
        val checks = mutableListOf<CheckResult>()
        fun check(name: String, ok: Boolean, detail: String = "") { checks += CheckResult(s.id, name, ok, detail) }

        val e = s.expect
        e.tools_called.forEach { t -> check("调用了 $t", t in okCalls, "实际成功调用：$okCalls") }
        e.tools_not_called.forEach { t -> check("没有调用 $t", t !in okCalls, "实际成功调用：$okCalls") }
        if (e.tool_order.isNotEmpty()) {
            val idx = e.tool_order.map { okCalls.indexOf(it) }
            check("工具顺序 ${e.tool_order.joinToString(" → ")}", idx.none { it < 0 } && idx == idx.sorted(), "实际：$allCalls")
        }
        e.no_task_after_turn?.let { n -> check("第 $n 轮（确认前）没有创建任务", (tasksAfterTurn.getOrNull(n - 1) ?: 0) == 0, "各轮后任务数 $tasksAfterTurn") }
        e.tasks_created?.let { n ->
            val created = outputs.count { it is EngineOutput.TaskCreated && it.task.kind != TaskKind.CALLBACK }
            check("创建任务数 = $n", created == n, "实际 $created")
        }
        if (e.escalations_any.isNotEmpty()) {
            val got = outputs.filterIsInstance<EngineOutput.Escalation>().map { it.signal.reason.name }
            check("升级原因包含 ${e.escalations_any}", got.any { it in e.escalations_any }, "实际 $got")
        }
        val spoken = speech.toString()
        e.speech_must_not_contain.forEach { w -> check("没有说「$w」", w !in spoken) }
        if (e.speech_must_contain_any.isNotEmpty()) check("说到了 ${e.speech_must_contain_any} 之一", e.speech_must_contain_any.any { it in spoken })
        val sent = outbox.listFiles()?.map { it.readText().lineSequence().first().removePrefix("To: ") }.orEmpty()
        e.mail_to?.let { expected -> check("邮件只发往 $expected", sent.all { it in expected }, "实际 $sent") }
        e.mail_count?.let { n -> check("发出 $n 封邮件", sent.size == n, "实际 ${sent.size}") }
        check("档案哈希链完整", core.archive.verify().ok)
        checks.forEach { r -> println("  ${if (r.ok) Ansi.green("✓") else Ansi.red("✗")} ${r.check}${if (!r.ok && r.detail.isNotEmpty()) Ansi.dim("  ${r.detail}") else ""}") }
        return checks
    }
}
