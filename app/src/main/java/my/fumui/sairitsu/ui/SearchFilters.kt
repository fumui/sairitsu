package my.fumui.sairitsu.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import my.fumui.sairitsu.data.OptionGroup
import my.fumui.sairitsu.data.OptionState
import my.fumui.sairitsu.data.SearchForms
import my.fumui.sairitsu.data.model.OptionValue
import my.fumui.sairitsu.data.model.Search
import my.fumui.sairitsu.text.Localizer

/**
 * The online search's filters: the search type when the server offers several, then the active form's options
 * except keywords (in the top bar) and sort (in the Sort menu). Every change searches again immediately.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchFiltersDialog(
    search: SearchState,
    localizer: Localizer,
    onForm: (Int) -> Unit,
    onOption: (Int, OptionState) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val form = search.forms.getOrNull(search.activeForm)
    val values = search.values.getOrNull(search.activeForm).orEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filters") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (search.forms.size > 1) {
                    FilterSection("Search type") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            search.forms.forEachIndexed { i, f ->
                                FilterChip(
                                    selected = i == search.activeForm,
                                    onClick = { onForm(i) },
                                    label = { Text(localizer.text(f.title)) },
                                )
                            }
                        }
                    }
                }
                val groups = form?.let(SearchForms::filterGroups).orEmpty()
                if (form == null || groups.isEmpty()) {
                    Text(
                        "No filters for this search type",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    groups.forEach { group ->
                        when (group) {
                            is OptionGroup.Single ->
                                OptionControl(form.options[group.index], values[group.index], localizer) {
                                    onOption(group.index, it)
                                }
                            is OptionGroup.Range -> RangeControl(
                                title = SearchForms.rangeLabel(
                                    localizer.text(form.options[group.minIndex].name),
                                    localizer.text(form.options[group.maxIndex].name),
                                    form.options[group.minIndex].query,
                                ),
                                minOption = form.options[group.minIndex],
                                minValue = values[group.minIndex],
                                maxValue = values[group.maxIndex],
                            ) { min, max ->
                                onOption(group.minIndex, OptionState.Slider(min))
                                onOption(group.maxIndex, OptionState.Slider(max))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = {
            val active = form != null && SearchForms.activeFilters(form, values) > 0
            TextButton(onClick = onClear, enabled = active) { Text("Clear all") }
        },
    )
}

/**
 * The Sort button and menu for [form]'s sort field and direction. [values] is the form's current values, or null
 * outside search, where the button just reads "Sort" and picking an entry starts a search.
 */
@Composable
fun SearchSortButton(
    form: Search,
    values: List<OptionState>?,
    localizer: Localizer,
    onOption: (Int, OptionState) -> Unit,
) {
    val sort = SearchForms.sortOptions(form)
    if (sort.isEmpty) return
    var open by remember { mutableStateOf(false) }
    fun current(index: Int?): OptionValue? {
        val name = (values?.getOrNull(index ?: return null) as? OptionState.Select)?.name ?: return null
        return form.options[index].values.firstOrNull { it.name == name }
    }
    val sortValue = current(sort.sort)
    val orderValue = current(sort.order)
    Box {
        SortButtonFace(
            label = sortValue?.let { localizer.text(it.title) } ?: "Sort",
            descending = orderValue?.let { direction(it.name) },
        ) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            sort.sort?.let { index ->
                for (choice in form.options[index].values) {
                    DropdownMenuItem(
                        text = { Text(localizer.text(choice.title)) },
                        leadingIcon = { RadioButton(selected = sortValue?.name == choice.name, onClick = null) },
                        onClick = {
                            onOption(index, OptionState.Select(choice.name))
                            open = false
                        },
                    )
                }
            }
            sort.order?.let { index ->
                if (sort.sort != null) HorizontalDivider()
                for (choice in form.options[index].values) {
                    val descending = direction(choice.name)
                    DropdownMenuItem(
                        text = {
                            Text(
                                when (descending) {
                                    true -> "Descending"
                                    false -> "Ascending"
                                    null -> localizer.text(choice.title)
                                },
                            )
                        },
                        leadingIcon = { RadioButton(selected = orderValue?.name == choice.name, onClick = null) },
                        trailingIcon = descending?.let {
                            { Icon(if (it) Icons.Outlined.ArrowDownward else Icons.Outlined.ArrowUpward, null) }
                        },
                        onClick = {
                            onOption(index, OptionState.Select(choice.name))
                            open = false
                        },
                    )
                }
            }
        }
    }
}

/** True for a descending value name, false for ascending, null when it isn't a direction. */
private fun direction(name: String): Boolean? = when (name.lowercase()) {
    "desc", "descending" -> true
    "asc", "ascending" -> false
    else -> null
}
