package id.ohmymonev.android.ui

import android.os.Build
import android.view.ViewGroup
import android.widget.NumberPicker
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.time.LocalTime

internal fun parseTimeParts(hour: String, minute: String): LocalTime? {
    if (hour.length !in 1..2 || minute.length !in 1..2 ||
        !hour.all { it in '0'..'9' } || !minute.all { it in '0'..'9' }) return null
    val h = hour.toInt(); val m = minute.toInt()
    return if (h in 0..23 && m in 0..59) LocalTime.of(h, m) else null
}

@Composable
internal fun TimeWheels(hour: String, minute: String, onHour: (String) -> Unit, onMinute: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text("Jam", fontSize = 18.sp, color = MaterialTheme.colorScheme.secondary)
            }
            Box(Modifier.alpha(0f).clearAndSetSemantics {}) {
                Text(":", fontSize = 32.sp)
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text("Menit", fontSize = 18.sp, color = MaterialTheme.colorScheme.secondary)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TimePart("Jam", hour, 23, onHour, Modifier.weight(1f))
            Text(":", fontSize = 32.sp)
            TimePart("Menit", minute, 59, onMinute, Modifier.weight(1f))
        }
    }
}

@Composable
private fun TimePart(label: String, value: String, max: Int, onChange: (String) -> Unit, modifier: Modifier) {
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val density = LocalDensity.current
    val textSize = with(density) { 40.sp.toPx() }
    val wheelHeight = (200f * density.fontScale.coerceIn(1f, 1.5f)).dp
    val currentOnChange by rememberUpdatedState(onChange)
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AndroidView(modifier = Modifier.fillMaxWidth().height(wheelHeight).clipToBounds(), factory = { context ->
            NumberPicker(context).apply {
                clipChildren = true
                clipToPadding = true
                minValue = 0; maxValue = max; wrapSelectorWheel = true
                this.value = (value.toIntOrNull() ?: 0).coerceIn(0, max)
                descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
                isFocusableInTouchMode = true
                contentDescription = label
                setFormatter { it.toString().padStart(2, '0') }
                setOnValueChangedListener { _, _, next -> currentOnChange(next.toString().padStart(2, '0')) }
                setOnTouchListener { view, event ->
                    if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN)
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                    if (event.actionMasked == android.view.MotionEvent.ACTION_UP || event.actionMasked == android.view.MotionEvent.ACTION_CANCEL)
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                    false
                }
                for (i in 0 until childCount) {
                    (getChildAt(i) as? android.widget.EditText)?.apply {
                        setSelectAllOnFocus(true)
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        inputType = android.text.InputType.TYPE_CLASS_NUMBER
                        imeOptions = if (max == 23) android.view.inputmethod.EditorInfo.IME_ACTION_NEXT else android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                        addTextChangedListener(object : android.text.TextWatcher {
                            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { currentOnChange(s?.toString().orEmpty()) }
                            override fun afterTextChanged(s: android.text.Editable?) = Unit
                        })
                    }
                }
            }
        }, update = { picker ->
            val selected = (value.toIntOrNull() ?: 0).coerceIn(0, max)
            if (!picker.hasFocus() && picker.value != selected) picker.value = selected
            if (Build.VERSION.SDK_INT >= 29) {
                if (picker.textColor != textColor) picker.textColor = textColor
                if (picker.textSize != textSize) picker.textSize = textSize
                if (picker.selectionDividerHeight != 0) picker.selectionDividerHeight = 0
            }
        })
    }
}
