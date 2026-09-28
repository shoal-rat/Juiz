package app.juiz.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.juiz.R
import app.juiz.core.buddy.BuddyBrain
import app.juiz.core.buddy.BuddyEvent
import app.juiz.core.buddy.BuddyFacts
import app.juiz.core.buddy.BuddyMood
import app.juiz.ui.theme.J
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime
import kotlin.random.Random

enum class BuddyPose(@param:DrawableRes val res: Int) {
    IDLE(R.drawable.buddy_idle),
    BLINK(R.drawable.buddy_blink),
    HAPPY(R.drawable.buddy_happy),
    WAVE(R.drawable.buddy_wave),
    SURPRISED(R.drawable.buddy_surprised),
    SLEEPY(R.drawable.buddy_sleepy),
    DETERMINED(R.drawable.buddy_determined),
    THINKING(R.drawable.buddy_thinking),
}

data class BuddyLine(val pose: BuddyPose, val text: String)

typealias BuddyContext = BuddyFacts

fun BuddyMood.pose(): BuddyPose = BuddyPose.valueOf(name)

/** 模型生成的台词只在一次打开应用里问候一次，避免每次重组都去请求。 */
private var lastGreetingAt = 0L

object BuddyScript {
    fun greeting(ctx: BuddyContext, now: LocalTime = LocalTime.now()): BuddyLine {
        ctx.liveCall?.let { return BuddyLine(BuddyPose.DETERMINED, it) }
        if (!ctx.dialerReady) return BuddyLine(BuddyPose.SURPRISED, "还没把来电交给我呢——先完成上面的设置吧。")
        if (ctx.errandsLastNight > 0) return BuddyLine(BuddyPose.HAPPY, "昨晚替你办了 ${ctx.errandsLastNight} 件小事，记录都在下面～")
        if (ctx.recentDigest) return BuddyLine(BuddyPose.DETERMINED, "刚才那通电话，难听的话我都挡住了，只留了要点。")
        val h = now.hour
        return when {
            h in 0..4 -> BuddyLine(BuddyPose.SLEEPY, "……还不睡吗？夜里的电话交给我。")
            h in 5..10 -> BuddyLine(BuddyPose.WAVE, "早上好${ctx.ownerName.takeIf { it.isNotBlank() && it != "机主" }?.let { "，$it" } ?: ""}！今天的电话我帮你盯着。")
            h in 11..13 -> BuddyLine(BuddyPose.HAPPY, "午饭要好好吃哦，电话我先接着。")
            h in 18..21 -> BuddyLine(BuddyPose.WAVE, "下班啦？剩下的交给我。")
            h >= 22 -> BuddyLine(BuddyPose.SLEEPY, "该休息了。深夜来电，我按你的授权处理。")
            ctx.pendingTasks > 0 -> BuddyLine(BuddyPose.THINKING, "有 ${ctx.pendingTasks} 件委托等你确认。")
            else -> BuddyLine(BuddyPose.IDLE, "今天很平静。我在这里。")
        }
    }

    private val taps = listOf(
        BuddyLine(BuddyPose.HAPPY, "嗯？找我吗～"),
        BuddyLine(BuddyPose.WAVE, "今天也辛苦啦。"),
        BuddyLine(BuddyPose.DETERMINED, "有我在，别怕接电话。"),
        BuddyLine(BuddyPose.THINKING, "让我想想……对了，记得喝水。"),
        BuddyLine(BuddyPose.HAPPY, "被夸了会开心的，被骂的话——交给我过滤。"),
    )

    fun tap(ctx: BuddyContext, n: Int): BuddyLine = when {
        n >= 10 -> BuddyLine(BuddyPose.SLEEPY, "……戳太多次，眼睛要转晕了。")
        ctx.pendingTasks > 0 && n % 3 == 0 -> BuddyLine(BuddyPose.THINKING, "还有 ${ctx.pendingTasks} 件委托没确认哦。")
        ctx.needsVerify > 0 && n % 4 == 0 -> BuddyLine(BuddyPose.THINKING, "${ctx.needsVerify} 件交付物等你核对，我不会替你说「做完了」。")
        else -> taps[Random.nextInt(taps.size)]
    }

    val longPress = BuddyLine(BuddyPose.SURPRISED, "呀！……别突然按住我嘛。")
    val doubleTap = BuddyLine(BuddyPose.DETERMINED, "交给我吧！")
}

/**
 * 活过来的 Juiz：会眨眼、轻轻上下浮动、被点会跳一下并说话。
 * 台词和表情优先由主人配置的模型（ChatGPT 或本地小模型）根据当前情况生成；
 * 没配置模型、超时或输出不合规时，退回预设台词。
 */
@Composable
fun JuizBuddy(
    ctx: BuddyContext,
    modifier: Modifier = Modifier,
    height: Dp = 150.dp,
    showBubble: Boolean = true,
    external: BuddyLine? = null,
    brain: BuddyBrain? = null,
    chatText: String? = null,
) {
    val c = J.c
    var line by remember { mutableStateOf(BuddyLine(BuddyPose.IDLE, "")) }
    var pose by remember { mutableStateOf(line.pose) }
    var blink by remember { mutableStateOf(false) }
    var tapCount by remember { mutableIntStateOf(0) }
    var shown by remember { mutableStateOf("") }
    val hop = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    fun say(l: BuddyLine) {
        line = l
        pose = l.pose
        scope.launch {
            hop.animateTo(-16f, tween(110))
            hop.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow))
        }
    }

    fun think(event: BuddyEvent, fallback: () -> BuddyLine, text: String? = null) {
        if (brain == null) { say(fallback()); return }
        pose = BuddyPose.THINKING
        line = BuddyLine(BuddyPose.THINKING, "・・・")
        scope.launch {
            val r = brain.react(event, ctx.copy(hour = LocalTime.now().hour), text)
            say(r?.let { BuddyLine(it.mood.pose(), it.text) } ?: fallback())
        }
    }

    LaunchedEffect(ctx.liveCall, ctx.dialerReady, ctx.errandsLastNight) {
        val fresh = System.currentTimeMillis() - lastGreetingAt > 10 * 60_000L
        if (ctx.liveCall != null || !fresh || brain == null) say(BuddyScript.greeting(ctx))
        else { lastGreetingAt = System.currentTimeMillis(); think(BuddyEvent.OPEN_APP, { BuddyScript.greeting(ctx) }) }
    }
    LaunchedEffect(chatText) { chatText?.takeIf { it.isNotBlank() }?.let { think(BuddyEvent.CHAT, { BuddyLine(BuddyPose.HAPPY, "嗯，我在听。") }, it) } }
    LaunchedEffect(external) { external?.let { say(it) } }
    // 眨眼：随机间隔，偶尔连眨两下
    LaunchedEffect(Unit) {
        while (true) {
            delay(Random.nextLong(2400, 5600))
            blink = true; delay(120); blink = false
            if (Random.nextInt(4) == 0) { delay(140); blink = true; delay(110); blink = false }
        }
    }
    // 打字机效果；说完一段时间后回到待机姿势
    LaunchedEffect(line) {
        shown = ""
        for (i in line.text.indices) { shown = line.text.substring(0, i + 1); delay(38) }
        if (line.text == "・・・") return@LaunchedEffect
        delay(3800)
        if (pose != BuddyPose.SLEEPY) pose = BuddyPose.IDLE
    }

    val t = rememberInfiniteTransition(label = "buddy")
    val bob by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob")
    val shown2 = if (blink && (pose == BuddyPose.IDLE)) BuddyPose.BLINK else pose

    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.height(height).width(height * 0.87f)
                .graphicsLayer {
                    translationY = hop.value * density + (bob - 0.5f) * 5f * density
                    scaleY = 1f + bob * 0.012f
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                }
                .pointerInput(ctx) {
                    detectTapGestures(
                        onTap = { tapCount++; if (tapCount >= 10) say(BuddyScript.tap(ctx, tapCount)) else think(BuddyEvent.TAP, { BuddyScript.tap(ctx, tapCount) }) },
                        onDoubleTap = { think(BuddyEvent.DOUBLE_TAP, { BuddyScript.doubleTap }) },
                        onLongPress = { think(BuddyEvent.LONG_PRESS, { BuddyScript.longPress }) },
                    )
                },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Image(painterResource(shown2.res), contentDescription = "Juiz", modifier = Modifier.size(height * 0.87f, height))
        }
        if (showBubble) {
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Surface(
                    shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 16.dp),
                    color = c.surfaceHi,
                    border = BorderStroke(1.dp, c.line),
                ) {
                    Text(shown.ifEmpty { " " }, color = c.text, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp))
                }
            }
        }
    }
}
