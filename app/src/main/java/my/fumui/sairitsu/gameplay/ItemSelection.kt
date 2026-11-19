package my.fumui.sairitsu.gameplay

/** What a level or an engine is set to look like, for one [ItemKind]. */
sealed interface ItemSelection {
    /** A level follows its engine's choice. */
    data object UseEngine : ItemSelection

    /** The item the level's server assigned. */
    data object Original : ItemSelection

    /** An item saved on this device. */
    data class Saved(val id: String) : ItemSelection
}

/**
 * What the pickers of "Level options" show: the [saved] items and the choice per [ItemKind] for this level and for its
 * engine. [engineKnown]: the level's engine has a name to save a choice under.
 */
data class ItemPickerState(
    val saved: List<SavedItem> = emptyList(),
    val level: Map<ItemKind, ItemSelection> = emptyMap(),
    val engine: Map<ItemKind, ItemSelection> = emptyMap(),
    val engineKnown: Boolean = false,
)

/** The saved item of [kind] the engine is set to; null for `Original`, no choice, or an item no longer saved. */
fun ItemPickerState.engineItem(kind: ItemKind): SavedItem? {
    val id = (engine[kind] as? ItemSelection.Saved)?.id ?: return null
    return saved.firstOrNull { it.id == id && it.kind == kind }
}

/** The saved item to use: the level's choice, else the engine's; null keeps the level's own. */
fun resolveItemId(level: ItemSelection, engine: ItemSelection): String? = when (level) {
    is ItemSelection.Saved -> level.id
    ItemSelection.Original -> null
    ItemSelection.UseEngine -> (engine as? ItemSelection.Saved)?.id
}

/** The rows of the "This level" picker for [kind], in display order. [title] resolves an item's title for display. */
fun levelItemOptions(kind: ItemKind, saved: List<SavedItem>, title: (String) -> String): List<Pair<ItemSelection, String>> =
    listOf<Pair<ItemSelection, String>>(ItemSelection.UseEngine to "Use engine's ${kind.label}") +
        engineItemOptions(kind, saved, title)

/** The rows of the "This engine" picker for [kind], in display order. [title] resolves an item's title for display. */
fun engineItemOptions(kind: ItemKind, saved: List<SavedItem>, title: (String) -> String): List<Pair<ItemSelection, String>> =
    listOf<Pair<ItemSelection, String>>(ItemSelection.Original to "Original ${kind.label}") +
        saved.filter { it.kind == kind }.map { ItemSelection.Saved(it.id) to title(it.title) }
