package app.juiz.core.policy

/**
 * 关键信息复述确认，在代码层强制执行：
 * 1. 模型调用 confirm_details 登记要复述的字段（进入 pending）；
 * 2. 来电方的下一句话必须是肯定答复，pending 才转为 confirmed；否定或更正则作废；
 * 3. create_task 里任何"看起来是关键信息"的值，都必须出现在 confirmed 里。
 */
class ConfirmationTracker {
    private var pending: Map<String, String> = emptyMap()
    private val confirmed = linkedMapOf<String, String>()

    val confirmedFields: Map<String, String> get() = confirmed.toMap()
    val pendingFields: Map<String, String> get() = pending

    fun registerReadBack(fields: Map<String, String>) {
        pending = fields.mapValues { normalize(it.value) }
    }

    enum class Outcome { NONE_PENDING, CONFIRMED, REJECTED, UNCLEAR }

    fun observeCallerUtterance(text: String): Outcome {
        if (pending.isEmpty()) return Outcome.NONE_PENDING
        return when (classify(text)) {
            Answer.YES -> {
                confirmed.putAll(pending)
                pending = emptyMap()
                Outcome.CONFIRMED
            }
            Answer.NO -> {
                pending = emptyMap()
                Outcome.REJECTED
            }
            Answer.UNCLEAR -> Outcome.UNCLEAR
        }
    }

    /** 返回违规说明；空列表表示通过。 */
    fun violations(fields: Map<String, String>): List<String> {
        val confirmedValues = confirmed.values.toSet()
        return fields.mapNotNull { (name, raw) ->
            val v = normalize(raw)
            val byName = confirmed[name]
            when {
                byName != null && byName != v -> "字段「$name」的值（$raw）与对方确认的值（$byName）不一致"
                byName != null -> null
                looksCritical(raw) && v !in confirmedValues -> "字段「$name」的值（$raw）属于关键信息，尚未向对方复述确认"
                else -> null
            }
        }
    }

    private enum class Answer { YES, NO, UNCLEAR }

    companion object {
        private val negatives = listOf("不对", "不是", "错了", "说错", "搞错", "不太对", "不正确", "改成", "改为", "应该是", "不行", "wrong", "no,", "not ")
        private val affirmatives = listOf("对", "是的", "是", "没错", "没问题", "可以", "好的", "好", "嗯", "确认", "正确", "行", "ok", "yes", "right", "correct")

        private val leadingNoise = Regex("^[\\s，。,.!！?？~～…]*(呃|额|那个|啊)?[\\s，。,.!！?？~～…]*")

        /** 否定出现在任何位置都算否定；肯定必须出现在句首，避免"这是周五吗"被当成确认。 */
        private fun classify(text: String): Answer {
            val t = text.lowercase().replace("没错", "〇确〇").replace("不错", "〇确〇")
            if (negatives.any { it in t }) return Answer.NO
            val head = t.replace(leadingNoise, "")
            if (head.startsWith("〇确〇") || affirmatives.any { head.startsWith(it) }) return Answer.YES
            return Answer.UNCLEAR
        }

        private val chineseNumber = Regex("[零〇一二两三四五六七八九十百千万亿]+\\s*(元|块|万|千|号|日|点|月|个|份|人|天|周|年|分|%|折)")
        private val digit = Regex("\\d")

        /** 金额、日期、号码、邮箱、数量：含数字、中文数量词或 @ 的值都视为关键信息。 */
        fun looksCritical(value: String): Boolean =
            digit.containsMatchIn(value) || '@' in value || chineseNumber.containsMatchIn(value)

        fun normalize(v: String): String = v.trim().replace(Regex("\\s+"), " ")
    }
}
