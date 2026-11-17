package my.fumui.sairitsu.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import my.fumui.sairitsu.data.OptionState
import my.fumui.sairitsu.data.SearchForms
import my.fumui.sairitsu.data.model.SearchOption
import my.fumui.sairitsu.text.Localizer
import kotlin.math.roundToInt

@Composable
fun OptionControl(option: SearchOption, value: OptionState, localizer: Localizer, onChange: (OptionState) -> Unit) {
    val label = localizer.text(option.name)
    when (value) {
        is OptionState.Text -> OutlinedTextField(
            value = value.value,
            onValueChange = { text -> if (SearchForms.acceptsText(option, text)) onChange(OptionState.Text(text)) },
            label = { Text(label) },
            placeholder = option.placeholder?.let { { Text(localizer.text(it)) } },
            singleLine = option.type == "text",
            modifier = Modifier.fillMaxWidth(),
        )
        is OptionState.Toggle -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = value.on, onCheckedChange = { onChange(OptionState.Toggle(it)) })
        }
        is OptionState.Select -> Labeled(label) {
            ChipRow {
                option.values.forEach { choice ->
                    FilterChip(
                        selected = value.name == choice.name,
                        onClick = { onChange(OptionState.Select(choice.name)) },
                        label = { Text(localizer.text(choice.title)) },
                    )
                }
            }
        }
        is OptionState.Multi -> Labeled(label) { MultiControl(option, value, localizer, onChange) }
        is OptionState.Slider -> SliderControl(option, value, label, onChange)
        OptionState.Unsupported -> Labeled(label) {
            Text("Not supported", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * A multi-select option as an autocomplete field. Servers often default to everything selected (meaning "any"); that
 * state shows as an empty field, the first value picked narrows to just it, and removing the last one goes back to
 * everything. Otherwise the chips are exactly the selected values.
 */
@Composable
private fun MultiControl(option: SearchOption, value: OptionState.Multi, localizer: Localizer, onChange: (OptionState) -> Unit) {
    val size = option.values.size
    val flags = List(size) { value.selected.getOrElse(it) { false } }
    val allByDefault = (SearchForms.defaultOf(option) as? OptionState.Multi)?.selected?.let { def ->
        def.size == size && def.all { it }
    } == true
    val showingAll = allByDefault && flags.all { it }
    val labels = remember(option.values, localizer) { option.values.map { localizer.text(it.title) } }
    AutocompleteChipsField(
        options = labels,
        selected = if (showingAll) emptyList() else flags.indices.filter { flags[it] },
        onAdd = { i ->
            val base = if (showingAll) List(size) { false } else flags
            onChange(OptionState.Multi(base.toMutableList().also { it[i] = true }))
        },
        onRemove = { i ->
            val next = flags.toMutableList().also { it[i] = false }
            onChange(OptionState.Multi(if (allByDefault && next.none { it }) List(size) { true } else next))
        },
    )
}

/**
 * A min/max slider pair shown as one range titled [title]. A range with too many values to pick on a slider
 * ([SearchForms.usesNumberFields]) is two number fields instead. Commits when a drag ends, or when a field is done,
 * so each change is one search.
 */
@Composable
fun RangeControl(
    title: String,
    minOption: SearchOption,
    minValue: OptionState,
    maxValue: OptionState,
    onChange: (min: Double, max: Double) -> Unit,
) {
    val lower = (minValue as? OptionState.Slider)?.value ?: minOption.min ?: 0.0
    val upper = (maxValue as? OptionState.Slider)?.value ?: minOption.max ?: 0.0
    if (SearchForms.usesNumberFields(minOption)) {
        Labeled(title) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                RangeNumberField("Min", lower, minOption, Modifier.weight(1f)) { onChange(it, upper) }
                Text("–", style = MaterialTheme.typography.bodyLarge)
                RangeNumberField("Max", upper, minOption, Modifier.weight(1f)) { onChange(lower, it) }
            }
        }
        return
    }
    val bounds = (minOption.min ?: 0.0).toFloat()..(minOption.max ?: 100.0).toFloat()
    val step = SearchForms.sliderStep(minOption)
    var dragging by remember(lower, upper) { mutableStateOf(lower.toFloat()..upper.toFloat()) }
    Labeled("$title ${formatRating(snap(dragging.start, bounds.start, step))} – ${formatRating(snap(dragging.endInclusive, bounds.start, step))}") {
        RangeSlider(
            value = dragging,
            onValueChange = { dragging = it },
            valueRange = bounds,
            steps = steps(bounds, step),
            onValueChangeFinished = {
                onChange(snap(dragging.start, bounds.start, step), snap(dragging.endInclusive, bounds.start, step))
            },
        )
    }
}

/**
 * One bound of a wide range, typed. The value is committed (clamped and snapped, [SearchForms.parseRangeNumber]) on
 * the keyboard's Done or when the field loses focus; text that isn't a number goes back to the current value.
 */
@Composable
private fun RangeNumberField(
    label: String,
    value: Double,
    option: SearchOption,
    modifier: Modifier,
    onCommit: (Double) -> Unit,
) {
    var text by remember(value) { mutableStateOf(formatRating(value)) }
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    fun commit() {
        val parsed = SearchForms.parseRangeNumber(text, option)
        text = formatRating(parsed ?: value)
        if (parsed != null && parsed != value) onCommit(parsed)
    }
    // Compose has no signed-number keyboard; ranges that go below zero get one with a minus sign.
    val type = if ((option.min ?: 0.0) < 0) KeyboardType.Ascii else KeyboardType.Decimal
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        modifier = modifier.onFocusChanged {
            if (focused && !it.isFocused) commit()
            focused = it.isFocused
        },
    )
}

@Composable
private fun SliderControl(option: SearchOption, value: OptionState.Slider, label: String, onChange: (OptionState) -> Unit) {
    val bounds = (option.min ?: 0.0).toFloat()..(option.max ?: 100.0).toFloat()
    val step = SearchForms.sliderStep(option)
    var dragging by remember(value) { mutableFloatStateOf(value.value.toFloat()) }
    Labeled("$label ${formatRating(snap(dragging, bounds.start, step))}") {
        Slider(
            value = dragging,
            onValueChange = { dragging = it },
            valueRange = bounds,
            steps = steps(bounds, step),
            onValueChangeFinished = { onChange(OptionState.Slider(snap(dragging, bounds.start, step))) },
        )
    }
}

@Composable
private fun Labeled(label: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

private fun steps(bounds: ClosedFloatingPointRange<Float>, step: Double): Int =
    (((bounds.endInclusive - bounds.start) / step).roundToInt() - 1).coerceAtLeast(0)

private fun snap(value: Float, min: Float, step: Double): Double =
    min + ((value - min) / step).roundToInt() * step
