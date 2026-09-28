package app.juiz.core.conversation

import app.juiz.core.model.Task
import app.juiz.core.policy.EscalationSignal
import kotlinx.coroutines.CancellationException

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

    /** 开场白由模板生成（不经模型），这里只把它记入历史。 */
    fun opening(greeting: String): List<EngineOutput> {
        history += ChatItem.Assistant(greeting)
        recorder.record("assistant", greeting)
        return context.escalation.initial().map { EngineOutput.Escalation(it) }
    }

    suspend fun respond(callerText: String, emit: suspend (EngineOutput) -> Unit) {
        recorder.record("caller", callerText)
        context.confirmations.observeCallerUtterance(callerText)
        for (s in context.escalation.inspectCaller(callerText)) emit(EngineOutput.Escalation(s))
        history += ChatItem.User(PromptBuilder.wrapCaller(callerText))

        var round = 0
        while (true) {
            val chunker = SentenceChunker()
            val spoken = StringBuilder()
            val calls = mutableListOf<ChatItem.ToolCall>()
            try {
                model.stream(ChatRequest(systemPrompt, history.toList(), tools)).collect { ev ->
                    when (ev) {
                        is ChatEvent.TextDelta -> for (s in chunker.push(ev.text)) {
                            spoken.append(s)
                            emit(EngineOutput.Speech(s))
                        }
                        is ChatEvent.ToolCallDone -> calls += ChatItem.ToolCall(ev.callId, ev.name, ev.arguments)
                        is ChatEvent.Completed -> Unit
                    }
                }
                chunker.flush()?.let {
                    spoken.append(it)
                    emit(EngineOutput.Speech(it))
                }
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
                for (s in context.escalation.inspectAssistant(text)) emit(EngineOutput.Escalation(s))
            }
            if (calls.isEmpty()) return

            for (c in calls) {
                history += c
                val outcome = executor.execute(c, context)
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
}
