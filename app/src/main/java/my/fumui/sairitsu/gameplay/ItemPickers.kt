package my.fumui.sairitsu.gameplay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import my.fumui.sairitsu.text.Localizer

/** "Level options" > "Look & sound": per kind, the item for this level and for its engine. */
@Composable
fun ColumnScope.ItemPickers(
    state: ItemPickerState,
    localizer: Localizer,
    onLevel: (ItemKind, ItemSelection) -> Unit,
    onEngine: (ItemKind, ItemSelection) -> Unit,
) {
    if (state.saved.isEmpty()) {
        Text(
            "Save skins, backgrounds, SFX or particles from a server's pages to use them here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    for (kind in ItemKind.entries) {
        // The kind's name sits above its pickers so both get the full width ("Use engine's background" must fit).
        Text(kindName(kind), style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ItemPicker(
                "This level", levelItemOptions(kind, state.saved, localizer::text),
                state.level[kind] ?: ItemSelection.UseEngine, { onLevel(kind, it) }, Modifier.weight(1f),
            )
            if (state.engineKnown) {
                ItemPicker(
                    "This engine", engineItemOptions(kind, state.saved, localizer::text),
                    state.engine[kind] ?: ItemSelection.Original, { onEngine(kind, it) }, Modifier.weight(1f),
                )
            }
        }
    }
}

/** The kind's heading: its label with a capital. */
private fun kindName(kind: ItemKind): String = kind.label.replaceFirstChar { it.uppercase() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemPicker(
    label: String,
    options: List<Pair<ItemSelection, String>>,
    selected: ItemSelection,
    onSelect: (ItemSelection) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == selected }?.second.orEmpty(),
            onValueChange = {},
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            readOnly = true,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for ((value, title) in options) {
                DropdownMenuItem(
                    text = { Text(title) },
                    onClick = {
                        expanded = false
                        onSelect(value)
                    },
                )
            }
        }
    }
}
