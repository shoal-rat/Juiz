package app.juiz.core.archive

import app.juiz.core.util.sha256Hex
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 档案包里的附带文件（录音、交付物），内容由调用方提供。 */
class BundleFile(val path: String, val bytes: ByteArray)

/**
 * 导出不依赖 ChatGPT、也不依赖本应用即可阅读和校验的档案包：
 * timeline.html（人读）、events.jsonl（机读）、files/、MANIFEST.sha256、README.txt。
 */
object ExportBundle {

    fun write(
        out: OutputStream,
        events: List<ArchiveEvent>,
        files: List<BundleFile>,
        ownerName: String,
        zone: ZoneId,
        exportedAt: Instant,
    ) {
        val chain = ArchiveLog.verifyEvents(events)
        val entries = linkedMapOf<String, ByteArray>()
        entries["events.jsonl"] = events.joinToString("\n") { eventJson(it).toString() }.toByteArray()
        entries["timeline.html"] = timelineHtml(events, ownerName, zone, exportedAt, chain).toByteArray()
        files.forEach { entries["files/${it.path}"] = it.bytes }
        entries["README.txt"] = readme(chain, events.lastOrNull(), exportedAt).toByteArray()
        val manifest = entries.entries.joinToString("\n") { (name, bytes) -> "${sha256Hex(bytes)}  $name" } + "\n"
        entries["MANIFEST.sha256"] = manifest.toByteArray()

        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    fun eventJson(e: ArchiveEvent): JsonObject = buildJsonObject {
        put("seq", e.seq)
        put("at", e.at)
        put("type", e.type)
        e.ref?.let { put("ref", it) }
        put("payload", e.payload)
        put("prev_hash", e.prevHash)
        put("hash", e.hash)
    }

    private fun readme(chain: ChainReport, last: ArchiveEvent?, exportedAt: Instant) = """
        Juiz 档案包
        导出时间：$exportedAt
        哈希链校验：${chain.message}
        链头：${last?.let { "seq=${it.seq} hash=${it.hash}" } ?: "（空）"}

        如何自行校验：
        1. 文件完整性：在本目录运行 `shasum -a 256 -c MANIFEST.sha256`（MANIFEST 自身除外）。
        2. 哈希链：对 events.jsonl 的每一行，计算
           SHA-256(prev_hash + "\n" + seq + "\n" + at + "\n" + type + "\n" + ref + "\n" + 规范化(payload))，
           规范化 = 对象键按字典序、无空白的 JSON。结果应等于该行的 hash，且 prev_hash 等于上一行的 hash。
        3. 外部锚点：把你在别处留存的"链头"（每日简报或备份里）与上面的 seq/hash 对照。

        说明：本档案如实记录应用实际取得的信息。ChatGPT Work 内部操作若无法取得，不会出现在这里；
        档案用于提高可追溯性，不承诺任何特定的法律证明力。
    """.trimIndent() + "\n"

    private fun timelineHtml(
        events: List<ArchiveEvent>,
        ownerName: String,
        zone: ZoneId,
        exportedAt: Instant,
        chain: ChainReport,
    ): String {
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(zone)
        val rows = events.joinToString("\n") { e ->
            val summary = describe(e)
            """<tr><td class="n">${e.seq}</td><td class="t">${fmt.format(Instant.ofEpochMilli(e.at))}</td>""" +
                """<td><b>${esc(label(e.type))}</b> <span class="ty">${esc(e.type)}</span>""" +
                (e.ref?.let { """ <span class="ref">${esc(it)}</span>""" } ?: "") +
                """<div>${esc(summary)}</div><code>${e.hash.take(16)}…</code></td></tr>"""
        }
        return """<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(ownerName)} 的助理档案</title>
<style>
body{font:15px/1.6 -apple-system,"PingFang SC","Noto Sans CJK SC",sans-serif;margin:0 auto;max-width:960px;padding:24px 16px;color:#1d1d1f;background:#fbfaf7}
h1{font-size:22px;margin:0 0 4px}.meta{color:#666;margin-bottom:16px}
.ok{color:#1a7f37}.bad{color:#c62828}
table{border-collapse:collapse;width:100%}td{border-top:1px solid #e6e2da;padding:8px 6px;vertical-align:top}
td.n{color:#999;width:48px}td.t{white-space:nowrap;color:#555;width:160px}
.ty{color:#888;font-size:12px}.ref{background:#efe9dd;border-radius:4px;padding:0 6px;font-size:12px}
code{color:#aaa;font-size:11px}
@media (prefers-color-scheme:dark){body{background:#15171a;color:#e8e6e1}td{border-color:#2a2d31}.ref{background:#2a2d31}}
</style></head><body>
<h1>${esc(ownerName)} 的助理档案</h1>
<div class="meta">导出于 ${fmt.format(exportedAt)} · 共 ${events.size} 条 ·
<span class="${if (chain.ok) "ok" else "bad"}">${esc(chain.message)}</span></div>
<table>
$rows
</table>
</body></html>
"""
    }

    private fun label(type: String): String = when (type) {
        "conversation.started" -> "会话开始"
        "conversation.ended" -> "会话结束"
        "turn" -> "对话"
        "tool.called" -> "工具调用"
        "tool.rejected" -> "工具被拒"
        "escalation" -> "通知主人"
        "task.created" -> "创建任务"
        "task.status" -> "任务状态"
        "task.handoff" -> "交接 Work"
        "task.verification" -> "交付物核验"
        "action.proposed" -> "外发提议"
        "action.approved" -> "主人审批"
        "action.status" -> "外发状态"
        "memory.added", "memory.updated", "memory.deleted" -> "记忆变更"
        "consent.changed" -> "同意变更"
        "call.decision" -> "来电决策"
        "capability.report" -> "能力报告"
        "sms.sent", "sms.received" -> "短信"
        else -> type
    }

    private fun describe(e: ArchiveEvent): String {
        val p = e.payload
        fun s(k: String) = p[k]?.toString()?.trim('"')
        return when (e.type) {
            "turn" -> "${s("speaker")}：${s("text")}"
            "task.created" -> "${s("title")}"
            "task.status" -> "${s("from")} → ${s("to")}${s("note")?.let { "（$it）" } ?: ""}"
            "escalation" -> "${s("reason")} ${s("detail") ?: ""}"
            else -> p.entries.take(4).joinToString("；") { (k, v) -> "$k=${v.toString().trim('"').take(80)}" }
        }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
