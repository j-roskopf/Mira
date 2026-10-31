package com.joetr.mira.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mira.ui.generated.resources.InterTight
import mira.ui.generated.resources.Literata
import mira.ui.generated.resources.Res
import org.jetbrains.compose.resources.Font

/**
 * Semantic colour tokens. Feature code uses these or [MiraAccent]; raw `Color(0x…)` in a feature
 * module is a bug.
 *
 * See DESIGN.md. Dark is the tuned mode — light is derived and must be checked independently.
 */
@Immutable
public data class MiraColorTokens(
    val canvas: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceSunken: Color,
    val ink: Color,
    val inkMuted: Color,
    val inkFaint: Color,
    val outline: Color,
    val scrim: Color,
    val error: Color,
    val accent: Color,
    val onAccent: Color,
    val heatmap: List<Color>,
    val isDark: Boolean,
)

private val DarkTokens = MiraColorTokens(
    canvas = Color(0xFF0A0A0B),
    surface = Color(0xFF141416),
    surfaceRaised = Color(0xFF1C1C1F),
    surfaceSunken = Color(0xFF060607),
    ink = Color(0xFFF2F2F5),
    inkMuted = Color(0xFF8A8A92),
    inkFaint = Color(0xFF4A4A52),
    outline = Color(0xFF232327),
    scrim = Color(0x99000000),
    error = Color(0xFFFF8A80),
    accent = MiraAccent.Default.color,
    onAccent = MiraAccent.Default.onAccent,
    heatmap = emptyList(),
    isDark = true,
)

private val LightTokens = MiraColorTokens(
    canvas = Color(0xFFFBFAF8),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFF4F2EE),
    surfaceSunken = Color(0xFFF0EEE9),
    ink = Color(0xFF121214),
    inkMuted = Color(0xFF6E6E76),
    inkFaint = Color(0xFFB4B4BC),
    outline = Color(0xFFECEAE6),
    scrim = Color(0x52121214),
    error = Color(0xFFB3261E),
    accent = MiraAccent.Default.color,
    onAccent = MiraAccent.Default.onAccent,
    heatmap = emptyList(),
    isDark = false,
)

public object MiraSpacing {
    public val xxs: Dp = 2.dp
    public val xs: Dp = 4.dp
    public val sm: Dp = 8.dp
    public val md: Dp = 12.dp
    public val lg: Dp = 16.dp
    public val xl: Dp = 20.dp
    public val xxl: Dp = 24.dp
    public val xxxl: Dp = 32.dp
    public val huge: Dp = 48.dp
    public val giant: Dp = 64.dp

    /** Phone side gutter. */
    public val gutter: Dp = 20.dp

    /** Desktop and web side gutter. */
    public val gutterWide: Dp = 32.dp

    /** Between stacked entry cards. */
    public val cardGap: Dp = 8.dp

    /**
     * Between sections. The jump from [cardGap] to this is what replaces dividers — if sections
     * ever read as merged, this drifted, not the need for a line.
     */
    public val sectionGap: Dp = 32.dp
}

public object MiraRadii {
    public val card: Dp = 14.dp
    public val sheet: Dp = 28.dp
    public val control: Dp = 10.dp
    public val media: Dp = 12.dp
    public val pill: Dp = 999.dp
}

public object MiraSizes {
    /** Heatmap day circle. */
    public val dayCircle: Dp = 34.dp
    public val dayGap: Dp = 6.dp

    /** Year-view day dot. */
    public val yearDot: Dp = 8.dp

    /** Minimum touch target, even where the visual is smaller. */
    public val touchTarget: Dp = 44.dp

    /** Maximum content width on desktop and web. */
    public val contentMax: Dp = 720.dp

    /** Entry body measure cap, in characters. */
    public const val BODY_MEASURE_CHARS: Int = 68
}

/** Three durations, no more. See DESIGN.md §5. */
public object MiraMotion {
    public const val QUICK_MS: Int = 140
    public const val STANDARD_MS: Int = 240
    public const val EXPRESSIVE_MS: Int = 420

    /** Per-day stagger when a month's heatmap first paints. */
    public const val HEATMAP_STAGGER_MS: Int = 12

    /** Record-button pulse period. */
    public const val RECORD_PULSE_MS: Int = 1400
}

/**
 * The type scale.
 *
 * The rule: if the user typed it, it is Literata. If the app says it, it is Inter Tight.
 */
@Immutable
public data class MiraTypeScale(
    val monthTitle: TextStyle,
    val dayTitle: TextStyle,
    val dayDate: TextStyle,
    val sectionLabel: TextStyle,
    val yearNav: TextStyle,
    val dayNumeral: TextStyle,
    val weekdayHead: TextStyle,
    val uiBody: TextStyle,
    val entryBody: TextStyle,
    val entryBodyLarge: TextStyle,
    val entryExcerpt: TextStyle,
    val timestamp: TextStyle,
    val button: TextStyle,
    val meta: TextStyle,
)

@Composable
private fun interTight(): FontFamily = FontFamily(
    Font(Res.font.InterTight, FontWeight.Normal),
    Font(Res.font.InterTight, FontWeight.Medium),
    Font(Res.font.InterTight, FontWeight.SemiBold),
    Font(Res.font.InterTight, FontWeight.Bold),
)

@Composable
private fun literata(): FontFamily = FontFamily(
    Font(Res.font.Literata, FontWeight.Normal),
    Font(Res.font.Literata, FontWeight.Medium),
    Font(Res.font.Literata, FontWeight.SemiBold),
)

@Composable
private fun miraTypeScale(ui: FontFamily, reading: FontFamily): MiraTypeScale = MiraTypeScale(
    monthTitle = TextStyle(
        fontFamily = ui, fontSize = 34.sp, lineHeight = 38.sp,
        fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp,
    ),
    dayTitle = TextStyle(
        fontFamily = ui, fontSize = 24.sp, lineHeight = 28.sp,
        fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp,
    ),
    dayDate = TextStyle(
        fontFamily = ui, fontSize = 11.sp, lineHeight = 14.sp,
        fontWeight = FontWeight.Medium, letterSpacing = 1.6.sp,
    ),
    sectionLabel = TextStyle(
        fontFamily = ui, fontSize = 10.sp, lineHeight = 14.sp,
        fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp,
    ),
    yearNav = TextStyle(
        fontFamily = ui, fontSize = 11.sp, lineHeight = 16.sp,
        fontWeight = FontWeight.Medium, letterSpacing = 1.2.sp,
    ),
    dayNumeral = TextStyle(
        fontFamily = ui, fontSize = 13.sp, lineHeight = 13.sp,
        fontWeight = FontWeight.Medium,
    ),
    weekdayHead = TextStyle(
        fontFamily = ui, fontSize = 10.sp, lineHeight = 12.sp,
        fontWeight = FontWeight.Medium, letterSpacing = 1.0.sp,
    ),
    uiBody = TextStyle(fontFamily = ui, fontSize = 15.sp, lineHeight = 22.sp),
    entryBody = TextStyle(fontFamily = reading, fontSize = 16.sp, lineHeight = 26.sp),
    entryBodyLarge = TextStyle(fontFamily = reading, fontSize = 18.sp, lineHeight = 30.sp),
    entryExcerpt = TextStyle(fontFamily = reading, fontSize = 14.sp, lineHeight = 21.sp),
    timestamp = TextStyle(
        fontFamily = ui, fontSize = 11.sp, lineHeight = 14.sp,
        fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp,
    ),
    button = TextStyle(
        fontFamily = ui, fontSize = 13.sp, lineHeight = 16.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp,
    ),
    meta = TextStyle(fontFamily = ui, fontSize = 12.sp, lineHeight = 16.sp),
)

public val LocalMiraColors: ProvidableCompositionLocal<MiraColorTokens> =
    staticCompositionLocalOf { DarkTokens }

public val LocalMiraAccent: ProvidableCompositionLocal<MiraAccent> =
    staticCompositionLocalOf { MiraAccent.Default }

public val LocalMiraType: ProvidableCompositionLocal<MiraTypeScale> =
    compositionLocalOf { error("MiraTheme not applied") }

/** Set false by the host when the platform reports a reduced-motion preference. */
public val LocalMiraMotionEnabled: ProvidableCompositionLocal<Boolean> =
    staticCompositionLocalOf { true }

public object MiraTheme {
    public val colors: MiraColorTokens
        @Composable @ReadOnlyComposable get() = LocalMiraColors.current

    public val type: MiraTypeScale
        @Composable @ReadOnlyComposable get() = LocalMiraType.current

    public val accent: MiraAccent
        @Composable @ReadOnlyComposable get() = LocalMiraAccent.current

    public val motionEnabled: Boolean
        @Composable @ReadOnlyComposable get() = LocalMiraMotionEnabled.current
}

@Composable
public fun MiraTheme(
    darkTheme: Boolean = true,
    accent: MiraAccent = MiraAccent.Default,
    motionEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val ui = interTight()
    val reading = literata()
    val type = miraTypeScale(ui, reading)

    val colors = remember(darkTheme, accent) {
        val base = if (darkTheme) DarkTokens else LightTokens
        base.copy(
            accent = accent.color,
            onAccent = accent.onAccent,
            heatmap = accent.heatmapRamp(darkTheme),
        )
    }

    // Material is a carrier here, not a source of colour: components inherit Mira's tokens so a
    // stray MaterialTheme.colorScheme lookup still lands on the right value.
    val scheme = remember(colors) {
        if (colors.isDark) {
            darkColorScheme(
                primary = colors.accent,
                onPrimary = colors.onAccent,
                secondary = colors.accent,
                onSecondary = colors.onAccent,
                background = colors.canvas,
                onBackground = colors.ink,
                surface = colors.surface,
                onSurface = colors.ink,
                surfaceVariant = colors.surfaceRaised,
                onSurfaceVariant = colors.inkMuted,
                outline = colors.outline,
                outlineVariant = colors.outline,
                error = colors.error,
                scrim = colors.scrim,
            )
        } else {
            lightColorScheme(
                primary = colors.accent,
                onPrimary = colors.onAccent,
                secondary = colors.accent,
                onSecondary = colors.onAccent,
                background = colors.canvas,
                onBackground = colors.ink,
                surface = colors.surface,
                onSurface = colors.ink,
                surfaceVariant = colors.surfaceRaised,
                onSurfaceVariant = colors.inkMuted,
                outline = colors.outline,
                outlineVariant = colors.outline,
                error = colors.error,
                scrim = colors.scrim,
            )
        }
    }

    val material = remember(type) {
        Typography(
            displayLarge = type.monthTitle,
            displayMedium = type.dayTitle,
            headlineSmall = type.dayTitle,
            titleLarge = type.dayTitle,
            titleMedium = type.button,
            bodyLarge = type.entryBody,
            bodyMedium = type.entryExcerpt,
            bodySmall = type.meta,
            labelLarge = type.button,
            labelMedium = type.sectionLabel,
            labelSmall = type.timestamp,
        )
    }

    CompositionLocalProvider(
        LocalMiraColors provides colors,
        LocalMiraAccent provides accent,
        LocalMiraType provides type,
        LocalMiraMotionEnabled provides motionEnabled,
        LocalContentColor provides colors.ink,
    ) {
        MaterialTheme(colorScheme = scheme, typography = material, content = content)
    }
}
