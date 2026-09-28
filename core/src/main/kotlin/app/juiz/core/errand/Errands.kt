package app.juiz.core.errand

import app.juiz.core.archive.ArchiveLog
import app.juiz.core.db.JuizDatabase
import app.juiz.core.model.ActionContent
import app.juiz.core.model.ActionKind
import app.juiz.core.model.AttachmentRef
import app.juiz.core.rules.TimeWindow
import app.juiz.core.tasks.ExecutionOutcome
import app.juiz.core.tasks.TaskService
import app.juiz.core.util.Clock
import app.juiz.core.util.JuizJson
import app.juiz.core.util.normalizeNumber
import app.juiz.core.util.sha256Hex
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.put
import java.io.File
import java.time.format.DateTimeFormatter

@Serializable
data class InfoNote(val title: String, val content: String)

/**
 * 主人事先给某个联系人的"代办授权"。它是一张白名单：
 * - 只能在 windows 时段内使用（例如 22:00–08:00）；
 * - 文件只能来自主人指定的"可外发文件夹"，只能发到这里预先登记的邮箱——通话里对方报的新地址一律不用；
 * - 只能告知 notes 里主人写好的资料；
 * - 每天有次数上限。
 * 来电方无法扩大这张白名单，权限判断全在代码里。
 */
@Serializable
data class ErrandGrant(
    val id: String,
    val contactNumber: String,
    val contactName: String,
    val enabled: Boolean = true,
    val windows: List<TimeWindow>? = null,
    val allowSendFiles: Boolean = true,
    val deliveryEmail: String? = null,
    val maxFilesPerDay: Int = 3,
    val notes: List<InfoNote> = emptyList(),
)

/** 按字二元组重合度检索：「会议地点」也能命中「例会在 3 楼 301 会议室」。 */
fun ErrandGrant.relevantNotes(query: String, minOverlap: Int = 1): List<InfoNote> {
    fun grams(s: String): Set<String> {
        val t = s.lowercase().filter { it.isLetterOrDigit() }
        return if (t.length < 2) setOf(t) else (0 until t.length - 1).map { t.substring(it, it + 2) }.toSet()
    }
    val q = grams(query)
    if (q.isEmpty()) return emptyList()
    return notes.map { n -> n to (grams(n.title + n.content) intersect q).size }
        .filter { it.second >= minOverlap }
        .sortedByDescending { it.second }
        .take(3)
        .map { it.first }
}

data class CatalogFile(val name: String, val bytes: Long, val sha256: String)

/** 可外发文件夹。Android 上由 SAF 授权的目录实现。 */
interface FileCatalog {
    fun list(): List<CatalogFile>
    fun read(name: String): ByteArray?
}

class LocalFileCatalog(private val dir: File) : FileCatalog {
    override fun list(): List<CatalogFile> = dir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") }?.sortedBy { it.name }?.map {
        CatalogFile(it.name, it.length(), sha256Hex(it.readBytes()))
    }.orEmpty()

    override fun read(name: String): ByteArray? {
        val f = File(dir, name)
        return if (f.parentFile?.canonicalPath == dir.canonicalPath && f.isFile) f.readBytes() else null
    }
}

data class MailAttachment(val name: String, val bytes: ByteArray, val mime: String = "application/octet-stream")

interface Mailer {
    val id: String
    /** 返回服务器回执。明确没发出去时抛出 DefinitelyNotSent。 */
    suspend fun send(to: String, subject: String, body: String, attachments: List<MailAttachment>): String
}

class GrantStore(private val db: JuizDatabase) {
    private val ser = ListSerializer(ErrandGrant.serializer())
    fun all(): List<ErrandGrant> = db.juizQueries.getSetting("errand_grants").executeAsOneOrNull()
        ?.let { runCatching { JuizJson.decodeFromString(ser, it) }.getOrNull() }.orEmpty()
    fun save(list: List<ErrandGrant>) = db.juizQueries.putSetting("errand_grants", JuizJson.encodeToString(ser, list))
    fun forNumber(number: String): ErrandGrant? = all().firstOrNull { it.enabled && normalizeNumber(it.contactNumber) == normalizeNumber(number) }
}

/**
 * 在通话/短信里执行代办白名单。发送文件走 TaskService 的外发动作：
 * 以授权 ID 作为审批来源、绑定文件哈希、单次执行、留档。
 */
class ErrandService(
    private val db: JuizDatabase,
    private val clock: Clock,
    private val archive: ArchiveLog,
    private val tasks: TaskService,
    private val grants: GrantStore,
    private val catalog: FileCatalog?,
    private val mailer: Mailer?,
    private val ownerName: () -> String,
) {
    /** 当前时刻对这个号码生效的授权；不在时段内返回 null。 */
    fun activeGrant(number: String): ErrandGrant? {
        val g = grants.forNumber(number) ?: return null
        val now = clock.now().atZone(clock.zone())
        if (g.windows != null && g.windows.none { it.contains(now) }) return null
        return g
    }

    fun canSendFiles(g: ErrandGrant) = g.allowSendFiles && g.deliveryEmail != null && catalog != null && mailer != null

    fun listFiles(g: ErrandGrant, query: String?): List<CatalogFile> {
        if (!canSendFiles(g)) return emptyList()
        val all = catalog!!.list()
        if (query.isNullOrBlank()) return all.take(20)
        val terms = query.lowercase().split(Regex("[\\s，,、]+")).filter { it.isNotBlank() }
        return all.filter { f -> terms.any { f.name.lowercase().contains(it) } }.take(20)
    }

    private fun today(): String = DateTimeFormatter.ofPattern("yyyyMMdd").format(clock.now().atZone(clock.zone()))

    fun sentToday(g: ErrandGrant): Long =
        db.juizQueries.getCounter(today(), "errand_files:${g.id}").executeAsOneOrNull() ?: 0

    sealed interface SendResult {
        data class Sent(val file: String, val maskedTo: String, val receipt: String) : SendResult
        data class Refused(val reason: String) : SendResult
    }

    suspend fun sendFile(g: ErrandGrant, fileName: String, conversationId: String): SendResult {
        if (!canSendFiles(g)) return SendResult.Refused("本人没有为这个号码开启发送文件，或没有配置可外发文件夹/发信账号")
        val to = g.deliveryEmail!!
        val entry = catalog!!.list().firstOrNull { it.name == fileName }
            ?: return SendResult.Refused("可外发文件夹里没有名为「$fileName」的文件，请先用 list_authorized_files 查找准确的文件名")
        if (sentToday(g) >= g.maxFilesPerDay) return SendResult.Refused("今天通过代办发送的文件已达上限（${g.maxFilesPerDay} 个）")
        val bytes = catalog.read(fileName) ?: return SendResult.Refused("文件读取失败")
        if (sha256Hex(bytes) != entry.sha256) return SendResult.Refused("文件在读取过程中发生了变化，已停止发送")

        val subject = "【${ownerName()}】$fileName"
        val body = "您好，这是 ${ownerName()} 的 AI 助理 Juiz 按照您在电话中的要求，发送的文件「$fileName」。\n" +
            "本邮件由 AI 助理根据 ${ownerName()} 事先设定的授权自动发送；如有问题，${ownerName()} 上线后会跟进。"
        val content = ActionContent(subject, body, listOf(AttachmentRef(fileName, entry.sha256, entry.bytes)))
        val action = tasks.proposeAction(null, ActionKind.EMAIL, to, content)
        tasks.approve(action.id, action.contentHash, approver = "standing-grant:${g.id}")
        db.transaction {
            db.juizQueries.initCounter(today(), "errand_files:${g.id}")
            db.juizQueries.incrementCounter(today(), "errand_files:${g.id}")
        }
        val outcome = tasks.execute(action.id) { a ->
            mailer!!.send(a.target, a.content.subject.orEmpty(), a.content.body, listOf(MailAttachment(fileName, bytes, mimeFor(fileName))))
        }
        archive.append("errand.file", conversationId) {
            put("grant", g.id)
            put("file", fileName)
            put("sha256", entry.sha256)
            put("to", to)
            put("action_id", action.id)
            put("outcome", outcome.toString().take(300))
        }
        return when (outcome) {
            is ExecutionOutcome.Done -> SendResult.Sent(fileName, mask(to), outcome.receipt)
            is ExecutionOutcome.Failed -> SendResult.Refused("发送没有成功：${outcome.reason}")
            is ExecutionOutcome.Refused -> SendResult.Refused(outcome.reason)
        }
    }

    fun lookup(g: ErrandGrant, query: String): List<InfoNote> = g.relevantNotes(query)

    companion object {
        /** 至少两个字二元组重合才附给模型，避免把无关资料塞进对话。 */
        fun ErrandGrant.relevantNotesFor(text: String): List<InfoNote> = relevantNotes(text, minOverlap = 2)

        fun mask(email: String): String {
            val at = email.indexOf('@')
            if (at <= 1) return email
            return email.take(1) + "***" + email.substring(at)
        }

        fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "pdf" -> "application/pdf"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "txt", "md" -> "text/plain; charset=utf-8"
            "csv" -> "text/csv; charset=utf-8"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
    }
}
