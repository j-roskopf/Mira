package mira.ui.generated.resources

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource

@Composable
internal fun GoogleMarkAsset(
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
) {
    Image(
        painter = painterResource(Res.drawable.google_g),
        contentDescription = "Google",
        modifier = modifier.size(size),
        contentScale = ContentScale.Fit,
    )
}
