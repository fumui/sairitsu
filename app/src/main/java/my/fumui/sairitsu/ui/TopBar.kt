package my.fumui.sairitsu.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import my.fumui.sairitsu.data.OptionState
import my.fumui.sairitsu.data.SearchForms
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import my.fumui.sairitsu.text.Localizer

@Composable
fun BrowseTopBar(
    state: PickerUiState,
    localizer: Localizer,
    onBack: () -> Unit,
    onSection: (Int?) -> Unit,
    onSearch: () -> Unit,
    onFilters: () -> Unit,
    onSort: (Int, OptionState) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
        Text(
            serverTitle(state, localizer),
            Modifier.widthIn(max = 340.dp).padding(end = 12.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        LazyRow(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            item(key = "all") {
                FilterChip(selected = state.activeSection == null, onClick = { onSection(null) }, label = { Text("All") })
            }
            val levelSections = state.sections.withIndex().filter { it.value.itemType == "level" }
            items(levelSections, key = { it.index }) { (index, section) ->
                FilterChip(
                    selected = state.activeSection == index,
                    onClick = { onSection(index) },
                    label = { Text(localizer.text(section.title)) },
                )
            }
        }
        IconButton(onClick = onSearch) { Icon(Icons.Outlined.Search, contentDescription = "Search") }
        // Outside search, Filters and Sort start one with the form search opens on.
        state.searches.getOrNull(SearchForms.defaultForm(state.searches))?.let { form ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (SearchForms.filterGroups(form).isNotEmpty() || state.searches.size > 1) FiltersButton(0, onFilters)
                SearchSortButton(form, values = null, localizer = localizer, onOption = onSort)
            }
        }
    }
}

@Composable
fun SearchTopBar(
    search: SearchState,
    localizer: Localizer,
    onClose: () -> Unit,
    onOption: (Int, OptionState) -> Unit,
    onQuickKeywords: (String) -> Unit,
    onFilters: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = "Close search") }
        val form = search.forms.getOrNull(search.activeForm)
        val keywordsIndex = form?.let(SearchForms::keywordsIndex)
        when {
            form == null -> KeywordField(search.quickKeywords, "Enter keywords", onQuickKeywords, Modifier.weight(1f))
            keywordsIndex != null -> {
                val option = form.options[keywordsIndex]
                val value = (search.values[search.activeForm][keywordsIndex] as? OptionState.Text)?.value.orEmpty()
                KeywordField(
                    value = value,
                    placeholder = localizer.text(option.placeholder ?: option.name),
                    onChange = { text ->
                        if (SearchForms.acceptsText(option, text)) onOption(keywordsIndex, OptionState.Text(text))
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            else -> Spacer(Modifier.weight(1f))
        }
        if (form != null) {
            val values = search.values[search.activeForm]
            if (SearchForms.filterGroups(form).isNotEmpty() || search.forms.size > 1) {
                FiltersButton(SearchForms.activeFilters(form, values), onFilters)
            }
            SearchSortButton(form, values, localizer, onOption)
        }
    }
}

/** A compact single-line search field that fits the 56dp top bar. */
@Composable
private fun KeywordField(value: String, placeholder: String, onChange: (String) -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        modifier = modifier.height(44.dp),
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxSize()
                    .border(1.dp, colors.outline, RoundedCornerShape(22.dp))
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Search, contentDescription = null, tint = colors.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                    }
                    inner()
                }
            }
        },
    )
}

private fun serverTitle(state: PickerUiState, localizer: Localizer): String =
    (state.serverInfo as? Loadable.Ready)?.value?.title?.let(localizer::text) ?: state.serverInput
