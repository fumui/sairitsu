package my.fumui.sairitsu.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
        val hasOptions = ready != null && ready.options.isNotEmpty()
        SettingsFrame(
            title = "Configuration",
            onBack = back,
            modifier = Modifier.padding(padding),
            action = { TextButton(onClick = onReset, enabled = ready != null) { Text("Reset") } },
            bottomBar = if (ready != null) {
                { Button(onClick = onSave, enabled = ready.changed) { Text("Save") } }
            } else {
                null
            },
            scrollable = hasOptions,
        ) {
            when {
                form is Loadable.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ErrorMessage(form.message, onRetry)
                }
                ready == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                !hasOptions -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("This server has no options.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> SettingsSection(title = null, first = true) { ConfigOptions(ready, localizer, onChange) }
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

/** The server's options in its order, each in the shared row style with its description under the label. */
@Composable
private fun ConfigOptions(form: ConfigForm, localizer: Localizer, onChange: (Int, OptionState) -> Unit) {
    val groups = remember(form.options) { SearchForms.groups(Search("", "", options = form.options)) }
    for (group in groups) {
        when (group) {
            is OptionGroup.Single -> {
                val option = form.options[group.index]
                OptionControl(option, form.values[group.index], localizer, option.description?.let(localizer::text)) {
                    onChange(group.index, it)
                }
            }
            is OptionGroup.Range -> {
                val min = form.options[group.minIndex]
                val max = form.options[group.maxIndex]
                RangeControl(
                    title = SearchForms.rangeLabel(localizer.text(min.name), localizer.text(max.name), min.query),
                    minOption = min,
                    minValue = form.values[group.minIndex],
                    maxValue = form.values[group.maxIndex],
                    description = (min.description ?: max.description)?.let(localizer::text),
                ) { lower, upper ->
                    onChange(group.minIndex, OptionState.Slider(lower))
                    onChange(group.maxIndex, OptionState.Slider(upper))
                }
            }
        }
    }
}
