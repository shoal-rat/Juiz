package app.juiz.core.conversation

import app.juiz.core.archive.ArchiveLog
import app.juiz.core.archive.ConsentLedger
import app.juiz.core.db.JuizDatabase
import app.juiz.core.errand.ErrandService
import app.juiz.core.memory.MemoryService
import app.juiz.core.model.CallerInfo
import app.juiz.core.model.Channel
import app.juiz.core.model.ConsentKind
import app.juiz.core.settings.SettingsStore
import app.juiz.core.util.Clock
import app.juiz.core.util.randomId
import app.juiz.core.util.sha256Hex
import kotlinx.serialization.json.put

data class ConversationSummary(
    val id: String,
    val channel: Channel,
    val number: String,
    val contactName: String?,
    val startedAt: Long,
    val endedAt: Long?,
    val mode: String,
    val outcome: String?,
    val summary: String?,
)

data class TurnRow(val at: Long, val speaker: String, val text: String)

/**
 * 负责会话的生命周期与留痕：
 * - 转写原文进 turn 表（受"转写留存"同意和保留期限约束，可以删除）；
 * - 档案链里只放每句话的哈希承诺，这样按期删除原文不会破坏哈希链，留存的原文也能被证明没改过。
 */
class ConversationService(
    private val db: JuizDatabase,
    private val clock: Clock,
    private val archive: ArchiveLog,
    private val consents: ConsentLedger,
    private val memory: MemoryService,
    private val settings: SettingsStore,
    private val executor: ToolExecutor,
    private val errands: ErrandService? = null,
) {
    private val q get() = db.juizQueries

    fun start(caller: CallerInfo, channel: Channel, mode: String, model: ChatModel): ConversationEngine {
        val id = randomId("C-", 10)
        q.insertConversation(id, channel.name, caller.number, caller.displayName, caller.tier.name, clock.millis(), mode)
        archive.append("conversation.started", id) {
            put("channel", channel.name)
            put("number", caller.number)
            caller.displayName?.let { put("name", it) }
            put("tier", caller.tier.name)
            put("mode", mode)
            put("model", model.id)
        }
        val grant = if (caller.tier == app.juiz.core.model.ContactTier.SPAM) null else errands?.activeGrant(caller.number)
        val files = grant != null && errands!!.canSendFiles(grant)
        if (grant != null) {
            archive.append("errand.grant.active", id) {
                put("grant", grant.id)
                put("files", files)
                put("notes", grant.notes.size)
            }
        }
        val ctx = ConversationContext(id, caller, channel, grant, files)
        val prompt = PromptBuilder.system(settings.ownerProfile(), caller, channel, memory.shareableInCalls(), grant, files)
        return ConversationEngine(model, executor, ctx, prompt, recorderFor(id))
    }

    fun recorderFor(conversationId: String) = TurnRecorder { speaker, text ->
        val now = clock.millis()
        if (consents.isGranted(ConsentKind.TRANSCRIPT_RETENTION)) q.insertTurn(conversationId, now, speaker, text)
        archive.append("turn", conversationId) {
            put("speaker", speaker)
            put("text_sha256", sha256Hex(text))
            put("chars", text.length)
        }
    }

    fun end(conversationId: String, outcome: String, summary: String?) {
        q.endConversation(clock.millis(), outcome, summary, conversationId)
        archive.append("conversation.ended", conversationId) {
            put("outcome", outcome)
            summary?.let { put("summary", it) }
        }
    }

    fun get(id: String): ConversationSummary? = q.selectConversation(id).executeAsOneOrNull()?.let {
        ConversationSummary(it.id, Channel.valueOf(it.channel), it.number, it.contact_name, it.started_at, it.ended_at, it.mode, it.outcome, it.summary)
    }

    fun recent(limit: Long = 50): List<ConversationSummary> = q.selectRecentConversations(limit).executeAsList().map {
        ConversationSummary(it.id, Channel.valueOf(it.channel), it.number, it.contact_name, it.started_at, it.ended_at, it.mode, it.outcome, it.summary)
    }

    fun turns(conversationId: String): List<TurnRow> =
        q.selectTurns(conversationId).executeAsList().map { TurnRow(it.at, it.speaker, it.text) }

    /** 按保留期限删除转写原文（档案链里的哈希承诺不受影响）。 */
    fun purgeTurnsOlderThan(days: Int) {
        q.deleteTurnsBefore(clock.millis() - days * 86_400_000L)
    }

    /** 根据引擎输出拼出一条会话摘要，供主人界面和档案使用。 */
    class SummaryBuilder {
        private val parts = mutableListOf<String>()
        fun add(o: EngineOutput) {
            when (o) {
                is EngineOutput.TaskCreated -> parts += "创建任务 ${o.task.id}「${o.task.title}」"
                is EngineOutput.MessageTaken -> parts += "留言：${o.summary}"
                is EngineOutput.Escalation -> parts += "通知主人：${o.signal.reason.zh}"
                is EngineOutput.SpamMarked -> parts += "标记骚扰"
                else -> Unit
            }
        }
        fun build(): String? = parts.distinct().joinToString("；").ifEmpty { null }
    }
}
