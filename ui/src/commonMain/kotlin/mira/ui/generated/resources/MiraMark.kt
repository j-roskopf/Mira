package mira.ui.generated.resources

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource

@Composable
internal fun MiraMarkAsset(
    modifier: Modifier = Modifier,
    tint: Color,
    size: Dp = 112.dp,
) {
    Image(
        painter = painterResource(Res.drawable.mira_mark),
        contentDescription = "Mira",
        modifier = modifier.size(size).semantics { contentDescription = "Mira" },
        contentScale = ContentScale.Fit,
        colorFilter = ColorFilter.tint(tint),
    )
}
