package app.juiz.core.work

import app.juiz.core.util.JuizJson
import app.juiz.core.util.toHex
import kotlinx.serialization.Serializable
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

@Serializable
data class DeliverableEntry(val path: String, val sha256: String, val bytes: Long)

@Serializable
data class ActionEntry(
    val type: String,
    val target: String? = null,
    val state: String? = null,
    val receipt: String? = null,
    /** email_draft：执行方写好的邮件草稿，需本人批准后由手机发出。 */
    val subject: String? = null,
    val body: String? = null,
    /** 附件为交付物里的路径（相对任务目录），哈希以 deliverables 为准。 */
    val attachments: List<String> = emptyList(),
)

@Serializable
data class ResultManifest(
    val schema: String,
    val task_id: String,
    val status: String,
    val summary: String? = null,
    val deliverables: List<DeliverableEntry> = emptyList(),
    val actions: List<ActionEntry> = emptyList(),
)

@Serializable
data class VerificationReport(
    val ok: Boolean,
    val manifestFound: Boolean,
    val issues: List<String>,
    val verified: List<String>,
    val summary: String? = null,
    val reportedStatus: String? = null,
    val drafts: List<ActionEntry> = emptyList(),
    val deliverables: List<DeliverableEntry> = emptyList(),
)

/** 交换目录：Android 上由 SAF 授权的目录实现，桌面/测试用本地目录。 */
interface ExchangeFolder {
    fun readText(relPath: String): String?
    fun open(relPath: String): InputStream?
    /** 写入文件（自动创建上级目录）。只读实现返回 false。 */
    fun write(relPath: String, bytes: ByteArray): Boolean = false
    fun exists(relPath: String): Boolean = open(relPath)?.use { true } ?: false
}

class LocalExchangeFolder(private val root: File) : ExchangeFolder {
    private fun resolve(rel: String): File? {
        val f = File(root, rel).canonicalFile
        return if (f.path.startsWith(root.canonicalPath)) f else null
    }
    override fun readText(relPath: String): String? = resolve(relPath)?.takeIf { it.isFile }?.readText()
    override fun open(relPath: String): InputStream? = resolve(relPath)?.takeIf { it.isFile }?.inputStream()
    override fun write(relPath: String, bytes: ByteArray): Boolean {
        val f = resolve(relPath) ?: return false
        f.parentFile?.mkdirs()
        val tmp = java.io.File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(bytes)
        return tmp.renameTo(f)
    }
}

/**
 * 结果清单核验。只有清单存在、task_id 一致、每个文件都存在且哈希与大小都匹配，才算通过。
 * 清单里自称 completed 不等于通过；核验不过的任务保持"待核实"，并列出不一致项。
 */
object ResultVerifier {
    fun verify(taskId: String, folder: ExchangeFolder): VerificationReport {
        val base = "$taskId/"
        val text = folder.readText(base + "result.json")
            ?: return VerificationReport(false, false, listOf("没有找到 $taskId/result.json"), emptyList())
        val m = try {
            JuizJson.decodeFromString(ResultManifest.serializer(), text)
        } catch (e: Exception) {
            return VerificationReport(false, true, listOf("result.json 格式错误：${e.message?.take(120)}"), emptyList())
        }
        val issues = mutableListOf<String>()
        val verified = mutableListOf<String>()
        if (m.schema != "juiz.result/v1") issues += "schema 不是 juiz.result/v1（实际：${m.schema}）"
        if (m.task_id != taskId) issues += "task_id 不一致（清单：${m.task_id}）"
        if (m.status != "completed") issues += "清单状态为 ${m.status}"
        if (m.deliverables.isEmpty()) issues += "清单没有列出任何交付物"
        for (d in m.deliverables) {
            if (d.path.contains("..") || d.path.startsWith("/")) {
                issues += "非法路径：${d.path}"
                continue
            }
            val stream = folder.open(base + d.path)
            if (stream == null) {
                issues += "缺少文件：${d.path}"
                continue
            }
            val (hash, size) = stream.use { hashAndSize(it) }
            when {
                size != d.bytes -> issues += "${d.path} 大小不符（清单 ${d.bytes}，实际 $size）"
                !hash.equals(d.sha256, ignoreCase = true) -> issues += "${d.path} 哈希不符"
                else -> verified += d.path
            }
        }
        // 草稿附件必须是已核验的交付物
        val drafts = m.actions.filter { it.type == "email_draft" && !it.target.isNullOrBlank() }
        drafts.forEach { d -> d.attachments.filter { it !in verified }.forEach { issues += "草稿附件「$it」不在已核验的交付物里" } }
        return VerificationReport(issues.isEmpty(), true, issues, verified, m.summary, m.status, drafts, m.deliverables)
    }

    fun hashAndSize(input: InputStream): Pair<String, Long> {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
            total += n
        }
        return md.digest().toHex() to total
    }
}
