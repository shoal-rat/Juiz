package app.juiz.core.tasks

import app.juiz.core.archive.ArchiveLog
import app.juiz.core.db.JuizDatabase
import app.juiz.core.db.Outbound_action
import app.juiz.core.model.ActionContent
import app.juiz.core.model.ActionKind
import app.juiz.core.model.ActionStatus
import app.juiz.core.model.OutboundAction
import app.juiz.core.model.Task
import app.juiz.core.model.TaskKind
import app.juiz.core.model.TaskStatus
import app.juiz.core.util.Clock
import app.juiz.core.util.JuizJson
import app.juiz.core.util.canonicalJson
import app.juiz.core.util.randomId
import app.juiz.core.util.sha256Hex
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.time.format.DateTimeFormatter

class IllegalTransition(val from: TaskStatus, val to: TaskStatus) :
    IllegalStateException("任务状态不能从 ${from.zh} 变为 ${to.zh}")

data class NewTask(
    val title: String,
    val request: String,
    val kind: TaskKind,
    val contactNumber: String?,
    val contactName: String?,
    val conversationId: String?,
    val confirmedFields: Map<String, String> = emptyMap(),
    val deliverable: String? = null,
    val due: String? = null,
    val note: String? = null,
)

/** 外发动作执行结果。UNKNOWN 表示"不知道到底发没发出去"，只能由主人核实，绝不自动重试。 */
sealed interface ExecutionOutcome {
    data class Done(val receipt: String) : ExecutionOutcome
    data class Failed(val reason: String) : ExecutionOutcome
    data class Refused(val reason: String) : ExecutionOutcome
}

/** 执行器明确知道没有发出去时抛出它，任务标为 FAILED 而不是 UNKNOWN。 */
class DefinitelyNotSent(message: String) : Exception(message)

class TaskService(
    private val db: JuizDatabase,
    private val clock: Clock,
    private val archive: ArchiveLog,
) {
    private val q get() = db.juizQueries
    private val fieldsSerializer = MapSerializer(String.serializer(), String.serializer())

    fun create(t: NewTask): Task = db.transactionWithResult {
        val now = clock.millis()
        val day = DateTimeFormatter.ofPattern("yyyyMMdd").format(clock.now().atZone(clock.zone()))
        val n = q.countTasksWithIdPrefix("T-$day-%").executeAsOne() + 1
        val id = "T-$day-" + n.toString().padStart(4, '0')
        q.insertTask(
            id, now, now, TaskStatus.PENDING_CONFIRMATION.name, t.title, t.request, t.kind.name,
            t.contactNumber, t.contactName, t.conversationId,
            JuizJson.encodeToString(fieldsSerializer, t.confirmedFields),
            t.deliverable, t.due, t.note,
        )
        archive.append("task.created", id) {
            put("title", t.title)
            put("request", t.request)
            put("kind", t.kind.name)
            t.contactNumber?.let { put("contact", it) }
            t.conversationId?.let { put("conversation", it) }
            put("confirmed_fields", JuizJson.encodeToJsonElement(fieldsSerializer, t.confirmedFields))
        }
        get(id)!!
    }

    fun get(id: String): Task? = q.selectTask(id).executeAsOneOrNull()?.toModel()

    fun all(): List<Task> = q.selectAllTasks().executeAsList().map { it.toModel() }

    fun withStatus(vararg s: TaskStatus): List<Task> =
        q.selectTasksWithStatus(s.map { it.name }).executeAsList().map { it.toModel() }

    fun forContact(number: String, limit: Long = 10): List<Task> =
        q.selectTasksForContact(number, limit).executeAsList().map { it.toModel() }

    /** 状态迁移在事务里先读后写，非法迁移直接抛出，不依赖调用方自觉。 */
    fun transition(id: String, to: TaskStatus, note: String? = null): Task = db.transactionWithResult {
        val cur = get(id) ?: throw NoSuchElementException("任务不存在：$id")
        if (cur.status == to) return@transactionWithResult cur
        if (!TaskStatus.canTransition(cur.status, to)) throw IllegalTransition(cur.status, to)
        q.setTaskStatus(to.name, clock.millis(), note, id)
        archive.append("task.status", id) {
            put("from", cur.status.name)
            put("to", to.name)
            note?.let { put("note", it) }
        }
        get(id)!!
    }

    /**
     * 主人亲自核对后标记完成（回电、留言类任务，或主人手动核对了交付物）。
     * 这是状态机之外唯一的完成入口，必须写明核对说明，并记入档案。
     */
    fun completeByOwner(id: String, note: String): Task = db.transactionWithResult {
        val cur = get(id) ?: throw NoSuchElementException("任务不存在：$id")
        require(note.isNotBlank()) { "请写明核对说明" }
        if (cur.status.terminal) return@transactionWithResult cur
        q.setTaskStatus(TaskStatus.COMPLETED.name, clock.millis(), "主人核对：$note", id)
        archive.append("task.status", id) {
            put("from", cur.status.name)
            put("to", TaskStatus.COMPLETED.name)
            put("note", "主人核对：$note")
            put("by", "owner")
        }
        get(id)!!
    }

    fun recordHandoff(id: String, route: String, attempt: Int, runId: String?, conversationUrl: String?) {
        q.setTaskHandoff(route, attempt.toLong(), runId, conversationUrl, clock.millis(), id)
        archive.append("task.handoff", id) {
            put("route", route)
            put("attempt", attempt)
            runId?.let { put("run_id", it) }
            conversationUrl?.let { put("conversation_url", it) }
        }
    }

    fun recordResult(id: String, summary: String?, verificationJson: String) {
        q.setTaskResult(summary, verificationJson, clock.millis(), id)
        archive.append("task.verification", id) {
            summary?.let { put("summary", it) }
            put("verification", JuizJson.parseToJsonElement(verificationJson))
        }
    }

    // ---------- 外发动作 ----------

    fun proposeAction(taskId: String?, kind: ActionKind, target: String, content: ActionContent): OutboundAction {
        val now = clock.millis()
        val id = randomId("A-")
        val hash = contentHash(kind, target, content)
        q.insertAction(
            id, taskId, kind.name, target, JuizJson.encodeToString(ActionContent.serializer(), content), hash,
            ActionStatus.PROPOSED.name, "$id:$hash", now, now,
        )
        archive.append("action.proposed", taskId ?: id) {
            put("action_id", id)
            put("kind", kind.name)
            put("target", target)
            put("content_hash", hash)
        }
        return action(id)!!
    }

    fun action(id: String): OutboundAction? = q.selectAction(id).executeAsOneOrNull()?.toModel()

    fun actionsForTask(taskId: String): List<OutboundAction> = q.selectActionsForTask(taskId).executeAsList().map { it.toModel() }

    fun actionsWithStatus(vararg s: ActionStatus): List<OutboundAction> =
        q.selectActionsWithStatus(s.map { it.name }).executeAsList().map { it.toModel() }

    /**
     * 主人审批。调用方必须传入主人在界面上看到的内容哈希；
     * 如果内容在展示后被改过，哈希对不上，审批失败。
     */
    fun approve(actionId: String, contentHashShownToOwner: String, approver: String = "owner"): OutboundAction =
        db.transactionWithResult {
            val a = action(actionId) ?: throw NoSuchElementException("动作不存在：$actionId")
            check(a.status == ActionStatus.PROPOSED) { "只有待审批的动作可以批准（当前：${a.status}）" }
            val recomputed = contentHash(a.kind, a.target, a.content)
            check(recomputed == a.contentHash && recomputed == contentHashShownToOwner) { "内容已变化，原审批无效，请重新查看后审批" }
            val now = clock.millis()
            val approvalHash = sha256Hex("$recomputed|$approver|$now")
            q.approveAction(approvalHash, now, now, actionId)
            archive.append("action.approved", a.taskId ?: actionId) {
                put("action_id", actionId)
                put("content_hash", recomputed)
                put("approval_hash", approvalHash)
                put("approver", approver)
            }
            action(actionId)!!
        }

    fun revoke(actionId: String) = setActionStatus(actionId, ActionStatus.REVOKED, null, setOf(ActionStatus.PROPOSED, ActionStatus.APPROVED))

    /**
     * 单次执行：先在事务里把 APPROVED 改成 EXECUTING，改不成功（已执行、正在执行、未批准）就拒绝。
     * 执行器抛出 [DefinitelyNotSent] → FAILED；抛出其他异常 → UNKNOWN（可能已经发出，不自动重试）。
     */
    suspend fun execute(actionId: String, executor: suspend (OutboundAction) -> String): ExecutionOutcome {
        val claimed: OutboundAction? = db.transactionWithResult {
            val a = action(actionId)
            if (a == null || a.status != ActionStatus.APPROVED) return@transactionWithResult null
            if (contentHash(a.kind, a.target, a.content) != a.contentHash) return@transactionWithResult null
            q.setActionStatus(ActionStatus.EXECUTING.name, null, clock.millis(), actionId)
            a
        }
        if (claimed == null) {
            val cur = action(actionId)
            return ExecutionOutcome.Refused("动作不可执行（当前状态：${cur?.status ?: "不存在"}）")
        }
        return try {
            val receipt = executor(claimed)
            setActionStatus(actionId, ActionStatus.DONE, receipt, setOf(ActionStatus.EXECUTING))
            ExecutionOutcome.Done(receipt)
        } catch (e: DefinitelyNotSent) {
            setActionStatus(actionId, ActionStatus.FAILED, e.message, setOf(ActionStatus.EXECUTING))
            ExecutionOutcome.Failed(e.message ?: "发送失败")
        } catch (e: Exception) {
            setActionStatus(actionId, ActionStatus.UNKNOWN, e.toString(), setOf(ActionStatus.EXECUTING))
            ExecutionOutcome.Failed("结果未知，请主人核实是否已发出：${e.message}")
        }
    }

    /** 应用启动时调用：上次崩溃时仍在 EXECUTING 的动作一律标为 UNKNOWN。 */
    fun markInterruptedExecutions(): Int {
        val stuck = actionsWithStatus(ActionStatus.EXECUTING)
        stuck.forEach { setActionStatus(it.id, ActionStatus.UNKNOWN, "执行中断（应用重启）", setOf(ActionStatus.EXECUTING)) }
        return stuck.size
    }

    /** 主人核实 UNKNOWN 动作的实际结果。 */
    fun resolveUnknown(actionId: String, actuallySent: Boolean, note: String) =
        setActionStatus(actionId, if (actuallySent) ActionStatus.DONE else ActionStatus.FAILED, "主人核实：$note", setOf(ActionStatus.UNKNOWN))

    private fun setActionStatus(id: String, to: ActionStatus, receipt: String?, from: Set<ActionStatus>) {
        db.transaction {
            val a = action(id) ?: return@transaction
            if (a.status !in from) return@transaction
            q.setActionStatus(to.name, receipt, clock.millis(), id)
            archive.append("action.status", a.taskId ?: id) {
                put("action_id", id)
                put("from", a.status.name)
                put("to", to.name)
                receipt?.let { put("receipt", it.take(500)) }
            }
        }
    }

    companion object {
        fun contentHash(kind: ActionKind, target: String, content: ActionContent): String {
            val obj = buildJsonObject {
                put("kind", kind.name)
                put("target", target.trim().lowercase())
                put("content", JuizJson.encodeToJsonElement(content))
            }
            return sha256Hex(canonicalJson(obj))
        }
    }

    private fun app.juiz.core.db.Task.toModel() = Task(
        id = id,
        createdAt = created_at,
        updatedAt = updated_at,
        status = TaskStatus.valueOf(status),
        title = title,
        request = request,
        kind = TaskKind.valueOf(kind),
        contactNumber = contact_number,
        contactName = contact_name,
        conversationId = conversation_id,
        confirmedFields = JuizJson.decodeFromString(fieldsSerializer, confirmed_fields),
        deliverable = deliverable,
        due = due,
        handoffRoute = handoff_route,
        handoffAttempt = handoff_attempt.toInt(),
        workRunId = work_run_id,
        workConversationUrl = work_conversation_url,
        resultSummary = result_summary,
        verification = verification,
        note = note,
    )

    private fun Outbound_action.toModel() = OutboundAction(
        id = id,
        taskId = task_id,
        kind = ActionKind.valueOf(kind),
        target = target,
        content = JuizJson.decodeFromString(ActionContent.serializer(), content),
        contentHash = content_hash,
        status = ActionStatus.valueOf(status),
        approvalHash = approval_hash,
        approvedAt = approved_at,
        idempotencyKey = idempotency_key,
        receipt = receipt,
    )
}
