package my.fumui.sairitsu.gameplay

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import my.fumui.sairitsu.text.Localizer
import my.fumui.sairitsu.ui.ItemPicture
import my.fumui.sairitsu.ui.OptionLabel

private val TILE_WIDTH = 144.dp
private val PICTURE_HEIGHT = 84.dp
private val TILE_SHAPE = RoundedCornerShape(12.dp)

/** "Level options" > "Look & sound": per kind, a row of picture tiles for this level and one for its engine. */
@Composable
fun ColumnScope.ItemPickers(
    state: ItemPickerState,
    pictures: ItemPictures,
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
        Column(Modifier.fillMaxWidth()) {
            OptionLabel(kind.label.replaceFirstChar { it.uppercase() })
            TileRow(
                "This level", kind, levelItemOptions(kind, state.saved, localizer::text),
                state.level[kind] ?: ItemSelection.UseEngine, state, pictures,
            ) { onLevel(kind, it) }
            if (state.engineKnown) {
                TileRow(
                    "This engine", kind, engineItemOptions(kind, state.saved, localizer::text),
                    state.engine[kind] ?: ItemSelection.Original, state, pictures,
                ) { onEngine(kind, it) }
            }
        }
    }
}

@Composable
private fun TileRow(
    caption: String,
    kind: ItemKind,
    options: List<Pair<ItemSelection, String>>,
    selected: ItemSelection,
    state: ItemPickerState,
    pictures: ItemPictures,
    onSelect: (ItemSelection) -> Unit,
) {
    Spacer(Modifier.height(8.dp))
    Text(caption, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(6.dp))
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = options.indexOfFirst { it.first == selected }.coerceAtLeast(0))
    LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(options, key = { _, (choice, _) -> choice.toString() }) { _, (choice, title) ->
            val item = when (choice) {
                is ItemSelection.Saved -> state.saved.firstOrNull { it.id == choice.id }
                ItemSelection.UseEngine -> state.engineItem(kind)
                ItemSelection.Original -> null
            }
            Tile(title, item, kind, pictures, selected = choice == selected) { onSelect(choice) }
        }
    }
}

/** One choice: its picture (or the kind's icon) and title; the selected one has a primary border and a check. */
@Composable
private fun Tile(
    title: String,
    item: SavedItem?,
    kind: ItemKind,
    pictures: ItemPictures,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .width(TILE_WIDTH)
            .clip(TILE_SHAPE)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { if (selected) contentDescription = "$title, selected" },
    ) {
        Box {
            ItemPicture(
                item, kind, pictures,
                Modifier
                    .width(TILE_WIDTH)
                    .height(PICTURE_HEIGHT)
                    .clip(TILE_SHAPE)
                    .border(2.dp, if (selected) colors.primary else Color.Transparent, TILE_SHAPE),
            )
            if (selected) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp).background(colors.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(14.dp), tint = colors.onPrimary)
                }
            }
        }
        Text(
            title,
            Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) colors.onSurface else colors.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
