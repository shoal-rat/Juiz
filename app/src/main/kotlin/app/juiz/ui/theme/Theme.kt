package app.juiz.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Juiz 的色板：夜色、冰蓝、一点点暖色用于警示。 */
@Immutable
data class JuizColors(
    val bg: Color,
    val bgDeep: Color,
    val surface: Color,
    val surfaceHi: Color,
    val line: Color,
    val text: Color,
    val sub: Color,
    val faint: Color,
    val sora: Color,   // 主色：青
    val ice: Color,    // 辅色：冰蓝
    val amber: Color,
    val rose: Color,
    val mint: Color,
    val dark: Boolean,
)

val NightColors = JuizColors(
    bg = Color(0xFF0A0F1C),
    bgDeep = Color(0xFF060A14),
    surface = Color(0xFF111A2B),
    surfaceHi = Color(0xFF17233A),
    line = Color(0xFF22304B),
    text = Color(0xFFE6EEFF),
    sub = Color(0xFF93A5C2),
    faint = Color(0xFF55667F),
    sora = Color(0xFF5CE1E6),
    ice = Color(0xFFA6C8FF),
    amber = Color(0xFFF5B85B),
    rose = Color(0xFFFF6B81),
    mint = Color(0xFF5BE3A1),
    dark = true,
)

val DayColors = JuizColors(
    bg = Color(0xFFF3F6FB),
    bgDeep = Color(0xFFE8EEF7),
    surface = Color(0xFFFFFFFF),
    surfaceHi = Color(0xFFF0F4FA),
    line = Color(0xFFD9E1EE),
    text = Color(0xFF0E1A2B),
    sub = Color(0xFF52627A),
    faint = Color(0xFF9AA8BD),
    sora = Color(0xFF0A95A6),
    ice = Color(0xFF3E6FCB),
    amber = Color(0xFFC77A0E),
    rose = Color(0xFFD63A57),
    mint = Color(0xFF14956A),
    dark = false,
)

val LocalJuiz = staticCompositionLocalOf { NightColors }

object J {
    val c: JuizColors @Composable get() = LocalJuiz.current
    val mono = FontFamily.Monospace
    val label = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Medium)
}

@Composable
fun JuizTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    val c = if (dark) NightColors else DayColors
    val scheme = if (dark) darkColorScheme(
        primary = c.sora, onPrimary = c.bgDeep, secondary = c.ice, background = c.bg, surface = c.surface,
        onBackground = c.text, onSurface = c.text, surfaceVariant = c.surfaceHi, onSurfaceVariant = c.sub,
        outline = c.line, error = c.rose,
    ) else lightColorScheme(
        primary = c.sora, secondary = c.ice, background = c.bg, surface = c.surface,
        onBackground = c.text, onSurface = c.text, surfaceVariant = c.surfaceHi, onSurfaceVariant = c.sub,
        outline = c.line, error = c.rose,
    )
    val type = Typography(
        headlineLarge = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp),
        headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
        bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
        bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
        labelSmall = J.label,
    )
    CompositionLocalProvider(LocalJuiz provides c) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}
