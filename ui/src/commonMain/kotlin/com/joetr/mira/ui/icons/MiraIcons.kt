package com.joetr.mira.ui.icons

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
public fun MiraIcon(
    painter: MiraIconPainter,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    strokeWidth: Dp = 1.6.dp,
) {
    Canvas(modifier.size(size)) {
        val stroke = Stroke(
            width = strokeWidth.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        painter.draw(this, tint, stroke)
    }
}

/** Official Google "G" mark for OAuth buttons. */
@Composable
public fun GoogleMark(
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
) {
    mira.ui.generated.resources.GoogleMarkAsset(modifier = modifier, size = size)
}

private val TargetRed = Color(0xFFCC0000)

/** Target bullseye mark for grocery search links. */
@Composable
public fun TargetMark(
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
) {
    Canvas(
        modifier
            .size(size)
            .semantics { contentDescription = "Target" },
    ) {
        val c = Offset(this.size.width / 2f, this.size.height / 2f)
        val r = this.size.minDimension / 2f
        drawCircle(TargetRed, radius = r * 0.96f, center = c)
        drawCircle(Color.White, radius = r * 0.62f, center = c)
        drawCircle(TargetRed, radius = r * 0.30f, center = c)
    }
}

/** Hy-Vee mark for grocery search links. */
@Composable
public fun HyVeeMark(
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
) {
    mira.ui.generated.resources.HyVeeMarkAsset(modifier = modifier, size = size)
}

/** The Mira leaf used in the launcher icon and brand moments. */
@Composable
public fun MiraAppMark(
    modifier: Modifier = Modifier,
    tint: Color,
    size: Dp = 112.dp,
) {
    Canvas(
        modifier
            .size(size)
            .semantics { contentDescription = "Mira" },
    ) {
        val canvasSize = this.size
        val leaf = Path().apply {
            moveTo(canvasSize.width * 0.16f, canvasSize.height * 0.82f)
            cubicTo(
                canvasSize.width * 0.05f, canvasSize.height * 0.56f,
                canvasSize.width * 0.24f, canvasSize.height * 0.20f,
                canvasSize.width * 0.82f, canvasSize.height * 0.10f,
            )
            cubicTo(
                canvasSize.width * 0.78f, canvasSize.height * 0.46f,
                canvasSize.width * 0.61f, canvasSize.height * 0.79f,
                canvasSize.width * 0.30f, canvasSize.height * 0.88f,
            )
            cubicTo(
                canvasSize.width * 0.23f, canvasSize.height * 0.90f,
                canvasSize.width * 0.18f, canvasSize.height * 0.87f,
                canvasSize.width * 0.16f, canvasSize.height * 0.82f,
            )
            close()
        }
        drawPath(leaf, tint)

        val vein = tint.copy(alpha = 0.42f)
        val stem = tint.copy(alpha = 0.75f)
        drawLine(
            color = stem,
            start = Offset(canvasSize.width * 0.18f, canvasSize.height * 0.98f),
            end = Offset(canvasSize.width * 0.60f, canvasSize.height * 0.30f),
            strokeWidth = canvasSize.minDimension * 0.045f,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = vein,
            start = Offset(canvasSize.width * 0.42f, canvasSize.height * 0.59f),
            end = Offset(canvasSize.width * 0.69f, canvasSize.height * 0.42f),
            strokeWidth = canvasSize.minDimension * 0.025f,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = vein,
            start = Offset(canvasSize.width * 0.31f, canvasSize.height * 0.76f),
            end = Offset(canvasSize.width * 0.55f, canvasSize.height * 0.72f),
            strokeWidth = canvasSize.minDimension * 0.025f,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = vein,
            start = Offset(canvasSize.width * 0.47f, canvasSize.height * 0.51f),
            end = Offset(canvasSize.width * 0.43f, canvasSize.height * 0.34f),
            strokeWidth = canvasSize.minDimension * 0.025f,
            cap = StrokeCap.Round,
        )
    }
}

public fun interface MiraIconPainter {
    public fun draw(
        scope: androidx.compose.ui.graphics.drawscope.DrawScope,
        tint: Color,
        stroke: Stroke,
    )
}

public object MiraIcons {
    public val Search: MiraIconPainter = MiraIconPainter { scope, tint, stroke ->
        with(scope) {
            val center = Offset(size.width * 0.44f, size.height * 0.44f)
            val radius = size.minDimension * 0.23f
            drawCircle(tint, radius = radius, center = center, style = stroke)
            drawLine(
                tint,
                Offset(size.width * 0.61f, size.height * 0.61f),
                Offset(size.width * 0.82f, size.height * 0.82f),
                stroke.width,
                StrokeCap.Round,
            )
        }
    }

    public val Recipes: MiraIconPainter = MiraIconPainter { scope, tint, stroke ->
        with(scope) {
            val inset = size.minDimension * 0.18f
            drawRoundRect(
                color = tint,
                topLeft = Offset(inset, inset * 0.9f),
                size = Size(size.width - inset * 2, size.height - inset * 1.8f),
                cornerRadius = CornerRadius(4f, 4f),
                style = stroke,
            )
            drawLine(
                color = tint,
                start = Offset(inset * 1.4f, size.height * 0.42f),
                end = Offset(size.width - inset * 1.4f, size.height * 0.42f),
                strokeWidth = stroke.width,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = tint,
                start = Offset(inset * 1.4f, size.height * 0.58f),
                end = Offset(size.width - inset * 1.4f, size.height * 0.58f),
                strokeWidth = stroke.width,
                cap = StrokeCap.Round,
            )
        }
    }

    public val Discover: MiraIconPainter = MiraIconPainter { scope, tint, stroke ->
        with(scope) {
            val c = Offset(size.width / 2, size.height / 2)
            val r = size.minDimension * 0.32f
            drawCircle(tint, radius = r, center = c, style = stroke)
            val path = Path().apply {
                moveTo(c.x + r * 0.15f, c.y - r * 0.55f)
                lineTo(c.x + r * 0.55f, c.y + r * 0.15f)
                lineTo(c.x - r * 0.15f, c.y + r * 0.55f)
                lineTo(c.x - r * 0.55f, c.y - r * 0.15f)
                close()
            }
            drawPath(path, tint, style = stroke)
        }
    }

    public val Account: MiraIconPainter = MiraIconPainter { scope, tint, stroke ->
        with(scope) {
            val c = Offset(size.width / 2, size.height * 0.36f)
            drawCircle(tint, radius = size.minDimension * 0.16f, center = c, style = stroke)
            val path = Path().apply {
                moveTo(size.width * 0.22f, size.height * 0.82f)
                quadraticTo(
                    size.width * 0.5f,
                    size.height * 0.58f,
                    size.width * 0.78f,
                    size.height * 0.82f,
                )
            }
            drawPath(path, tint, style = stroke)
        }
    }

    public val Clock: MiraIconPainter = MiraIconPainter { scope, tint, stroke ->
        with(scope) {
            val c = Offset(size.width / 2, size.height / 2)
            drawCircle(tint, radius = size.minDimension * 0.34f, center = c, style = stroke)
            drawLine(tint, c, Offset(c.x, c.y - size.minDimension * 0.18f), stroke.width, StrokeCap.Round)
            drawLine(tint, c, Offset(c.x + size.minDimension * 0.14f, c.y + size.minDimension * 0.08f), stroke.width, StrokeCap.Round)
        }
    }

    public val Tag: MiraIconPainter = MiraIconPainter { scope, tint, stroke ->
        with(scope) {
            val path = Path().apply {
                moveTo(size.width * 0.18f, size.height * 0.42f)
                lineTo(size.width * 0.18f, size.height * 0.22f)
                lineTo(size.width * 0.55f, size.height * 0.22f)
                lineTo(size.width * 0.82f, size.height * 0.5f)
                lineTo(size.width * 0.55f, size.height * 0.78f)
                lineTo(size.width * 0.18f, size.height * 0.78f)
                close()
            }
            drawPath(path, tint, style = stroke)
            drawCircle(tint, radius = size.minDimension * 0.05f, center = Offset(size.width * 0.34f, size.height * 0.38f))
        }
    }

    public val Globe: MiraIconPainter = MiraIconPainter { scope, tint, stroke ->
        with(scope) {
            val c = Offset(size.width / 2, size.height / 2)
            val r = size.minDimension * 0.34f
            drawCircle(tint, radius = r, center = c, style = stroke)
            drawOval(
                color = tint,
                topLeft = Offset(c.x - r * 0.45f, c.y - r),
                size = Size(r * 0.9f, r * 2),
                style = stroke,
            )
            drawLine(
                tint,
                Offset(c.x - r, c.y),
                Offset(c.x + r, c.y),
                stroke.width,
                StrokeCap.Round,
            )
        }
    }

    public val Bag: MiraIconPainter = MiraIconPainter { scope, tint, stroke ->
        with(scope) {
            drawRoundRect(
                color = tint,
                topLeft = Offset(size.width * 0.22f, size.height * 0.36f),
                size = Size(size.width * 0.56f, size.height * 0.46f),
                cornerRadius = CornerRadius(4f, 4f),
                style = stroke,
            )
            val path = Path().apply {
                moveTo(size.width * 0.34f, size.height * 0.36f)
                quadraticTo(size.width * 0.34f, size.height * 0.18f, size.width * 0.5f, size.height * 0.18f)
                quadraticTo(size.width * 0.66f, size.height * 0.18f, size.width * 0.66f, size.height * 0.36f)
            }
            drawPath(path, tint, style = stroke)
        }
    }

    public val Play: MiraIconPainter = MiraIconPainter { scope, tint, stroke ->
        with(scope) {
            val c = Offset(size.width / 2, size.height / 2)
            drawCircle(tint, radius = size.minDimension * 0.36f, center = c, style = stroke)
            val path = Path().apply {
                moveTo(size.width * 0.42f, size.height * 0.32f)
                lineTo(size.width * 0.72f, size.height * 0.5f)
                lineTo(size.width * 0.42f, size.height * 0.68f)
                close()
            }
            drawPath(path, tint)
        }
    }

    public val Adjust: MiraIconPainter = MiraIconPainter { scope, tint, stroke ->
        with(scope) {
            val inset = size.minDimension * 0.22f
            val left = inset
            val right = size.width - inset
            val knobRadius = size.minDimension * 0.055f
            val trackY = floatArrayOf(
                size.height * 0.30f,
                size.height * 0.50f,
                size.height * 0.70f,
            )
            val knobX = floatArrayOf(
                left + (right - left) * 0.14f,
                left + (right - left) * 0.86f,
                (left + right) / 2f,
            )

            trackY.forEach { y ->
                drawLine(tint, Offset(left, y), Offset(right, y), stroke.width, StrokeCap.Round)
            }
            trackY.forEachIndexed { index, y ->
                drawCircle(tint, radius = knobRadius, center = Offset(knobX[index], y))
            }
        }
    }

    public val Edit: MiraIconPainter = MiraIconPainter { scope, tint, _ ->
        with(scope) {
            val tip = Path().apply {
                moveTo(size.width * 0.10f, size.height * 0.90f)
                lineTo(size.width * 0.30f, size.height * 0.70f)
                lineTo(size.width * 0.24f, size.height * 0.76f)
                close()
            }
            val body = Path().apply {
                moveTo(size.width * 0.29f, size.height * 0.71f)
                lineTo(size.width * 0.71f, size.height * 0.29f)
                lineTo(size.width * 0.77f, size.height * 0.35f)
                lineTo(size.width * 0.35f, size.height * 0.77f)
                close()
            }
            val eraser = Path().apply {
                moveTo(size.width * 0.73f, size.height * 0.27f)
                lineTo(size.width * 0.90f, size.height * 0.10f)
                lineTo(size.width * 0.84f, size.height * 0.04f)
                lineTo(size.width * 0.67f, size.height * 0.21f)
                close()
            }

            drawPath(tip, tint)
            drawPath(body, tint)
            drawPath(eraser, tint)
        }
    }

    public val Plus: MiraIconPainter = MiraIconPainter { scope, tint, stroke ->
        with(scope) {
            val c = Offset(size.width / 2, size.height / 2)
            val arm = size.minDimension * 0.28f
            drawLine(tint, Offset(c.x - arm, c.y), Offset(c.x + arm, c.y), stroke.width, StrokeCap.Round)
            drawLine(tint, Offset(c.x, c.y - arm), Offset(c.x, c.y + arm), stroke.width, StrokeCap.Round)
        }
    }

    public val More: MiraIconPainter = MiraIconPainter { scope, tint, stroke ->
        with(scope) {
            val y = size.height / 2
            val r = size.minDimension * 0.07f
            drawCircle(tint, r, Offset(size.width * 0.28f, y))
            drawCircle(tint, r, Offset(size.width * 0.5f, y))
            drawCircle(tint, r, Offset(size.width * 0.72f, y))
        }
    }

    public val Share: MiraIconPainter = MiraIconPainter { scope, tint, stroke ->
        with(scope) {
            val top = Offset(size.width * 0.70f, size.height * 0.24f)
            val left = Offset(size.width * 0.28f, size.height * 0.50f)
            val bottom = Offset(size.width * 0.70f, size.height * 0.76f)
            drawLine(tint, left, top, stroke.width, StrokeCap.Round)
            drawLine(tint, left, bottom, stroke.width, StrokeCap.Round)
            drawCircle(tint, radius = size.minDimension * 0.12f, center = top, style = stroke)
            drawCircle(tint, radius = size.minDimension * 0.12f, center = left, style = stroke)
            drawCircle(tint, radius = size.minDimension * 0.12f, center = bottom, style = stroke)
        }
    }

    public val Back: MiraIconPainter = MiraIconPainter { scope, tint, stroke ->
        with(scope) {
            val path = Path().apply {
                moveTo(size.width * 0.58f, size.height * 0.28f)
                lineTo(size.width * 0.32f, size.height * 0.5f)
                lineTo(size.width * 0.58f, size.height * 0.72f)
            }
            drawPath(path, tint, style = stroke)
        }
    }

    public val Close: MiraIconPainter = MiraIconPainter { scope, tint, stroke ->
        with(scope) {
            drawLine(
                tint,
                Offset(size.width * 0.28f, size.height * 0.28f),
                Offset(size.width * 0.72f, size.height * 0.72f),
                stroke.width,
                StrokeCap.Round,
            )
            drawLine(
                tint,
                Offset(size.width * 0.72f, size.height * 0.28f),
                Offset(size.width * 0.28f, size.height * 0.72f),
                stroke.width,
                StrokeCap.Round,
            )
        }
    }

    public val Timer: MiraIconPainter = MiraIconPainter { scope, tint, stroke ->
        with(scope) {
            drawLine(
                tint,
                Offset(size.width * 0.38f, size.height * 0.18f),
                Offset(size.width * 0.62f, size.height * 0.18f),
                stroke.width,
                StrokeCap.Round,
            )
            val c = Offset(size.width / 2, size.height * 0.56f)
            drawCircle(tint, radius = size.minDimension * 0.28f, center = c, style = stroke)
            drawLine(tint, c, Offset(c.x, c.y - size.minDimension * 0.14f), stroke.width, StrokeCap.Round)
        }
    }

    public val Check: MiraIconPainter = MiraIconPainter { scope, tint, stroke ->
        with(scope) {
            val path = Path().apply {
                moveTo(size.width * 0.22f, size.height * 0.52f)
                lineTo(size.width * 0.42f, size.height * 0.7f)
                lineTo(size.width * 0.78f, size.height * 0.3f)
            }
            drawPath(path, tint, style = stroke)
        }
    }

    public val Download: MiraIconPainter = MiraIconPainter { scope, tint, stroke ->
        with(scope) {
            drawRoundRect(
                color = tint,
                topLeft = Offset(size.width * 0.22f, size.height * 0.58f),
                size = Size(size.width * 0.56f, size.height * 0.24f),
                cornerRadius = CornerRadius(3f, 3f),
                style = stroke,
            )
            drawLine(
                tint,
                Offset(size.width * 0.5f, size.height * 0.2f),
                Offset(size.width * 0.5f, size.height * 0.58f),
                stroke.width,
                StrokeCap.Round,
            )
            val path = Path().apply {
                moveTo(size.width * 0.34f, size.height * 0.44f)
                lineTo(size.width * 0.5f, size.height * 0.6f)
                lineTo(size.width * 0.66f, size.height * 0.44f)
            }
            drawPath(path, tint, style = stroke)
        }
    }

    public val Scan: MiraIconPainter = MiraIconPainter { scope, tint, stroke ->
        with(scope) {
            val inset = size.minDimension * 0.2f
            val arm = size.minDimension * 0.18f
            // Top-left
            drawLine(tint, Offset(inset, inset + arm), Offset(inset, inset), stroke.width, StrokeCap.Round)
            drawLine(tint, Offset(inset, inset), Offset(inset + arm, inset), stroke.width, StrokeCap.Round)
            // Top-right
            drawLine(tint, Offset(size.width - inset - arm, inset), Offset(size.width - inset, inset), stroke.width, StrokeCap.Round)
            drawLine(tint, Offset(size.width - inset, inset), Offset(size.width - inset, inset + arm), stroke.width, StrokeCap.Round)
            // Bottom-left
            drawLine(tint, Offset(inset, size.height - inset - arm), Offset(inset, size.height - inset), stroke.width, StrokeCap.Round)
            drawLine(tint, Offset(inset, size.height - inset), Offset(inset + arm, size.height - inset), stroke.width, StrokeCap.Round)
            // Bottom-right
            drawLine(tint, Offset(size.width - inset - arm, size.height - inset), Offset(size.width - inset, size.height - inset), stroke.width, StrokeCap.Round)
            drawLine(tint, Offset(size.width - inset, size.height - inset - arm), Offset(size.width - inset, size.height - inset), stroke.width, StrokeCap.Round)
        }
    }

    public val Chevron: MiraIconPainter = MiraIconPainter { scope, tint, stroke ->
        with(scope) {
            val path = Path().apply {
                moveTo(size.width * 0.38f, size.height * 0.28f)
                lineTo(size.width * 0.62f, size.height * 0.5f)
                lineTo(size.width * 0.38f, size.height * 0.72f)
            }
            drawPath(path, tint, style = stroke)
        }
    }

    public val Camera: MiraIconPainter = MiraIconPainter { scope, tint, stroke ->
        with(scope) {
            drawRoundRect(
                color = tint,
                topLeft = Offset(size.width * 0.16f, size.height * 0.34f),
                size = Size(size.width * 0.68f, size.height * 0.46f),
                cornerRadius = CornerRadius(4f, 4f),
                style = stroke,
            )
            drawCircle(tint, radius = size.minDimension * 0.14f, center = Offset(size.width * 0.5f, size.height * 0.56f), style = stroke)
            drawRoundRect(
                color = tint,
                topLeft = Offset(size.width * 0.36f, size.height * 0.22f),
                size = Size(size.width * 0.28f, size.height * 0.14f),
                cornerRadius = CornerRadius(2f, 2f),
                style = stroke,
            )
        }
    }

    public val Photo: MiraIconPainter = MiraIconPainter { scope, tint, stroke ->
        with(scope) {
            drawRoundRect(
                color = tint,
                topLeft = Offset(size.width * 0.18f, size.height * 0.22f),
                size = Size(size.width * 0.64f, size.height * 0.56f),
                cornerRadius = CornerRadius(4f, 4f),
                style = stroke,
            )
            drawCircle(tint, radius = size.minDimension * 0.07f, center = Offset(size.width * 0.36f, size.height * 0.4f))
            val path = Path().apply {
                moveTo(size.width * 0.24f, size.height * 0.7f)
                lineTo(size.width * 0.42f, size.height * 0.52f)
                lineTo(size.width * 0.54f, size.height * 0.6f)
                lineTo(size.width * 0.7f, size.height * 0.42f)
                lineTo(size.width * 0.76f, size.height * 0.7f)
                close()
            }
            drawPath(path, tint, style = stroke)
        }
    }
}
