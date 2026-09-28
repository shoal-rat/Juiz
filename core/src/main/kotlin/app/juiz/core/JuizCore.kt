package app.juiz.core

import app.cash.sqldelight.db.SqlDriver
import app.juiz.core.archive.ArchiveLog
import app.juiz.core.archive.ConsentLedger
import app.juiz.core.conversation.ChatModel
import app.juiz.core.conversation.ConversationService
import app.juiz.core.conversation.ToolExecutor
import app.juiz.core.db.JuizDatabase
import app.juiz.core.detox.DetoxRewriter
import app.juiz.core.detox.DigestBuilder
import app.juiz.core.errand.ErrandService
import app.juiz.core.errand.FileCatalog
import app.juiz.core.errand.GrantStore
import app.juiz.core.errand.Mailer
import app.juiz.core.errand.SmtpMailer
import app.juiz.core.memory.MemoryService
import app.juiz.core.model.ConsentKind
import app.juiz.core.providers.elevenlabs.ElevenLabsTts
import app.juiz.core.providers.openai.CompatChatModel
import app.juiz.core.providers.openai.OpenAiRealtimeStt
import app.juiz.core.providers.openai.OpenAiResponsesModel
import app.juiz.core.providers.openai.OpenAiTts
import app.juiz.core.rules.RuleEngine
import app.juiz.core.settings.LlmProvider
import app.juiz.core.settings.SettingsStore
import app.juiz.core.settings.TtsProvider
import app.juiz.core.tasks.TaskService
import app.juiz.core.util.Clock
import app.juiz.core.util.JuizJson
import app.juiz.core.voice.StreamingStt
import app.juiz.core.voice.TextToSpeech
import app.juiz.core.work.HandoffService
import app.juiz.core.work.WorkspaceAgentsClient
import kotlinx.serialization.Serializable

/** 密钥来源：Android 用 Keystore 加密存储，仿真台用环境变量。密钥从不写进数据库或仓库。 */
interface Secrets {
    fun get(key: String): String?

    companion object {
        const val OPENAI = "OPENAI_API_KEY"
        const val ELEVENLABS = "ELEVENLABS_API_KEY"
        const val WORKSPACE_AGENT = "WORKSPACE_AGENT_TOKEN"
        const val COMPAT = "COMPAT_API_KEY"
        const val SMTP_PASSWORD = "SMTP_PASSWORD"
    }
}

@Serializable
data class SmtpConfig(val host: String = "", val port: Int = 465, val username: String = "", val fromName: String = "Juiz")

class MissingCredential(what: String) : IllegalStateException("缺少凭证：$what")

/**
 * 组合根。Android 应用和桌面仿真台都从这里取服务，保证两边跑的是同一套规则和状态机。
 */
class JuizCore(
    driver: SqlDriver,
    val clock: Clock,
    val secrets: Secrets,
    private val fileCatalog: () -> FileCatalog? = { null },
    /** 与电脑同步的交换目录（juiz-desk 在另一端）。 */
    private val exchangeFolder: () -> app.juiz.core.work.ExchangeFolder? = { null },
    /** 云端执行成品的本地存放目录（应用私有目录）。 */
    private val cloudDir: java.io.File? = null,
    /** 通话录音目录与加密器（Android：no-backup 目录 + Keystore 密钥）。任一为空则不录音。 */
    recordingDir: java.io.File? = null,
    recordingCipher: app.juiz.core.recording.RecordingCipher? = null,
) {
    val db = JuizDatabase(driver)
    val archive = ArchiveLog(db, clock)
    val consents = ConsentLedger(db, clock, archive)
    val settings = SettingsStore(db)
    val memory = MemoryService(db, clock, archive)
    val tasks = TaskService(db, clock, archive)
    val grants = GrantStore(db)
    val recordings: app.juiz.core.recording.RecordingStore? =
        if (recordingDir != null && recordingCipher != null) app.juiz.core.recording.RecordingStore(recordingDir, recordingCipher, archive, clock) else null

    init {
        // 撤销录音同意：已有录音全部删除（删除记入档案）
        consents.onChange { kind, granted -> if (kind == ConsentKind.CALL_RECORDING && !granted) recordings?.deleteAll("撤销录音同意") }
    }

    /** 这一通电话要不要录：有录音库、且本人开了录音同意（开场白会告知对方）。 */
    fun shouldRecordCalls(): Boolean = recordings != null && consents.isGranted(ConsentKind.CALL_RECORDING)

    val errands: ErrandService
        get() = ErrandService(db, clock, archive, tasks, grants, fileCatalog(), mailer(), { settings.ownerProfile().ownerName })

    val executor: ToolExecutor
        get() = ToolExecutor(tasks, memory, archive, { settings.ownerProfile() }, errands, onTaskCreated = ::autoHandoff)

    /**
     * 自动交给电脑上的 Codex：只对通讯录/重要联系人、非回电类任务，每天有上限。
     * Codex 只能产出文件和草稿，任何外发仍要本人批准，所以这一步不需要预先确认。
     */
    fun autoHandoff(task: app.juiz.core.model.Task, caller: app.juiz.core.model.CallerInfo) {
        val w = settings.work()
        if (!w.autoHandoff) return
        if (task.kind == app.juiz.core.model.TaskKind.CALLBACK) return
        if (caller.tier != app.juiz.core.model.ContactTier.KNOWN && caller.tier != app.juiz.core.model.ContactTier.VIP) return
        val day = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd").format(clock.now().atZone(clock.zone()))
        val used = db.juizQueries.getCounter(day, "auto_desk").executeAsOneOrNull() ?: 0
        if (used >= w.autoHandoffDailyLimit) return
        val h = handoff()
        val preferred = runCatching { app.juiz.core.work.HandoffRoute.valueOf(w.autoRoute) }.getOrNull()
        val route = preferred?.takeIf { it in h.availableRoutes }
            ?: h.availableRoutes.firstOrNull { it != app.juiz.core.work.HandoffRoute.MANUAL_SHARE } ?: return
        kotlinx.coroutines.runBlocking { h.handoff(task.id, route) }
        db.transaction {
            db.juizQueries.initCounter(day, "auto_desk")
            db.juizQueries.incrementCounter(day, "auto_desk")
        }
    }

    val conversations: ConversationService
        get() = ConversationService(db, clock, archive, consents, memory, settings, executor, errands)

    fun ruleEngine() = RuleEngine(settings.rules())

    // ---------- 供应商 ----------

    fun chatModel(): ChatModel {
        val p = settings.providers()
        return when (p.llm) {
            LlmProvider.OPENAI_RESPONSES -> OpenAiResponsesModel(
                apiKey = secrets.get(Secrets.OPENAI) ?: throw MissingCredential("OpenAI API Key"),
                model = p.llmModel,
                reasoningEffort = p.reasoningEffort.ifBlank { null },
            )
            LlmProvider.OPENAI_COMPATIBLE_CHAT -> CompatChatModel(
                baseUrl = p.compatBaseUrl.ifBlank { throw MissingCredential("兼容端点地址") },
                model = p.compatModel.ifBlank { throw MissingCredential("兼容端点模型名") },
                apiKey = secrets.get(Secrets.COMPAT),
                extraBody = kotlinx.serialization.json.buildJsonObject {
                    if (p.compatReasoningEffort.isNotBlank()) put("reasoning_effort", kotlinx.serialization.json.JsonPrimitive(p.compatReasoningEffort))
                },
            )
        }
    }

    fun stt(): StreamingStt {
        val p = settings.providers()
        return OpenAiRealtimeStt(
            apiKey = secrets.get(Secrets.OPENAI) ?: throw MissingCredential("OpenAI API Key（转写）"),
            model = p.sttModel,
            languages = p.sttLanguages,
            keywords = p.sttKeywords + listOf(settings.ownerProfile().ownerName),
        )
    }

    /** 克隆音色只有在"使用本人克隆音色"同意有效时才会启用，否则一律用普通合成音色。 */
    fun tts(): TextToSpeech {
        val p = settings.providers()
        val cloned = p.tts == TtsProvider.ELEVENLABS && p.elevenVoiceId.isNotBlank() &&
            consents.isGranted(ConsentKind.CLONED_VOICE) && secrets.get(Secrets.ELEVENLABS) != null
        return if (cloned) {
            ElevenLabsTts(secrets.get(Secrets.ELEVENLABS)!!, p.elevenVoiceId, p.elevenModel)
        } else {
            OpenAiTts(secrets.get(Secrets.OPENAI) ?: throw MissingCredential("OpenAI API Key（合成）"), p.openAiTtsModel, p.openAiVoice)
        }
    }

    fun usingClonedVoice(): Boolean {
        val p = settings.providers()
        return p.tts == TtsProvider.ELEVENLABS && p.elevenVoiceId.isNotBlank() &&
            consents.isGranted(ConsentKind.CLONED_VOICE) && secrets.get(Secrets.ELEVENLABS) != null
    }

    fun handoff(): HandoffService {
        val w = settings.work()
        val token = secrets.get(Secrets.WORKSPACE_AGENT)
        val client = if (w.triggerId.isNotBlank() && !token.isNullOrBlank()) WorkspaceAgentsClient(token, w.triggerId, w.baseUrl) else null
        val openai = secrets.get(Secrets.OPENAI)
        val cloud = if (w.cloudEnabled && !openai.isNullOrBlank() && cloudDir != null) {
            app.juiz.core.work.CloudWorker(openai, w.cloudModel, w.cloudEffort.ifBlank { null })
        } else null
        return HandoffService(tasks, client, w.exchangeFolderHint, if (w.deskEnabled) exchangeFolder() else null, cloud, cloudDir)
    }

    fun smtpConfig(): SmtpConfig =
        settings.raw("smtp")?.let { runCatching { JuizJson.decodeFromString(SmtpConfig.serializer(), it) }.getOrNull() } ?: SmtpConfig()

    fun saveSmtpConfig(c: SmtpConfig) = settings.putRaw("smtp", JuizJson.encodeToString(SmtpConfig.serializer(), c))

    var mailerOverride: Mailer? = null

    fun mailer(): Mailer? {
        mailerOverride?.let { return it }
        val c = smtpConfig()
        val pw = secrets.get(Secrets.SMTP_PASSWORD)
        if (c.host.isBlank() || c.username.isBlank() || pw.isNullOrBlank()) return null
        return SmtpMailer(c.host, c.port, c.username, pw, fromName = c.fromName)
    }

    fun detoxRewriter(): DetoxRewriter? = runCatching { DetoxRewriter(chatModel()) }.getOrNull()

    fun digestBuilder(): DigestBuilder = DigestBuilder(runCatching { chatModel() }.getOrNull())

    /** 应用启动时的例行维护。 */
    fun startupMaintenance() {
        tasks.markInterruptedExecutions()
        conversations.purgeTurnsOlderThan(settings.behavior().transcriptRetentionDays)
        recordings?.purgeOlderThan(settings.behavior().recordingRetentionDays)
    }

    companion object {
        fun createSchema(driver: SqlDriver) = JuizDatabase.Schema.create(driver)
    }
}
