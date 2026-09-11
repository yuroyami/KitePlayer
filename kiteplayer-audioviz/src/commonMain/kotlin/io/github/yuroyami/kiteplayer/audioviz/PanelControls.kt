package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The panels' small controls, built on foundation alone so the module needs no design system.

internal object PanelColors {
    val Bright = Color(0xFFE8EEF8)
    val Soft = Color(0xFFB6BECD)
    val Faint = Color(0xFF7C8798)
    val Raised = Color(0xFF2A3140)
    val Dark = Color(0xFF0B0E14)
}

private val Rounded = RoundedCornerShape(6.dp)
private val ThumbRadius = 6.dp

@Composable
internal fun Heading(text: String, modifier: Modifier = Modifier) {
    BasicText(
        text,
        modifier.padding(top = 8.dp),
        style = TextStyle(color = PanelColors.Bright, fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    )
}

@Composable
internal fun Note(text: String, modifier: Modifier = Modifier, color: Color = PanelColors.Soft) {
    BasicText(text, modifier, style = TextStyle(color = color, fontSize = 12.sp))
}

@Composable
internal fun PanelButton(label: String, onClick: () -> Unit) {
    BasicText(
        label,
        Modifier.clip(Rounded).background(PanelColors.Raised).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        style = TextStyle(color = PanelColors.Bright, fontSize = 12.sp),
    )
}

/** A box that is ticked or not, with its label. The whole row toggles. */
@Composable
internal fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clip(Rounded).clickable { onChange(!on) }.padding(vertical = 3.dp),
    ) {
        Canvas(Modifier.size(16.dp)) {
            drawRoundRect(if (on) PanelColors.Bright else PanelColors.Raised, cornerRadius = CornerRadius(3.dp.toPx()))
            if (on) {
                val stroke = 2.dp.toPx()
                val w = size.width
                val start = Offset(w * 0.22f, w * 0.52f)
                val corner = Offset(w * 0.42f, w * 0.72f)
                val end = Offset(w * 0.78f, w * 0.30f)
                drawLine(PanelColors.Dark, start, corner, stroke, StrokeCap.Round)
                drawLine(PanelColors.Dark, corner, end, stroke, StrokeCap.Round)
            }
        }
        Note(label, Modifier.padding(start = 8.dp))
    }
}

/** A small button for one option of a choice, filled when it is the chosen one. */
@Composable
internal fun Chip(label: String, chosen: Boolean, onPick: () -> Unit) {
    BasicText(
        label,
        Modifier.clip(RoundedCornerShape(4.dp)).background(if (chosen) PanelColors.Bright else PanelColors.Raised)
            .clickable(onClick = onPick).padding(horizontal = 10.dp, vertical = 4.dp),
        style = TextStyle(color = if (chosen) PanelColors.Dark else PanelColors.Bright, fontSize = 12.sp),
    )
}

/** A horizontal slider over [range]. A tap or a drag anywhere along it sets the value. */
@Composable
internal fun Slider(value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    val latest by rememberUpdatedState(onChange)
    val span = range.endInclusive - range.start
    fun valueAt(x: Float, width: Float, radius: Float): Float =
        range.start + ((x - radius) / (width - 2 * radius)).coerceIn(0f, 1f) * span
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(22.dp)
            .pointerInput(range) {
                detectTapGestures { latest(valueAt(it.x, size.width.toFloat(), ThumbRadius.toPx())) }
            }
            .pointerInput(range) {
                detectHorizontalDragGestures { change, _ ->
                    latest(valueAt(change.position.x, size.width.toFloat(), ThumbRadius.toPx()))
                }
            },
    ) {
        val radius = ThumbRadius.toPx()
        val y = size.height / 2
        val fraction = if (span > 0f) ((value - range.start) / span).coerceIn(0f, 1f) else 0f
        val x = radius + fraction * (size.width - 2 * radius)
        drawLine(PanelColors.Raised, Offset(radius, y), Offset(size.width - radius, y), 3.dp.toPx(), StrokeCap.Round)
        drawLine(PanelColors.Bright, Offset(radius, y), Offset(x, y), 3.dp.toPx(), StrokeCap.Round)
        drawCircle(PanelColors.Bright, radius, Offset(x, y))
    }
}

/** One line of text input with a hint while it is empty. */
@Composable
internal fun SearchField(query: String, hint: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    BasicTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = PanelColors.Bright, fontSize = 14.sp),
        cursorBrush = SolidColor(PanelColors.Bright),
        modifier = modifier.clip(Rounded).background(PanelColors.Raised).padding(horizontal = 10.dp, vertical = 8.dp),
        decorationBox = { field ->
            Box {
                if (query.isEmpty()) Note(hint, color = PanelColors.Faint)
                field()
            }
        },
    )
}
