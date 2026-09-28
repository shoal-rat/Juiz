package app.juiz.core.conversation

import app.juiz.core.model.Task
import app.juiz.core.policy.EscalationSignal
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

sealed interface EngineOutput {
    /** 一句可以立即播放/发送的话。 */
    data class Speech(val text: String) : EngineOutput
    data class ToolActivity(val name: String, val ok: Boolean, val output: String) : EngineOutput
    data class Escalation(val signal: EscalationSignal) : EngineOutput
    data class TaskCreated(val task: Task) : EngineOutput
    data class MessageTaken(val summary: String, val callback: Boolean, val urgency: String) : EngineOutput
    data class SpamMarked(val reason: String) : EngineOutput
    data class EndRequested(val reason: String) : EngineOutput
    data class ModelFailure(val message: String) : EngineOutput
}

/**
 * 防谎报：小模型偶尔会在没调用工具的情况下说"已经发送""办好了""已受理任务"。
 * 一句话里出现这类说法，只有当确实有对应工具成功执行过才放行，否则换成中性、真实的说法。
 */
object ClaimGuard {
    private val sendClaims = Regex("(已经?发送|发送完成|发送成功|已经?发到|已经?发给|发过去了|已经?寄出)")
    private val doneClaims = Regex("(已经?办好|已办妥|已经?完成|做好了|弄完了|已经?处理好)")
    /** 说"已经登记/记录了您的要求"：创建过任务或留过言都算。 */
    private val noteClaims = Regex("已经?(登记|记录)(好)?(了)?(下)?(您的)?(要求|需求|请求|事情)")
    // "任务已经建好"的说法太多（已受理、提交了请办任务、任务已确认创建……），不逐个列举，按分句判断：
    // 同一分句里有"任务"类名词 + 登记类动词 + 已然标记，且没有"会/将/确认后"之类的将来时标记，就算声称任务已建立。
    private val taskNoun = Regex("(任务|工单|请办|事项|需求单)")
    private val taskVerb = Regex("(受理|登记|创建|建立|建好|提交|生成|立好|记录|记下|录入|下单)")
    private val doneMark = Regex("(已|了|成功|完毕)")
    private val futureMark = Regex("(会|将|稍后|之后|以后|确认后|待|等|需要|再)")
    const val REPLACEMENT = "这个我需要确认后再告诉您。"
    const val TASK_REPLACEMENT = "您的要求我会完整转告本人，由本人确认后处理。"

    fun claimsTask(sentence: String): Boolean =
        Regex("已经?受理").containsMatchIn(sentence) ||
            sentence.split('，', ',', '；', ';', '：', ':').any { c ->
                taskNoun.containsMatchIn(c) && taskVerb.containsMatchIn(c) && doneMark.containsMatchIn(c) && !futureMark.containsMatchIn(c)
            }

    fun check(sentence: String, sentOk: Boolean, verifiedDone: Boolean, taskOk: Boolean = false, noteOk: Boolean = false): String? = when {
        sendClaims.containsMatchIn(sentence) && !sentOk -> REPLACEMENT
        doneClaims.containsMatchIn(sentence) && !sentOk && !verifiedDone -> REPLACEMENT
        claimsTask(sentence) && !taskOk -> TASK_REPLACEMENT
        noteClaims.containsMatchIn(sentence) && !taskOk && !noteOk -> TASK_REPLACEMENT
        else -> null
    }
}

/** 模型常给机主加上"先生/女士"，这里按机主名字和姓氏去掉，不替机主猜性别；也去掉 Markdown 加粗。 */
object Honorifics {
    private val titles = listOf("先生", "女士", "小姐")
    private val tags = Regex("</?[A-Za-z_][A-Za-z0-9_:-]*[^>]*>")
    /** 小模型偶尔把提示词里的写作要求当成台词说出来，例如"（不超过25字）"。 */
    private val metaNotes = Regex("[（(][^（）()]{0,12}(不超过|以内|字数|\\d+\\s*个?字|简短|口语化)[^（）()]{0,12}[）)]")
    /** 还会把工具名、参数名放在括号里念出来，例如"（take_message）""（取 callback_requested 信息）"。 */
    private val toolNotes = Regex("[（(][^（）()]{0,20}[A-Za-z]+_[A-Za-z_]+[^（）()]{0,20}[）)]")

    fun strip(text: String, ownerName: String): String {
        var t = text.replace("**", "").replace(tags, "").replace(metaNotes, "").replace(toolNotes, "").replace(Regex("\\s{2,}"), " ").trim()
        if (ownerName.isBlank() || ownerName == "机主") return t
        for (title in titles) {
            t = t.replace(ownerName + title, ownerName)
            t = t.replace(ownerName.first() + title, ownerName)
        }
        return t
    }
}

fun interface TurnRecorder {
    fun record(speaker: String, text: String)
}

/**
 * 与通道无关的对话引擎。语音通道把 Speech 送去合成，短信通道把一轮的 Speech 合并成一条短信。
 * 每轮：记录来电方的话 → 代码层检测（复述确认、升级）→ 流式调用模型 → 逐句输出 → 执行工具 → 必要时再调用模型。
 */
class ConversationEngine(
    private val model: ChatModel,
    private val executor: ToolExecutor,
    val context: ConversationContext,
    private val systemPrompt: String,
    private val recorder: TurnRecorder,
    private val maxToolRounds: Int = 3,
) {
    private val history = mutableListOf<ChatItem>()
    private val tools = context.tools

    val transcript: List<ChatItem> get() = history.toList()

    /** 恢复会话（例如短信窗口里应用进程被回收后）：把已留存的往来放回历史，不重复记录。 */
    fun seed(turns: List<Pair<String, String>>) {
        for ((speaker, text) in turns) {
            history += if (speaker == "caller") ChatItem.User(PromptBuilder.wrapCaller(text)) else ChatItem.Assistant(text)
        }
    }

    /** 开场白由模板生成（不经模型），这里只把它记入历史。 */
    fun opening(greeting: String): List<EngineOutput> {
        history += ChatItem.Assistant(greeting)
        recorder.record("assistant", greeting)
        return context.escalation.initial().map { EngineOutput.Escalation(it) }
    }

    suspend fun respond(callerText: String, emit: suspend (EngineOutput) -> Unit) {
        recorder.record("caller", callerText)
        val confirmation = context.confirmations.observeCallerUtterance(callerText)
        for (s in context.escalation.inspectCaller(callerText)) emit(EngineOutput.Escalation(s))
        // 代办授权里的资料由代码检索后附给模型，不依赖小模型自己想起去调用查询工具
        val notes = context.errandGrant?.let { g -> with(app.juiz.core.errand.ErrandService) { g.relevantNotesFor(callerText) } }.orEmpty()
        val confirmedNow = confirmation == app.juiz.core.policy.ConfirmationTracker.Outcome.CONFIRMED && context.pendingTask == null
        val userText = PromptBuilder.wrapCaller(callerText) + (if (notes.isEmpty()) "" else
            "\n〔本人授权可以告诉对方的相关资料，只按原文回答〕\n" + notes.joinToString("\n") { "- ${it.title}：${it.content}" })
        history += ChatItem.User(userText)
        when (confirmation) {
            // 对方肯定了复述：挂起的任务由代码创建，并把这次"工具调用"放进历史，让模型知道已经办了
            app.juiz.core.policy.ConfirmationTracker.Outcome.CONFIRMED -> executor.commitPending(context)?.let { (call, outcome) ->
                history += call
                history += ChatItem.ToolResult(call.callId, outcome.output)
                emit(EngineOutput.ToolActivity(call.name, outcome.ok, outcome.output))
                outcome.effects.filterIsInstance<ToolEffect.TaskCreated>().forEach { emit(EngineOutput.TaskCreated(it.task)) }
            }
            app.juiz.core.policy.ConfirmationTracker.Outcome.REJECTED -> context.pendingTask = null
            else -> Unit
        }
        // 对方肯定了复述、却没有挂起的任务（模型当时没调用 create_task）：单独补登记一次
        if (confirmedNow) rescueTask(emit)

        var round = 0
        var sentOk = false
        var verifiedDone = false
        suspend fun say(raw: String, spoken: StringBuilder) {
            val cleaned = Honorifics.strip(raw, context.ownerName)
            if (cleaned.isBlank() || cleaned.all { !it.isLetterOrDigit() }) return
            val blocked = ClaimGuard.check(cleaned, sentOk, verifiedDone, taskOk = context.tasksCreated > 0, noteOk = context.messagesTaken > 0)
            if (blocked != null) {
                executor.recordBlockedClaim(context, cleaned)
                if (blocked in spoken) return
            }
            val s = blocked ?: cleaned
            spoken.append(s)
            emit(EngineOutput.Speech(s))
        }
        while (true) {
            val chunker = SentenceChunker()
            val spoken = StringBuilder()
            val calls = mutableListOf<ChatItem.ToolCall>()
            try {
                model.stream(ChatRequest(systemPrompt, history.toList(), tools)).collect { ev ->
                    when (ev) {
                        is ChatEvent.TextDelta -> for (raw in chunker.push(ev.text)) say(raw, spoken)
                        is ChatEvent.ToolCallDone -> calls += ChatItem.ToolCall(ev.callId, ev.name, ev.arguments)
                        is ChatEvent.Completed -> Unit
                    }
                }
                chunker.flush()?.let { raw -> say(raw, spoken) }
            } catch (e: CancellationException) {
                // 被打断：历史里只留下已经说出的部分，并注明被打断
                if (spoken.isNotEmpty()) {
                    history += ChatItem.Assistant("$spoken……（被对方打断）")
                    recorder.record("assistant", "$spoken……（被打断）")
                }
                throw e
            } catch (e: Exception) {
                val fallback = "抱歉，我这边线路不太稳定。您的来电我已记下，稍后由本人联系您。"
                emit(EngineOutput.ModelFailure(e.message ?: e.toString()))
                emit(EngineOutput.Speech(fallback))
                history += ChatItem.Assistant(fallback)
                recorder.record("assistant", fallback)
                return
            }

            if (spoken.isNotEmpty()) {
                val text = spoken.toString()
                history += ChatItem.Assistant(text)
                recorder.record("assistant", text)
                context.confirmations.observeAssistantUtterance(text)
                for (s in context.escalation.inspectAssistant(text)) emit(EngineOutput.Escalation(s))
            }
            if (calls.isEmpty()) return

            for (c in calls) {
                history += c
                val outcome = executor.execute(c, context)
                if (outcome.ok && c.name == Tools.SEND_FILE) sentOk = true
                if (outcome.ok && c.name == Tools.CHECK_TASK_STATUS && "已完成（已核实）" in outcome.output) verifiedDone = true
                history += ChatItem.ToolResult(c.callId, outcome.output)
                emit(EngineOutput.ToolActivity(c.name, outcome.ok, outcome.output))
                for (eff in outcome.effects) {
                    when (eff) {
                        is ToolEffect.Escalate -> eff.signals.forEach { emit(EngineOutput.Escalation(it)) }
                        is ToolEffect.EndConversation -> emit(EngineOutput.EndRequested(eff.reason))
                        is ToolEffect.TaskCreated -> emit(EngineOutput.TaskCreated(eff.task))
                        is ToolEffect.MessageTaken -> emit(EngineOutput.MessageTaken(eff.summary, eff.callback, eff.urgency))
                        is ToolEffect.MarkedSpam -> emit(EngineOutput.SpamMarked(eff.reason))
                    }
                }
            }
            // 已道别并请求结束时不再追问模型
            if (context.ended && spoken.isNotEmpty()) return
            round++
            if (round > maxToolRounds) {
                val fallback = "好的，我已经记下了。"
                emit(EngineOutput.Speech(fallback))
                history += ChatItem.Assistant(fallback)
                recorder.record("assistant", fallback)
                return
            }
        }
    }

    /**
     * 补登记：对方刚确认了复述的信息，但没有挂起的任务——小模型常常只说"已受理"却不调用工具，
     * 单独给它一个只有 create_task 的请求也不一定调用（真机实测）。所以这里不靠工具调用：
     * 让模型只输出一个 JSON（是不是办事请求、标题、请求内容），由代码拼出 create_task，字段值直接用对方确认过的原值；
     * JSON 解析不出来时，用对方自己的原话建任务。任务照常过确认闸门，建好后仍然要本人确认才会办理。
     */
    private suspend fun rescueTask(emit: suspend (EngineOutput) -> Unit) {
        if (tools.none { it.name == Tools.CREATE_TASK }) return
        val fields = context.confirmations.confirmedFields
        val callerLines = history.filterIsInstance<ChatItem.User>().map { it.text.removePrefix("〔来电方说〕").substringBefore("\n〔") }
        val convo = history.takeLast(10).mapNotNull {
            when (it) {
                is ChatItem.User -> "来电方：" + it.text.removePrefix("〔来电方说〕").substringBefore("\n〔")
                is ChatItem.Assistant -> "助理：" + it.text
                else -> null
            }
        }.joinToString("\n")
        val ask = "对话：\n$convo\n\n来电方已确认：" + fields.entries.joinToString("；") { "${it.key}=${it.value}" } +
            "\n\n只输出一个 JSON 对象，不要其它文字：{\"is_request\": 来电方是否要本人去办一件事（写、发、查、改、约）true 或 false, " +
            "\"title\": \"不超过15字的标题\", \"request\": \"完整的请求\", \"kind\": \"DOCUMENT、EMAIL、MEETING、CALLBACK、INFO、OTHER 之一\", \"deliverable\": \"交付物\"}"
        val text = StringBuilder()
        try {
            model.stream(ChatRequest("你是任务登记员，只输出 JSON。", listOf(ChatItem.User(ask)), emptyList(), maxOutputTokens = 300)).collect { ev ->
                if (ev is ChatEvent.TextDelta) text.append(ev.text)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            text.clear()
        }
        val raw = text.toString()
        val parsed = raw.let {
            val i = it.indexOf('{'); val j = it.lastIndexOf('}')
            if (i < 0 || j <= i) null else runCatching { app.juiz.core.util.JuizJson.parseToJsonElement(it.substring(i, j + 1)).jsonObject }.getOrNull()
        }
        // 严格解析失败时按字段宽松提取：小模型常把引号写成全角、冒号写成"："
        fun str(k: String) = (parsed?.get(k) as? JsonPrimitive)?.contentOrNull?.trim()
            ?: Regex("[\"“]$k[\"”]\\s*[:：]\\s*[\"“]([^\"”]*)[\"”]").find(raw)?.groupValues?.get(1)?.trim().orEmpty()
        val isRequest = (parsed?.get("is_request") as? JsonPrimitive)?.booleanOrNull
            ?: Regex("[\"“]is_request[\"”]\\s*[:：]\\s*(true|false)").find(raw)?.groupValues?.get(1)?.toBoolean()
        if (isRequest == false) return executor.recordRescue(context, "not_request")
        val fromModel = str("request").isNotEmpty()
        val request = str("request").ifEmpty { callerLines.dropLast(1).lastOrNull { it.length > 6 } ?: "" }
        if (request.isBlank()) return executor.recordRescue(context, "no_request")
        val due = fields.entries.firstOrNull { it.key.startsWith("时间") || it.key.startsWith("日期") }?.value
        val args = buildJsonObject {
            put("title", str("title").ifEmpty { fallbackTitle(request) })
            put("request", request)
            put("kind", str("kind").takeIf { k -> app.juiz.core.model.TaskKind.entries.any { it.name == k } } ?: "OTHER")
            put("deliverable", str("deliverable"))
            due?.let { put("due", it) }
            // 用作截止时间的那一项不再重复写进 fields（create_task 会把 due 记成"截止时间"）
            putJsonArray("fields") { fields.forEach { (k, v) -> if (v != due) addJsonObject { put("name", k); put("value", v) } } }
        }
        val call = ChatItem.ToolCall("rescue_${context.tasksCreated + 1}", Tools.CREATE_TASK, args.toString())
        history += call
        val outcome = executor.execute(call, context)
        history += ChatItem.ToolResult(call.callId, outcome.output)
        executor.recordRescue(context, (if (fromModel) "" else "fallback ") + if (outcome.ok) "created" else "rejected: ${outcome.output.take(120)}")
        emit(EngineOutput.ToolActivity(call.name, outcome.ok, outcome.output))
        outcome.effects.filterIsInstance<ToolEffect.TaskCreated>().forEach { emit(EngineOutput.TaskCreated(it.task)) }
    }

    /** 用对方原话建任务时的标题：去掉"我是王总。"这类自我介绍，取第一个分句。 */
    private fun fallbackTitle(request: String): String {
        val t = request.replace(Regex("^(您好|你好|喂)?[，,。 ]*(我是[^，。,.！!]{1,10}[，。,.！!])?\\s*"), "")
        return t.split('，', '。', ',', '；', '！', '!').firstOrNull { it.isNotBlank() }?.trim()?.take(20) ?: request.take(20)
    }
}
