package mira.ui.generated.resources

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

public object MiraAssetIcons {
    public val Adjust: DrawableResource = Res.drawable.ic_adjust
    public val Edit: DrawableResource = Res.drawable.ic_edit
}

@Composable
public fun MiraAssetIcon(
    resource: DrawableResource,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
) {
    Image(
        painter = painterResource(resource),
        contentDescription = null,
        modifier = modifier.size(size),
        colorFilter = ColorFilter.tint(tint),
        contentScale = ContentScale.Fit,
    )
}
