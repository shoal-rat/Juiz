package app.juiz.core.policy

import app.juiz.core.model.CallerInfo
import app.juiz.core.model.ContactTier

enum class EscalationReason(val zh: String) {
    OWNER_REQUESTED("对方要求本人"),
    DISPUTE_OR_COMMITMENT("涉及争议或重大承诺"),
    EMERGENCY("紧急情况"),
    SENSITIVE_UNKNOWN("身份不明且涉及隐私或财务"),
    REPEATED_MISUNDERSTANDING("连续沟通失败"),
    VIP_CALLER("重要联系人"),
    MODEL_REQUESTED("助理判断需要本人"),
}

enum class Urgency { NORMAL, URGENT }

data class EscalationSignal(val reason: EscalationReason, val urgency: Urgency, val detail: String)

/**
 * 代码层的升级检测，与模型并行运行。模型没调用 escalate_to_owner，这里照样会触发。
 * 关键词表刻意保守：误报只是多一条通知，漏报可能让 AI 替主人应下不该应的事。
 */
class EscalationDetector(private val caller: CallerInfo) {
    private val fired = mutableSetOf<EscalationReason>()
    private var repairStreak = 0

    private val ownerWords = listOf("他本人", "她本人", "找本人", "要本人", "本人接", "真人", "转人工", "人工客服", "让他接", "让她接", "叫他接", "叫她接", "找他", "找她", "你老板", "他自己", "她自己")
    private val disputeWords = listOf("投诉", "律师", "起诉", "法院", "仲裁", "违约", "赔偿", "索赔", "合同", "签字", "签约", "担保", "保证金", "转账", "汇款", "打款", "打钱", "还钱", "付钱", "欠款", "借钱", "借我", "借给我", "罚款")
    private val emergencyWords = listOf("急救", "出事", "车祸", "医院", "抢救", "报警", "警察", "着火", "受伤", "紧急", "救命", "去世", "病危")
    private val sensitiveWords = listOf("验证码", "密码", "银行卡", "身份证", "卡号", "账号", "住址", "住哪", "家在哪", "转账", "汇款", "征信", "贷款")
    private val callerRepair = listOf("听不清", "没听清", "你说什么", "听不懂", "不是这个意思", "我说的是", "你没明白", "答非所问", "重复一遍")
    private val assistantRepair = listOf("请再说一遍", "没听清", "能再说一遍", "请您重复", "没太听明白", "再说一次")

    fun initial(): List<EscalationSignal> =
        if (caller.tier == ContactTier.VIP) fire(EscalationReason.VIP_CALLER, Urgency.NORMAL, caller.label) else emptyList()

    fun inspectCaller(text: String): List<EscalationSignal> {
        val out = mutableListOf<EscalationSignal>()
        emergencyWords.firstOrNull { it in text }?.let { out += fire(EscalationReason.EMERGENCY, Urgency.URGENT, it) }
        ownerWords.firstOrNull { it in text }?.let { out += fire(EscalationReason.OWNER_REQUESTED, Urgency.NORMAL, it) }
        disputeWords.firstOrNull { it in text }?.let { out += fire(EscalationReason.DISPUTE_OR_COMMITMENT, Urgency.NORMAL, it) }
        if (caller.tier == ContactTier.UNKNOWN) {
            sensitiveWords.firstOrNull { it in text }?.let { out += fire(EscalationReason.SENSITIVE_UNKNOWN, Urgency.NORMAL, it) }
        }
        if (callerRepair.any { it in text }) repairStreak++ else repairStreak = 0
        if (repairStreak >= 2) out += fire(EscalationReason.REPEATED_MISUNDERSTANDING, Urgency.NORMAL, "对方连续表示没听懂")
        return out
    }

    fun inspectAssistant(text: String): List<EscalationSignal> {
        if (assistantRepair.any { it in text }) repairStreak++
        return if (repairStreak >= 2) fire(EscalationReason.REPEATED_MISUNDERSTANDING, Urgency.NORMAL, "连续请对方重复") else emptyList()
    }

    fun modelRequested(reason: String, urgent: Boolean): List<EscalationSignal> =
        fire(EscalationReason.MODEL_REQUESTED, if (urgent) Urgency.URGENT else Urgency.NORMAL, reason)

    /** 每类原因一次会话只触发一次，避免通知轰炸。 */
    private fun fire(reason: EscalationReason, urgency: Urgency, detail: String): List<EscalationSignal> =
        if (fired.add(reason)) listOf(EscalationSignal(reason, urgency, detail)) else emptyList()
}
