package app.juiz.core.archive

import app.juiz.core.db.Archive_event
import app.juiz.core.db.JuizDatabase
import app.juiz.core.util.Clock
import app.juiz.core.util.JuizJson
import app.juiz.core.util.canonicalJson
import app.juiz.core.util.sha256Hex
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject

const val GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000"

data class ArchiveEvent(
    val seq: Long,
    val at: Long,
    val type: String,
    val ref: String?,
    val payload: JsonObject,
    val prevHash: String,
    val hash: String,
)

@Serializable
data class ArchiveAnchor(val seq: Long, val hash: String, val at: Long)

data class ChainReport(val ok: Boolean, val count: Long, val firstBadSeq: Long?, val message: String)

/**
 * 追加式档案。每条事件的哈希覆盖上一条的哈希，任何一条被改动、删除或插入，
 * 之后的整条链都对不上。同机哈希只能发现"不完整的篡改"，所以还要把链头锚定到手机以外（见 [head]）。
 */
class ArchiveLog(private val db: JuizDatabase, private val clock: Clock) {

    fun append(type: String, ref: String?, payload: JsonObject): ArchiveEvent =
        db.transactionWithResult {
            val last = db.juizQueries.lastEvent().executeAsOneOrNull()
            val seq = (last?.seq ?: 0L) + 1
            val prev = last?.hash ?: GENESIS_HASH
            val at = clock.millis()
            val hash = computeHash(prev, seq, at, type, ref, payload)
            db.juizQueries.insertEvent(seq, at, type, ref, payload.toString(), prev, hash)
            ArchiveEvent(seq, at, type, ref, payload, prev, hash)
        }

    fun append(type: String, ref: String?, build: JsonObjectBuilder.() -> Unit): ArchiveEvent =
        append(type, ref, buildJsonObject(build))

    fun all(): List<ArchiveEvent> = db.juizQueries.selectEvents().executeAsList().map { it.toModel() }

    fun forRef(ref: String): List<ArchiveEvent> =
        db.juizQueries.selectEventsForRef(ref).executeAsList().map { it.toModel() }

    fun recent(limit: Long): List<ArchiveEvent> =
        db.juizQueries.selectRecentEvents(limit).executeAsList().map { it.toModel() }

    fun head(): ArchiveAnchor? = db.juizQueries.lastEvent().executeAsOneOrNull()?.let { ArchiveAnchor(it.seq, it.hash, it.at) }

    fun verify(): ChainReport = verifyEvents(all())

    /** 用外部留存的锚点核对：锚点所在序号的哈希必须与链上一致。 */
    fun verifyAnchor(anchor: ArchiveAnchor): Boolean {
        val events = all()
        if (!verifyEvents(events).ok) return false
        return events.firstOrNull { it.seq == anchor.seq }?.hash == anchor.hash
    }

    companion object {
        fun computeHash(prev: String, seq: Long, at: Long, type: String, ref: String?, payload: JsonObject): String {
            val material = buildString {
                append(prev).append('\n')
                append(seq).append('\n')
                append(at).append('\n')
                append(type).append('\n')
                append(ref ?: "").append('\n')
                append(canonicalJson(payload))
            }
            return sha256Hex(material)
        }

        fun verifyEvents(events: List<ArchiveEvent>): ChainReport {
            var prev = GENESIS_HASH
            var expectedSeq = 1L
            for (e in events) {
                if (e.seq != expectedSeq) return ChainReport(false, events.size.toLong(), e.seq, "序号不连续：期望 $expectedSeq，实际 ${e.seq}")
                if (e.prevHash != prev) return ChainReport(false, events.size.toLong(), e.seq, "第 ${e.seq} 条的前序哈希不匹配")
                val h = computeHash(e.prevHash, e.seq, e.at, e.type, e.ref, e.payload)
                if (h != e.hash) return ChainReport(false, events.size.toLong(), e.seq, "第 ${e.seq} 条内容与哈希不符")
                prev = e.hash
                expectedSeq++
            }
            return ChainReport(true, events.size.toLong(), null, "共 ${events.size} 条，哈希链完整")
        }
    }
}

internal fun Archive_event.toModel() = ArchiveEvent(
    seq = seq,
    at = at,
    type = type,
    ref = ref,
    payload = JuizJson.parseToJsonElement(payload) as JsonObject,
    prevHash = prev_hash,
    hash = hash,
)
