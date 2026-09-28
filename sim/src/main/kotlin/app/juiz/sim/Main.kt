package app.juiz.sim

import app.juiz.core.archive.ExportBundle
import app.juiz.core.conversation.ConversationEngine
import app.juiz.core.conversation.EngineOutput
import app.juiz.core.detox.DetoxRewriter
import app.juiz.core.detox.DigestBuilder
import app.juiz.core.detox.LexiconFilter
import app.juiz.core.model.CallerInfo
import app.juiz.core.model.CapabilityLevel
import app.juiz.core.model.Channel
import app.juiz.core.model.ContactTier
import app.juiz.core.policy.Disclosure
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileOutputStream
import kotlin.system.exitProcess

private const val BANNER = """
     ▄▄▄▄▄                         Juiz · Personal Assistant
       █   █  █  ▀  ▀▀▀█           desktop simulator
       █   █  █  █    ▄▀           仿真结果不代表真机电话链路验收
    ▀▄▄▀   ▀▄▄▀  █  ▄█▄▄
"""

private fun usage(): Nothing {
    println(
        """
        用法：./gradlew :sim:run --args="<命令> [选项]"

          chat     以来电方身份和 Juiz 文字对话
                   --number 13800000000 --name 李女士 --tier KNOWN|UNKNOWN|VIP|SPAM
                   --model scripted|openai|compat [--model-name gpt-6-luna|qwen3.5:9b] [--url http://127.0.0.1:11434/v1]
                   --at 2026-09-29T02:30:00+08:00   固定"现在"的时间（测试深夜代办）
                   --db sim-data/juiz.db           持久化（默认内存）
          eval     运行场景评测集 --model …（scripted 只检验代码层规则）
          detox    去情绪过滤一句话："<原话>" [--model …]
          digest   对转写文件生成去情绪摘要 <file>，每行"对方：…"或"本人：…"
          demo     离线演示完整闭环（来电决策 → 对话 → 任务 → 交接 → 核验 → 深夜代办 → 档案导出）
        """.trimIndent(),
    )
    exitProcess(2)
}

fun main(argv: Array<String>) {
    val args = Args(argv.toList())
    val cmd = args.positional.firstOrNull() ?: usage()
    runBlocking {
        when (cmd) {
            "chat" -> chat(args)
            "eval" -> exitProcess(Scenarios.run(args))
            "detox" -> detox(args)
            "digest" -> digest(args)
            "demo" -> Demo.run()
            else -> usage()
        }
    }
    exitProcess(0)
}

fun printOutput(o: EngineOutput) {
    when (o) {
        is EngineOutput.Speech -> Unit
        is EngineOutput.ToolActivity -> println(Ansi.dim("    ⚙ ${o.name} ${if (o.ok) "✓" else "✗"} ${o.output.take(160)}"))
        is EngineOutput.Escalation -> println(Ansi.amber("    ⚑ 通知主人：${o.signal.reason.zh}（${o.signal.detail}）"))
        is EngineOutput.TaskCreated -> println(Ansi.green("    ＋ 任务 ${o.task.id}「${o.task.title}」${o.task.status.zh}"))
        is EngineOutput.MessageTaken -> println(Ansi.green("    ✉ 留言：${o.summary}"))
        is EngineOutput.SpamMarked -> println(Ansi.amber("    ⊘ 标记骚扰：${o.reason}"))
        is EngineOutput.EndRequested -> println(Ansi.dim("    ⏻ 结束：${o.reason}"))
        is EngineOutput.ModelFailure -> println(Ansi.red("    ! 模型失败：${o.message}"))
    }
}

/** 打印一轮回复：工具与事件单独成行，说出的话接在"Juiz ▸"后面。 */
suspend fun speakTurn(engine: ConversationEngine, text: String, indent: String = "", sink: (EngineOutput) -> Unit = {}): String {
    val said = StringBuilder()
    var open = false
    engine.respond(text) { o ->
        sink(o)
        if (o is EngineOutput.Speech) {
            if (!open) { print(indent + Ansi.cyan("Juiz ▸ ")); open = true }
            print(o.text)
            said.append(o.text)
        } else {
            if (open) { println(); open = false }
            print(indent); printOutput(o)
        }
    }
    if (open) println()
    return said.toString()
}

private suspend fun chat(args: Args) {
    println(Ansi.cyan(BANNER))
    val clock = SimEnv.clock(args["at"])
    val core = SimEnv.core(args["db"]?.let { File(it) }, clock)
    val caller = CallerInfo(args["number"] ?: "17000000000", args["name"], ContactTier.valueOf(args["tier"] ?: "UNKNOWN"))
    val decision = core.ruleEngine().decide(caller, clock.now().atZone(clock.zone()), CapabilityLevel.L1_PRIVILEGED_VOICE, true)
    println(Ansi.dim("来电：${caller.label} · 规则：${decision.ruleId ?: "无"} → ${decision.action.zh}${decision.downgradeReason?.let { "（$it）" } ?: ""}"))
    val model = SimEnv.model(args.modelChoice())
    val engine = core.conversations.start(caller, Channel.TEXT_SIM, "sim", model)
    val greeting = Disclosure.greeting(core.settings.ownerProfile(), recording = false, clonedVoice = false)
    engine.opening(greeting).forEach(::printOutput)
    println(Ansi.cyan("Juiz ▸ ") + greeting)
    println(Ansi.dim("（输入来电方的话；/end 结束，/tasks 查看任务）"))
    val summary = app.juiz.core.conversation.ConversationService.SummaryBuilder()
    while (!engine.context.ended) {
        print(Ansi.bold("来电 ▸ "))
        val line = readlnOrNull()?.trim() ?: break
        when {
            line.isEmpty() -> continue
            line == "/end" -> break
            line == "/tasks" -> {
                core.tasks.all().forEach { println("  ${it.id} ${it.status.zh} ${it.title} ${it.confirmedFields}") }
                continue
            }
        }
        speakTurn(engine, line) { summary.add(it) }
    }
    core.conversations.end(engine.context.conversationId, "sim-ended", summary.build())
    println(Ansi.dim("会话结束 · 摘要：${summary.build() ?: "（无）"} · 档案：${core.archive.verify().message}"))
}

private suspend fun detox(args: Args) {
    val text = args.positional.getOrNull(1) ?: usage()
    val quick = LexiconFilter.filter(text)
    println(Ansi.dim("原话（仅本人可见）：$text"))
    println("词表过滤 ▸ ${quick.calm}   ${Ansi.dim("强度 ${quick.intensity} · 过滤 ${quick.filteredCount} 处 · 期限 ${quick.deadline ?: "-"}")}")
    if (args["model"] != null && args["model"] != "scripted") {
        val line = DetoxRewriter(SimEnv.model(args.modelChoice())).rewrite(text)
        println("模型改写 ▸ ${line.calm}")
        println(Ansi.dim("  要求：${line.requests} · 期限：${line.deadline} · 不满原因：${line.concern} · 强度：${line.intensity}"))
    }
}

private suspend fun digest(args: Args) {
    val file = File(args.positional.getOrNull(1) ?: usage())
    val turns = file.readLines().mapNotNull { l ->
        when {
            l.startsWith("对方：") -> "caller" to l.removePrefix("对方：")
            l.startsWith("本人：") -> "owner" to l.removePrefix("本人：")
            else -> null
        }
    }
    val model = args["model"]?.takeIf { it != "scripted" }?.let { SimEnv.model(args.modelChoice()) }
    val d = DigestBuilder(model).build(turns)
    println(Ansi.bold("去情绪摘要"))
    println(d.summary)
    if (d.action_items.isNotEmpty()) {
        println(Ansi.bold("要做的事"))
        d.action_items.forEach { println("  ☐ ${it.what}${it.due?.let { due -> " · $due" } ?: ""}${it.detail?.let { x -> Ansi.dim(" · $x") } ?: ""}") }
    }
    if (d.concerns.isNotEmpty()) { println(Ansi.bold("对方不满的具体原因")); d.concerns.forEach { println("  · $it") } }
    if (d.commitments.isNotEmpty()) { println(Ansi.bold("你已答应")); d.commitments.forEach { println("  · $it") } }
    println(Ansi.dim("语气强度 ${d.tone_level}/3 ${d.tone_note ?: ""} · 已过滤 ${d.filtered_count} 处情绪化表达"))
}

fun exportArchive(core: app.juiz.core.JuizCore, out: File) {
    out.parentFile?.mkdirs()
    FileOutputStream(out).use {
        ExportBundle.write(it, core.archive.all(), emptyList(), core.settings.ownerProfile().ownerName, core.clock.zone(), core.clock.now())
    }
}
