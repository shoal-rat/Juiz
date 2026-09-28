package app.juiz.core

import app.juiz.core.voice.UtteranceJoiner
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals

class JoinerTest {
    @Test
    fun fragmentsSplitByPausesAreJoinedIntoOneTurn(): Unit = runBlocking {
        val got = CopyOnWriteArrayList<String>()
        var speaking = false
        val j = UtteranceJoiner(this, { speaking }) { t, _ -> got += t }
        j.add("小张，", 0)            // 逗号结尾：宽限 900 ms
        delay(300)
        speaking = true; j.callerResumed()
        delay(400)
        speaking = false
        j.add("把 Q3 周报发我一下。", 0)
        delay(1200)
        assertEquals(listOf("小张，把 Q3 周报发我一下。"), got.toList())

        j.add("明早开会要用，麻烦了。", 0)   // 完整句：宽限 350 ms
        delay(600)
        assertEquals(2, got.size)
        assertEquals(350, UtteranceJoiner.graceFor("明早开会要用，麻烦了。"))
        assertEquals(900, UtteranceJoiner.graceFor("小张，"))
    }
}
