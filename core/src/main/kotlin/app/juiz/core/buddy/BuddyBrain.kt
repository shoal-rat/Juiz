package app.juiz.core.buddy

import app.juiz.core.conversation.ChatEvent
import app.juiz.core.conversation.ChatItem
import app.juiz.core.conversation.ChatModel
import app.juiz.core.conversation.ChatRequest
import app.juiz.core.util.JuizJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

/** 伙伴能摆出的表情（与应用里的立绘一一对应）。 */
enum class BuddyMood { IDLE, HAPPY, WAVE, SURPRISED, SLEEPY, DETERMINED, THINKING }

enum class BuddyEvent(val zh: String) {
    OPEN_APP("{addr}打开了应用"),
    TAP("{addr}轻轻点了你一下"),
    DOUBLE_TAP("{addr}连点了你两下"),
    LONG_PRESS("{addr}按住你不放"),
    CHAT("{addr}对你说了一句话"),
}

/** 伙伴此刻"知道"的事。只能根据这些说话，不能编造。 */
data class BuddyFacts(
    val hour: Int,
    val ownerName: String = "",
    /** Juiz 怎么称呼使用者，默认"伙伴"。 */
    val addressAs: String = "伙伴",
    val pendingTasks: Int = 0,
    val needsVerify: Int = 0,
    val errandsLastNight: Int = 0,
    val recentDigest: Boolean = false,
    val dialerReady: Boolean = true,
    val liveCall: String? = null,
)

data class BuddySay(val mood: BuddyMood, val text: String)

@Serializable
private data class RawSay(val mood: String = "IDLE", val text: String = "")

/**
 * 伙伴的"大脑"：用使用者配置的模型（ChatGPT 或本地小模型）生成一句符合情境的话和表情。
 * 模型不可用、超时或输出不合规时返回 null，界面退回预设台词。
 */
class BuddyBrain(private val model: ChatModel?, private val timeoutMs: Long = 6000) {
    private val recent = ArrayDeque<String>()
    /** 最近一次模型原始输出，便于调试"为什么回退了"。 */
    @Volatile var lastRaw: String? = null
        private set

    private fun system(addr: String) = """
        你是 Juiz，一个住在{addr}手机里的迷你礼宾助理（银蓝短发、头顶一缕呆毛、戴耳麦）。你叫手机的使用者"{addr}"。
        性格：温柔、可靠、有一点点俏皮，会关心{addr}，但从不啰嗦、不油腻、不卖惨。
        你的本职是替{addr}应付电话：接听、过滤难听的话、深夜按授权办小事、整理委托。
        规则：
        - 只说一句中文，不超过 28 个字；不用表情符号，不用引号。
        - 只能根据给你的"现在的情况"说话，没有提到的事不要编（比如不要凭空说"替你办了几件事"）。
        - 称呼对方只用"{addr}"或"你"（这是{addr}自己选的称呼），不要叫名字，不要用"主人"，也不要加"先生""女士""小姐"之类的称谓。
        - 深夜（0–5 点）温柔地劝{addr}去睡，告诉{addr}夜里的电话有你。
        - 从这些表情里选一个最贴切的：IDLE HAPPY WAVE SURPRISED SLEEPY DETERMINED THINKING。
        只输出 JSON：{"mood":"HAPPY","text":"……"}
    """.trimIndent().replace("{addr}", addr)

    fun situation(f: BuddyFacts): String = buildString {
        append("现在是 ${f.hour} 点。")
        val a = f.addressAs
        if (!f.dialerReady) append("${a}还没把 Juiz 设为默认电话应用，你暂时接不了电话。")
        f.liveCall?.let { append("此刻：$it。") }
        if (f.pendingTasks > 0) append("有 ${f.pendingTasks} 件委托等${a}确认。")
        if (f.needsVerify > 0) append("有 ${f.needsVerify} 件交付物等${a}核对。")
        if (f.errandsLastNight > 0) append("昨晚你按授权替${a}办了 ${f.errandsLastNight} 件小事。")
        if (f.recentDigest) append("刚才有一通电话，你把难听的话过滤掉，整理成了要点。")
        if (f.pendingTasks == 0 && f.needsVerify == 0 && f.errandsLastNight == 0 && !f.recentDigest && f.liveCall == null) append("今天没有待办，很平静。")
    }

    suspend fun react(event: BuddyEvent, facts: BuddyFacts, userText: String? = null): BuddySay? {
        val m = model ?: return null
        val prompt = buildString {
            appendLine("现在的情况：${situation(facts)}")
            appendLine("刚发生的事：${event.zh.replace("{addr}", facts.addressAs)}${userText?.let { "：「${it.take(80)}」" } ?: ""}")
        }
        return try {
            val raw = withTimeoutOrNull(timeoutMs) {
                val sb = StringBuilder()
                m.stream(ChatRequest(system(facts.addressAs), listOf(ChatItem.User(prompt)), emptyList(), maxOutputTokens = 120)).collect {
                    if (it is ChatEvent.TextDelta) sb.append(it.text)
                }
                sb.toString()
            } ?: return null
            lastRaw = raw
            val parsed = parse(raw) ?: return null
            var text = parsed.text.trim().trim('"', '“', '”', '「', '」').replace("\n", "")
            // 使用者选了怎么被称呼，就不用名字叫他（模型偶尔还是会叫名字）
            if (facts.ownerName.isNotBlank() && facts.ownerName != "机主" && facts.addressAs.isNotBlank()) text = text.replace(facts.ownerName, facts.addressAs)
            if (text.isEmpty() || text.length > 40 || text in recent) return null
            val mood = runCatching { BuddyMood.valueOf(parsed.mood.trim().uppercase()) }.getOrDefault(BuddyMood.IDLE)
            remember(text)
            BuddySay(mood, text)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** 先按严格 JSON 解析；小模型常把结尾引号写成全角"”"，再用宽松规则兜底。 */
    private fun parse(raw: String): RawSay? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start >= 0 && end > start) {
            runCatching { return JuizJson.decodeFromString(RawSay.serializer(), raw.substring(start, end + 1)) }
        }
        val mood = Regex("\"mood\"\\s*[:：]\\s*[\"“]?(\\w+)").find(raw)?.groupValues?.get(1) ?: "IDLE"
        val text = Regex("\"text\"\\s*[:：]\\s*[\"“](.+?)[\"”]\\s*}").find(raw)?.groupValues?.get(1) ?: return null
        return RawSay(mood, text)
    }

    private fun remember(text: String) {
        recent.addLast(text)
        while (recent.size > 5) recent.removeFirst()
    }
}
