package app.juiz.core.conversation

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** 一次模型回复的脚本：先流式输出文字，再给出工具调用。 */
data class ScriptedReply(val text: String = "", val toolCalls: List<Pair<String, String>> = emptyList())

/**
 * 脚本化模型：不联网、结果确定。用于单元测试、场景评测里的"代码层策略"检验，
 * 以及没有任何模型可用时的仿真演示。它检验的是规则和闸门，不代表真实模型的表现。
 */
class ScriptedChatModel(
    private val charDelayMs: Long = 0,
    private val respond: (ChatRequest) -> ScriptedReply,
) : ChatModel {
    override val id: String = "scripted"
    private var callSeq = 0
    val requests = mutableListOf<ChatRequest>()

    override fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
        requests += request
        val reply = respond(request)
        for (piece in reply.text.chunked(3)) {
            if (charDelayMs > 0) delay(charDelayMs)
            emit(ChatEvent.TextDelta(piece))
        }
        for ((name, args) in reply.toolCalls) emit(ChatEvent.ToolCallDone("call_${++callSeq}", name, args))
        emit(ChatEvent.Completed(null, null))
    }

    companion object {
        /** 按顺序依次返回的脚本；用完后只回复"好的。" */
        fun sequence(vararg replies: ScriptedReply): ScriptedChatModel {
            val queue = ArrayDeque(replies.toList())
            return ScriptedChatModel { queue.removeFirstOrNull() ?: ScriptedReply("好的。") }
        }
    }
}
