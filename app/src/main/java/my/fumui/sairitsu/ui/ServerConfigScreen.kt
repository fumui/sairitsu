package my.fumui.sairitsu.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import my.fumui.sairitsu.data.OptionGroup
import my.fumui.sairitsu.data.OptionState
import my.fumui.sairitsu.data.SearchForms
import my.fumui.sairitsu.data.model.Search
import my.fumui.sairitsu.text.Localizer

/**
 * A server's configuration: its options in the server's order, a Reset button on top and Save at the bottom end.
 * Leaving with unsaved changes asks first. [snackbar] shows messages from the caller (e.g. a failed save).
 */
@Composable
fun ServerConfigScreen(
    form: Loadable<ConfigForm>,
    localizer: Localizer,
    onChange: (index: Int, value: OptionState) -> Unit,
    onReset: () -> Unit,
    onSave: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    val ready = (form as? Loadable.Ready)?.value
    val changed = ready?.changed == true
    var confirmingDiscard by remember { mutableStateOf(false) }
    val back = { if (changed) confirmingDiscard = true else onBack() }
    BackHandler(onBack = back)

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, contentWindowInsets = WindowInsets(0)) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).windowInsetsPadding(WindowInsets.displayCutout)) {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
                Text("Configuration", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onReset, enabled = ready != null) { Text("Reset") }
            }
            HorizontalDivider()
            when (form) {
                Loadable.Idle, Loadable.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is Loadable.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ErrorMessage(form.message, onRetry)
                }
                is Loadable.Ready -> ConfigBody(form.value, localizer, onChange, onSave)
            }
        }
    }

    if (confirmingDiscard) {
        AlertDialog(
            onDismissRequest = { confirmingDiscard = false },
            title = { Text("Discard changes?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDiscard = false
                    onBack()
                }) { Text("Discard") }
            },
            dismissButton = { TextButton(onClick = { confirmingDiscard = false }) { Text("Keep editing") } },
        )
    }
}

@Composable
private fun ConfigBody(
    form: ConfigForm,
    localizer: Localizer,
    onChange: (Int, OptionState) -> Unit,
    onSave: () -> Unit,
) {
    val groups = remember(form.options) { SearchForms.groups(Search("", "", options = form.options)) }
    Column(Modifier.fillMaxSize()) {
        if (groups.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("This server has no options.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(groups) { group ->
                    when (group) {
                        is OptionGroup.Single -> {
                            val option = form.options[group.index]
                            Described(option.description, localizer) {
                                if (form.values[group.index] == OptionState.Unsupported) {
                                    UnsupportedRow(localizer.text(option.name))
                                } else {
                                    OptionControl(option, form.values[group.index], localizer) { onChange(group.index, it) }
                                }
                            }
                        }
                        is OptionGroup.Range -> {
                            val min = form.options[group.minIndex]
                            val max = form.options[group.maxIndex]
                            Described(min.description ?: max.description, localizer) {
                                RangeControl(
                                    title = SearchForms.rangeLabel(localizer.text(min.name), localizer.text(max.name), min.query),
                                    minOption = min,
                                    minValue = form.values[group.minIndex],
                                    maxValue = form.values[group.maxIndex],
                                ) { lower, upper ->
                                    onChange(group.minIndex, OptionState.Slider(lower))
                                    onChange(group.maxIndex, OptionState.Slider(upper))
                                }
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.End) {
            Button(onClick = onSave, enabled = form.changed) { Text("Save") }
        }
    }
}

/** [content] with the option's [description], when it has one, under it. */
@Composable
private fun Described(description: String?, localizer: Localizer, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        content()
        if (!description.isNullOrBlank()) {
            Text(
                localizer.text(description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UnsupportedRow(name: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text("Not supported", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
