package app.juiz.core.conversation

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/** 与供应商无关的对话历史条目。 */
sealed interface ChatItem {
    data class User(val text: String) : ChatItem
    data class Assistant(val text: String) : ChatItem
    data class ToolCall(val callId: String, val name: String, val arguments: String) : ChatItem
    data class ToolResult(val callId: String, val output: String) : ChatItem
}

data class ToolSpec(val name: String, val description: String, val parameters: JsonObject)

data class ChatRequest(
    val system: String,
    val history: List<ChatItem>,
    val tools: List<ToolSpec>,
    val maxOutputTokens: Int = 400,
)

sealed interface ChatEvent {
    data class TextDelta(val text: String) : ChatEvent
    data class ToolCallDone(val callId: String, val name: String, val arguments: String) : ChatEvent
    data class Completed(val inputTokens: Int?, val outputTokens: Int?) : ChatEvent
}

class ModelException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 文字模型：流式输出文字增量和工具调用。实现必须在协程取消时中止网络请求。 */
interface ChatModel {
    val id: String
    fun stream(request: ChatRequest): Flow<ChatEvent>
}
