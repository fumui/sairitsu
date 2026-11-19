package my.fumui.sairitsu.gameplay

import androidx.compose.foundation.layout.ColumnScope
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
import my.fumui.sairitsu.text.Localizer

/** "Level options": the skin for this level and for its engine. */
@Composable
fun ColumnScope.SkinPickers(
    state: SkinPickerState,
    localizer: Localizer,
    onLevel: (SkinSelection) -> Unit,
    onEngine: (SkinSelection) -> Unit,
) {
    if (state.saved.isEmpty()) {
        Text(
            "Add a skin in Settings to use it here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    SkinPicker("This level", levelSkinOptions(state.saved, localizer::text), state.level, onLevel)
    if (state.engineKnown) {
        SkinPicker("This engine", engineSkinOptions(state.saved, localizer::text), state.engine, onEngine)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkinPicker(
    label: String,
    options: List<Pair<SkinSelection, String>>,
    selected: SkinSelection,
    onSelect: (SkinSelection) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == selected }?.second.orEmpty(),
            onValueChange = {},
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            readOnly = true,
            singleLine = true,
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
