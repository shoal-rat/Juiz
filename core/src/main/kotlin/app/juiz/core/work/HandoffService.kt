package app.juiz.core.work

import app.juiz.core.model.Task
import app.juiz.core.model.TaskStatus
import app.juiz.core.tasks.TaskService
import app.juiz.core.util.JuizJson

enum class HandoffRoute(val zh: String) {
    /** 手机直连 OpenAI：gpt-6-sol 在云端沙箱里干活，手机下载成品。不需要电脑。 */
    OPENAI_CLOUD("OpenAI 云端执行（手机直连，全自动，最终由你批准）"),
    WORKSPACE_AGENT("Workspace Agents API（触发与状态自动）"),
    /** 任务卡写进与电脑同步的交换目录，由电脑上的 juiz-desk 调用 Codex（默认 gpt-6-sol）完成。 */
    CODEX_DESK("电脑上的 Codex（juiz-desk，全自动，最终由本人批准）"),
    MANUAL_SHARE("分享任务卡（半自动，需本人操作）"),
}

sealed interface HandoffResult {
    /** 已放入交换目录，等待 juiz-desk 领取。 */
    data class Queued(val path: String) : HandoffResult
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
    /** 与电脑共享的交换目录（可写时才能交给 juiz-desk）。 */
    private val desk: ExchangeFolder? = null,
    /** 云端执行器与存放下载成品的本地目录。 */
    private val cloud: CloudWorker? = null,
    private val cloudDir: java.io.File? = null,
) {
    val availableRoutes: List<HandoffRoute>
        get() = listOfNotNull(
            if (cloud != null && cloudDir != null) HandoffRoute.OPENAI_CLOUD else null,
            if (desk != null) HandoffRoute.CODEX_DESK else null,
            if (agents != null) HandoffRoute.WORKSPACE_AGENT else null,
            HandoffRoute.MANUAL_SHARE,
        )

    fun cardFor(task: Task): String = TaskCard.render(TaskCard.data(task, folderRoot))

    suspend fun handoff(taskId: String, route: HandoffRoute): HandoffResult {
        val task = tasks.get(taskId) ?: return HandoffResult.Failed("任务不存在")
        if (task.status != TaskStatus.PENDING_CONFIRMATION) return HandoffResult.Failed("只有待确认的任务可以交接（当前：${task.status.zh}）")
        val attempt = task.handoffAttempt + 1
        val staged = task.copy(handoffAttempt = attempt)
        val card = cardFor(staged)
        return when (route) {
            HandoffRoute.OPENAI_CLOUD -> {
                val worker = cloud ?: return HandoffResult.Failed("未配置 OpenAI API Key")
                try {
                    val id = worker.start(card, staged.handoffKey)
                    tasks.recordHandoff(taskId, route.name, attempt, id, null)
                    tasks.transition(taskId, TaskStatus.HANDED_OFF, "已交给云端执行（${id.take(16)}…）")
                    HandoffResult.Triggered(id, null)
                } catch (e: Exception) {
                    HandoffResult.Failed(e.message ?: e.toString())
                }
            }
            HandoffRoute.CODEX_DESK -> {
                val folder = desk ?: return HandoffResult.Failed("没有可写的交换目录")
                val data = TaskCard.data(staged, folderRoot)
                val ok = folder.write("${task.id}/card.md", card.toByteArray()) &&
                    folder.write("${task.id}/card.json", JuizJson.encodeToString(TaskCardData.serializer(), data).toByteArray())
                if (!ok) return HandoffResult.Failed("写入交换目录失败")
                tasks.recordHandoff(taskId, route.name, attempt, null, null)
                tasks.transition(taskId, TaskStatus.HANDED_OFF, "任务卡已放入交换目录，等待电脑上的 juiz-desk 领取")
                HandoffResult.Queued("${task.id}/")
            }
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

    /**
     * 执行方写好的邮件草稿 → 待本人审批的外发动作。附件绑定已核验交付物的哈希，
     * 审批后才会由手机发出；同一草稿不会重复生成。
     */
    private fun proposeDrafts(taskId: String, report: VerificationReport) {
        val existing = tasks.actionsForTask(taskId).map { it.target to it.content.subject }.toSet()
        for (d in report.drafts) {
            val target = d.target ?: continue
            if (target to d.subject in existing) continue
            val atts = d.attachments.mapNotNull { path -> report.deliverables.firstOrNull { it.path == path } }
                .map { app.juiz.core.model.AttachmentRef(it.path, it.sha256, it.bytes) }
            tasks.proposeAction(taskId, app.juiz.core.model.ActionKind.EMAIL, target, app.juiz.core.model.ActionContent(d.subject, d.body.orEmpty(), atts))
        }
    }

    /** 主人确认已经把任务卡交给了 ChatGPT（分享路径）。 */
    fun confirmManualHandoff(taskId: String) = tasks.transition(taskId, TaskStatus.HANDED_OFF, "本人已手动交给 ChatGPT")

    /** 查询 API 运行状态并映射到本地状态机。返回 null 表示无需更新。 */
    suspend fun poll(taskId: String): TaskStatus? {
        val task = tasks.get(taskId) ?: return null
        if (task.handoffRoute == HandoffRoute.CODEX_DESK.name) return pollDesk(task)
        if (task.handoffRoute == HandoffRoute.OPENAI_CLOUD.name) return pollCloud(task)
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

    /** 云端：queued/in_progress → 执行中；completed → 下载成品、写清单 → 待核实 → 核验。 */
    private suspend fun pollCloud(task: Task): TaskStatus? {
        val worker = cloud ?: return null
        val dir = cloudDir ?: return null
        val id = task.workRunId ?: return null
        if (task.status !in setOf(TaskStatus.HANDED_OFF, TaskStatus.IN_PROGRESS)) return null
        return when (val st = worker.check(id)) {
            is CloudStatus.Running -> if (st.status == "in_progress" && task.status == TaskStatus.HANDED_OFF) tasks.transition(task.id, TaskStatus.IN_PROGRESS, "云端正在处理").status else null
            is CloudStatus.Failed -> tasks.transition(task.id, TaskStatus.FAILED, st.reason).status
            is CloudStatus.Done -> {
                worker.collect(st.response, java.io.File(dir, task.id).apply { mkdirs() }, task.id)
                tasks.transition(task.id, TaskStatus.NEEDS_VERIFICATION, "云端已完成，成品已下载，等待核验")
                verify(task.id, LocalExchangeFolder(dir))
                tasks.get(task.id)?.status
            }
        }
    }

    /** 按交接路径选择成品所在的目录。 */
    fun folderFor(task: Task): ExchangeFolder? = when (task.handoffRoute) {
        HandoffRoute.OPENAI_CLOUD.name -> cloudDir?.let { LocalExchangeFolder(it) }
        HandoffRoute.CODEX_DESK.name -> desk
        else -> null
    }

    /** juiz-desk：claimed.json 出现 → 执行中；result.json 出现 → 待核实（随后由 verify 核验）。 */
    private fun pollDesk(task: Task): TaskStatus? {
        val folder = desk ?: return null
        if (task.status == TaskStatus.HANDED_OFF && folder.exists("${task.id}/claimed.json")) {
            return tasks.transition(task.id, TaskStatus.IN_PROGRESS, "juiz-desk 已领取，Codex 正在处理").status
        }
        if (task.status in setOf(TaskStatus.HANDED_OFF, TaskStatus.IN_PROGRESS) && folder.exists("${task.id}/result.json")) {
            return tasks.transition(task.id, TaskStatus.NEEDS_VERIFICATION, "juiz-desk 已写回结果，等待核验").status
        }
        return null
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
            proposeDrafts(taskId, report)
        }
        return report
    }
}
