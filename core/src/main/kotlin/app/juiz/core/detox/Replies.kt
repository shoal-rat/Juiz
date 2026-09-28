package app.juiz.core.detox

import app.juiz.core.conversation.ChatModel
import app.juiz.core.util.JuizJson
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

@Serializable
data class ReplyOption(val label: String, val text: String)

@Serializable
private data class ReplyOptions(val options: List<ReplyOption>)

/**
 * 选句代答：本人接听时，Juiz 根据对方刚说的话准备几句可以直接说出口的回复，
 * 本人选一句（或自己输入），再由语音合成说给对方。每一句都是本人选定的，Juiz 不替本人做决定。
 */
class ReplySuggester(private val model: ChatModel?) {
    private val system = """
        你在帮接电话的本人准备回复。对方可能在发火，但本人只需要平静、得体地回应。
        根据对方最新的话，给出 3 句本人可以直接说出口的中文回复：
        1. label "确认"：确认对方的具体要求（只复述对方已提出的内容，不额外答应别的）；
        2. label "说明"：简短说明情况，或询问一个关键细节；
        3. label "缓一缓"：礼貌地争取时间，稍后给出答复。
        要求：第一人称；每句不超过 30 个字；不卑不亢，不反复道歉，不顶撞；
        不承诺对方没提的事，不编造事实，不提 AI。
        只输出 JSON：{"options":[{"label":"确认","text":"…"},{"label":"说明","text":"…"},{"label":"缓一缓","text":"…"}]}
    """.trimIndent()

    suspend fun suggest(latest: DetoxLine, history: List<Pair<String, String>>): List<ReplyOption> {
        val fallback = fallback(latest)
        val m = model ?: return fallback
        return try {
            val ctx = history.takeLast(6).joinToString("\n") { (who, t) -> (if (who == "caller") "对方：" else "本人：") + t }
            val raw = collect(m, system, "此前的对话：\n$ctx\n\n对方最新的话（已去掉情绪）：${latest.calm}")
            val parsed = JuizJson.decodeFromString(ReplyOptions.serializer(), extractJson(raw)).options
                .filter { it.text.isNotBlank() && it.text.length <= 60 }
                .take(3)
            if (parsed.isEmpty()) fallback else parsed
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            fallback
        }
    }

    companion object {
        fun fallback(latest: DetoxLine): List<ReplyOption> = listOfNotNull(
            latest.requests.firstOrNull()?.let { ReplyOption("确认", "好的，我明白了，${it.trimEnd('。')}。") }
                ?: ReplyOption("确认", "好的，我明白您的意思了。"),
            latest.deadline?.let { ReplyOption("说明", "我确认一下，是${it}对吗？") } ?: ReplyOption("说明", "我想确认一下具体要求，您看方便说一下吗？"),
            ReplyOption("缓一缓", "收到，我整理一下，稍后给您答复。"),
        )
    }
}
