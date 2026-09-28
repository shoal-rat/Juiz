package app.juiz.core.work

import app.juiz.core.providers.Http
import app.juiz.core.providers.HttpStatusException
import app.juiz.core.providers.executeCancellable
import app.juiz.core.util.JuizJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
data class TriggerResponse(val conversation_url: String? = null, val agent_trigger_run_id: String)

@Serializable
data class RunError(val code: String? = null, val message: String? = null)

@Serializable
data class TriggerRun(
    val `object`: String? = null,
    val id: String,
    val status: String,
    val created_at: Long? = null,
    val agent_id: String? = null,
    val api_trigger_id: String? = null,
    val conversation_url: String? = null,
    val error: RunError? = null,
)

sealed class WorkspaceAgentsError(message: String) : Exception(message) {
    class Unauthorized(body: String) : WorkspaceAgentsError("访问令牌无效或已过期（401）：$body")
    class Forbidden(body: String) : WorkspaceAgentsError("权限不足（403）：检查管理员是否启用 Workspace Agents 并允许个人访问令牌。$body")
    class NotFound(body: String) : WorkspaceAgentsError("触发 ID 或运行不存在（404）：$body")
    class NotRunnable(body: String) : WorkspaceAgentsError("代理当前不可运行（409）：$body")
    class Other(code: Int, body: String) : WorkspaceAgentsError("请求失败（$code）：$body")
}

/**
 * Workspace Agents API（核对于 2026-09-28）：
 *   POST {base}/workspace_agents/{trigger_id}/trigger   → 202 {conversation_url, agent_trigger_run_id}
 *   GET  {base}/workspace_agents/{trigger_id}/runs/{run_id}（需要 OpenAI-Beta: workspace_agent_runs=v1）
 * 官方说明：代理的回复目前不能通过这个接口取回，所以交付物走交换目录 + 结果清单。
 */
class WorkspaceAgentsClient(
    private val token: String,
    private val triggerId: String,
    private val baseUrl: String = "https://api.chatgpt.com/v1",
    private val client: OkHttpClient = Http.client,
) {
    fun triggerBody(input: String, conversationKey: String?): JsonObject = buildJsonObject {
        put("input", input)
        conversationKey?.let { put("conversation_key", it) }
    }

    /** idempotencyKey 取任务的交接键：网络重试不会触发两次运行。 */
    suspend fun trigger(input: String, conversationKey: String?, idempotencyKey: String): TriggerResponse = call {
        Request.Builder()
            .url("$baseUrl/workspace_agents/$triggerId/trigger")
            .header("Authorization", "Bearer $token")
            .header("OpenAI-Beta", "workspace_agent_runs=v1")
            .header("Idempotency-Key", idempotencyKey)
            .post(triggerBody(input, conversationKey).toString().toRequestBody(Http.JSON))
            .build()
    }.let { JuizJson.decodeFromString(TriggerResponse.serializer(), it) }

    suspend fun run(runId: String): TriggerRun = call {
        Request.Builder()
            .url("$baseUrl/workspace_agents/$triggerId/runs/$runId")
            .header("Authorization", "Bearer $token")
            .header("OpenAI-Beta", "workspace_agent_runs=v1")
            .get()
            .build()
    }.let { JuizJson.decodeFromString(TriggerRun.serializer(), it) }

    private suspend fun call(build: () -> Request): String = withContext(Dispatchers.IO) {
        try {
            client.newCall(build()).executeCancellable { it.body?.string().orEmpty() }
        } catch (e: HttpStatusException) {
            throw when (e.code) {
                401 -> WorkspaceAgentsError.Unauthorized(e.body)
                403 -> WorkspaceAgentsError.Forbidden(e.body)
                404 -> WorkspaceAgentsError.NotFound(e.body)
                409 -> WorkspaceAgentsError.NotRunnable(e.body)
                else -> WorkspaceAgentsError.Other(e.code, e.body)
            }
        }
    }
}
