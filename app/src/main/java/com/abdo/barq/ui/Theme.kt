package com.abdo.barq.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.abdo.barq.Lang
import com.abdo.barq.Prefs
import com.abdo.barq.tr

object Themes {
    fun names(): List<String> = listOf(
        tr("Like the phone", "مثل الهاتف"), tr("Light", "فاتح"), tr("Dark", "داكن"), tr("Ocean blue", "أزرق المحيط"),
        tr("Midnight purple", "بنفسجي ليلي"), tr("Forest green", "أخضر الغابة"), tr("Black", "أسود"), tr("Sunset", "غروب"),
        tr("Teal night", "ليل فيروزي"), tr("Rose", "وردي"), tr("3D", "ثلاثي الأبعاد"), tr("Aurora glass", "زجاج الشفق"),
        tr("Soft clay", "طين ناعم"), tr("Cyber neon", "نيون"),
    )

    private fun light(primary: Long, secondary: Long, bg: Long, surface: Long = bg, variant: Long = 0xFFE7E9F0) = lightColorScheme(
        primary = Color(primary), onPrimary = Color.White, secondary = Color(secondary), onSecondary = Color.White,
        primaryContainer = Color(primary).copy(alpha = 0.16f).compositeOver(Color(surface)), onPrimaryContainer = Color(primary).darken(),
        secondaryContainer = Color(secondary).copy(alpha = 0.16f).compositeOver(Color(surface)), onSecondaryContainer = Color(secondary).darken(),
        background = Color(bg), surface = Color(surface), surfaceVariant = Color(variant),
        onBackground = Color(0xFF1B1B1F), onSurface = Color(0xFF1B1B1F), onSurfaceVariant = Color(0xFF44474F),
    )

    private fun dark(primary: Long, secondary: Long, bg: Long, surface: Long, variant: Long) = darkColorScheme(
        primary = Color(primary), onPrimary = Color(0xFF0B0B10), secondary = Color(secondary), onSecondary = Color(0xFF0B0B10),
        primaryContainer = Color(primary).copy(alpha = 0.22f).compositeOver(Color(surface)), onPrimaryContainer = Color(primary).lighten(),
        secondaryContainer = Color(secondary).copy(alpha = 0.22f).compositeOver(Color(surface)), onSecondaryContainer = Color(secondary).lighten(),
        background = Color(bg), surface = Color(surface), surfaceVariant = Color(variant),
        onBackground = Color(0xFFE6E6EC), onSurface = Color(0xFFE6E6EC), onSurfaceVariant = Color(0xFFC4C6D0),
    )

    @Composable
    fun scheme(index: Int): ColorScheme = when (index) {
        1 -> light(0xFF1565C0, 0xFF00897B, 0xFFF7F8FC, 0xFFFFFFFF)
        2 -> dark(0xFF90CAF9, 0xFF80CBC4, 0xFF121318, 0xFF1C1D23, 0xFF2C2E36)
        3 -> dark(0xFF4FC3F7, 0xFF26C6DA, 0xFF071A2E, 0xFF0D2540, 0xFF16324F)
        4 -> dark(0xFFB39DDB, 0xFFF48FB1, 0xFF120E22, 0xFF1C1631, 0xFF2A2245)
        5 -> dark(0xFF81C784, 0xFFDCE775, 0xFF0C1710, 0xFF13231A, 0xFF1F3427)
        6 -> dark(0xFFFFB300, 0xFF4FC3F7, 0xFF000000, 0xFF0A0A0A, 0xFF1A1A1A)
        7 -> light(0xFFE65100, 0xFFAD1457, 0xFFFFF5EC, 0xFFFFFBF7, 0xFFFFE3CC)
        8 -> dark(0xFF4DB6AC, 0xFFFFD54F, 0xFF081616, 0xFF0F2222, 0xFF1A3333)
        9 -> light(0xFFC2185B, 0xFF7B1FA2, 0xFFFFF2F6, 0xFFFFFAFC, 0xFFFBDDE8)
        10 -> light(0xFF3949AB, 0xFFFF7043, 0xFFE8EAF6, 0xFFF5F6FC, 0xFFD5D9F0)
        11 -> dark(0xFF64FFDA, 0xFFB388FF, 0xFF0B1626, 0xFF122036, 0xFF1C2D47)
        12 -> light(0xFF8D6E63, 0xFF6D8B74, 0xFFF3EEEA, 0xFFFAF7F5, 0xFFE6DDD7)
        13 -> dark(0xFF00E5FF, 0xFFFF4081, 0xFF07000F, 0xFF120A22, 0xFF1F1236)
        else -> if (isSystemInDarkTheme()) scheme(2) else scheme(1)
    }
}

private fun Color.darken(): Color = Color(red * 0.45f, green * 0.45f, blue * 0.45f, 1f)
private fun Color.lighten(): Color = Color(red + (1 - red) * 0.6f, green + (1 - green) * 0.6f, blue + (1 - blue) * 0.6f, 1f)
private fun Color.compositeOver(bg: Color): Color {
    val a = alpha
    return Color(red * a + bg.red * (1 - a), green * a + bg.green * (1 - a), blue * a + bg.blue * (1 - a), 1f)
}

@Composable
fun BarqTheme(content: @Composable () -> Unit) {
    Prefs.version.intValue   // re-read options when they change
    val scheme = Themes.scheme(Prefs.theme)
    val dir = if (Lang.ar.value) LayoutDirection.Rtl else LayoutDirection.Ltr
    val d = LocalDensity.current
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(
            LocalLayoutDirection provides dir,
            LocalDensity provides Density(d.density, d.fontScale * Prefs.textScale.coerceIn(0.8f, 1.4f)),
        ) {
            content()
        }
    }
}
