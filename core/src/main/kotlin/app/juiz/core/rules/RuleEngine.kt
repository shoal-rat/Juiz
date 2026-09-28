package app.juiz.core.rules

import app.juiz.core.model.CallerInfo
import app.juiz.core.model.CapabilityLevel
import app.juiz.core.model.ContactTier
import app.juiz.core.util.normalizeNumber
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime

@Serializable
enum class CallActionType(val zh: String) {
    /** 正常响铃，不做任何代接。 */
    RING_ONLY("只响铃"),
    /** 响铃 delaySeconds 秒无人接后，AI 语音代接（需要 L1）。 */
    AI_VOICE_ANSWER("AI 语音代接"),
    /** 响铃 delaySeconds 秒无人接后，拒接并以短信继续（L0）。 */
    SMS_SCREEN("短信代办"),
    /** 静默拒接（骚扰）。 */
    REJECT_SILENT("静默拒接"),
    /** 正常响铃，本人接起后自动开启情绪滤网（需要 L1）。 */
    RING_WITH_SHIELD("本人接听 · 情绪滤网"),
}

@Serializable
data class TimeWindow(
    val days: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    /** "HH:mm"；start > end 表示跨午夜，例如 22:00–07:00。 */
    val start: String = "00:00",
    val end: String = "23:59",
) {
    fun contains(t: ZonedDateTime): Boolean {
        val s = LocalTime.parse(start)
        val e = LocalTime.parse(end)
        val now = t.toLocalTime()
        return if (!s.isAfter(e)) {
            t.dayOfWeek in days && !now.isBefore(s) && !now.isAfter(e)
        } else {
            // 跨午夜：凌晨那一段属于前一天设定的窗口
            (t.dayOfWeek in days && !now.isBefore(s)) ||
                (t.dayOfWeek.minus(1) in days && !now.isAfter(e))
        }
    }
}

@Serializable
data class RuleMatch(
    val tiers: Set<ContactTier>? = null,
    val numbers: List<String>? = null,
    val windows: List<TimeWindow>? = null,
)

@Serializable
data class CallRule(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val match: RuleMatch = RuleMatch(),
    val action: CallActionType,
    val delaySeconds: Int = 15,
)

data class CallDecision(
    val action: CallActionType,
    val delaySeconds: Int,
    val ruleId: String?,
    /** 规则要求的动作因能力或授权不足被降级时，写明原因并进入档案。 */
    val downgradeReason: String? = null,
)

/** 紧急号码：来电和去电一律不代接、不拦截。 */
object EmergencyNumbers {
    private val numbers = setOf("110", "119", "120", "122", "112", "911", "999", "000", "12110", "12395")
    fun isEmergency(number: String): Boolean = normalizeNumber(number) in numbers
}

object DefaultRules {
    val rules: List<CallRule> = listOf(
        CallRule("spam", "骚扰号码静默拒接", match = RuleMatch(tiers = setOf(ContactTier.SPAM)), action = CallActionType.REJECT_SILENT, delaySeconds = 0),
        CallRule("vip", "重要联系人只响铃", match = RuleMatch(tiers = setOf(ContactTier.VIP)), action = CallActionType.RING_ONLY),
        CallRule(
            "night", "夜间陌生来电代接",
            match = RuleMatch(tiers = setOf(ContactTier.UNKNOWN), windows = listOf(TimeWindow(start = "22:00", end = "07:30"))),
            action = CallActionType.AI_VOICE_ANSWER, delaySeconds = 3,
        ),
        CallRule("known", "通讯录联系人 20 秒未接代接", match = RuleMatch(tiers = setOf(ContactTier.KNOWN)), action = CallActionType.AI_VOICE_ANSWER, delaySeconds = 20),
        CallRule("unknown", "陌生来电 10 秒未接代接", match = RuleMatch(tiers = setOf(ContactTier.UNKNOWN)), action = CallActionType.AI_VOICE_ANSWER, delaySeconds = 10),
    )
}

/**
 * 来电决策：规则按顺序匹配，第一条命中的生效。
 * 规则只描述主人的意图；能不能做到由设备能力和同意账本决定，做不到就降级并说明原因。
 */
class RuleEngine(private val rules: List<CallRule>) {

    fun decide(
        caller: CallerInfo,
        at: ZonedDateTime,
        capability: CapabilityLevel,
        smsScreeningConsented: Boolean,
        autoAnswerEnabled: Boolean = true,
    ): CallDecision {
        if (EmergencyNumbers.isEmergency(caller.number)) {
            return CallDecision(CallActionType.RING_ONLY, 0, null, "紧急号码不代接")
        }
        val number = normalizeNumber(caller.number)
        val rule = rules.firstOrNull { it.enabled && matches(it.match, caller, number, at) }
            ?: return CallDecision(CallActionType.RING_ONLY, 0, null)

        if (!autoAnswerEnabled && rule.action in setOf(CallActionType.AI_VOICE_ANSWER, CallActionType.SMS_SCREEN)) {
            return CallDecision(CallActionType.RING_ONLY, 0, rule.id, "主人已暂停自动代接")
        }
        return when (rule.action) {
            CallActionType.AI_VOICE_ANSWER -> when {
                capability == CapabilityLevel.L1_PRIVILEGED_VOICE -> CallDecision(rule.action, rule.delaySeconds, rule.id)
                smsScreeningConsented -> CallDecision(CallActionType.SMS_SCREEN, rule.delaySeconds, rule.id, "本机未通过语音代接验证，降级为短信代办")
                else -> CallDecision(CallActionType.RING_ONLY, 0, rule.id, "本机未通过语音代接验证，且未开启短信代办")
            }
            CallActionType.RING_WITH_SHIELD ->
                if (capability == CapabilityLevel.L1_PRIVILEGED_VOICE) CallDecision(rule.action, 0, rule.id)
                else CallDecision(CallActionType.RING_ONLY, 0, rule.id, "情绪滤网需要通话音频（L1），本机按普通来电处理")
            CallActionType.SMS_SCREEN ->
                if (smsScreeningConsented) CallDecision(rule.action, rule.delaySeconds, rule.id)
                else CallDecision(CallActionType.RING_ONLY, 0, rule.id, "未开启短信代办")
            else -> CallDecision(rule.action, rule.delaySeconds, rule.id)
        }
    }

    private fun matches(m: RuleMatch, caller: CallerInfo, number: String, at: ZonedDateTime): Boolean {
        if (m.tiers != null && caller.tier !in m.tiers) return false
        if (m.numbers != null && m.numbers.none { normalizeNumber(it) == number }) return false
        if (m.windows != null && m.windows.none { it.contains(at) }) return false
        return true
    }
}
