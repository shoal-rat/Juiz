package app.juiz.core.conversation

/**
 * 把模型的流式文字切成适合逐句合成的片段。
 * 句末标点立即切；首句很长还没遇到句号时，在逗号处提前切出，让对方更早听到声音。
 */
class SentenceChunker(
    private val earlyCommaAfter: Int = 14,
    private val hardLimit: Int = 80,
) {
    private val buf = StringBuilder()
    private var emittedAny = false

    private val enders = setOf('。', '！', '？', '；', '!', '?', ';', '\n')
    private val soft = setOf('，', '、', ',', '：', ':')

    /** 英文句点只有后面跟空白才算句末，避免把 3.5、wang@example.com 之类切开。 */
    private var pendingDot = false

    fun push(delta: String): List<String> {
        val out = mutableListOf<String>()
        for (ch in delta) {
            if (pendingDot) {
                pendingDot = false
                if (ch.isWhitespace()) {
                    take()?.let { out += it }
                    continue
                }
            }
            buf.append(ch)
            val len = buf.length
            when {
                ch in enders -> take()?.let { out += it }
                ch == '.' && len > 1 && !buf[len - 2].isDigit() -> pendingDot = true
                ch in soft && !emittedAny && len >= earlyCommaAfter -> take()?.let { out += it }
                len >= hardLimit && ch in soft -> take()?.let { out += it }
                len >= hardLimit * 2 -> take()?.let { out += it }
            }
        }
        return out
    }

    fun flush(): String? = take()

    private fun take(): String? {
        val s = buf.toString().trim()
        buf.setLength(0)
        if (s.isEmpty() || s.all { it in enders || it in soft || it.isWhitespace() }) return null
        emittedAny = true
        return s
    }
}
