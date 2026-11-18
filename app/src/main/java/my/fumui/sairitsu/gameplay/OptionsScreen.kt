package my.fumui.sairitsu.gameplay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import my.fumui.sairitsu.text.Localizer
import my.fumui.sairitsu.ui.AppSettingsSliders
import my.fumui.sairitsu.ui.InputOffsetSlider
import my.fumui.sairitsu.ui.OptionControl
import my.fumui.sairitsu.ui.StepScale
import my.fumui.sairitsu.ui.StepperSlider
import my.fumui.sairitsu.ui.ValueFormat
import java.util.Locale

/**
 * "Level options": the engine's Level Options, grouped by category (sliders as [StepperSlider], toggles and selects
 * by the picker's `OptionControl`; each change is saved at once) and the app's [AppSettings].
 */
@Composable
fun OptionsScreen(
    state: GameplayUiState.Options,
    onOption: (name: String, value: Float) -> Unit,
    onSettings: (AppSettings) -> Unit,
    onInputOffset: (OffsetScope, Float) -> Unit,
    onBack: () -> Unit,
) {
    val localizer = remember { Localizer(Localizer.serverLanguage(Locale.getDefault())) }
    val groups = remember(state.options) { state.options.groupBy { it.category.orEmpty() }.toList() }
    val changed = state.options.filter { o -> state.values[o.name]?.let { it != o.def } == true }
    val sections = groups.map { (category, options) ->
        SectionSpec(categoryTitle(category, untitled = groups.size == 1), options.sumOf { it.rowWeight() }) {
            for (option in options) {
                OptionRow(option, state.values[option.name] ?: option.def, localizer) { onOption(option.name, it) }
            }
        }
    } + SectionSpec("Audio & input", 6 * SLIDER_WEIGHT + 1) {
        AppSettingsSliders(state.settings, inputOffsetLabel = "Input offset (all levels)", onSettings = onSettings)
        InputOffsetSlider("Input offset (this server)", state.offsets.server) { onInputOffset(OffsetScope.Server, it) }
        InputOffsetSlider("Input offset (this level)", state.offsets.level) { onInputOffset(OffsetScope.Level, it) }
        Text(
            "Total input offset: ${offsetText(state.offsets.total)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
        BoxWithConstraints(Modifier.widthIn(max = 1280.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) {
            val twoColumns = maxWidth >= 840.dp && sections.size > 1
            Column {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Level options", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                    if (changed.isNotEmpty()) {
                        TextButton(onClick = { changed.forEach { onOption(it.name, it.def) } }) { Text("Reset all") }
                    }
                    OutlinedButton(onClick = onBack) { Text("Back") }
                }
                Spacer(Modifier.height(12.dp))
                if (twoColumns) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        for (column in balance(sections)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                column.forEach { Section(it.title, it.content) }
                            }
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        sections.forEach { Section(it.title, it.content) }
                    }
                }
            }
        }
    }
}

private class SectionSpec(val title: String?, val weight: Int, val content: @Composable ColumnScope.() -> Unit)

private const val SLIDER_WEIGHT = 2

/** Rough height of an option's row: a slider is about two toggle rows. */
private fun EngineOption.rowWeight(): Int = if (type == "slider") SLIDER_WEIGHT else 1

/**
 * Splits [sections] into two columns of similar height, keeping their order: the first column takes sections until it
 * holds about half the total.
 */
private fun balance(sections: List<SectionSpec>): List<List<SectionSpec>> {
    val total = sections.sumOf { it.weight + 1 } // + 1 for the card's title and padding
    var left = 0
    var split = 0
    while (split < sections.size - 1) {
        val next = left + sections[split].weight + 1
        if (left > 0 && next - total / 2.0 > total / 2.0 - left) break
        left = next
        split++
    }
    return listOf(sections.take(split), sections.drop(split))
}

@Composable
private fun OptionRow(option: EngineOption, current: Float, localizer: Localizer, onChange: (Float) -> Unit) {
    val label = localizer.text(option.title ?: option.name)
    val description = option.description?.let(localizer::text)
    if (option.type == "slider" && option.min != null && option.max != null) {
        val search = remember(option) { option.toSearchOption() }
        val scale = remember(option) { StepScale(search.min!!, search.max!!, search.step ?: 1.0) }
        val format = remember(option) { ValueFormat.forUnit(option.unit) }
        StepperSlider(
            label = label,
            value = current.toString().toDouble(),
            scale = scale,
            format = format,
            default = option.def.toString().toDouble(),
            onChange = { onChange(it.toFloat()) },
            description = description,
        )
    } else {
        val search = remember(option) { option.toSearchOption() }
        OptionControl(search, option.toState(current), localizer) { next -> next.toStoredFloat()?.let(onChange) }
    }
}

@Composable
private fun Section(title: String?, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            content()
        }
    }
}

/**
 * "gameplay" → "Gameplay", "ui" → "UI" (two letters read as an acronym); options without a category share an untitled
 * group (titled "Options" beside others).
 */
private fun categoryTitle(category: String, untitled: Boolean): String? = when {
    category.length <= 2 -> category.uppercase().ifEmpty { if (untitled) null else "Options" }
    category.isNotEmpty() -> category.replace('_', ' ').replaceFirstChar { it.titlecase() }
    untitled -> null
    else -> "Options"
}
