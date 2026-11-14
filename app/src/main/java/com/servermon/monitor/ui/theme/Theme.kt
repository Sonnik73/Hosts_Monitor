package com.servermon.monitor.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.lerp

/** Единая форма карточек — скруглённые, как в Pin/SonnikToPower. */
val CardShape = RoundedCornerShape(20.dp)

/** Светлая, тёмная или как в системе. */
enum class ThemeMode(val title: String) { SYSTEM("Как в системе"), LIGHT("Светлая"), DARK("Тёмная") }

/**
 * Палитры — как в Pin/SonnikToPower: акцент, второй акцент и лёгкий оттенок фона;
 * [tintStrength] — насколько сильно оттенок окрашивает фон и карточки.
 * Имена enum хранятся в настройках — не переименовывать.
 */
enum class ThemePalette(
    val title: String,
    val lightPrimary: Color,
    val darkPrimary: Color,
    val lightTertiary: Color,
    val darkTertiary: Color,
    val tint: Color,
    val tintStrength: Float = 1f,
    /** Сколько тёмной основы подмешивать в контейнер акцента тёмной темы: меньше — ярче контейнер. */
    val containerMix: Float = 0.68f,
) {
    STEEL("Сталь", Color(0xFF2F5C8F), Color(0xFFA9C7F0), Color(0xFF2E7D5B), Color(0xFF8FD6AE), Color(0xFF2F5C8F)),
    TEAL("Волна", Color(0xFF1B5E6E), Color(0xFF7FD8BE), Color(0xFF2E6E5C), Color(0xFF9FE3C9), Color(0xFF1B5E6E)),
    FOREST("Лес", Color(0xFF2E6B3F), Color(0xFF94D3A2), Color(0xFF7A5A14), Color(0xFFE0C27A), Color(0xFF3F6B4A)),
    SUNSET("Закат", Color(0xFFB4501C), Color(0xFFFFB68F), Color(0xFF00796B), Color(0xFF7FD3C6), Color(0xFFB4501C)),
    AMETHYST("Аметист", Color(0xFF6A4BA8), Color(0xFFCDB8FF), Color(0xFF2E7D7A), Color(0xFF86D5CF), Color(0xFF6A4BA8)),
    GRAPHITE("Графит", Color(0xFF3C4043), Color(0xFFD0D3D6), Color(0xFF5B6B78), Color(0xFFB4C2CC), Color(0xFF6B6F73)),
    CYBER("Киберпанк", Color(0xFF8F6B00), Color(0xFFFCEE0A), Color(0xFF00696E), Color(0xFF00E0F0), Color(0xFF5A4395), tintStrength = 3f, containerMix = 0.35f),
    DYNAMIC("Обои", Color(0xFF2F5C8F), Color(0xFFA9C7F0), Color(0xFF2E7D5B), Color(0xFF8FD6AE), Color(0xFF2F5C8F));

    companion object {
        fun parse(name: String?): ThemePalette = entries.firstOrNull { it.name == name } ?: STEEL
    }
}

private val BaseLight = lightColorScheme(
    primary = Color(0xFF2F5C8F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE7F5),
    onPrimaryContainer = Color(0xFF0F2A47),
    secondary = Color(0xFF56606D),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6EBF1),
    onSecondaryContainer = Color(0xFF1B2430),
    tertiary = Color(0xFF2E7D5B),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD6EFE2),
    onTertiaryContainer = Color(0xFF0C3A26),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFFBE4E2),
    onErrorContainer = Color(0xFF5C1A16),
    background = Color(0xFFF3F5F8),
    onBackground = Color(0xFF1A1C1F),
    surface = Color(0xFFF3F5F8),
    onSurface = Color(0xFF1A1C1F),
    surfaceVariant = Color(0xFFE6E9EE),
    onSurfaceVariant = Color(0xFF5F6672),
    outline = Color(0xFF8A919C),
    outlineVariant = Color(0xFFDDE1E6),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color.White,
)

private val BaseDark = darkColorScheme(
    primary = Color(0xFFA9C7F0),
    onPrimary = Color(0xFF0F2A47),
    primaryContainer = Color(0xFF2A4566),
    onPrimaryContainer = Color(0xFFDCE7F5),
    secondary = Color(0xFFBCC5D1),
    onSecondary = Color(0xFF26303B),
    secondaryContainer = Color(0xFF2C333C),
    onSecondaryContainer = Color(0xFFDCE3EC),
    tertiary = Color(0xFF8FD6AE),
    onTertiary = Color(0xFF0C3A26),
    tertiaryContainer = Color(0xFF1E4D37),
    onTertiaryContainer = Color(0xFFD6EFE2),
    error = Color(0xFFF2B8B5),
    errorContainer = Color(0xFF4A1C19),
    onErrorContainer = Color(0xFFFBE4E2),
    background = Color(0xFF111317),
    onBackground = Color(0xFFE3E5E9),
    surface = Color(0xFF111317),
    onSurface = Color(0xFFE3E5E9),
    surfaceVariant = Color(0xFF2A2F36),
    onSurfaceVariant = Color(0xFFA9B0BA),
    outline = Color(0xFF6E7681),
    outlineVariant = Color(0xFF30353D),
    surfaceContainerLowest = Color(0xFF0C0E11),
    surfaceContainerLow = Color(0xFF16191D),
    surfaceContainer = Color(0xFF181B20),
    surfaceContainerHigh = Color(0xFF23272E),
    surfaceContainerHighest = Color(0xFF1C2026),
)

private val DarkBase = Color(0xFF111317)

private fun lightScheme(p: ThemePalette): ColorScheme = BaseLight.copy(
    primary = p.lightPrimary,
    primaryContainer = lerp(p.lightPrimary, Color.White, 0.86f),
    onPrimaryContainer = lerp(p.lightPrimary, Color.Black, 0.55f),
    secondary = lerp(BaseLight.secondary, p.tint, 0.2f * p.tintStrength),
    secondaryContainer = lerp(BaseLight.secondaryContainer, p.tint, 0.1f * p.tintStrength),
    tertiary = p.lightTertiary,
    tertiaryContainer = lerp(p.lightTertiary, Color.White, 0.85f),
    onTertiaryContainer = lerp(p.lightTertiary, Color.Black, 0.6f),
    background = lerp(Color(0xFFF4F5F7), p.tint, 0.05f * p.tintStrength),
    surface = lerp(Color(0xFFF4F5F7), p.tint, 0.05f * p.tintStrength),
    surfaceVariant = lerp(Color(0xFFE6E8EC), p.tint, 0.08f * p.tintStrength),
    outlineVariant = lerp(Color(0xFFDDE0E4), p.tint, 0.06f * p.tintStrength),
)

private fun darkScheme(p: ThemePalette): ColorScheme = BaseDark.copy(
    primary = p.darkPrimary,
    onPrimary = lerp(p.darkPrimary, Color.Black, 0.75f),
    primaryContainer = lerp(p.darkPrimary, DarkBase, p.containerMix),
    onPrimaryContainer = lerp(p.darkPrimary, Color.White, 0.7f),
    secondary = lerp(BaseDark.secondary, p.tint, 0.2f * p.tintStrength),
    secondaryContainer = lerp(BaseDark.secondaryContainer, p.tint, 0.1f * p.tintStrength),
    tertiary = p.darkTertiary,
    onTertiary = lerp(p.darkTertiary, Color.Black, 0.75f),
    tertiaryContainer = lerp(p.darkTertiary, DarkBase, 0.7f),
    onTertiaryContainer = lerp(p.darkTertiary, Color.White, 0.7f),
    background = lerp(DarkBase, p.tint, 0.04f * p.tintStrength),
    surface = lerp(DarkBase, p.tint, 0.04f * p.tintStrength),
    surfaceVariant = lerp(BaseDark.surfaceVariant, p.tint, 0.08f * p.tintStrength),
    outlineVariant = lerp(BaseDark.outlineVariant, p.tint, 0.06f * p.tintStrength),
    surfaceContainerLowest = lerp(BaseDark.surfaceContainerLowest, p.tint, 0.03f * p.tintStrength),
    surfaceContainerLow = lerp(BaseDark.surfaceContainerLow, p.tint, 0.05f * p.tintStrength),
    surfaceContainer = lerp(BaseDark.surfaceContainer, p.tint, 0.05f * p.tintStrength),
    surfaceContainerHigh = lerp(BaseDark.surfaceContainerHigh, p.tint, 0.05f * p.tintStrength),
    surfaceContainerHighest = lerp(BaseDark.surfaceContainerHighest, p.tint, 0.05f * p.tintStrength),
)

/** Чёрный фон для AMOLED: экран не светится, карточки чуть светлее. */
private fun ColorScheme.pureBlack(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0B0B0C),
    surfaceContainer = Color(0xFF111214),
    surfaceContainerHigh = Color(0xFF1B1C1F),
    surfaceContainerHighest = Color(0xFF151618),
)

/** Схема палитры без цветов обоев: у «Обоев» — основа «Стали». */
fun staticColorScheme(palette: ThemePalette, dark: Boolean, pureBlack: Boolean = false): ColorScheme {
    val scheme = when (palette) {
        ThemePalette.STEEL, ThemePalette.DYNAMIC -> if (dark) BaseDark else BaseLight
        else -> if (dark) darkScheme(palette) else lightScheme(palette)
    }
    return if (dark && pureBlack) scheme.pureBlack() else scheme
}

/** Тёмная ли тема для выбранного режима. */
@Composable
fun isDarkTheme(mode: String): Boolean = when (mode) {
    "dark" -> true
    "light" -> false
    else -> isSystemInDarkTheme()
}

/** Тема Hosts Monitor по настройкам оформления — дизайн-язык Pin/SonnikToPower. */
@Composable
fun HostsMonitorTheme(
    themeMode: String,
    paletteName: String = ThemePalette.STEEL.name,
    pureBlack: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = isDarkTheme(themeMode)
    val palette = ThemePalette.parse(paletteName)
    val scheme = if (palette == ThemePalette.DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        val dynamic = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        if (dark && pureBlack) dynamic.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceContainerLow = Color(0xFF0B0B0C),
            surfaceContainer = Color(0xFF111214),
        ) else dynamic
    } else {
        staticColorScheme(palette, dark, pureBlack)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
