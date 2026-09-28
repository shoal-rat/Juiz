package app.juiz.sim

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.juiz.core.JuizCore
import app.juiz.core.Secrets
import app.juiz.core.conversation.ChatModel
import app.juiz.core.conversation.ScriptedChatModel
import app.juiz.core.conversation.ScriptedReply
import app.juiz.core.errand.LocalFileCatalog
import app.juiz.core.errand.MailAttachment
import app.juiz.core.errand.Mailer
import app.juiz.core.providers.openai.CompatChatModel
import app.juiz.core.providers.openai.OpenAiResponsesModel
import app.juiz.core.util.Clock
import app.juiz.core.util.FixedClock
import app.juiz.core.util.SystemClock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.OffsetDateTime

/** 终端颜色：只在交互终端里使用。 */
object Ansi {
    private val on = System.console() != null && System.getenv("NO_COLOR") == null
    fun c(code: String, s: String) = if (on) "\u001b[${code}m$s\u001b[0m" else s
    fun cyan(s: String) = c("36", s)
    fun dim(s: String) = c("2", s)
    fun amber(s: String) = c("33", s)
    fun red(s: String) = c("31", s)
    fun green(s: String) = c("32", s)
    fun bold(s: String) = c("1", s)
}

object EnvSecrets : Secrets {
    override fun get(key: String): String? = System.getenv(key)?.takeIf { it.isNotBlank() }
}

/** 仿真用的"发信"：不真的发出去，把邮件写进 outbox 目录。 */
class OutboxMailer(private val dir: File) : Mailer {
    override val id = "sim-outbox"
    override suspend fun send(to: String, subject: String, body: String, attachments: List<MailAttachment>): String {
        dir.mkdirs()
        val f = File(dir, "${System.currentTimeMillis()}-${to.replace('@', '_')}.txt")
        f.writeText("To: $to\nSubject: $subject\nAttachments: ${attachments.joinToString { "${it.name} (${it.bytes.size} B)" }}\n\n$body\n")
        return "SIM-OUTBOX ${f.name}"
    }
}

data class ModelChoice(val kind: String, val model: String?, val url: String?)

object SimEnv {
    val dataDir = File("sim-data")

    fun clock(at: String?): Clock = if (at == null) SystemClock else FixedClock(OffsetDateTime.parse(at).toInstant())

    fun core(dbFile: File?, clock: Clock, outboxDir: File = File(dataDir, "outbox"), catalogDir: File? = File(dataDir, "outbox-files")): JuizCore {
        val url = if (dbFile == null) JdbcSqliteDriver.IN_MEMORY else "jdbc:sqlite:${dbFile.absolutePath}"
        dbFile?.parentFile?.mkdirs()
        val fresh = dbFile == null || !dbFile.exists()
        val driver = JdbcSqliteDriver(url)
        if (fresh) JuizCore.createSchema(driver)
        val core = JuizCore(driver, clock, EnvSecrets, fileCatalog = { catalogDir?.takeIf { it.isDirectory }?.let { LocalFileCatalog(it) } })
        core.mailerOverride = OutboxMailer(outboxDir)
        return core
    }

    /**
     * 选择模型：
     *  scripted  —— 不联网的脚本模型（只检验代码层规则）
     *  openai    —— Responses API，需要 OPENAI_API_KEY
     *  compat    —— 兼容 Chat Completions 的端点，默认本机 ollama
     */
    fun model(choice: ModelChoice, scripted: () -> ScriptedChatModel = { ScriptedChatModel.sequence(ScriptedReply("好的，我记下了。")) }): ChatModel =
        when (choice.kind) {
            "scripted" -> scripted()
            "openai" -> OpenAiResponsesModel(
                EnvSecrets.get(Secrets.OPENAI) ?: error("需要环境变量 OPENAI_API_KEY"),
                choice.model ?: "gpt-6-luna",
                "none",
            )
            "compat" -> CompatChatModel(
                choice.url ?: "http://127.0.0.1:11434/v1",
                choice.model ?: "qwen3.5:9b",
                EnvSecrets.get(Secrets.COMPAT),
                // ollama：关闭思考过程，降低延迟
                extraBody = buildJsonObject { put("reasoning_effort", "none") },
            )
            else -> error("未知模型类型：${choice.kind}（可选 scripted / openai / compat）")
        }
}

/** 极简参数解析：--key value 与 --flag。 */
class Args(argv: List<String>) {
    val positional = mutableListOf<String>()
    private val opts = mutableMapOf<String, String>()

    init {
        var i = 0
        while (i < argv.size) {
            val a = argv[i]
            if (a.startsWith("--")) {
                val k = a.removePrefix("--")
                if (i + 1 < argv.size && !argv[i + 1].startsWith("--")) {
                    opts[k] = argv[i + 1]; i += 2
                } else {
                    opts[k] = "true"; i++
                }
            } else {
                positional += a; i++
            }
        }
    }

    operator fun get(k: String): String? = opts[k]
    fun flag(k: String) = opts[k] == "true"

    fun modelChoice() = ModelChoice(opts["model"] ?: "scripted", opts["model-name"], opts["url"])
}
