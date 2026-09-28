package app.juiz.core.conversation

import app.juiz.core.archive.ArchiveLog
import app.juiz.core.errand.ErrandGrant
import app.juiz.core.errand.ErrandService
import app.juiz.core.memory.MemoryService
import app.juiz.core.model.CallerInfo
import app.juiz.core.model.Channel
import app.juiz.core.model.ContactTier
import app.juiz.core.model.FactSource
import app.juiz.core.model.OwnerProfile
import app.juiz.core.model.Task
import app.juiz.core.model.TaskKind
import app.juiz.core.model.TaskStatus
import app.juiz.core.policy.ConfirmationTracker
import app.juiz.core.policy.EscalationDetector
import app.juiz.core.policy.EscalationSignal
import app.juiz.core.tasks.NewTask
import app.juiz.core.tasks.TaskService
import app.juiz.core.util.JuizJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** 一次会话（一通电话或一个短信窗口）的可变状态。 */
class ConversationContext(
    val conversationId: String,
    val caller: CallerInfo,
    val channel: Channel,
    /** 本次会话生效的代办授权（在时段内才有）。 */
    val errandGrant: ErrandGrant? = null,
    val errandFilesEnabled: Boolean = false,
    /** 机主名字：用于把模型自作主张加上的"先生/女士"去掉，不替机主猜性别。 */
    val ownerName: String = "",
) {
    val errandInfoEnabled: Boolean get() = errandGrant?.notes?.isNotEmpty() == true
    val tools: List<ToolSpec> get() = Tools.forContext(channel, caller.tier, errandFilesEnabled, errandInfoEnabled)

    val confirmations = ConfirmationTracker()
    val escalation = EscalationDetector(caller)
    var tasksCreated = 0
    /** 关键信息还没被对方确认、先挂起的任务（对方下一句肯定后由代码自动创建）。 */
    var pendingTask: JsonObject? = null
    /** 最近创建的任务签名，防止模型重复调用造成重复任务。 */
    val createdSignatures = mutableMapOf<String, Task>()
    var messagesTaken = 0
    var callbacksScheduled = 0
    var ended = false
}

sealed interface ToolEffect {
    data class Escalate(val signals: List<EscalationSignal>) : ToolEffect
    data class EndConversation(val reason: String) : ToolEffect
    data class TaskCreated(val task: Task) : ToolEffect
    data class MessageTaken(val summary: String, val callback: Boolean, val urgency: String) : ToolEffect
    data class MarkedSpam(val reason: String) : ToolEffect
}

data class ToolOutcome(val ok: Boolean, val output: String, val effects: List<ToolEffect> = emptyList())

/**
 * 执行模型提出的工具调用。所有权限判断都在这里用代码完成：
 * 参数不合规、关键信息未确认、超出次数、查询别人的任务，一律拒绝并把原因回给模型。
 */
class ToolExecutor(
    private val tasks: TaskService,
    private val memory: MemoryService,
    private val archive: ArchiveLog,
    private val owner: () -> OwnerProfile,
    private val errands: ErrandService? = null,
    private val limits: Limits = Limits(),
    /** 任务创建后的钩子（例如自动交给电脑上的 Codex）。 */
    private val onTaskCreated: (Task, CallerInfo) -> Unit = { _, _ -> },
) {
    data class Limits(val tasksPerConversation: Int = 3, val messagesPerConversation: Int = 3, val callbacksPerConversation: Int = 2)

    suspend fun execute(call: ChatItem.ToolCall, ctx: ConversationContext): ToolOutcome {
        val allowed = ctx.tools.map { it.name }
        if (call.name !in allowed) return reject(call, ctx, "当前会话不能使用工具 ${call.name}")
        val args = try {
            JuizJson.parseToJsonElement(call.arguments.ifBlank { "{}" }).jsonObject
        } catch (e: Exception) {
            return reject(call, ctx, "参数不是合法 JSON")
        }
        val outcome = when (call.name) {
            Tools.TAKE_MESSAGE -> takeMessage(args, ctx)
            Tools.CONFIRM_DETAILS -> confirmDetails(args, ctx)
            Tools.CREATE_TASK -> createTask(args, ctx)
            Tools.CHECK_TASK_STATUS -> checkStatus(ctx)
            Tools.SCHEDULE_CALLBACK -> scheduleCallback(args, ctx)
            Tools.ESCALATE -> escalate(args, ctx)
            Tools.OWNER_AVAILABILITY -> availability()
            Tools.NOTE_FACT -> noteFact(args, ctx)
            Tools.MARK_SPAM -> markSpam(args, ctx)
            Tools.END_CALL -> endCall(args, ctx)
            Tools.LIST_FILES -> listFiles(args, ctx)
            Tools.SEND_FILE -> sendFile(args, ctx)
            Tools.LOOKUP_INFO -> lookup(args, ctx)
            else -> null
        } ?: return reject(call, ctx, "未知工具")

        archive.append(if (outcome.ok) "tool.called" else "tool.rejected", ctx.conversationId) {
            put("tool", call.name)
            put("arguments", args)
            put("result", outcome.output.take(1000))
        }
        return outcome
    }

    private fun reject(call: ChatItem.ToolCall, ctx: ConversationContext, why: String): ToolOutcome {
        archive.append("tool.rejected", ctx.conversationId) {
            put("tool", call.name)
            put("reason", why)
        }
        return ToolOutcome(false, err(why))
    }

    private fun JsonObject.s(k: String): String = this[k]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    private fun JsonObject.b(k: String): Boolean = this[k]?.jsonPrimitive?.contentOrNull == "true"
    private fun JsonObject.fields(k: String): Map<String, String> =
        (this[k] as? JsonArray)?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val n = o.s("name")
            val v = o.s("value")
            if (n.isEmpty() || v.isEmpty()) null else n to v
        }?.toMap().orEmpty()

    private fun ok(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        buildJsonObject { put("ok", true); build() }.toString()

    private fun err(msg: String) = buildJsonObject { put("ok", false); put("error", msg) }.toString()

    private fun takeMessage(a: JsonObject, ctx: ConversationContext): ToolOutcome {
        if (ctx.messagesTaken >= limits.messagesPerConversation) return ToolOutcome(false, err("本次会话留言次数已达上限"))
        val summary = a.s("summary").ifEmpty { return ToolOutcome(false, err("留言内容不能为空")) }
        val urgency = a.s("urgency").ifEmpty { "normal" }
        ctx.messagesTaken++
        archive.append("message.taken", ctx.conversationId) {
            put("from", ctx.caller.number)
            ctx.caller.displayName?.let { put("name", it) }
            put("summary", summary)
            put("callback_requested", a.b("callback_requested"))
            put("urgency", urgency)
        }
        return ToolOutcome(
            true, ok { put("note", "已记录留言，本人会看到。不要承诺本人何时回复。") },
            listOf(ToolEffect.MessageTaken(summary, a.b("callback_requested"), urgency)),
        )
    }

    private fun confirmDetails(a: JsonObject, ctx: ConversationContext): ToolOutcome {
        val fields = a.fields("fields")
        if (fields.isEmpty()) return ToolOutcome(false, err("没有要确认的字段"))
        ctx.confirmations.registerReadBack(fields)
        return ToolOutcome(true, ok {
            put("instruction", "现在逐项向对方复述这些信息并询问是否正确。对方明确肯定之前，不要创建任务。" +
                "对方说对之后调用 create_task，字段值要一字不差地沿用：" + fields.entries.joinToString("；") { "${it.key}=${it.value}" })
        })
    }

    private fun createTask(a: JsonObject, ctx: ConversationContext): ToolOutcome {
        if (ctx.tasksCreated >= limits.tasksPerConversation) return ToolOutcome(false, err("本次会话创建任务数已达上限，请改为留言"))
        val title = a.s("title")
        val request = a.s("request")
        if (title.isEmpty() || request.isEmpty()) return ToolOutcome(false, err("标题和请求内容不能为空"))
        val kind = runCatching { TaskKind.valueOf(a.s("kind")) }.getOrDefault(TaskKind.OTHER)
        val due = a.s("due").ifEmpty { null }
        val fields = a.fields("fields").toMutableMap()
        due?.let { fields["截止时间"] = it }
        val deliverable = a.s("deliverable").ifEmpty { null }
        val brief = a.s("brief").ifEmpty { null }

        val signature = "$title|$request"
        ctx.createdSignatures[signature]?.let { existing ->
            return ToolOutcome(true, ok { put("task_id", existing.id); put("note", "这个任务已经创建过了，不要重复创建。") })
        }
        val problems = ctx.confirmations.violations(fields)
        if (problems.isNotEmpty()) {
            // 两阶段：先挂起，登记需要复述的值；对方下一句肯定后由引擎自动创建，不依赖模型再调用一次
            val critical = fields.filter { (_, v) -> ConfirmationTracker.looksCritical(v) }
            ctx.confirmations.registerReadBack(critical)
            ctx.pendingTask = a
            return ToolOutcome(false, err(
                "关键信息尚未向对方复述确认，任务先挂起：" + problems.joinToString("；") +
                    "。现在逐项复述这些值并问对方是否正确：" + critical.entries.joinToString("；") { "${it.key}=${it.value}" } +
                    "。对方确认后任务会自动创建，不需要你再调用 create_task。",
            ))
        }
        val task = tasks.create(
            NewTask(
                title = title,
                request = request,
                kind = kind,
                contactNumber = ctx.caller.number,
                contactName = ctx.caller.displayName,
                conversationId = ctx.conversationId,
                confirmedFields = fields,
                deliverable = deliverable,
                due = due,
                brief = brief,
            ),
        )
        ctx.tasksCreated++
        ctx.createdSignatures[signature] = task
        ctx.pendingTask = null
        runCatching { onTaskCreated(task, ctx.caller) }
        return ToolOutcome(
            true,
            ok {
                put("task_id", task.id)
                put("status", task.status.zh)
                put("instruction", "告诉对方已经记下，需要本人确认后才会办理；不要承诺完成时间或结果。")
            },
            listOf(ToolEffect.TaskCreated(task)),
        )
    }

    /** 记录一次被拦下的"谎报完成"。 */
    fun recordBlockedClaim(ctx: ConversationContext, sentence: String) {
        archive.append("guard.claim_blocked", ctx.conversationId) { put("sentence", sentence.take(200)) }
    }

    /** 记录一次补登记任务的结果（created / no_call / rejected / error），方便事后排查小模型漏调工具。 */
    fun recordRescue(ctx: ConversationContext, result: String) {
        archive.append("guard.task_rescue", ctx.conversationId) { put("result", result.take(200)) }
    }

    /** 对方肯定了复述内容：把挂起的任务真正创建出来。 */
    suspend fun commitPending(ctx: ConversationContext): Pair<ChatItem.ToolCall, ToolOutcome>? {
        val args = ctx.pendingTask ?: return null
        ctx.pendingTask = null
        val call = ChatItem.ToolCall("auto_${ctx.tasksCreated + 1}", Tools.CREATE_TASK, args.toString())
        return call to execute(call, ctx)
    }

    private fun checkStatus(ctx: ConversationContext): ToolOutcome {
        val list = tasks.forContact(ctx.caller.number, 5)
        return ToolOutcome(true, ok {
            putJsonArray("tasks") {
                list.forEach { t ->
                    addJsonObject {
                        put("task_id", t.id)
                        put("title", t.title)
                        put("status", describeForCaller(t.status))
                    }
                }
            }
            if (list.isEmpty()) put("note", "没有查到这个号码提出的任务")
            put("instruction", "只能按这里的状态回答，不得推测或声称已完成。")
        })
    }

    /** 对来电方描述状态时，"Work 说完成了"只能说成"结果正在核对"。 */
    private fun describeForCaller(s: TaskStatus): String = when (s) {
        TaskStatus.PENDING_CONFIRMATION -> "已记录，等待本人确认"
        TaskStatus.HANDED_OFF, TaskStatus.IN_PROGRESS -> "正在处理"
        TaskStatus.AWAITING_APPROVAL -> "等待本人审批"
        TaskStatus.NEEDS_VERIFICATION -> "处理结果正在由本人核对，尚未确认完成"
        TaskStatus.COMPLETED -> "已完成（已核实）"
        TaskStatus.FAILED -> "处理遇到问题，本人会跟进"
        TaskStatus.CANCELLED -> "已取消"
    }

    private fun scheduleCallback(a: JsonObject, ctx: ConversationContext): ToolOutcome {
        if (ctx.callbacksScheduled >= limits.callbacksPerConversation) return ToolOutcome(false, err("回电待办已登记"))
        val note = a.s("note").ifEmpty { "回电" }
        val whenText = a.s("when")
        val task = tasks.create(
            NewTask(
                title = "回电：${ctx.caller.displayName ?: ctx.caller.number}",
                request = note,
                kind = TaskKind.CALLBACK,
                contactNumber = ctx.caller.number,
                contactName = ctx.caller.displayName,
                conversationId = ctx.conversationId,
                due = whenText.ifEmpty { null },
                note = "对方方便的时间：${whenText.ifEmpty { "未说明" }}",
            ),
        )
        ctx.callbacksScheduled++
        return ToolOutcome(true, ok {
            put("task_id", task.id)
            put("instruction", "告诉对方已登记回电请求，本人会尽量安排；不要承诺具体回电时间。")
        }, listOf(ToolEffect.TaskCreated(task)))
    }

    private fun escalate(a: JsonObject, ctx: ConversationContext): ToolOutcome {
        val signals = ctx.escalation.modelRequested(a.s("reason").ifEmpty { "助理判断需要本人" }, a.s("urgency") == "urgent")
        return ToolOutcome(true, ok {
            put("instruction", "已通知本人。告诉对方正在联系本人，请对方稍候；如果本人未接，改为留言。")
        }, listOf(ToolEffect.Escalate(signals)))
    }

    private fun availability(): ToolOutcome {
        val status = owner().publicStatus
        return ToolOutcome(true, ok {
            put("status", status.ifEmpty { "本人没有设置可对外说明的状态。只能说本人现在不方便接听。" })
        })
    }

    private fun noteFact(a: JsonObject, ctx: ConversationContext): ToolOutcome {
        val fact = a.s("fact").ifEmpty { return ToolOutcome(false, err("内容不能为空")) }
        val subject = a.s("subject").ifEmpty { ctx.caller.label }
        memory.add(subject, fact, FactSource.CALLER, "${ctx.conversationId}|${ctx.caller.number}")
        return ToolOutcome(true, ok { put("note", "已记录为待本人确认的信息。") })
    }

    private fun markSpam(a: JsonObject, ctx: ConversationContext): ToolOutcome {
        if (ctx.caller.tier == ContactTier.KNOWN || ctx.caller.tier == ContactTier.VIP) {
            return ToolOutcome(false, err("通讯录联系人不能被标记为骚扰"))
        }
        val reason = a.s("reason")
        return ToolOutcome(true, ok { put("instruction", "礼貌结束对话。") }, listOf(ToolEffect.MarkedSpam(reason)))
    }

    // ---------- 代办白名单 ----------

    private fun grantOrNull(ctx: ConversationContext): ErrandGrant? {
        val svc = errands ?: return null
        // 授权以当前时刻重新判定：通话跨出授权时段后立即失效
        return ctx.errandGrant?.let { svc.activeGrant(ctx.caller.number) }
    }

    private fun listFiles(a: JsonObject, ctx: ConversationContext): ToolOutcome {
        val g = grantOrNull(ctx) ?: return ToolOutcome(false, err("当前没有生效的代办授权"))
        val files = errands!!.listFiles(g, a.s("query"))
        return ToolOutcome(true, ok {
            putJsonArray("files") { files.forEach { f -> addJsonObject { put("name", f.name); put("bytes", f.bytes) } } }
            if (files.isEmpty()) put("note", "没有找到匹配的文件。如实告诉对方，并记下需求留给本人。")
        })
    }

    private suspend fun sendFile(a: JsonObject, ctx: ConversationContext): ToolOutcome {
        val g = grantOrNull(ctx) ?: return ToolOutcome(false, err("当前没有生效的代办授权"))
        val name = a.s("file_name").ifEmpty { return ToolOutcome(false, err("缺少文件名")) }
        return when (val r = errands!!.sendFile(g, name, ctx.conversationId)) {
            is ErrandService.SendResult.Sent -> ToolOutcome(true, ok {
                put("sent", r.file)
                put("to", r.maskedTo)
                put("instruction", "告诉对方文件已发到其登记的邮箱（只说打码后的地址），并说明这是 AI 助理按授权发送的。")
            })
            is ErrandService.SendResult.Refused -> ToolOutcome(false, err(r.reason))
        }
    }

    private fun lookup(a: JsonObject, ctx: ConversationContext): ToolOutcome {
        val g = grantOrNull(ctx) ?: return ToolOutcome(false, err("当前没有生效的代办授权"))
        val hits = errands!!.lookup(g, a.s("query"))
        return ToolOutcome(true, ok {
            putJsonArray("notes") { hits.forEach { n -> addJsonObject { put("title", n.title); put("content", n.content) } } }
            put("instruction", if (hits.isEmpty()) "没有查到，如实说明并记下需求留给本人，不要猜测。" else "只按资料原文回答，不要补充资料里没有的内容。")
        })
    }

    private fun endCall(a: JsonObject, ctx: ConversationContext): ToolOutcome {
        ctx.ended = true
        return ToolOutcome(true, ok { put("note", "会话将在道别后结束。") }, listOf(ToolEffect.EndConversation(a.s("reason"))))
    }
}
