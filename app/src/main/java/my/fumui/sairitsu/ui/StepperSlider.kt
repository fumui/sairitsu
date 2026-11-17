package my.fumui.sairitsu.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A stepped numeric setting: the label with the value on the right, a slider between − and + buttons (hold to repeat),
 * a reset button while the value differs from [default], and tap-the-value to type an exact number. Dragging and
 * holding update the shown value live; [onChange] gets the snapped value once the gesture ends.
 */
@Composable
fun StepperSlider(
    label: String,
    value: Double,
    scale: StepScale,
    format: ValueFormat,
    default: Double?,
    onChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    var current by remember(value, scale.min, scale.max, scale.step) { mutableDoubleStateOf(scale.snap(value)) }
    var editing by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val commit: (Double) -> Unit = { v -> if (v != scale.snap(value)) onChange(v) }
    val stepBy: (Int) -> Unit = { delta ->
        val next = scale.valueAt(scale.indexOf(current) + delta)
        if (next != current) {
            current = next
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }
    val index = scale.indexOf(current)

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            if (default != null && scale.snap(default) != current) {
                IconButton(onClick = { scale.snap(default).let { current = it; commit(it) } }, Modifier.size(36.dp)) {
                    Icon(
                        Icons.Outlined.RestartAlt,
                        contentDescription = "Reset $label to ${format.format(default, scale)}",
                        Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Surface(
                onClick = { editing = true },
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.widthIn(min = 64.dp),
            ) {
                Text(
                    format.format(current, scale),
                    Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            RepeatButton(Icons.Outlined.Remove, "Decrease $label", enabled = index > 0, onStep = { stepBy(-1) }) {
                commit(current)
            }
            Slider(
                value = current.toFloat(),
                onValueChange = { v ->
                    val next = scale.snap(v.toDouble())
                    if (next != current) {
                        current = next
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                },
                onValueChangeFinished = { commit(current) },
                valueRange = scale.min.toFloat()..scale.max.toFloat(),
                modifier = Modifier.weight(1f),
            )
            RepeatButton(Icons.Outlined.Add, "Increase $label", enabled = index < scale.count, onStep = { stepBy(1) }) {
                commit(current)
            }
        }
        if (description != null) {
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (editing) {
        ValueEntryDialog(label, current, scale, format, onDismiss = { editing = false }) { typed ->
            editing = false
            current = typed
            commit(typed)
        }
    }
}

/** A round button that steps once on press, then repeats (speeding up) while held; [onRelease] ends the gesture. */
@Composable
private fun RepeatButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onStep: () -> Unit,
    onRelease: () -> Unit,
) {
    val step by rememberUpdatedState(onStep)
    val release by rememberUpdatedState(onRelease)
    val isEnabled by rememberUpdatedState(enabled)
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape,
        color = if (enabled) colors.secondaryContainer else colors.surfaceVariant.copy(alpha = 0.4f),
        contentColor = if (enabled) colors.onSecondaryContainer else colors.onSurface.copy(alpha = 0.38f),
        modifier = Modifier
            .size(40.dp)
            .semantics {
                role = Role.Button
                contentDescription = description
                if (enabled) onClick { step(); release(); true }
            }
            // Keyed on Unit: reaching the end of the range mid-hold disables the button but must not cancel the
            // gesture, or its release (which saves) would never run.
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    if (!isEnabled) return@detectTapGestures
                    val repeat = scope.launch {
                        step()
                        delay(400)
                        var interval = 120L
                        while (true) {
                            step()
                            delay(interval)
                            interval = (interval * 0.85).toLong().coerceAtLeast(25L)
                        }
                    }
                    try {
                        tryAwaitRelease()
                    } finally {
                        repeat.cancel()
                        release()
                    }
                })
            },
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, Modifier.size(20.dp)) }
    }
}

@Composable
private fun ValueEntryDialog(
    label: String,
    value: Double,
    scale: StepScale,
    format: ValueFormat,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit,
) {
    val initial = format.format(value, scale).removeSuffix(format.suffix)
    var text by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val parsed = format.parse(text.text, scale)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val range = "${format.format(scale.min, scale)} – ${format.format(scale.max, scale)}"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(label) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                isError = parsed == null,
                suffix = format.suffix.takeIf { it.isNotBlank() }?.let { { Text(it.trim()) } },
                supportingText = {
                    Text(if (parsed == null) "Enter a number ($range)" else "$range, step ${stepText(format, scale)}")
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { parsed?.let(onConfirm) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { parsed?.let(onConfirm) }, enabled = parsed != null) { Text("Set") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The step size in display units ("5%", "0.05", "5 ms"). */
private fun stepText(format: ValueFormat, scale: StepScale): String = format.format(scale.step, StepScale(0.0, scale.step, scale.step))
