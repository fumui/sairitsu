package my.fumui.sairitsu.gameplay

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import my.fumui.sairitsu.text.Localizer
import my.fumui.sairitsu.ui.AppSettingsSliders
import my.fumui.sairitsu.ui.InputOffsetSlider
import my.fumui.sairitsu.ui.OptionControl
import my.fumui.sairitsu.ui.SettingsFrame
import my.fumui.sairitsu.ui.SettingsSection
import my.fumui.sairitsu.ui.StepScale
import my.fumui.sairitsu.ui.StepperSlider
import my.fumui.sairitsu.ui.ValueFormat
import java.util.Locale

/**
 * "Level options": the engine's Level Options, grouped by category (sliders as [StepperSlider], toggles and selects
 * by the picker's `OptionControl`; each change is saved at once), the saved items for this level and engine, and the
 * app's [AppSettings], in one column of the shared settings frame.
 */
@Composable
fun OptionsScreen(
    state: GameplayUiState.Options,
    pictures: ItemPictures,
    onOption: (name: String, value: Float) -> Unit,
    onSettings: (AppSettings) -> Unit,
    onInputOffset: (OffsetScope, Float) -> Unit,
    onLevelItem: (ItemKind, ItemSelection) -> Unit,
    onEngineItem: (ItemKind, ItemSelection) -> Unit,
    onBack: () -> Unit,
) {
    val localizer = remember { Localizer(Localizer.serverLanguage(Locale.getDefault())) }
    val groups = remember(state.options) { state.options.groupBy { it.category.orEmpty() }.toList() }
    val changed = state.options.filter { o -> state.values[o.name]?.let { it != o.def } == true }

    SettingsFrame(
        title = "Level options",
        onBack = onBack,
        action = {
            if (changed.isNotEmpty()) {
                TextButton(onClick = { changed.forEach { onOption(it.name, it.def) } }) { Text("Reset all") }
            }
        },
    ) {
        groups.forEachIndexed { index, (category, options) ->
            SettingsSection(categoryTitle(category, untitled = groups.size == 1), first = index == 0) {
                for (option in options) {
                    OptionRow(option, state.values[option.name] ?: option.def, localizer) { onOption(option.name, it) }
                }
            }
        }
        SettingsSection("Look & sound", first = groups.isEmpty()) {
            ItemPickers(state.items, pictures, localizer, onLevelItem, onEngineItem)
        }
        SettingsSection("Audio & input") {
            AppSettingsSliders(state.settings, inputOffsetLabel = "Input offset (all levels)", onSettings = onSettings)
            InputOffsetSlider("Input offset (this server)", state.offsets.server) { onInputOffset(OffsetScope.Server, it) }
            InputOffsetSlider("Input offset (this level)", state.offsets.level) { onInputOffset(OffsetScope.Level, it) }
            Text(
                "Total input offset: ${offsetText(state.offsets.total)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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
        OptionControl(search, option.toState(current), localizer, description) { next -> next.toStoredFloat()?.let(onChange) }
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
