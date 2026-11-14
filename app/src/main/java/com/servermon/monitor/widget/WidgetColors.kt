package com.servermon.monitor.widget

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import com.servermon.monitor.data.AppSettings
import com.servermon.monitor.data.SettingsStore
import com.servermon.monitor.ui.theme.ThemePalette
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Цвета виджетов по выбранной в приложении теме — как в Pin: акцент и второй акцент
 * палитры, фон с её оттенком, светлый или тёмный вариант по режиму темы. ARGB-числа.
 */
data class WidgetColors(
    val background: Int,
    val text: Int,
    val muted: Int,
    val accent: Int,
    val second: Int,
    /** Сервер в сети. */
    val up: Int,
    /** Виден через релей. */
    val relay: Int,
    /** Недоступен. */
    val down: Int,
    val dark: Boolean,
) {
    companion object {

        /** Непрозрачность фона виджета, 0–255 для setImageAlpha (95% — как в Pin по умолчанию). */
        const val BG_ALPHA = 242

        fun current(context: Context): WidgetColors {
            val settings: AppSettings = runBlocking {
                runCatching { SettingsStore(context).settings.first() }.getOrDefault(AppSettings())
            }
            return of(settings, isDark(context, settings.theme))
        }

        fun of(settings: AppSettings, dark: Boolean): WidgetColors {
            val palette = ThemePalette.parse(settings.palette)
            if (palette == ThemePalette.DYNAMIC && Build.VERSION.SDK_INT >= 31) {
                // Настоящие цвета обоев подставит вызывающий код через system_* ресурсов.
                return ofStatic(palette, dark)
            }
            return ofStatic(palette, dark)
        }

        private fun ofStatic(palette: ThemePalette, dark: Boolean): WidgetColors {
            val primary = if (dark) palette.darkPrimary else palette.lightPrimary
            val tertiary = if (dark) palette.darkTertiary else palette.lightTertiary
            return of(
                primary = argb(primary),
                tertiary = argb(tertiary),
                tint = argb(palette.tint),
                tintStrength = palette.tintStrength,
                dark = dark,
            )
        }

        /** Чистый расчёт по палитре; [primary]/[tertiary] — ARGB акцентов, [tint] — оттенок фона. */
        fun of(primary: Int, tertiary: Int, tint: Int, tintStrength: Float, dark: Boolean): WidgetColors {
            val base = if (dark) 0xFF111317.toInt() else 0xFFF4F5F7.toInt()
            return WidgetColors(
                background = mix(base, tint, (if (dark) 0.05f else 0.06f) * tintStrength.coerceAtMost(3f)),
                text = if (dark) 0xFFE3E5E9.toInt() else 0xFF1A1C1F.toInt(),
                muted = if (dark) 0xFFA9B0BA.toInt() else 0xFF5F6672.toInt(),
                accent = primary,
                second = tertiary,
                up = if (dark) 0xFF8FD6AE.toInt() else 0xFF2E7D5B.toInt(),
                relay = if (dark) 0xFFE3B95C.toInt() else 0xFFB7791F.toInt(),
                down = if (dark) 0xFFFF8A80.toInt() else 0xFFD32F2F.toInt(),
                dark = dark,
            )
        }

        /** Смешать два цвета: [t] = 0 — первый, 1 — второй (непрозрачный результат). */
        fun mix(a: Int, b: Int, t: Float): Int {
            fun ch(shift: Int) =
                (((a shr shift) and 0xFF) * (1 - t) + ((b shr shift) and 0xFF) * t).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }

        private fun isDark(context: Context, theme: String): Boolean = when (theme) {
            "dark" -> true
            "light" -> false
            else -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }

        private fun argb(c: androidx.compose.ui.graphics.Color): Int = android.graphics.Color.argb(
            (c.alpha * 255).toInt(), (c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt(),
        )
    }
}
