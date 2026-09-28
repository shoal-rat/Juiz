package app.juiz.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.juiz.R
import app.juiz.ui.theme.J
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 背景：极淡的点阵，像夜里的仪表盘。 */
fun Modifier.nightGrid(c: app.juiz.ui.theme.JuizColors): Modifier = drawBehind {
    drawRect(Brush.verticalGradient(listOf(c.bg, c.bgDeep)))
    val step = 22.dp.toPx()
    val dot = c.line.copy(alpha = 0.35f)
    var y = step / 2
    while (y < size.height) {
        var x = step / 2
        while (x < size.width) {
            drawCircle(dot, radius = 0.9f, center = Offset(x, y))
            x += step
        }
        y += step
    }
}

/** 英文等宽小标签 + 中文标题：界面的"仪表"语言。 */
@Composable
fun SectionHeader(en: String, zh: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(en.uppercase(), style = J.label, color = J.c.sora.copy(alpha = 0.8f))
            Text(zh, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = J.c.text)
        }
        trailing()
    }
}

/** 卡片四角的细括号，只在重点卡片上用。 */
fun Modifier.cornerBrackets(color: Color, len: Dp = 10.dp): Modifier = drawBehind {
    val l = len.toPx()
    val w = 1.2.dp.toPx()
    val s = Stroke(width = w, cap = StrokeCap.Square)
    fun corner(x: Float, y: Float, dx: Float, dy: Float) {
        val p = Path().apply { moveTo(x + dx * l, y); lineTo(x, y); lineTo(x, y + dy * l) }
        drawPath(p, color, style = s)
    }
    corner(0f, 0f, 1f, 1f)
    corner(size.width, 0f, -1f, 1f)
    corner(0f, size.height, 1f, -1f)
    corner(size.width, size.height, -1f, -1f)
}

@Composable
fun JuizCard(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = J.c
    Surface(
        modifier = modifier.fillMaxWidth().let { if (accent != null) it.cornerBrackets(accent.copy(alpha = 0.7f)) else it },
        shape = RoundedCornerShape(16.dp),
        color = c.surface,
        border = BorderStroke(1.dp, c.line),
        onClick = onClick ?: {},
        enabled = onClick != null,
    ) {
        Column(Modifier.padding(padding), content = content)
    }
}

@Composable
fun StatusDot(color: Color, pulse: Boolean = false, size: Dp = 8.dp) {
    val t = rememberInfiniteTransition(label = "dot")
    val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    Box(
        Modifier.size(size + 6.dp).drawBehind {
            if (pulse) drawCircle(color.copy(alpha = 0.25f * a), radius = this.size.minDimension / 2)
            drawCircle(color, radius = size.toPx() / 2)
        },
    )
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier, filled: Boolean = false) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (filled) color.copy(alpha = 0.18f) else Color.Transparent)
            .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(50))
            .padding(horizontal = 9.dp, vertical = 3.dp),
    ) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
fun Mono(text: String, color: Color = J.c.sub, size: Int = 12, modifier: Modifier = Modifier) {
    Text(text, fontFamily = J.mono, fontSize = size.sp, color = color, modifier = modifier, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, color: Color = J.c.sora, icon: ImageVector? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(46.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (enabled) color else J.c.line,
        contentColor = J.c.bgDeep,
    ) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (icon != null) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
            Text(text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
    }
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, color: Color = J.c.sora, icon: ImageVector? = null, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(42.dp),
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, color.copy(alpha = 0.6f)),
        contentColor = color,
    ) {
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (icon != null) { Icon(icon, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)) }
            Text(text, fontWeight = FontWeight.Medium, fontSize = 14.sp)
        }
    }
}

@Composable
fun KeyValue(k: String, v: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(k, color = J.c.sub, fontSize = 13.sp, modifier = Modifier.width(96.dp))
        if (mono) Mono(v, J.c.text, 13) else Text(v, color = J.c.text, fontSize = 13.sp)
    }
}

@Composable
fun EmptyState(title: String, whisper: String? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = J.c.sub, fontSize = 14.sp)
        if (whisper != null) {
            Spacer(Modifier.height(4.dp))
            Mono(whisper, J.c.faint, 10)
        }
    }
}

enum class OrbMood { IDLE, LISTENING, SPEAKING, SHIELD, ALERT }

/**
 * Juiz 的"核心"：呼吸的光球 + 两道缓慢旋转的弧。
 * 顶上那一小缕弯弯的光，会随着状态轻轻摆动——长按它会"弹"一下。
 */
@Composable
fun Orb(mood: OrbMood, modifier: Modifier = Modifier, size: Dp = 168.dp, onLongPressTuft: (() -> Unit)? = null) {
    val c = J.c
    val tint = when (mood) {
        OrbMood.IDLE -> c.sora
        OrbMood.LISTENING -> c.ice
        OrbMood.SPEAKING -> c.sora
        OrbMood.SHIELD -> c.amber
        OrbMood.ALERT -> c.rose
    }
    val speed = when (mood) { OrbMood.IDLE -> 14000; OrbMood.SPEAKING -> 3500; else -> 7000 }
    val t = rememberInfiniteTransition(label = "orb")
    val breathe by t.animateFloat(0.92f, 1.04f, infiniteRepeatable(tween(if (mood == OrbMood.SPEAKING) 700 else 2600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(speed, easing = LinearEasing)), label = "s")
    val sway by t.animateFloat(-7f, 7f, infiniteRepeatable(tween(1900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "w")
    var boing by remember { mutableStateOf(0f) }
    val boingAnim by androidx.compose.animation.core.animateFloatAsState(boing, spring(dampingRatio = Spring.DampingRatioHighBouncy, stiffness = Spring.StiffnessLow), label = "boing")
    LaunchedEffect(boing) { if (boing != 0f) { delay(120); boing = 0f } }

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = this.size.minDimension / 2 * 0.62f * breathe
            val center = Offset(this.size.width / 2, this.size.height / 2 + this.size.height * 0.04f)
            drawCircle(Brush.radialGradient(listOf(tint.copy(alpha = 0.28f), Color.Transparent), center, r * 1.9f), r * 1.9f, center)
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.85f), tint, tint.copy(alpha = 0.15f)), center, r), r, center)
            rotate(spin, center) {
                drawArc(tint.copy(alpha = 0.7f), 20f, 110f, false, Offset(center.x - r * 1.32f, center.y - r * 1.32f), Size(r * 2.64f, r * 2.64f), style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
                drawArc(c.ice.copy(alpha = 0.45f), 200f, 70f, false, Offset(center.x - r * 1.32f, center.y - r * 1.32f), Size(r * 2.64f, r * 2.64f), style = Stroke(1.dp.toPx(), cap = StrokeCap.Round))
            }
            rotate(-spin * 0.6f, center) {
                drawArc(tint.copy(alpha = 0.35f), 90f, 150f, false, Offset(center.x - r * 1.6f, center.y - r * 1.6f), Size(r * 3.2f, r * 3.2f), style = Stroke(0.8.dp.toPx()))
            }
            // 顶上的一缕光
            val top = Offset(center.x, center.y - r * 0.98f)
            rotate(sway + boingAnim, top) {
                val p = Path().apply {
                    moveTo(top.x, top.y)
                    cubicTo(top.x - r * 0.05f, top.y - r * 0.42f, top.x + r * 0.42f, top.y - r * 0.62f, top.x + r * 0.34f, top.y - r * 0.30f)
                    cubicTo(top.x + r * 0.28f, top.y - r * 0.16f, top.x + r * 0.12f, top.y - r * 0.22f, top.x + r * 0.16f, top.y - r * 0.34f)
                }
                drawPath(p, tint, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        if (onLongPressTuft != null) {
            Box(
                Modifier.align(Alignment.TopCenter).padding(top = size * 0.08f).size(size * 0.28f)
                    .clickable(interactionSource = null, indication = null) { boing = 38f; onLongPressTuft() },
            )
        }
    }
}

/** 情绪强度 ≥ 2 时浮现的六边形屏障，一圈圈向外扩散后消失。 */
@Composable
fun HexBarrier(active: Boolean, color: Color, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "hex")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "p")
    if (!active) return
    Canvas(modifier) {
        val cell = 26.dp.toPx()
        val h = cell * sqrt(3f) / 2
        val center = Offset(size.width / 2, size.height * 0.42f)
        val maxD = size.maxDimension * 0.7f
        var row = 0
        var y = -h
        while (y < size.height + h) {
            var x = if (row % 2 == 0) 0f else cell * 0.75f
            while (x < size.width + cell) {
                val d = (Offset(x, y) - center).getDistance()
                val wave = 1f - kotlin.math.abs(d / maxD - phase) * 5f
                if (wave > 0f) {
                    val a = (wave * 0.35f).coerceIn(0f, 0.35f)
                    val p = Path()
                    for (k in 0..5) {
                        val ang = Math.toRadians((60.0 * k)).toFloat()
                        val px = x + cell / 2 * 0.92f * cos(ang)
                        val py = y + cell / 2 * 0.92f * sin(ang)
                        if (k == 0) p.moveTo(px, py) else p.lineTo(px, py)
                    }
                    p.close()
                    drawPath(p, color.copy(alpha = a), style = Stroke(1.1.dp.toPx()))
                }
                x += cell * 1.5f
            }
            y += h / 2 * 2
            row++
        }
    }
}

/** 从屏幕底边探出头的 Juiz，说一句话就缩回去。 */
@Composable
fun MascotPeek(visible: Boolean, line: String, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically(spring(dampingRatio = 0.6f, stiffness = 300f)) { it },
        exit = slideOutVertically(tween(260)) { it },
    ) {
        Box(contentAlignment = Alignment.TopCenter) {
            Image(painterResource(R.drawable.mascot_peek), contentDescription = null, modifier = Modifier.padding(top = 30.dp).size(132.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = J.c.surfaceHi,
                border = BorderStroke(1.dp, J.c.line),
                modifier = Modifier.padding(start = 110.dp),
            ) {
                Text(line, color = J.c.text, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
            }
        }
    }
}

@Composable
fun Divider() = Box(Modifier.fillMaxWidth().height(1.dp).background(J.c.line))

@Composable
fun IconBadge(icon: ImageVector, tint: Color) {
    Box(Modifier.size(36.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(19.dp))
    }
}
