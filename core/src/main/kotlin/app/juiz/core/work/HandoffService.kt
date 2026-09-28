package app.juiz.core.work

import app.juiz.core.model.Task
import app.juiz.core.model.TaskStatus
import app.juiz.core.tasks.TaskService
import app.juiz.core.util.JuizJson

enum class HandoffRoute(val zh: String) {
    WORKSPACE_AGENT("Workspace Agents API（触发与状态自动）"),
    MANUAL_SHARE("分享任务卡（半自动，需主人操作）"),
}

sealed interface HandoffResult {
    data class Triggered(val runId: String, val conversationUrl: String?) : HandoffResult
    /** 需要主人把任务卡分享给 ChatGPT 或复制粘贴。 */
    data class ShareNeeded(val cardText: String) : HandoffResult
    data class Failed(val reason: String) : HandoffResult
}

/**
 * 把"待确认"任务交给 Work。只有主人在界面上确认后才会调用。
 * API 报告 completed 只会把任务推进到"待核实"；真正完成要靠结果清单核验或主人核对。
 */
class HandoffService(
    private val tasks: TaskService,
    private val agents: WorkspaceAgentsClient?,
    private val folderRoot: String,
) {
    val availableRoutes: List<HandoffRoute>
        get() = listOfNotNull(if (agents != null) HandoffRoute.WORKSPACE_AGENT else null, HandoffRoute.MANUAL_SHARE)

    fun cardFor(task: Task): String = TaskCard.render(TaskCard.data(task, folderRoot))

    suspend fun handoff(taskId: String, route: HandoffRoute): HandoffResult {
        val task = tasks.get(taskId) ?: return HandoffResult.Failed("任务不存在")
        if (task.status != TaskStatus.PENDING_CONFIRMATION) return HandoffResult.Failed("只有待确认的任务可以交接（当前：${task.status.zh}）")
        val attempt = task.handoffAttempt + 1
        val staged = task.copy(handoffAttempt = attempt)
        val card = cardFor(staged)
        return when (route) {
            HandoffRoute.MANUAL_SHARE -> {
                tasks.recordHandoff(taskId, route.name, attempt, null, null)
                HandoffResult.ShareNeeded(card)
            }
            HandoffRoute.WORKSPACE_AGENT -> {
                val client = agents ?: return HandoffResult.Failed("未配置 Workspace Agents API")
                try {
                    val r = client.trigger(card, conversationKey = task.id, idempotencyKey = staged.handoffKey)
                    tasks.recordHandoff(taskId, route.name, attempt, r.agent_trigger_run_id, r.conversation_url)
                    tasks.transition(taskId, TaskStatus.HANDED_OFF, "已触发 Workspace Agent")
                    HandoffResult.Triggered(r.agent_trigger_run_id, r.conversation_url)
                } catch (e: Exception) {
                    HandoffResult.Failed(e.message ?: e.toString())
                }
            }
        }
    }

    /** 主人确认已经把任务卡交给了 ChatGPT（分享路径）。 */
    fun confirmManualHandoff(taskId: String) = tasks.transition(taskId, TaskStatus.HANDED_OFF, "主人已手动交给 ChatGPT")

    /** 查询 API 运行状态并映射到本地状态机。返回 null 表示无需更新。 */
    suspend fun poll(taskId: String): TaskStatus? {
        val task = tasks.get(taskId) ?: return null
        val runId = task.workRunId ?: return null
        val client = agents ?: return null
        if (task.status.terminal || task.status == TaskStatus.NEEDS_VERIFICATION) return null
        val run = client.run(runId)
        val target = when (run.status) {
            "queued" -> TaskStatus.HANDED_OFF
            "in_progress" -> TaskStatus.IN_PROGRESS
            "suspended" -> TaskStatus.AWAITING_APPROVAL
            "completed" -> TaskStatus.NEEDS_VERIFICATION
            "failed" -> TaskStatus.FAILED
            else -> return null
        }
        if (target == task.status) return null
        val note = when (run.status) {
            "completed" -> "Work 报告完成，等待核验交付物"
            "failed" -> "Work 运行失败：${run.error?.code ?: "unknown"}"
            "suspended" -> "Work 等待外部操作或审批"
            else -> null
        }
        return if (TaskStatus.canTransition(task.status, target)) tasks.transition(taskId, target, note).status else null
    }

    /** 核验交换目录里的结果清单；通过才标记为完成。 */
    fun verify(taskId: String, folder: ExchangeFolder): VerificationReport {
        val report = ResultVerifier.verify(taskId, folder)
        tasks.recordResult(taskId, report.summary, JuizJson.encodeToString(VerificationReport.serializer(), report))
        val task = tasks.get(taskId) ?: return report
        if (report.ok) {
            var cur = task.status
            if (cur != TaskStatus.NEEDS_VERIFICATION && TaskStatus.canTransition(cur, TaskStatus.NEEDS_VERIFICATION)) {
                cur = tasks.transition(taskId, TaskStatus.NEEDS_VERIFICATION, "发现结果清单").status
            }
            if (cur == TaskStatus.NEEDS_VERIFICATION) {
                tasks.transition(taskId, TaskStatus.COMPLETED, "结果清单核验通过：${report.verified.joinToString()}")
            }
        }
        return report
    }
}
