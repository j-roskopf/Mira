package com.joetr.mira.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joetr.mira.ui.icons.MiraIcon
import com.joetr.mira.ui.icons.MiraIconPainter
import com.joetr.mira.ui.icons.MiraIcons
import com.joetr.mira.ui.layout.LocalWindowChromeInsets
import com.joetr.mira.ui.layout.miraSafeArea
import com.joetr.mira.ui.theme.MiraSpacing

@Composable
public fun AdaptiveScaffold(
    widthDp: Int,
    selected: TopLevelDestination,
    onNavigate: (TopLevelDestination) -> Unit,
    accountLabel: String? = null,
    content: @Composable () -> Unit,
) {
    when {
        widthDp >= 960 -> SidebarLayout(selected, onNavigate, accountLabel, content)
        widthDp >= 600 -> RailLayout(selected, onNavigate, content)
        else -> BottomBarLayout(selected, onNavigate, content)
    }
}

@Composable
private fun SidebarLayout(
    selected: TopLevelDestination,
    onNavigate: (TopLevelDestination) -> Unit,
    accountLabel: String?,
    content: @Composable () -> Unit,
) {
    // Every Mira screen sits on the same canvas; nothing paints under the title bar.
    val immersive = false
    val canvas = MaterialTheme.colorScheme.background
    Row(
        Modifier
            .fillMaxSize()
            .background(canvas)
            .then(if (immersive) Modifier else Modifier.miraSafeArea()),
    ) {
        Box(
            Modifier
                .width(260.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface)
                .then(if (immersive) Modifier.windowInsetsPadding(LocalWindowChromeInsets.current) else Modifier)
                .padding(28.dp),
        ) {
            Column(Modifier.fillMaxHeight()) {
                // The wordmark is chrome, not an active state — only the selected tab takes accent.
                Text(
                    "MIRA",
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(MiraSpacing.xl))
                ColumnNav(selected, onNavigate)
                Spacer(Modifier.weight(1f))
                accountLabel?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNavigate(TopLevelDestination.Settings) }
                            .padding(MiraSpacing.sm),
                    )
                }
            }
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .then(if (immersive) Modifier else Modifier.padding(28.dp)),
        ) { content() }
    }
}

@Composable
private fun RailLayout(
    selected: TopLevelDestination,
    onNavigate: (TopLevelDestination) -> Unit,
    content: @Composable () -> Unit,
) {
    val immersive = false
    val canvas = MaterialTheme.colorScheme.background
    Row(
        Modifier
            .fillMaxSize()
            .background(canvas)
            .then(if (immersive) Modifier else Modifier.miraSafeArea()),
    ) {
        NavigationRail(
            containerColor = MaterialTheme.colorScheme.surface,
            windowInsets = if (immersive) {
                LocalWindowChromeInsets.current
            } else {
                WindowInsets(0, 0, 0, 0)
            },
        ) {
            destinations.forEach { dest -> RailItem(dest, selected, onNavigate) }
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .then(if (immersive) Modifier else Modifier.padding(MiraSpacing.lg)),
        ) { content() }
    }
}

@Composable
private fun BottomBarLayout(
    selected: TopLevelDestination,
    onNavigate: (TopLevelDestination) -> Unit,
    content: @Composable () -> Unit,
) {
    val immersive = false
    val canvas = MaterialTheme.colorScheme.background
    val navBar = if (immersive) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.background
    }
    val activeTint = if (immersive) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.primary
    }
    val inactiveTint = if (immersive) {
        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(Modifier.fillMaxSize().background(canvas)) {
        // Mira has no immersive tab; the shell always stays inside the safe area.
        // Other tabs keep chrome + status/cutout padding on the content slot.
        val contentModifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .then(
                if (immersive) {
                    Modifier
                } else {
                    Modifier
                        .windowInsetsPadding(LocalWindowChromeInsets.current)
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(
                                WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                            ),
                        )
                },
            )
        Box(contentModifier) { content() }
        Row(
            Modifier
                .fillMaxWidth()
                .background(navBar)
                .navigationBarsPadding()
                .height(74.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            destinations.forEach { dest ->
                val active = dest.topLevel == selected
                val tint = if (active) activeTint else inactiveTint
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onNavigate(dest.topLevel) }
                        .padding(top = 11.dp, bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    MiraIcon(dest.icon, tint = tint, size = 20.dp, strokeWidth = 1.6.dp)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        dest.label,
                        color = tint,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 10.sp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ColumnNav(
    selected: TopLevelDestination,
    onNavigate: (TopLevelDestination) -> Unit,
) {
    destinations.forEach { dest ->
        val active = dest.topLevel == selected
        val tint = if (active) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onNavigate(dest.topLevel) }
                .padding(vertical = MiraSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MiraIcon(dest.icon, tint = tint, size = 22.dp)
            Spacer(Modifier.width(MiraSpacing.md))
            Text(
                dest.label,
                color = tint,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun RailItem(
    dest: Destination,
    selected: TopLevelDestination,
    onNavigate: (TopLevelDestination) -> Unit,
) {
    val active = dest.topLevel == selected
    val activeTint = MaterialTheme.colorScheme.primary
    val inactiveTint = MaterialTheme.colorScheme.onSurface
    NavigationRailItem(
        selected = active,
        onClick = { onNavigate(dest.topLevel) },
        icon = {
            MiraIcon(
                dest.icon,
                tint = if (active) activeTint else inactiveTint,
                size = 22.dp,
            )
        },
        label = {
            Text(
                dest.label,
                color = if (active) activeTint else inactiveTint,
            )
        },
        colors = NavigationRailItemDefaults.colors(
            selectedIconColor = activeTint,
            selectedTextColor = activeTint,
            indicatorColor = Color.Transparent,
            unselectedIconColor = inactiveTint,
            unselectedTextColor = inactiveTint,
        ),
    )
}

private data class Destination(
    val topLevel: TopLevelDestination,
    val label: String,
    val icon: MiraIconPainter,
)

private val destinations = listOf(
    Destination(TopLevelDestination.Calendar, "Calendar", MiraIcons.Calendar),
    Destination(TopLevelDestination.Timeline, "Timeline", MiraIcons.Timeline),
    Destination(TopLevelDestination.Search, "Search", MiraIcons.Search),
    Destination(TopLevelDestination.Settings, "Settings", MiraIcons.Settings),
)
