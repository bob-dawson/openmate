package com.openmate.core.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/** The in-app size composes with the system setting; cap the product so neither can explode it. */
internal const val MAX_TOTAL_FONT_SCALE = 3.0f

/**
 * In-app text size, multiplied on top of the system font scale.
 *
 * Only `sp` text scales; `dp` layout stays the same, so every level is checked against the
 * fixed-height rows and single-line summaries in the chat UI.
 */
enum class AppFontScale(val storageKey: String, val factor: Float) {
    STANDARD("standard", 1.0f),
    LARGE("large", 1.25f),
    EXTRA_LARGE("xlarge", 1.5f),
    ;

    companion object {
        const val PREFS_NAME = "settings"
        const val PREF_KEY = "font_scale"

        fun fromKey(key: String?): AppFontScale =
            entries.firstOrNull { it.storageKey == key } ?: STANDARD

        fun fromIndex(index: Int): AppFontScale =
            entries.getOrElse(index) { STANDARD }
    }
}

fun setAppFontScale(context: Context, scale: AppFontScale) {
    context.getSharedPreferences(AppFontScale.PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(AppFontScale.PREF_KEY, scale.storageKey)
        .apply()
}

/** Follows the setting while the app is running, so a change redraws every screen at once. */
@Composable
fun rememberAppFontScale(): AppFontScale {
    val context = LocalContext.current
    val prefs: SharedPreferences = remember(context) {
        context.getSharedPreferences(AppFontScale.PREFS_NAME, Context.MODE_PRIVATE)
    }
    var scale by remember(prefs) {
        mutableStateOf(AppFontScale.fromKey(prefs.getString(AppFontScale.PREF_KEY, null)))
    }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == AppFontScale.PREF_KEY) {
                scale = AppFontScale.fromKey(prefs.getString(AppFontScale.PREF_KEY, null))
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return scale
}

/**
 * Applies [factor] to the current density. Use inside a window that does not inherit the
 * activity's composition locals, such as a Compose [androidx.compose.ui.window.Dialog].
 */
@Composable
fun ProvideAppFontScale(factor: Float, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val fontScale = (density.fontScale * factor).coerceAtMost(MAX_TOTAL_FONT_SCALE)
    CompositionLocalProvider(
        LocalDensity provides Density(density = density.density, fontScale = fontScale),
        content = content,
    )
}

/**
 * Caps the ambient text scale for secondary chrome (metadata, status chips) so it stays on one
 * line while the primary content keeps the user's full size.
 */
@Composable
fun CapAppFontScale(maxFontScale: Float, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(
            density = density.density,
            fontScale = density.fontScale.coerceAtMost(maxFontScale),
        ),
        content = content,
    )
}

/**
 * Markdown is rendered by an Android `TextView`, which does not inherit the Compose
 * `LocalDensity` override. Scale its sp-based sizes by the in-app factor so it matches the rest
 * of the screen. The TextView still applies the system font scale on top.
 */
@Composable
fun rememberScaledTextStyle(base: TextStyle): TextStyle {
    val factor = rememberAppFontScale().factor
    return remember(base, factor) {
        base.copy(
            fontSize = if (base.fontSize == TextUnit.Unspecified) base.fontSize
            else (base.fontSize.value * factor).sp,
            lineHeight = if (base.lineHeight == TextUnit.Unspecified) base.lineHeight
            else (base.lineHeight.value * factor).sp,
        )
    }
}
