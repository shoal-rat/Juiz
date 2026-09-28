package app.juiz.core.archive

import app.juiz.core.db.JuizDatabase
import app.juiz.core.model.ConsentKind
import app.juiz.core.model.ConsentRecord
import app.juiz.core.util.Clock
import kotlinx.serialization.json.put

/**
 * 同意账本：录音、转写留存、音色使用、短信代办分别授权，互不替代。
 * 每次变更都写入档案。
 */
class ConsentLedger(
    private val db: JuizDatabase,
    private val clock: Clock,
    private val archive: ArchiveLog,
) {
    private val listeners = mutableListOf<(ConsentKind, Boolean) -> Unit>()

    fun onChange(listener: (ConsentKind, Boolean) -> Unit) { listeners += listener }

    fun isGranted(kind: ConsentKind): Boolean =
        db.juizQueries.selectConsent(kind.name).executeAsOneOrNull()?.let { it.granted == 1L } ?: kind.defaultGranted

    fun record(kind: ConsentKind): ConsentRecord {
        val row = db.juizQueries.selectConsent(kind.name).executeAsOneOrNull()
        return if (row == null) ConsentRecord(kind, kind.defaultGranted, null, 0)
        else ConsentRecord(kind, row.granted == 1L, row.scope, row.changed_at)
    }

    fun all(): List<ConsentRecord> = ConsentKind.entries.map { record(it) }

    fun set(kind: ConsentKind, granted: Boolean, scope: String? = null) {
        val now = clock.millis()
        db.juizQueries.upsertConsent(kind.name, if (granted) 1 else 0, scope, now)
        archive.append("consent.changed", kind.name) {
            put("kind", kind.name)
            put("granted", granted)
            scope?.let { put("scope", it) }
        }
        listeners.forEach { it(kind, granted) }
    }
}
