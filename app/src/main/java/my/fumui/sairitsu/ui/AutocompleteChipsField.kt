package my.fumui.sairitsu.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * A multi-value picker: the chosen [options] (by index) as removable chips, and a text field that suggests the rest
 * as you type. Matches anywhere in the label, prefix matches first; Done on the keyboard adds the top suggestion.
 * The field reads "Any" while nothing is chosen.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AutocompleteChipsField(
    options: List<String>,
    selected: List<Int>,
    onAdd: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    val query = text.trim()
    val suggestions = remember(options, selected, query) {
        val chosen = selected.toSet()
        options.indices
            .filter { it !in chosen && options[it].contains(query, ignoreCase = true) }
            .sortedBy { if (options[it].startsWith(query, ignoreCase = true)) 0 else 1 }
            .take(MAX_SUGGESTIONS)
    }
    fun add(index: Int) {
        onAdd(index)
        text = ""
    }
    val showMenu = expanded && suggestions.isNotEmpty()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (selected.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (index in selected) {
                    InputChip(
                        selected = true,
                        onClick = { onRemove(index) },
                        label = { Text(options[index]) },
                        trailingIcon = {
                            Icon(Icons.Outlined.Close, contentDescription = "Remove", Modifier.size(InputChipDefaults.IconSize))
                        },
                    )
                }
            }
        }
        ExposedDropdownMenuBox(expanded = showMenu, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    expanded = true
                },
                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryEditable),
                singleLine = true,
                placeholder = { Text(if (selected.isEmpty()) "Any" else "Add more") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showMenu) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { suggestions.firstOrNull()?.let(::add) }),
            )
            ExposedDropdownMenu(expanded = showMenu, onDismissRequest = { expanded = false }) {
                for (index in suggestions) {
                    DropdownMenuItem(
                        text = { Text(options[index]) },
                        onClick = { add(index) },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                    )
                }
            }
        }
    }
}

private const val MAX_SUGGESTIONS = 100
