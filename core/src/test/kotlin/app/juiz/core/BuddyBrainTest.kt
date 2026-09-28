package app.juiz.core

import app.juiz.core.buddy.BuddyBrain
import app.juiz.core.buddy.BuddyEvent
import app.juiz.core.buddy.BuddyFacts
import app.juiz.core.buddy.BuddyMood
import app.juiz.core.conversation.ScriptedChatModel
import app.juiz.core.conversation.ScriptedReply
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BuddyBrainTest {
    @Test
    fun parsesMoodAndLineAndPassesFactsOnly(): Unit = runBlocking {
        val model = ScriptedChatModel { ScriptedReply("好的：{\"mood\":\"happy\",\"text\":\"有两件委托等你确认哦。\"}") }
        val say = BuddyBrain(model).react(BuddyEvent.TAP, BuddyFacts(hour = 10, pendingTasks = 2))!!
        assertEquals(BuddyMood.HAPPY, say.mood)
        assertEquals("有两件委托等你确认哦。", say.text)
        val prompt = model.requests.single().history.single().toString()
        assertTrue("2 件委托" in prompt && "昨晚" !in prompt, "只告诉模型真实存在的情况")
    }

    @Test
    fun toleratesFullWidthClosingQuote(): Unit = runBlocking {
        val say = BuddyBrain(ScriptedChatModel { ScriptedReply("{\"mood\":\"SLEEPY\",\"text\":\"两点了，快去睡吧。”}") }).react(BuddyEvent.OPEN_APP, BuddyFacts(hour = 2))!!
        assertEquals(BuddyMood.SLEEPY, say.mood)
        assertEquals("两点了，快去睡吧。", say.text)
    }

    @Test
    fun rejectsBadOutputSoUiFallsBack(): Unit = runBlocking {
        assertNull(BuddyBrain(ScriptedChatModel { ScriptedReply("我不会输出 JSON") }).react(BuddyEvent.TAP, BuddyFacts(hour = 9)))
        assertNull(BuddyBrain(ScriptedChatModel { ScriptedReply("{\"mood\":\"HAPPY\",\"text\":\"${"很长".repeat(30)}\"}") }).react(BuddyEvent.TAP, BuddyFacts(hour = 9)))
        assertNull(BuddyBrain(null).react(BuddyEvent.OPEN_APP, BuddyFacts(hour = 9)))
    }

    @Test
    fun usesChosenAddressNotTheOwnersName(): Unit = runBlocking {
        // 模拟器实测：小模型拿到名字就会叫名字（"林晓，凌晨四点啦？"），而使用者选的称呼是"队长"
        val model = ScriptedChatModel { ScriptedReply("{\"mood\":\"SLEEPY\",\"text\":\"林晓，凌晨四点啦？先睡吧。\"}") }
        val facts = BuddyFacts(hour = 4, ownerName = "林晓", addressAs = "队长")
        val say = BuddyBrain(model).react(BuddyEvent.OPEN_APP, facts)!!
        assertEquals("队长，凌晨四点啦？先睡吧。", say.text)
        val sent = model.requests.single().toString()
        assertTrue("林晓" !in sent, "名字不告诉模型")
        assertTrue("队长" in sent)
    }
}
