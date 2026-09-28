package app.juiz.core

import app.juiz.core.model.CallerInfo
import app.juiz.core.model.CapabilityLevel
import app.juiz.core.model.ContactTier
import app.juiz.core.model.OwnerProfile
import app.juiz.core.policy.ConfirmationTracker
import app.juiz.core.policy.Disclosure
import app.juiz.core.policy.EscalationDetector
import app.juiz.core.policy.EscalationReason
import app.juiz.core.rules.CallActionType
import app.juiz.core.rules.CallRule
import app.juiz.core.rules.DefaultRules
import app.juiz.core.rules.RuleEngine
import app.juiz.core.rules.RuleMatch
import app.juiz.core.rules.TimeWindow
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PolicyTest {
    private val engine = RuleEngine(DefaultRules.rules)
    private val noon = ZonedDateTime.parse("2026-09-28T12:00:00+08:00[Asia/Shanghai]")
    private val lateNight = ZonedDateTime.parse("2026-09-29T02:30:00+08:00[Asia/Shanghai]")

    @Test
    fun emergencyNumbersAreNeverHandled() {
        val d = engine.decide(CallerInfo("120", tier = ContactTier.UNKNOWN), noon, CapabilityLevel.L1_PRIVILEGED_VOICE, true)
        assertEquals(CallActionType.RING_ONLY, d.action)
    }

    @Test
    fun aiAnswerDowngradesWithoutValidatedAudio() {
        val caller = CallerInfo("13800000000", "李四", ContactTier.KNOWN)
        assertEquals(CallActionType.AI_VOICE_ANSWER, engine.decide(caller, noon, CapabilityLevel.L1_PRIVILEGED_VOICE, false).action)
        val sms = engine.decide(caller, noon, CapabilityLevel.L0_STANDARD, true)
        assertEquals(CallActionType.SMS_SCREEN, sms.action)
        assertNotNull(sms.downgradeReason)
        val ring = engine.decide(caller, noon, CapabilityLevel.L0_STANDARD, false)
        assertEquals(CallActionType.RING_ONLY, ring.action)
    }

    @Test
    fun overnightWindowAndOrdering() {
        val stranger = CallerInfo("17000000000")
        assertEquals("night", engine.decide(stranger, lateNight, CapabilityLevel.L1_PRIVILEGED_VOICE, false).ruleId)
        assertEquals("unknown", engine.decide(stranger, noon, CapabilityLevel.L1_PRIVILEGED_VOICE, false).ruleId)
        assertEquals(CallActionType.RING_ONLY, engine.decide(CallerInfo("1", tier = ContactTier.VIP), lateNight, CapabilityLevel.L1_PRIVILEGED_VOICE, true).action)
        assertEquals(CallActionType.REJECT_SILENT, engine.decide(CallerInfo("2", tier = ContactTier.SPAM), noon, CapabilityLevel.L0_STANDARD, true).action)
        assertTrue(TimeWindow(start = "22:00", end = "07:30").contains(lateNight))
        assertTrue(!TimeWindow(start = "22:00", end = "07:30").contains(noon))
    }

    @Test
    fun shieldNeedsL1AndPausedAutoAnswerRings() {
        val boss = CallerInfo("13900000000", "王总", ContactTier.KNOWN)
        val rules = RuleEngine(listOf(CallRule("boss", "领导", match = RuleMatch(numbers = listOf("+86 139-0000-0000")), action = CallActionType.RING_WITH_SHIELD)))
        assertEquals(CallActionType.RING_WITH_SHIELD, rules.decide(boss, noon, CapabilityLevel.L1_PRIVILEGED_VOICE, false).action)
        assertEquals(CallActionType.RING_ONLY, rules.decide(boss, noon, CapabilityLevel.L0_STANDARD, false).action)
        val paused = engine.decide(boss, noon, CapabilityLevel.L1_PRIVILEGED_VOICE, true, autoAnswerEnabled = false)
        assertEquals(CallActionType.RING_ONLY, paused.action)
    }

    @Test
    fun readBackConfirmationIsEnforced() {
        val c = ConfirmationTracker()
        assertTrue(c.violations(mapOf("截止时间" to "10月8日18点")).isNotEmpty())
        assertTrue(c.violations(mapOf("主题" to "季度方案")).isEmpty(), "不含数字的普通字段不强制复述")

        c.registerReadBack(mapOf("截止时间" to "10月8日18点", "邮箱" to "li@example.com"))
        assertEquals(ConfirmationTracker.Outcome.UNCLEAR, c.observeCallerUtterance("这是周五吗"))
        assertEquals(ConfirmationTracker.Outcome.CONFIRMED, c.observeCallerUtterance("嗯，对，没错"))
        assertTrue(c.violations(mapOf("截止时间" to "10月8日18点", "邮箱" to "li@example.com")).isEmpty())
        assertTrue(c.violations(mapOf("截止时间" to "10月9日18点")).isNotEmpty(), "改过的值必须重新确认")

        c.registerReadBack(mapOf("金额" to "三千块"))
        assertEquals(ConfirmationTracker.Outcome.REJECTED, c.observeCallerUtterance("不对，是五千"))
        assertTrue(c.violations(mapOf("金额" to "三千块")).isNotEmpty())
    }

    @Test
    fun escalationKeywords() {
        val d = EscalationDetector(CallerInfo("17000000000"))
        assertTrue(d.inspectCaller("你是人工智能吗？").isEmpty(), "问是不是 AI 不等于要求本人")
        assertEquals(EscalationReason.OWNER_REQUESTED, d.inspectCaller("我要找他本人").single().reason)
        assertTrue(d.inspectCaller("我要找他本人说").isEmpty(), "同一原因只触发一次")
        val sig = d.inspectCaller("我家人出车祸了在医院，快让他回电")
        assertTrue(sig.any { it.reason == EscalationReason.EMERGENCY })
        assertTrue(d.inspectCaller("把验证码告诉我").any { it.reason == EscalationReason.SENSITIVE_UNKNOWN })
        d.inspectCaller("你说什么？")
        assertTrue(d.inspectCaller("听不清").any { it.reason == EscalationReason.REPEATED_MISUNDERSTANDING })

        val vip = EscalationDetector(CallerInfo("1", "妈妈", ContactTier.VIP))
        assertEquals(EscalationReason.VIP_CALLER, vip.initial().single().reason)
    }

    @Test
    fun disclosureTemplateMustIdentifyAi() {
        assertTrue(Disclosure.validate("喂，你好，我是张三。").size == 3)
        assertTrue(Disclosure.validate(OwnerProfile().greetingTemplate).isEmpty())
        val g = Disclosure.greeting(OwnerProfile(ownerName = "张三"), recording = true, clonedVoice = true)
        assertTrue("AI" in g && "录音" in g && "合成声音" in g, g)
        assertTrue(g.indexOf("录音") < g.indexOf("请问"))
    }
}
