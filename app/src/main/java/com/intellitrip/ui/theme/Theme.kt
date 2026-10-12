package com.intellitrip.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Urban Signal: the candidate token set from the recovery plan, section 6.1.
 *
 * These are frozen for implementation, not left as suggestions. Two decisions are
 * load-bearing rather than cosmetic:
 *
 * - Dynamic color is deliberately not used. Material You can recolor surfaces freely,
 *   but this app has to keep transit and status meaning stable: green means live, amber
 *   means stale, red means canceled. A wallpaper-derived palette that moves those is
 *   worse than a palette that looks slightly less like the wallpaper.
 * - Route identity never rests on color alone. Every route is labeled by short name
 *   and direction as well, so the palettes below only need to make the *chrome*
 *   legible.
 */

/** Candidate palette values from section 6.1, promoted to real tokens. */
object UrbanSignal {
    val CanvasLight = Color(0xFFF4F7FA)
    val SurfaceLight = Color(0xFFFFFFFF)
    val InkLight = Color(0xFF122033)
    val SecondaryInkLight = Color(0xFF526478)
    val BrandLight = Color(0xFF006B93)
    val LiveLight = Color(0xFF087F65)
    val WarningLight = Color(0xFFA96B00)
    val CriticalLight = Color(0xFFB3261E)
    val DividerLight = Color(0xFFDBE4ED)

    val CanvasDark = Color(0xFF0D1522)
    val SurfaceDark = Color(0xFF182434)
    val InkDark = Color(0xFFF3F7FB)
    val SecondaryInkDark = Color(0xFFABBED0)
    val BrandDark = Color(0xFF53D1EE)
    val LiveDark = Color(0xFF5EDCB5)
    val WarningDark = Color(0xFFFFC66D)
    val CriticalDark = Color(0xFFFF8A80)
    val DividerDark = Color(0xFF2A3A4D)
}

/** Spacing rhythm: 4dp base with 8/12/16/24/32 steps. */
object Space {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
}

/** Container radii, from the 12-20dp range in section 6.1. */
object Radii {
    val chip: Dp = 12.dp
    val card: Dp = 16.dp
    val sheet: Dp = 20.dp
}

/**
 * The smallest allowed interactive target.
 *
 * Enforced as a named constant rather than left to each call site, because 48dp is an
 * accessibility floor, not a styling preference.
 */
val MinTouchTarget: Dp = 48.dp

/** Motion durations, section 6.1: 180-300ms for sheets and selection. */
object Motion {
    const val SelectionMillis = 180
    const val SheetMillis = 260
}

/**
 * Status colors that must not be remapped by the theme.
 *
 * These carry meaning, so they live outside the color scheme and are named for what
 * they assert rather than how they look.
 */
@androidx.compose.runtime.Immutable
data class StatusColors(
    val live: Color,
    val warning: Color,
    val critical: Color,
    val scheduled: Color,
)

val LocalStatusColors = staticCompositionLocalOf {
    StatusColors(
        live = UrbanSignal.LiveLight,
        warning = UrbanSignal.WarningLight,
        critical = UrbanSignal.CriticalLight,
        scheduled = UrbanSignal.SecondaryInkLight,
    )
}

val LocalIsDark = staticCompositionLocalOf { false }

private val LightColors = lightColorScheme(
    primary = UrbanSignal.BrandLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDE7F2),
    onPrimaryContainer = UrbanSignal.InkLight,
    secondary = UrbanSignal.LiveLight,
    onSecondary = Color.White,
    error = UrbanSignal.CriticalLight,
    onError = Color.White,
    background = UrbanSignal.CanvasLight,
    onBackground = UrbanSignal.InkLight,
    surface = UrbanSignal.SurfaceLight,
    onSurface = UrbanSignal.InkLight,
    surfaceVariant = Color(0xFFE8EEF4),
    onSurfaceVariant = UrbanSignal.SecondaryInkLight,
    outline = UrbanSignal.DividerLight,
    outlineVariant = UrbanSignal.DividerLight,
)

private val DarkColors = darkColorScheme(
    primary = UrbanSignal.BrandDark,
    onPrimary = Color(0xFF00232F),
    primaryContainer = Color(0xFF00425A),
    onPrimaryContainer = UrbanSignal.InkDark,
    secondary = UrbanSignal.LiveDark,
    onSecondary = Color(0xFF00382B),
    error = UrbanSignal.CriticalDark,
    onError = Color(0xFF4A0B06),
    background = UrbanSignal.CanvasDark,
    onBackground = UrbanSignal.InkDark,
    surface = UrbanSignal.SurfaceDark,
    onSurface = UrbanSignal.InkDark,
    surfaceVariant = Color(0xFF24344A),
    onSurfaceVariant = UrbanSignal.SecondaryInkDark,
    outline = UrbanSignal.DividerDark,
    outlineVariant = UrbanSignal.DividerDark,
)

/**
 * Type scale with the ETA as the dominant number.
 *
 * The hierarchy encodes priority directly: minutes-until-departure is the largest
 * thing on a departure row, the route and direction sit beneath it, and provenance
 * (live or scheduled) is smallest. That ordering is what lets a rider read a
 * departure board at a glance rather than parsing it.
 *
 * Font sizes are in sp throughout so they scale with the system font-size setting,
 * which is what the 200% type test in the R3 gate exercises.
 */
private fun typeScale(dark: Boolean) = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 44.sp,
        lineHeight = 50.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 23.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
)

@Composable
fun IntelliTripTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalIsDark provides darkTheme,
        LocalStatusColors provides if (darkTheme) {
            StatusColors(
                live = UrbanSignal.LiveDark,
                warning = UrbanSignal.WarningDark,
                critical = UrbanSignal.CriticalDark,
                scheduled = UrbanSignal.SecondaryInkDark,
            )
        } else {
            StatusColors(
                live = UrbanSignal.LiveLight,
                warning = UrbanSignal.WarningLight,
                critical = UrbanSignal.CriticalLight,
                scheduled = UrbanSignal.SecondaryInkLight,
            )
        },
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = typeScale(darkTheme),
            content = content,
        )
    }
}

/** Shorthand for the status palette inside composables. */
val MaterialTheme.status: StatusColors
    @Composable
    @ReadOnlyComposable
    get() = LocalStatusColors.current