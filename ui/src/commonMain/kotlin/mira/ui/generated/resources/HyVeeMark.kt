package mira.ui.generated.resources

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.decodeToImageBitmap

@Composable
internal fun HyVeeMarkAsset(
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
) {
    // Decode embedded PNG bytes. painterResource() on wasm fetches asynchronously and
    // stays blank when the relative resource URL 404s on SPA routes.
    val bitmap = remember { HyVeePngBytes.bytes.decodeToImageBitmap() }
    Image(
        bitmap = bitmap,
        contentDescription = "Hy-Vee",
        modifier = modifier.size(size),
        contentScale = ContentScale.Fit,
    )
}
