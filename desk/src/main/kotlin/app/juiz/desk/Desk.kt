package app.juiz.desk

import app.juiz.core.util.JuizJson
import app.juiz.core.work.ExecutorBrief
import app.juiz.core.work.ManifestBuilder
import app.juiz.core.work.ResultManifest
import app.juiz.core.work.TaskCardData
import kotlinx.serialization.Serializable
import java.io.File
import java.net.InetAddress
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

@Serializable
data class Claim(val host: String, val at: String, val model: String, val pid: Long)


data class DeskConfig(
    val root: File,
    val codex: String,
    val model: String,
    val allowApps: Boolean,
    val timeoutMinutes: Long,
    val dryRun: Boolean,
)

/**
 * juiz-desk：运行在主人电脑上的 Codex 执行器。
 *   手机把任务卡写进交换目录（由 Syncthing/网盘等同步到电脑）→ juiz-desk 领取 → codex exec 在任务目录里干活
 *   → juiz-desk 自己计算交付物哈希、收集邮件草稿、写 result.json → 手机核验 → 本人批准后由手机外发。
 * Codex 在 workspace-write 沙箱里运行，默认关闭 ChatGPT 应用连接器：它能做文件和草稿，但发不出任何东西。
 */
object Desk {
    fun prompt(card: TaskCardData, cardMd: String): String =
        ExecutorBrief.rules("./out", "./drafts") + "\n5. 不要创建或修改 result.json、card.json、claimed.json，这些由 juiz-desk 管理。\n\n" + cardMd.trim().ifEmpty { card.request }

    fun pending(root: File): List<File> = root.listFiles()
        ?.filter { it.isDirectory && File(it, "card.json").isFile && !File(it, "result.json").exists() && !File(it, "claimed.json").exists() }
        ?.sortedBy { it.name }
        .orEmpty()

    fun process(dir: File, cfg: DeskConfig, log: (String) -> Unit): ResultManifest {
        val card = JuizJson.decodeFromString(TaskCardData.serializer(), File(dir, "card.json").readText())
        val cardMd = File(dir, "card.md").takeIf { it.isFile }?.readText() ?: card.request
        File(dir, "claimed.json").writeText(
            JuizJson.encodeToString(Claim.serializer(), Claim(runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("desk"), Instant.now().toString(), cfg.model, ProcessHandle.current().pid())),
        )
        File(dir, "out").mkdirs()
        File(dir, "drafts").mkdirs()
        log("领取 ${card.task_id}「${card.title}」→ codex (${cfg.model})")

        val cmd = buildList {
            add(cfg.codex); add("exec")
            add("--sandbox"); add("workspace-write")
            add("--skip-git-repo-check")
            add("-m"); add(cfg.model)
            add("-C"); add(dir.absolutePath)
            if (!cfg.allowApps) { add("--disable"); add("apps") }
            add(prompt(card, cardMd))
        }
        val exit = if (cfg.dryRun) {
            File(dir, "out/SUMMARY.md").writeText("（dry-run：未调用 Codex）")
            0
        } else {
            val p = ProcessBuilder(cmd).directory(dir).redirectErrorStream(true)
                .redirectOutput(File(dir, "codex.log")).redirectInput(ProcessBuilder.Redirect.from(File("/dev/null"))).start()
            if (!p.waitFor(cfg.timeoutMinutes, TimeUnit.MINUTES)) { p.destroyForcibly(); -1 } else p.exitValue()
        }
        val manifest = buildManifest(dir, card.task_id, exit)
        ManifestBuilder.write(dir, manifest)
        log("完成 ${card.task_id}：${manifest.status}，交付物 ${manifest.deliverables.size} 个，草稿 ${manifest.actions.size} 封")
        return manifest
    }

    /** 哈希由 juiz-desk 计算，不采信模型自己写的任何清单。 */
    fun buildManifest(dir: File, taskId: String, exit: Int): ResultManifest =
        ManifestBuilder.build(dir, taskId, ok = exit == 0, summaryFallback = "codex 退出码 $exit")
}

private fun defaultCodex(): String = listOf(
    "/Applications/ChatGPT.app/Contents/Resources/codex-cli/bin/codex",
    "/Applications/Codex.app/Contents/Resources/codex",
).firstOrNull { File(it).canExecute() } ?: "codex"

fun main(argv: Array<String>) {
    val args = argv.toList()
    if (args.firstOrNull() != "watch" || args.size < 2) {
        println(
            """
            用法：juiz-desk watch <交换目录> [--model gpt-6-sol] [--codex <codex 路径>] [--once] [--interval 20]
                                        [--allow-apps] [--timeout 45] [--dry-run]
              交换目录：与手机同步的文件夹（手机端在「设置 → Work 交接」里选择同一个文件夹）。
              --allow-apps：允许 Codex 使用 ChatGPT 应用连接器（默认关闭；开启后 Codex 可能直接对外操作）。
            """.trimIndent(),
        )
        exitProcess(2)
    }
    fun opt(k: String) = args.indexOf("--$k").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val cfg = DeskConfig(
        root = File(args[1]).absoluteFile,
        codex = opt("codex") ?: defaultCodex(),
        model = opt("model") ?: "gpt-6-sol",
        allowApps = "--allow-apps" in args,
        timeoutMinutes = opt("timeout")?.toLongOrNull() ?: 45,
        dryRun = "--dry-run" in args,
    )
    val interval = opt("interval")?.toLongOrNull() ?: 20
    val once = "--once" in args
    require(cfg.root.isDirectory) { "交换目录不存在：${cfg.root}" }
    val log: (String) -> Unit = { println("[${Instant.now().toString().substring(11, 19)}] $it") }
    log("juiz-desk 监听 ${cfg.root} · 模型 ${cfg.model} · 应用连接器${if (cfg.allowApps) "开启" else "关闭"}")
    while (true) {
        for (dir in Desk.pending(cfg.root)) {
            runCatching { Desk.process(dir, cfg, log) }.onFailure { log("处理 ${dir.name} 失败：${it.message}") }
        }
        if (once) break
        Thread.sleep(interval * 1000)
    }
}
