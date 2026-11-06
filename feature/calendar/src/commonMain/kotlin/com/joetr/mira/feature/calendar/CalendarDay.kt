package com.joetr.mira.feature.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.joetr.mira.domain.Attachment
import com.joetr.mira.domain.AttachmentKind
import com.joetr.mira.media.AttachmentImage
import com.joetr.mira.media.AttachmentImageState
import com.joetr.mira.media.MediaSource
import com.joetr.mira.ui.theme.MiraRadii
import com.joetr.mira.ui.theme.MiraSizes
import com.joetr.mira.ui.theme.MiraTheme
import com.joetr.mira.ui.theme.heatmapStep

/**
 * What the calendar needs to know about a day's media, without loading every attachment on the
 * screen at once.
 *
 * The first photo — or, failing that, a video's poster — becomes the day's tile; video and audio
 * keep a small badge so a day with a recording is visibly different from a day with only text.
 */
public data class CalendarDayMedia(
    val image: Attachment? = null,
    val hasVideo: Boolean = false,
    val hasAudio: Boolean = false,
)

/**
 * One day in the month grid.
 *
 * Written days are filled tiles rather than bare numerals, echoing the calendar-as-scrapbook
 * reference: a photo fills its day, text-only entries take the accent's heatmap colour, and
 * video/audio leave a small mark. Empty days stay quiet so a month still reads at a glance.
 */
@Composable
internal fun CalendarDayCell(
    day: Int,
    entryCount: Int,
    media: CalendarDayMedia?,
    busyThreshold: Int,
    isToday: Boolean,
    isSelected: Boolean,
    tileSize: Dp,
    numeralStyle: TextStyle,
    thumbnailFor: suspend (Attachment) -> MediaSource?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiraTheme.colors
    val step = heatmapStep(entryCount, busyThreshold)
    val shape = RoundedCornerShape(MiraRadii.control)
    var hasThumbnail by remember(media?.image?.id) { mutableStateOf(false) }
    val filled = entryCount > 0
    val background = when {
        hasThumbnail -> Color.Transparent
        filled -> colors.heatmap.getOrElse(step) { colors.surfaceRaised }
        else -> Color.Transparent
    }
    val numeralColor = when {
        hasThumbnail -> Color.White
        step >= 2 -> colors.canvas
        else -> colors.ink
    }
    // On a heavily filled tile the foreground ink disappears into the fill, so the ring switches
    // to the canvas colour; an empty or lightly filled day keeps the usual ink ring.
    val ringColor = when {
        hasThumbnail -> Color.White
        step >= 2 -> colors.canvas
        else -> colors.ink
    }
    val borderModifier = when {
        isSelected -> Modifier.border(2.dp, ringColor, shape)
        isToday -> Modifier.border(1.dp, ringColor, shape)
        else -> Modifier
    }

    Box(
        modifier = modifier
            .sizeIn(minWidth = MiraSizes.touchTarget, minHeight = MiraSizes.touchTarget)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(tileSize)
                .clip(shape)
                .background(background)
                .then(borderModifier),
            contentAlignment = Alignment.Center,
        ) {
            media?.image?.let { image ->
                AttachmentImage(
                    attachment = image,
                    sourceFor = thumbnailFor,
                    contentDescription = if (image.kind == AttachmentKind.VIDEO) {
                        "Video on day $day"
                    } else {
                        "Photo on day $day"
                    },
                    contentScale = ContentScale.Crop,
                    cacheKey = "calendar-tile",
                    modifier = Modifier.size(tileSize),
                    onState = { hasThumbnail = it == AttachmentImageState.Success },
                )
                if (hasThumbnail) {
                    Box(Modifier.size(tileSize).background(Color.Black.copy(alpha = 0.28f)))
                }
            }

            Text(
                text = day.toString(),
                style = numeralStyle,
                color = numeralColor,
            )

            val mediaDotCount =
                (if (media?.hasVideo == true) 1 else 0) +
                    (if (media?.hasAudio == true) 1 else 0)
            if (mediaDotCount > 0) {
                val dotSize = (tileSize.value * 0.055f).coerceIn(3f, 5f).dp
                val dotGap = (tileSize.value * 0.045f).coerceIn(2.5f, 3.5f).dp
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(dotGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(mediaDotCount) {
                        Box(
                            Modifier
                                .size(dotSize)
                                .background(
                                    if (hasThumbnail) Color.White else numeralColor,
                                    CircleShape,
                                ),
                        )
                    }
                }
            }
        }
    }
}

