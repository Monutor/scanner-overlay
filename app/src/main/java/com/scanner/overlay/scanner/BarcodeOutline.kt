package com.scanner.overlay.scanner

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Тонкий контур ровно по найденному штрихкоду, а не по области экрана.
 *
 * [quad] — именно [State], а не значение: `.value` читается внутри draw-лямбды, и тогда
 * обновление (сигнал ~30 кадров/с) инвалидирует только фазу рисования, не перекомпозицию
 * бокса с камерой. Если передать значение, каждый кадр пересобирал бы весь бокс.
 *
 * Маппинг под `PreviewView.ScaleType.FILL_CENTER`: кадр равномерно масштабируется, чтобы
 * заполнить вью, и лишнее кропается по центру. Отсюда `scale = max(w/frameW, h/frameH)`
 * и центральное смещение — обе оси масштабируются одним коэффициентом, без растяжения.
 */
@Composable
fun BarcodeOutline(
    quad: State<BarcodeQuad?>,
    modifier: Modifier = Modifier,
    color: Color = Color(0xFF00E676),
    strokeWidth: Dp = 3.dp
) {
    Canvas(modifier) {
        val current = quad.value ?: return@Canvas
        val points = current.points
        val frameWidth = current.frameWidth
        val frameHeight = current.frameHeight
        if (points.size < 4 || frameWidth <= 0 || frameHeight <= 0) return@Canvas
        if (size.width <= 0f || size.height <= 0f) return@Canvas

        val scale = maxOf(size.width / frameWidth, size.height / frameHeight)
        val offsetX = (size.width - frameWidth * scale) / 2f
        val offsetY = (size.height - frameHeight * scale) / 2f

        val path = Path()
        for (i in 0 until 4) {
            val point = points[i]
            val x = point.x * scale + offsetX
            val y = point.y * scale + offsetY
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()

        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = strokeWidth.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}