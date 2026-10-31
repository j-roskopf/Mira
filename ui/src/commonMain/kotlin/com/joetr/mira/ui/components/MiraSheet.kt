package com.joetr.mira.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joetr.mira.ui.layout.MiraContentWidth
import com.joetr.mira.ui.layout.LocalWindowChromeInsets
import com.joetr.mira.ui.theme.MiraRadii
import com.joetr.mira.ui.theme.MiraSpacing

@Immutable
public data class MiraSheetColors(
    val sheet: Color,
    val onSheet: Color,
    val mutedOnSheet: Color,
    val dividerOnSheet: Color,
)

@Composable
public fun rememberMiraSheetColors(): MiraSheetColors {
    val sheet = MaterialTheme.colorScheme.primary
    val onSheet = MaterialTheme.colorScheme.onPrimary
    return remember(sheet, onSheet) {
        MiraSheetColors(
            sheet = sheet,
            onSheet = onSheet,
            mutedOnSheet = onSheet.copy(alpha = 0.72f),
            dividerOnSheet = onSheet.copy(alpha = 0.22f),
        )
    }
}

@Composable
public fun MiraSheetScaffold(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.(MiraSheetColors) -> Unit,
) {
    val colors = rememberMiraSheetColors()

    Box(
        modifier
            .fillMaxSize()
            .background(colors.sheet),
    ) {
        MiraContentWidth(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                        ),
                    )
                    .windowInsetsPadding(LocalWindowChromeInsets.current)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = MiraSpacing.gutter),
            ) {
                Spacer(Modifier.height(MiraSpacing.xl))
                content(colors)
                Spacer(Modifier.height(MiraSpacing.xxxl))
            }
        }
    }
}

@Composable
public fun SheetTitle(
    text: String,
    colors: MiraSheetColors,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.displayLarge,
        color = colors.onSheet,
    )
    Spacer(Modifier.height(MiraSpacing.xxl))
}

@Composable
public fun SheetSectionLabel(
    text: String,
    colors: MiraSheetColors,
    modifier: Modifier = Modifier,
) {
    Text(
        text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium.copy(
            fontSize = 11.sp,
            letterSpacing = 1.2.sp,
            fontWeight = FontWeight.Bold,
        ),
        color = colors.mutedOnSheet,
    )
}

@Composable
public fun SheetDivider(colors: MiraSheetColors, modifier: Modifier = Modifier) {
    Spacer(Modifier.height(MiraSpacing.lg).then(modifier))
    HorizontalDivider(thickness = 0.5.dp, color = colors.dividerOnSheet)
    Spacer(Modifier.height(MiraSpacing.lg))
}

@Composable
public fun SheetTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    colors: MiraSheetColors,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MiraRadii.field))
            .background(colors.onSheet.copy(alpha = 0.14f))
            .padding(horizontal = MiraSpacing.md, vertical = MiraSpacing.md),
        singleLine = singleLine,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSheet),
        cursorBrush = SolidColor(colors.onSheet),
        visualTransformation = visualTransformation,
        decorationBox = { inner ->
            Box {
                if (value.isBlank()) {
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.mutedOnSheet,
                    )
                }
                inner()
            }
        },
    )
}

@Composable
public fun SheetChip(
    text: String,
    selected: Boolean,
    colors: MiraSheetColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (selected) colors.onSheet else colors.onSheet.copy(alpha = 0.14f)
    val fg = if (selected) colors.sheet else colors.onSheet
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(MiraRadii.chip))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = MiraSpacing.md, vertical = 6.dp),
    ) {
        Text(text, color = fg, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
public fun SheetPillButton(
    text: String,
    colors: MiraSheetColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tonal: Boolean = false,
    enabled: Boolean = true,
) {
    val bg = if (tonal) colors.onSheet.copy(alpha = 0.14f) else colors.onSheet
    val fg = if (tonal) colors.onSheet else colors.sheet
    val bgAlpha = if (enabled) bg.alpha else bg.alpha * 0.5f
    val fgAlpha = if (enabled) fg.alpha else fg.alpha * 0.6f
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(MiraRadii.chip))
            .background(bg.copy(alpha = bgAlpha))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = MiraSpacing.lg, vertical = MiraSpacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = fg.copy(alpha = fgAlpha), style = MaterialTheme.typography.labelMedium)
    }
}
