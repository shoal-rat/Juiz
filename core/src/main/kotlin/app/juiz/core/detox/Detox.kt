package app.juiz.core.detox

import app.juiz.core.conversation.ChatEvent
import app.juiz.core.conversation.ChatItem
import app.juiz.core.conversation.ChatModel
import app.juiz.core.conversation.ChatRequest
import app.juiz.core.util.JuizJson
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/**
 * 去情绪：把对方话里的辱骂、贬损和情绪宣泄剥掉，只留下"要做什么、何时、为什么不满"。
 * 两层：
 *  - [LexiconFilter]：本地词表，零延迟，用于实时字幕的第一版；
 *  - [DetoxRewriter]：模型改写，几百毫秒后替换字幕，并用于挂断后的摘要。
 * 输出只给主人看，从不播给对方。
 */
@Serializable
data class DetoxLine(
    /** 去掉情绪后的平静表述。 */
    val calm: String,
    /** 这句话里的具体要求。 */
    val requests: List<String> = emptyList(),
    val deadline: String? = null,
    /** 对方不满的事实原因（不是情绪）。 */
    val concern: String? = null,
    /** 0 平静，1 不耐烦，2 生气，3 辱骂。 */
    val intensity: Int = 0,
    val filteredCount: Int = 0,
)

object LexiconFilter {
    /** 人身攻击与辱骂：整段剔除。 */
    private val insults = listOf(
        "废物", "蠢货", "蠢猪", "猪脑子", "白痴", "弱智", "脑残", "智障", "傻逼", "傻子", "傻瓜", "笨蛋", "饭桶", "垃圾",
        "没用的东西", "什么东西", "脑子进水", "脑子被驴踢", "有病吧", "你有病", "神经病", "滚蛋", "滚出去", "给我滚",
        "他妈的", "妈的", "我靠", "卧槽", "去死", "丢人现眼", "不要脸", "混账", "混蛋", "王八蛋", "狗东西",
        "你是猪吗", "你是不是傻", "你长没长脑子", "你脑子呢", "你还能干什么", "要你有什么用", "养你有什么用",
    )

    /** 情绪化的反问与宣泄：不带信息量，剔除。 */
    private val venting = listOf(
        Regex("你(到底|究竟)?(是)?怎么(搞|做|想)的[？?！!。]*"),
        Regex("(我)?说(了|过)(多少|几)(遍|次)(了)?[？?！!。]*"),
        Regex("你(自己)?(看看|想想)你(干|做)的(好事|什么)[？?！!。]*"),
        Regex("(真是|简直)?(气死我了|太让我失望了|无语了?)[！!。]*"),
        Regex("你(还)?想不想干了[？?！!。]*"),
        Regex("能不能(长点心|用点心)[？?！!。]*"),
    )

    private val deadlineRe = Regex(
        "(今天|今晚|明天|明早|明晚|后天|下周[一二三四五六日天]?|周[一二三四五六日天]|星期[一二三四五六日天]|\\d{1,2}月\\d{1,2}[日号])" +
            "(上午|下午|晚上|早上|中午|凌晨)?" +
            "([零一二两三四五六七八九十\\d]{1,3}[点:：]([半一二三四五六七八九十\\d]{0,3}分?)?)?" +
            "(之前|以前|前|下班前|为止)?",
    )

    /** 只有时间没有日期的期限，例如"下午三点""十点之前"。 */
    private val timeOnlyRe = Regex("(上午|下午|晚上|早上|中午|凌晨)?[零一二两三四五六七八九十\\d]{1,3}[点:：]([半一二三四五六七八九十\\d]{0,3}分?)?(之前|以前|前)?")

    private val clauseSplit = Regex("(?<=[，。！？!?,；;\\n])")

    /**
     * 按分句过滤：含辱骂或纯情绪宣泄的分句整句剔除（避免留下"你是吗"这种残片），
     * 其余分句保留原意，感叹号改成句号。
     */
    fun filter(text: String): DetoxLine {
        var count = 0
        val kept = mutableListOf<String>()
        for (clause in text.split(clauseSplit)) {
            if (clause.isBlank()) continue
            val insultHits = insults.count { it in clause }
            val ventHits = venting.count { it.containsMatchIn(clause) }
            if (insultHits + ventHits > 0) {
                count += insultHits + ventHits
                continue
            }
            kept += clause
        }
        val exclaims = text.count { it == '！' || it == '!' }
        val calm = kept.joinToString("").trim()
            .replace(Regex("[！!]+"), "。")
            .replace(Regex("[？?]{2,}"), "？")
            .replace(Regex("^[，。、,\\s]+"), "")
            .replace(Regex("[，,]$"), "。")
        val intensity = when {
            insults.any { it in text } || count >= 2 -> 3
            count == 1 || exclaims >= 3 -> 2
            exclaims >= 1 -> 1
            else -> 0
        }
        return DetoxLine(
            calm = calm.ifEmpty { EMOTION_ONLY },
            deadline = deadlineRe.find(text)?.value ?: timeOnlyRe.find(text)?.value,
            intensity = intensity,
            filteredCount = count,
        )
    }
}

const val EMOTION_ONLY = "（这句只有情绪表达，没有具体内容）"

/** 用模型把一句话改写成平静版本，并抽出要求、期限和不满的原因。失败时退回词表结果。 */
class DetoxRewriter(private val model: ChatModel) {
    private val system = """
        你是一个"情绪滤网"。输入是电话里对方说的一句话，读者是接电话的本人。
        任务：去掉其中的辱骂、贬损、讽刺、情绪宣泄和重复，只保留事实与要求，用平静、中性的第二人称转述。
        不要评价对方，不要安慰，不要添加原话里没有的内容；原话里的要求一条都不能漏，数字、时间、名称照原样保留。
        如果原话只有情绪、没有任何事实或要求，calm 填空字符串。
        只输出一个 JSON 对象，不要任何其他文字：
        {"calm":"平静转述","requests":["具体要求"],"deadline":"期限或null","concern":"对方不满的事实原因或null","intensity":0到3的整数}
        intensity：0 平静，1 不耐烦，2 明显生气，3 辱骂或人身攻击。
    """.trimIndent()

    suspend fun rewrite(text: String): DetoxLine {
        val fallback = LexiconFilter.filter(text)
        return try {
            val raw = collect(model, system, "原话：$text")
            val parsed = JuizJson.decodeFromString(DetoxLine.serializer(), extractJson(raw))
            val modelCalm = parsed.calm.trim().takeUnless { it.isEmpty() || it.startsWith("无") && ("要求" in it || "内容" in it || "信息" in it) }
            // 词表过滤后还留有实在内容（事实/要求）时，模型不能把整句判成"只有情绪"
            val calm = modelCalm ?: if (fallback.calm != EMOTION_ONLY && fallback.calm.length >= 6) fallback.calm else EMOTION_ONLY
            parsed.copy(calm = calm, deadline = parsed.deadline?.takeIf { it.isNotBlank() && it != "null" } ?: fallback.deadline, filteredCount = fallback.filteredCount, intensity = maxOf(parsed.intensity, fallback.intensity))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fallback
        }
    }
}

@Serializable
data class DigestItem(val what: String, val due: String? = null, val detail: String? = null)

@Serializable
data class CallDigest(
    /** 两三句话的平静概述。 */
    val summary: String,
    val action_items: List<DigestItem> = emptyList(),
    /** 对方不满的具体、可改进的原因。 */
    val concerns: List<String> = emptyList(),
    /** 本人在通话中已经答应的事，需要主人留意。 */
    val commitments: List<String> = emptyList(),
    val tone_level: Int = 0,
    val tone_note: String? = null,
    val filtered_count: Int = 0,
)

/** 挂断后生成的去情绪摘要：只给主人看，原始转写另行保存（受同意与保留期限约束）。 */
class DigestBuilder(private val model: ChatModel?) {
    private val system = """
        你在为接电话的本人整理一通电话的"去情绪摘要"。对方可能情绪激动甚至辱骂。
        要求：
        - 完全不复述辱骂和贬损的字句，也不要评价对方的人品；
        - 把所有具体要求逐条列出，保留数字、时间、文件名、人名；
        - 把对方不满的"事实原因"写成可以改进的点（例如"周报里的 Q3 数据和财务口径不一致"）；
        - 列出本人在电话里已经答应的事；
        - tone_level 0–3 表示对方情绪强度，tone_note 用一句中性的话描述（例如"对方较为急躁"）。
        只输出一个 JSON 对象：
        {"summary":"…","action_items":[{"what":"…","due":"…或null","detail":"…或null"}],"concerns":["…"],"commitments":["…"],"tone_level":0,"tone_note":"…"}
    """.trimIndent()

    /** turns：按顺序的 (说话人, 文本)，说话人为 "caller" 或 "owner"/"assistant"。 */
    suspend fun build(turns: List<Pair<String, String>>): CallDigest {
        val callerLines = turns.filter { it.first == "caller" }.map { it.second }
        val lexicon = callerLines.map { LexiconFilter.filter(it) }
        val filtered = lexicon.sumOf { it.filteredCount }
        val maxTone = lexicon.maxOfOrNull { it.intensity } ?: 0
        if (model != null) {
            try {
                val transcript = turns.joinToString("\n") { (who, text) ->
                    val label = if (who == "caller") "对方" else "本人"
                    "$label：$text"
                }
                val raw = collect(model, system, transcript)
                val d = JuizJson.decodeFromString(CallDigest.serializer(), extractJson(raw))
                return d.copy(filtered_count = filtered, tone_level = maxOf(d.tone_level, maxTone))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 退回本地规则
            }
        }
        return CallDigest(
            summary = lexicon.joinToString(" ") { it.calm }.take(200),
            action_items = lexicon.mapNotNull { l -> l.deadline?.let { DigestItem(l.calm.take(60), it) } },
            tone_level = maxTone,
            tone_note = if (maxTone >= 2) "对方情绪较为激动（具体措辞已过滤）" else null,
            filtered_count = filtered,
        )
    }
}

internal suspend fun collect(model: ChatModel, system: String, user: String): String {
    val sb = StringBuilder()
    model.stream(ChatRequest(system, listOf(ChatItem.User(user)), emptyList(), maxOutputTokens = 700)).collect {
        if (it is ChatEvent.TextDelta) sb.append(it.text)
    }
    return sb.toString()
}

/** 模型偶尔会在 JSON 外包一层 ``` 或解释文字，取第一个完整的对象。 */
internal fun extractJson(raw: String): String {
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    require(start >= 0 && end > start) { "没有 JSON" }
    return raw.substring(start, end + 1)
}
