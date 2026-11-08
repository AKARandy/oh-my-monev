package id.ohmymonev.android.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp

internal data class ScrollbarGeometry(val top: Float, val height: Float)

internal fun scrollbarGeometry(viewport: Float, maxScroll: Int, value: Int, minThumb: Float): ScrollbarGeometry? {
    if (viewport <= 0 || maxScroll <= 0 || maxScroll == Int.MAX_VALUE) return null
    val height = (viewport * viewport / (viewport + maxScroll)).coerceIn(minOf(minThumb, viewport), viewport)
    val top = (viewport - height) * value.coerceIn(0, maxScroll).toFloat() / maxScroll
    return ScrollbarGeometry(top, height)
}

/** Place before verticalScroll: drawing stays in the viewport, outside the text padding. */
@Composable
internal fun Modifier.scrollIndicator(state: ScrollState): Modifier {
    val thumb = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outlineVariant
    return drawWithContent {
        drawContent()
        val geometry = scrollbarGeometry(size.height, state.maxValue, state.value, 32.dp.toPx()) ?: return@drawWithContent
        val width = 3.dp.toPx()
        val x = size.width - width - 3.dp.toPx()
        drawRoundRect(track, Offset(x + width / 3, 0f), Size(width / 3, size.height), CornerRadius(width))
        drawRoundRect(thumb, Offset(x, geometry.top), Size(width, geometry.height), CornerRadius(width))
    }
}
