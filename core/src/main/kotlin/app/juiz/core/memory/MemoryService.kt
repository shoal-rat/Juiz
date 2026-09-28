package app.juiz.core.memory

import app.juiz.core.archive.ArchiveLog
import app.juiz.core.db.JuizDatabase
import app.juiz.core.db.Memory_fact
import app.juiz.core.model.FactSource
import app.juiz.core.model.FactStatus
import app.juiz.core.model.MemoryFact
import app.juiz.core.util.Clock
import app.juiz.core.util.randomId
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.put
import app.juiz.core.util.JuizJson

@Serializable
data class FactExport(
    val id: String,
    val subject: String,
    val text: String,
    val source: String,
    val sourceRef: String?,
    val status: String,
    val shareableInCalls: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * 手机端只保留必要、可控的记录。来电方提供的信息一律先存为 PENDING，
 * 主人确认后才生效；只有标记为"通话中可引用"的已确认记录才会进入通话提示词。
 */
class MemoryService(
    private val db: JuizDatabase,
    private val clock: Clock,
    private val archive: ArchiveLog,
) {
    private val q get() = db.juizQueries

    fun add(subject: String, text: String, source: FactSource, sourceRef: String?, shareable: Boolean = false): MemoryFact {
        val now = clock.millis()
        val id = randomId("M-", 10)
        val status = if (source == FactSource.OWNER) FactStatus.CONFIRMED else FactStatus.PENDING
        q.insertFact(id, subject, text, source.name, sourceRef, status.name, if (shareable && status == FactStatus.CONFIRMED) 1 else 0, now, now)
        archive.append("memory.added", id) {
            put("subject", subject)
            put("text", text)
            put("source", source.name)
            put("status", status.name)
            sourceRef?.let { put("source_ref", it) }
        }
        return get(id)!!
    }

    fun get(id: String): MemoryFact? = q.selectFact(id).executeAsOneOrNull()?.toModel()

    fun all(): List<MemoryFact> = q.selectFacts().executeAsList().map { it.toModel() }

    fun pending(): List<MemoryFact> = all().filter { it.status == FactStatus.PENDING }

    fun shareableInCalls(): List<MemoryFact> = q.selectShareableFacts().executeAsList().map { it.toModel() }

    fun update(id: String, subject: String, text: String, status: FactStatus, shareable: Boolean): MemoryFact {
        val cur = get(id) ?: throw NoSuchElementException(id)
        // 未确认的记录不能被标记为可在通话中引用
        val share = shareable && status == FactStatus.CONFIRMED
        q.updateFact(subject, text, status.name, if (share) 1 else 0, clock.millis(), id)
        archive.append("memory.updated", id) {
            put("from_status", cur.status.name)
            put("to_status", status.name)
            put("text", text)
            put("shareable", share)
        }
        return get(id)!!
    }

    fun confirm(id: String, shareable: Boolean = false) = get(id)?.let { update(id, it.subject, it.text, FactStatus.CONFIRMED, shareable) }

    fun reject(id: String) = get(id)?.let { update(id, it.subject, it.text, FactStatus.REJECTED, false) }

    fun delete(id: String) {
        val cur = get(id) ?: return
        q.deleteFact(id)
        // 删除事件只保留被删内容的哈希，不在档案里留下原文
        archive.append("memory.deleted", id) { put("text_sha256", app.juiz.core.util.sha256Hex(cur.text)) }
    }

    fun exportJson(): String = JuizJson.encodeToString(
        ListSerializer(FactExport.serializer()),
        all().map { FactExport(it.id, it.subject, it.text, it.source.name, it.sourceRef, it.status.name, it.shareableInCalls, it.createdAt, it.updatedAt) },
    )

    private fun Memory_fact.toModel() = MemoryFact(
        id = id,
        subject = subject,
        text = text,
        source = FactSource.valueOf(source),
        sourceRef = source_ref,
        status = FactStatus.valueOf(status),
        shareableInCalls = shareable_in_calls == 1L,
        createdAt = created_at,
        updatedAt = updated_at,
    )
}
