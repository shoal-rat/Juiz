package app.juiz.core.settings

import app.juiz.core.db.JuizDatabase
import app.juiz.core.model.OwnerProfile
import app.juiz.core.policy.Disclosure
import app.juiz.core.rules.CallRule
import app.juiz.core.rules.DefaultRules
import app.juiz.core.util.JuizJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable
enum class LlmProvider { OPENAI_RESPONSES, OPENAI_COMPATIBLE_CHAT }

@Serializable
enum class TtsProvider { ELEVENLABS, OPENAI }

/** 供应商选择与非机密参数。密钥不在这里，由平台的安全存储（Android Keystore / 环境变量）提供。 */
@Serializable
data class ProviderConfig(
    val llm: LlmProvider = LlmProvider.OPENAI_RESPONSES,
    val llmModel: String = "gpt-6-luna",
    val reasoningEffort: String = "none",
    /** 兼容 Chat Completions 的端点，例如本机 ollama 的 http://127.0.0.1:11434/v1 */
    val compatBaseUrl: String = "",
    val compatModel: String = "",
    /** 兼容端点的推理强度。ollama 上的 qwen3.5 需要 "none"，否则会把 token 全花在思考上、正文为空。留空表示不发送。 */
    val compatReasoningEffort: String = "none",
    val sttModel: String = "gpt-live-transcribe",
    val sttLanguages: List<String> = listOf("zh", "en"),
    val sttKeywords: List<String> = emptyList(),
    val tts: TtsProvider = TtsProvider.OPENAI,
    val elevenVoiceId: String = "",
    val elevenModel: String = "eleven_flash_v2_5",
    val openAiVoice: String = "marin",
    val openAiTtsModel: String = "gpt-4o-mini-tts",
)

@Serializable
data class WorkConfig(
    /** Workspace Agents API 的触发 ID（agtch_…）。为空表示只用"分享任务卡"路径。 */
    val triggerId: String = "",
    val baseUrl: String = "https://api.chatgpt.com/v1",
    /** 交换目录的说明文字（写进任务卡），实际访问由平台授权。 */
    val exchangeFolderHint: String = "Juiz",
    /** 手机直连 OpenAI 云端执行（需要 OpenAI API Key）。 */
    val cloudEnabled: Boolean = true,
    val cloudModel: String = "gpt-6-sol",
    val cloudEffort: String = "medium",
    /** 电脑上运行着 juiz-desk（Codex 执行器），可以把任务交给它（可选）。 */
    val deskEnabled: Boolean = false,
    /** 通讯录联系人/重要联系人提出的任务，创建后直接交给执行方；外发仍需本人批准。 */
    val autoHandoff: Boolean = false,
    val autoRoute: String = "OPENAI_CLOUD",
    val autoHandoffDailyLimit: Int = 5,
)

@Serializable
data class BehaviorConfig(
    val autoAnswerEnabled: Boolean = true,
    /** 升级时让手机重新响铃（L1）。关闭则只发通知。 */
    val ringOnEscalation: Boolean = true,
    val transcriptRetentionDays: Int = 30,
    /** 通话录音（加密）保留天数，过期自动删除。 */
    val recordingRetentionDays: Int = 30,
    val smsSessionHours: Int = 24,
    val smsMaxReplies: Int = 8,
    val dailyOutboundCallLimit: Int = 5,
    val maxCallMinutes: Int = 10,
)

class SettingsStore(private val db: JuizDatabase) {
    private val q get() = db.juizQueries

    private fun <T> read(key: String, ser: kotlinx.serialization.KSerializer<T>, default: T): T =
        q.getSetting(key).executeAsOneOrNull()?.let { runCatching { JuizJson.decodeFromString(ser, it) }.getOrNull() } ?: default

    private fun <T> write(key: String, ser: kotlinx.serialization.KSerializer<T>, value: T) =
        q.putSetting(key, JuizJson.encodeToString(ser, value))

    fun ownerProfile(): OwnerProfile = read("owner", OwnerProfile.serializer(), OwnerProfile())

    /** 保存前校验披露模板；不合规直接拒绝。 */
    fun saveOwnerProfile(p: OwnerProfile): List<String> {
        val problems = Disclosure.validate(p.greetingTemplate)
        if (problems.isEmpty()) write("owner", OwnerProfile.serializer(), p)
        return problems
    }

    fun rules(): List<CallRule> = read("rules", ListSerializer(CallRule.serializer()), DefaultRules.rules)
    fun saveRules(r: List<CallRule>) = write("rules", ListSerializer(CallRule.serializer()), r)

    fun providers(): ProviderConfig = read("providers", ProviderConfig.serializer(), ProviderConfig())
    fun saveProviders(p: ProviderConfig) = write("providers", ProviderConfig.serializer(), p)

    fun work(): WorkConfig = read("work", WorkConfig.serializer(), WorkConfig())
    fun saveWork(w: WorkConfig) = write("work", WorkConfig.serializer(), w)

    fun behavior(): BehaviorConfig = read("behavior", BehaviorConfig.serializer(), BehaviorConfig())
    fun saveBehavior(b: BehaviorConfig) = write("behavior", BehaviorConfig.serializer(), b)

    fun raw(key: String): String? = q.getSetting(key).executeAsOneOrNull()
    fun putRaw(key: String, value: String) = q.putSetting(key, value)
}
