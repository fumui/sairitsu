package my.fumui.sairitsu.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import my.fumui.sairitsu.library.LibraryFacets
import my.fumui.sairitsu.library.LibraryQuery
import my.fumui.sairitsu.library.LibrarySort
import my.fumui.sairitsu.library.PlayedFilter
import my.fumui.sairitsu.library.RatingRange
import my.fumui.sairitsu.text.Localizer
import kotlin.math.roundToInt

/** Search, a Filters button (opens the filter dialog) and a Sort button (opens the sort menu) for the Play list. */
@Composable
fun LibraryFilterBar(
    query: LibraryQuery,
    facets: LibraryFacets,
    localizer: Localizer,
    onQuery: (LibraryQuery) -> Unit,
    modifier: Modifier = Modifier,
    /** The collections to filter by, as (id, name) sorted by name; empty hides the section. */
    collections: List<Pair<String, String>> = emptyList(),
) {
    Row(
        modifier.padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = query.text,
            onValueChange = { onQuery(query.copy(text = it)) },
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text("Search") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = if (query.text.isEmpty()) null else {
                { IconButton(onClick = { onQuery(query.copy(text = "")) }) { Icon(Icons.Outlined.Close, contentDescription = "Clear search") } }
            },
        )
        LibraryFiltersButton(query, facets, localizer, collections, onQuery)
        LibrarySortButton(query, onQuery)
    }
}

private val SORTS = listOf(
    LibrarySort.Added to "Date added",
    LibrarySort.Title to "Title",
    LibrarySort.LastPlayed to "Last played",
    LibrarySort.BestScore to "Best score",
    LibrarySort.Rating to "Rating",
)

private val PLAYED = listOf(
    PlayedFilter.All to "All",
    PlayedFilter.Played to "Played",
    PlayedFilter.NotPlayed to "Unplayed",
)

/** How many filter categories are narrowing the list. */
private fun LibraryQuery.activeFilters(): Int = listOf(
    collections.isNotEmpty(),
    servers.isNotEmpty(),
    engines.isNotEmpty(),
    tags.isNotEmpty(),
    rating != null,
    played != PlayedFilter.All,
).count { it }

/** The query with every filter cleared; search text and sort are kept. */
private fun LibraryQuery.withoutFilters(): LibraryQuery =
    copy(
        servers = emptySet(), engines = emptySet(), tags = emptySet(), rating = null, played = PlayedFilter.All,
        collections = emptySet(),
    )

/** The "Filters" button: outlined when no filter is on, tonal with a count badge when [active] filters are. */
@Composable
internal fun FiltersButton(active: Int, onClick: () -> Unit) {
    val content: @Composable () -> Unit = {
        Icon(Icons.Outlined.FilterList, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text("Filters")
        if (active > 0) {
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Badge { Text("$active") }
        }
    }
    if (active > 0) {
        FilledTonalButton(onClick = onClick, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) { content() }
    } else {
        OutlinedButton(onClick = onClick, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) { content() }
    }
}

/** The sort button: the current sort's [label] and, when known, its direction. */
@Composable
internal fun SortButtonFace(label: String, descending: Boolean?, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
        Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(label)
        if (descending != null) {
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Icon(
                if (descending) Icons.Outlined.ArrowDownward else Icons.Outlined.ArrowUpward,
                contentDescription = if (descending) "Descending" else "Ascending",
                Modifier.size(ButtonDefaults.IconSize),
            )
        }
    }
}

@Composable
private fun LibraryFiltersButton(
    query: LibraryQuery,
    facets: LibraryFacets,
    localizer: Localizer,
    collections: List<Pair<String, String>>,
    onQuery: (LibraryQuery) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    FiltersButton(query.activeFilters()) { open = true }
    if (open) FiltersDialog(query, facets, localizer, collections, onQuery, onDismiss = { open = false })
}

/** All filters in one dialog; every change applies to the list immediately. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FiltersDialog(
    query: LibraryQuery,
    facets: LibraryFacets,
    localizer: Localizer,
    collections: List<Pair<String, String>>,
    onQuery: (LibraryQuery) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filters") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ChipSection("Collections", collections.map { setOf(it.first) to it.second }, query.collections) {
                    onQuery(query.copy(collections = it))
                }
                FilterSection("Played") {
                    SingleChoiceSegmentedButtonRow {
                        PLAYED.forEachIndexed { i, (value, label) ->
                            SegmentedButton(
                                selected = query.played == value,
                                onClick = { onQuery(query.copy(played = value)) },
                                shape = SegmentedButtonDefaults.itemShape(i, PLAYED.size),
                            ) { Text(label) }
                        }
                    }
                }
                facets.ratingRange?.let { bounds ->
                    if (bounds.min < bounds.max) RatingSection(query.rating, bounds) { onQuery(query.copy(rating = it)) }
                }
                ChipSection("Server", facets.servers.map { setOf(it.first) to localizer.text(it.second) }, query.servers) {
                    onQuery(query.copy(servers = it))
                }
                ChipSection("Engine", facets.engines.map { setOf(it.first) to localizer.text(it.second) }, query.engines) {
                    onQuery(query.copy(engines = it))
                }
                TagsSection(tagGroups(facets.tags, localizer), query.tags) { onQuery(query.copy(tags = it)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = {
            TextButton(onClick = { onQuery(query.withoutFilters()) }, enabled = query.activeFilters() > 0) {
                Text("Clear all")
            }
        },
    )
}

@Composable
internal fun FilterSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        content()
    }
}

/**
 * Raw tags grouped by how they read: servers send the same tag as a text key (`#EASY`) or plain text (`Easy`), and
 * both should be one chip. Groups are sorted by label.
 */
private fun tagGroups(tags: List<String>, localizer: Localizer): List<Pair<Set<String>, String>> =
    tags.map { it to localizer.text(it).trim() }
        .groupBy { (_, label) -> label.replace(WHITESPACE, " ").lowercase() }
        .values
        .map { group -> group.map { it.first }.toSet() to group.first().second }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.second })

private val WHITESPACE = Regex("\\s+")

/** Tags as an autocomplete field over [groups]; picking a group selects all its raw tags. */
@Composable
private fun TagsSection(groups: List<Pair<Set<String>, String>>, selected: Set<String>, onChange: (Set<String>) -> Unit) {
    if (groups.isEmpty()) return
    val chosen = groups.indices.filter { i -> groups[i].first.any { it in selected } }
    val labels = remember(groups) { groups.map { it.second } }
    FilterSection(if (chosen.isEmpty()) "Tags" else "Tags (${chosen.size})") {
        AutocompleteChipsField(
            options = labels,
            selected = chosen,
            onAdd = { onChange(selected + groups[it].first) },
            onRemove = { onChange(selected - groups[it].first) },
        )
    }
}

/**
 * A toggle chip per option (the raw values it stands for, to its label); nothing selected means all. A chip is on
 * when any of its values is selected, and toggling it adds or removes all of them. Hidden when there are no options.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipSection(
    title: String,
    options: List<Pair<Set<String>, String>>,
    selected: Set<String>,
    onChange: (Set<String>) -> Unit,
) {
    if (options.isEmpty()) return
    val count = options.count { (values, _) -> values.any { it in selected } }
    FilterSection(if (count == 0) title else "$title ($count)") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((values, label) in options) {
                val on = values.any { it in selected }
                FilterChip(
                    selected = on,
                    onClick = { onChange(if (on) selected - values else selected + values) },
                    label = { Text(label) },
                )
            }
        }
    }
}

/** The rating range filter; a saved range is clamped to the library's current [bounds]. Applies when the drag ends. */
@Composable
private fun RatingSection(range: RatingRange?, bounds: RatingRange, onChange: (RatingRange?) -> Unit) {
    val lo = bounds.min.toFloat()
    val hi = bounds.max.toFloat()
    var value by remember(range, bounds) {
        val start = (range?.min?.toFloat() ?: lo).coerceIn(lo, hi)
        mutableStateOf(start..(range?.max?.toFloat() ?: hi).coerceIn(start, hi))
    }
    FilterSection("Rating ${value.start.roundToInt()}–${value.endInclusive.roundToInt()}") {
        RangeSlider(
            value = value,
            onValueChange = { value = it.start.roundToInt().toFloat()..it.endInclusive.roundToInt().toFloat() },
            valueRange = lo..hi,
            onValueChangeFinished = {
                val full = value.start <= lo && value.endInclusive >= hi
                onChange(if (full) null else RatingRange(value.start.toDouble(), value.endInclusive.toDouble()))
            },
        )
    }
}

/** Shows the current sort and direction; opens a menu to pick either. */
@Composable
private fun LibrarySortButton(query: LibraryQuery, onQuery: (LibraryQuery) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = SORTS.first { it.first == query.sort }.second
    Box {
        SortButtonFace(label, query.descending) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((sort, text) in SORTS) {
                DropdownMenuItem(
                    text = { Text(text) },
                    leadingIcon = { RadioButton(selected = query.sort == sort, onClick = null) },
                    onClick = {
                        onQuery(query.copy(sort = sort))
                        open = false
                    },
                )
            }
            HorizontalDivider()
            for ((descending, text) in listOf(false to "Ascending", true to "Descending")) {
                DropdownMenuItem(
                    text = { Text(text) },
                    leadingIcon = { RadioButton(selected = query.descending == descending, onClick = null) },
                    trailingIcon = {
                        Icon(if (descending) Icons.Outlined.ArrowDownward else Icons.Outlined.ArrowUpward, null)
                    },
                    onClick = {
                        onQuery(query.copy(descending = descending))
                        open = false
                    },
                )
            }
        }
    }
}
