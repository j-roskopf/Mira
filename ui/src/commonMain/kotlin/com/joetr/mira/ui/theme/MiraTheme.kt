package com.joetr.mira.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mira.ui.generated.resources.Fraunces
import mira.ui.generated.resources.Inter
import mira.ui.generated.resources.Res
import org.jetbrains.compose.resources.Font

/** Semantic colour tokens — see DESIGN_DNA.md. Feature modules must not use raw Color(0x…). */
public object MiraColors {
    /** Leaf green sampled from the Mira mark (~#2E7160). */
    public val Leaf: Color = Color(0xFF2E7160)
    public val OnLeaf: Color = Color(0xFFF4F8F6)

    public val Yellow: Color = Leaf
    public val OnYellow: Color = OnLeaf

    public val BackgroundDark: Color = Color(0xFF1C1C1E)
    public val BackgroundDeep: Color = Color(0xFF111113)
    public val CookBackground: Color = Color(0xFF212122)
    public val SurfaceDark: Color = Color(0xFF303033)
    public val SurfaceRaisedDark: Color = Color(0xFF4A4A4C)
    public val TextDark: Color = Color(0xFFECECEE)
    public val TextMutedDark: Color = Color(0xFF8C8C90)
    public val TextFaintDark: Color = Color(0xFF5B5B5F)
    public val DividerDark: Color = Color(0x1AFFFFFF)
    public val ErrorDark: Color = Color(0xFFFF8A80)

    public val BackgroundLight: Color = Color(0xFFFFFFFF)
    public val SurfaceLight: Color = Color(0xFFF2F2F7)
    public val SurfaceRaisedLight: Color = Color(0xFFE5E5EA)
    public val TextLight: Color = Color(0xFF1C1C1E)
    public val TextMutedLight: Color = Color(0xFF6C6C70)
    public val TextFaintLight: Color = Color(0xFFAEAEB2)
    public val DividerLight: Color = Color(0x1A000000)
    public val ErrorLight: Color = Color(0xFFB3261E)

    // Kept as semantic aliases for existing feature code.
    public val Background: Color = BackgroundDark
    public val Surface: Color = SurfaceDark
    public val SurfaceRaised: Color = SurfaceRaisedDark
    public val Text: Color = TextDark
    public val TextMuted: Color = TextMutedDark
    public val TextFaint: Color = TextFaintDark
    public val Divider: Color = DividerDark
    public val Error: Color = ErrorDark
    public val AccentWarm: Color = Leaf
    public val OnCoralTitle: Color = TextDark
    public val OnCoralSubtitle: Color = TextMutedDark
}

public object MiraSpacing {
    public val xs: androidx.compose.ui.unit.Dp = 4.dp
    public val sm: androidx.compose.ui.unit.Dp = 8.dp
    public val md: androidx.compose.ui.unit.Dp = 12.dp
    public val lg: androidx.compose.ui.unit.Dp = 16.dp
    public val gutter: androidx.compose.ui.unit.Dp = 28.dp
    public val xl: androidx.compose.ui.unit.Dp = 24.dp
    public val xxl: androidx.compose.ui.unit.Dp = 32.dp
    public val xxxl: androidx.compose.ui.unit.Dp = 48.dp
}

public object MiraRadii {
    public val thumb: androidx.compose.ui.unit.Dp = 6.dp
    public val card: androidx.compose.ui.unit.Dp = 8.dp
    public val sheet: androidx.compose.ui.unit.Dp = 18.dp
    public val chip: androidx.compose.ui.unit.Dp = 999.dp
    public val field: androidx.compose.ui.unit.Dp = 9.dp
    public val image: androidx.compose.ui.unit.Dp = 6.dp
}

private val LightColors = lightColorScheme(
    primary = MiraColors.Leaf,
    onPrimary = MiraColors.OnLeaf,
    background = MiraColors.BackgroundLight,
    onBackground = MiraColors.TextLight,
    surface = MiraColors.BackgroundLight,
    onSurface = MiraColors.TextLight,
    surfaceVariant = MiraColors.SurfaceLight,
    onSurfaceVariant = MiraColors.TextMutedLight,
    outline = MiraColors.DividerLight,
    outlineVariant = MiraColors.TextFaintLight,
    error = MiraColors.ErrorLight,
    tertiary = MiraColors.Leaf,
)

private val DarkColors = darkColorScheme(
    primary = MiraColors.Leaf,
    onPrimary = MiraColors.OnLeaf,
    background = MiraColors.BackgroundDark,
    onBackground = MiraColors.TextDark,
    surface = MiraColors.BackgroundDark,
    onSurface = MiraColors.TextDark,
    surfaceVariant = MiraColors.SurfaceDark,
    onSurfaceVariant = MiraColors.TextMutedDark,
    outline = MiraColors.DividerDark,
    outlineVariant = MiraColors.TextFaintDark,
    error = MiraColors.ErrorDark,
    tertiary = MiraColors.Leaf,
)

@Composable
public fun MiraTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    val fraunces = FontFamily(
        Font(Res.font.Fraunces, FontWeight.Normal),
        Font(Res.font.Fraunces, FontWeight.Medium),
        Font(Res.font.Fraunces, FontWeight.SemiBold),
        Font(Res.font.Fraunces, FontWeight.Bold),
    )
    val inter = FontFamily(
        Font(Res.font.Inter, FontWeight.Normal),
        Font(Res.font.Inter, FontWeight.Medium),
        Font(Res.font.Inter, FontWeight.SemiBold),
        Font(Res.font.Inter, FontWeight.Bold),
    )
    val typography = remember(fraunces, inter) {
        Typography(
            displayLarge = TextStyle(
                fontFamily = fraunces,
                fontSize = 32.sp,
                lineHeight = 35.sp,
                fontWeight = FontWeight.Bold,
            ),
            displayMedium = TextStyle(
                fontFamily = fraunces,
                fontSize = 30.sp,
                lineHeight = 33.sp,
                fontWeight = FontWeight.Bold,
            ),
            titleLarge = TextStyle(
                fontFamily = fraunces,
                fontSize = 16.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Bold,
            ),
            titleMedium = TextStyle(
                fontFamily = inter,
                fontSize = 16.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.Medium,
            ),
            bodyLarge = TextStyle(
                fontFamily = inter,
                fontSize = 15.sp,
                lineHeight = 27.sp,
                fontWeight = FontWeight.Normal,
            ),
            bodyMedium = TextStyle(
                fontFamily = inter,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Normal,
            ),
            labelMedium = TextStyle(
                fontFamily = inter,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.7.sp,
                fontWeight = FontWeight.Medium,
            ),
            labelSmall = TextStyle(
                fontFamily = inter,
                fontSize = 10.sp,
                lineHeight = 13.sp,
                fontWeight = FontWeight.Normal,
            ),
        )
    }
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        typography = typography,
    ) {
        // Scaffold/layout surfaces don't set content color; without this, bare Text is black.
        CompositionLocalProvider(LocalContentColor provides colors.onBackground) {
            content()
        }
    }
}
