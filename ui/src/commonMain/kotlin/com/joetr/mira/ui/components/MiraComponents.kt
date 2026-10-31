package com.joetr.mira.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.DrawableResource
import coil3.compose.AsyncImage
import com.joetr.mira.platform.hyVeeSearchUrl
import com.joetr.mira.platform.imageUrlForDisplay
import com.joetr.mira.platform.openUrl
import com.joetr.mira.platform.targetSearchUrl
import com.joetr.mira.domain.model.SyncStatus
import mira.ui.generated.resources.MiraAssetIcon
import mira.ui.generated.resources.MiraAssetIcons
import com.joetr.mira.ui.icons.MiraIcon
import com.joetr.mira.ui.icons.MiraIconPainter
import com.joetr.mira.ui.icons.MiraIcons
import com.joetr.mira.ui.icons.HyVeeMark
import com.joetr.mira.ui.icons.TargetMark
import com.joetr.mira.ui.motion.sharedRecipeImage
import com.joetr.mira.ui.motion.sharedRecipeTitle
import com.joetr.mira.ui.theme.MiraColors
import com.joetr.mira.ui.theme.MiraRadii
import com.joetr.mira.ui.theme.MiraSpacing

@Composable
public fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .height(30.dp)
            .clip(RoundedCornerShape(MiraRadii.field))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = MiraSpacing.sm),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { innerTextField ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                MiraIcon(MiraIcons.Search, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 15.dp)
                Spacer(Modifier.width(7.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isBlank()) {
                        Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    innerTextField()
                }
            }
        },
    )
}

@Composable
public fun MiraCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(MiraRadii.card)
    Surface(
        modifier = modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 0.dp,
        content = content,
    )
}

@Composable
public fun RecipeGridCard(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    recipeId: String? = null,
    imageUrl: String? = null,
    imageModel: Any? = null,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(MiraRadii.card))
            .clickable(onClick = onClick),
    ) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f)) {
            RecipeImage(
                title = title,
                imageUrl = imageUrl,
                imageModel = imageModel,
                modifier = Modifier
                    .then(if (recipeId != null) Modifier.sharedRecipeImage(recipeId) else Modifier)
                    .fillMaxSize(),
                shape = RoundedCornerShape(MiraRadii.image),
            )
        }
        Spacer(Modifier.height(MiraSpacing.md))
        Text(
            title,
            modifier = if (recipeId != null) Modifier.sharedRecipeTitle(recipeId) else Modifier,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (subtitle != null) {
            Spacer(Modifier.height(MiraSpacing.xs))
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Phone recipe library row matching the reference "All" screen:
 * square thumb + Fraunces title + icon meta + 2-line description.
 */
@Composable
public fun RecipeListRow(
    title: String,
    description: String?,
    minutes: Int?,
    category: String?,
    sourceHost: String?,
    modifier: Modifier = Modifier,
    recipeId: String? = null,
    imageUrl: String? = null,
    imageModel: Any? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = MiraSpacing.xs),
        verticalAlignment = Alignment.Top,
    ) {
        RecipeImage(
            title = title,
            imageUrl = imageUrl,
            imageModel = imageModel,
            modifier = Modifier
                .then(if (recipeId != null) Modifier.sharedRecipeImage(recipeId) else Modifier)
                .size(78.dp),
            shape = RoundedCornerShape(MiraRadii.thumb),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                modifier = if (recipeId != null) Modifier.sharedRecipeTitle(recipeId) else Modifier,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(3.dp))
            RecipeMetaRow(minutes = minutes, category = category, sourceHost = sourceHost)
            if (!description.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
public fun RecipeMetaRow(
    minutes: Int?,
    category: String?,
    sourceHost: String?,
    modifier: Modifier = Modifier,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(MiraSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (minutes != null) {
            MetaIconLabel(MiraIcons.Clock, "${minutes}min", muted)
        }
        if (!category.isNullOrBlank()) {
            MetaIconLabel(MiraIcons.Tag, category, muted)
        }
        if (!sourceHost.isNullOrBlank()) {
            MetaIconLabel(MiraIcons.Globe, sourceHost, muted)
        }
    }
}

@Composable
private fun MetaIconLabel(icon: MiraIconPainter, label: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        MiraIcon(icon, tint = tint, size = 12.dp, strokeWidth = 1.4.dp)
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
public fun MetaRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
public fun DetailStat(
    icon: MiraIconPainter,
    value: String,
    label: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        MiraIcon(icon, tint = MaterialTheme.colorScheme.onSurface, size = 18.dp)
        Spacer(Modifier.width(MiraSpacing.sm))
        Column {
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
            )
            if (label != null) {
                Text(
                    label.uppercase(),
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 10.sp, letterSpacing = 0.8.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
public fun DetailAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 28.dp,
    icon: MiraIconPainter? = null,
    assetIcon: DrawableResource? = null,
) {
    require(icon != null || assetIcon != null) { "DetailAction requires icon or assetIcon" }
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(horizontal = MiraSpacing.md, vertical = MiraSpacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val tint = MaterialTheme.colorScheme.primary
        Box(
            modifier = Modifier.size(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                assetIcon != null -> MiraAssetIcon(assetIcon, tint = tint, size = iconSize)
                icon != null -> MiraIcon(icon, tint = tint, size = iconSize, strokeWidth = 1.8.dp)
            }
        }
        Spacer(Modifier.height(MiraSpacing.xs))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
public fun CircleIconButton(
    icon: MiraIconPainter,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    onImage: Boolean = false,
) {
    val background = if (onImage) {
        Color.Black.copy(alpha = 0.42f)
    } else {
        MiraColors.SurfaceRaised.copy(alpha = 0.82f)
    }
    val resolvedTint = tint ?: if (onImage) Color.White else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        MiraIcon(icon, tint = resolvedTint, size = 17.dp)
    }
}

@Composable
public fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tonal: Boolean = false,
) {
    val bg = if (tonal) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary
    val fg = if (tonal) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onPrimary
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(MiraRadii.chip))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = MiraSpacing.lg, vertical = MiraSpacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = fg, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
public fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
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
public fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        modifier = modifier,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
public fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(MiraSpacing.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.displayMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(MiraSpacing.md))
        Text(
            body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
public fun ImagePlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(MiraRadii.thumb))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text("🌿", style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
public fun RecipeImage(
    title: String,
    imageUrl: String?,
    imageModel: Any?,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(MiraRadii.thumb),
    contentScale: ContentScale = ContentScale.Crop,
) {
    Box(modifier = modifier.clip(shape)) {
        when (val model = imageModel ?: imageUrlForDisplay(imageUrl)) {
            null -> ImagePlaceholder(modifier = Modifier.fillMaxSize())
            else -> key(model) {
                AsyncImage(
                    model = model,
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = contentScale,
                )
            }
        }
    }
}

@Composable
public fun SyncStatusBadge(status: SyncStatus, pendingCount: Int, modifier: Modifier = Modifier) {
    val label = when (status) {
        SyncStatus.SYNCED -> "Synced"
        SyncStatus.SYNCING -> "Syncing…"
        SyncStatus.PENDING -> "$pendingCount pending"
        SyncStatus.ERROR -> "Sync error"
    }
    Text(label, modifier = modifier, style = MaterialTheme.typography.labelMedium, color = MiraColors.AccentWarm)
}

/**
 * Circular checkbox + title + optional note + coral quantity — groceries chrome reused for checklists.
 */
@Composable
public fun CheckableRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    note: String? = null,
    quantity: String? = null,
) {
    val ink = if (checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = MiraSpacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        CircularCheck(checked = checked)
        Spacer(Modifier.width(MiraSpacing.lg))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                buildAnnotatedString {
                    withStyle(
                        SpanStyle(
                            color = ink,
                            textDecoration = if (checked) TextDecoration.LineThrough else TextDecoration.None,
                        ),
                    ) {
                        append(title)
                    }
                    if (!note.isNullOrBlank()) {
                        append("  ")
                        withStyle(
                            SpanStyle(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontStyle = FontStyle.Italic,
                                fontSize = 13.sp,
                            ),
                        ) {
                            append(note)
                        }
                    }
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            if (!quantity.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    quantity,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = if (checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
        }
    }
}

@Composable
public fun CircularCheck(checked: Boolean, modifier: Modifier = Modifier) {
    val ring = MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(CircleShape)
            .then(
                if (checked) {
                    Modifier.background(MaterialTheme.colorScheme.primary)
                } else {
                    Modifier.border(1.5.dp, ring, CircleShape)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            MiraIcon(MiraIcons.Check, tint = MaterialTheme.colorScheme.onPrimary, size = 14.dp, strokeWidth = 2.dp)
        }
    }
}

/** When true, ingredient rows show Target / Hy-Vee grocery search marks. */
public val LocalShowStoreSearchLinks = compositionLocalOf { false }

@Composable
public fun IngredientLine(text: String, modifier: Modifier = Modifier) {
    val (quantity, rest) = splitIngredient(text)
    val searchQuery = ingredientSearchQuery(text)
    val showStoreSearchLinks = LocalShowStoreSearchLinks.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = MiraSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            buildAnnotatedString {
                if (quantity != null) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)) {
                        append(quantity)
                    }
                    append(" ")
                    append(rest)
                } else {
                    append(text)
                }
            },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (showStoreSearchLinks) {
            Spacer(Modifier.width(MiraSpacing.sm))
            Row(
                horizontalArrangement = Arrangement.spacedBy(MiraSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StoreSearchButton(
                    contentDescription = "Search Target for $searchQuery",
                    onClick = { openUrl(targetSearchUrl(searchQuery)) },
                ) {
                    TargetMark(size = 18.dp)
                }
                StoreSearchButton(
                    contentDescription = "Search Hy-Vee for $searchQuery",
                    onClick = { openUrl(hyVeeSearchUrl(searchQuery)) },
                ) {
                    HyVeeMark(size = 18.dp)
                }
            }
        }
    }
}

@Composable
private fun StoreSearchButton(
    contentDescription: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(MiraRadii.thumb))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
public fun StepCard(
    stepNumber: Int,
    text: String,
    minutes: Int?,
    completed: Boolean,
    onToggleComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(MiraSpacing.xl)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularCheck(checked = completed)
            Spacer(Modifier.width(MiraSpacing.md))
            Text("Step $stepNumber", style = MaterialTheme.typography.titleMedium)
            if (minutes != null) {
                Spacer(Modifier.width(MiraSpacing.md))
                Text(
                    "$minutes min",
                    style = MaterialTheme.typography.labelMedium,
                    color = MiraColors.AccentWarm,
                )
            }
        }
        Spacer(Modifier.height(MiraSpacing.md))
        Text(text, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(MiraSpacing.md))
        PillButton(if (completed) "Completed" else "Mark as complete", onToggleComplete, tonal = completed)
    }
}

@Composable
public fun HairlineDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.fillMaxWidth(),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outline,
    )
}

@Composable
public fun ScreenTitleRow(
    title: String,
    modifier: Modifier = Modifier,
    onMore: (() -> Unit)? = null,
    onAdd: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(MiraSpacing.sm)) {
            if (onAdd != null) {
                Box(
                    modifier = Modifier
            .size(30.dp)
                        .clickable(onClick = onAdd),
                    contentAlignment = Alignment.Center,
                ) {
                    MiraIcon(MiraIcons.Plus, tint = MaterialTheme.colorScheme.primary, size = 18.dp)
                }
            }
            if (onMore != null) {
                Box(
                    modifier = Modifier
                    .size(30.dp)
                        .clickable(onClick = onMore),
                    contentAlignment = Alignment.Center,
                ) {
                    MiraIcon(MiraIcons.More, tint = MaterialTheme.colorScheme.primary, size = 18.dp)
                }
            }
        }
    }
}

internal fun splitIngredient(raw: String): Pair<String?, String> {
    val match = Regex(
        pattern = """^(\d+(?:[./]\d+)?(?:\s*[-–]\s*\d+(?:[./]\d+)?)?\s*(?:g|kg|ml|l|oz|lb|tsp|tbsp|cups?|cans?|cloves?|pcs?|tbsp\.?|tsp\.?)?)\s+(.+)$""",
        option = RegexOption.IGNORE_CASE,
    ).find(raw.trim())
    return if (match != null) {
        match.groupValues[1] to match.groupValues[2]
    } else {
        null to raw
    }
}

private val leadingQuantityRegex = Regex(
    pattern = """^(?:\d+(?:[./]\d+)?(?:\s*[-–]\s*\d+(?:[./]\d+)?)?|[¼½¾⅓⅔⅕⅖⅗⅘⅙⅚⅛⅜⅝⅞]+)\s*""",
)

private val leadingUnitRegex = Regex(
    pattern = """^(?:pounds?|lbs?|ounces?|oz\.?|grams?|kilograms?|kgs?|milliliters?|liters?|mls?|""" +
        """teaspoons?|tablespoons?|tsps?|tbsps?|tbs\.?|cups?|cans?|cloves?|pieces?|pcs?|""" +
        """pinches?|dashes?|packages?|pkgs?|sticks?|bunches?|slices?|heads?|""" +
        """quarts?|qts?|pints?|pts?|gallons?|gals?|fluid\s+ounces?|fl\.?\s*oz\.?|""" +
        """kg|ml|tsp|tbsp|g|l)\.?\s+""",
    option = RegexOption.IGNORE_CASE,
)

/** Ingredient name suitable for grocery search (drops quantities, units, and prep notes). */
internal fun ingredientSearchQuery(raw: String, rest: String = splitIngredient(raw).second): String {
    var query = rest.ifBlank { raw }.trim()
    query = query.replace(Regex("""\([^)]*\)"""), " ")
    query = query.substringBefore(',').trim()
    // Keep stripping leading amounts/units so poorly split lines like "4" + "pounds butter"
    // (or "pounds butter" alone) don't search with sizing words.
    var previous: String
    do {
        previous = query
        query = leadingQuantityRegex.replace(query, "").trim()
        query = leadingUnitRegex.replace(query, "").trim()
    } while (query != previous)
    query = query.replace(Regex("""\s+"""), " ").trim()
    return query.ifBlank { raw.trim() }
}

public fun hostFromUrl(url: String?): String? {
    if (url.isNullOrBlank()) return null
    val cleaned = url.removePrefix("https://").removePrefix("http://").removePrefix("www.")
    return cleaned.substringBefore('/').takeIf { it.isNotBlank() }
}
