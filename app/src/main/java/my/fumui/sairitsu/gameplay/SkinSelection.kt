package my.fumui.sairitsu.gameplay

/** What a level or an engine is set to look like. */
sealed interface SkinSelection {
    /** A level follows its engine's choice. */
    data object UseEngine : SkinSelection

    /** The skin the level's server assigned. */
    data object Original : SkinSelection

    /** A skin saved on this device. */
    data class Saved(val id: String) : SkinSelection
}

/** What the skin pickers of "Level options" show. [engineKnown]: the level's engine has a name to save a choice under. */
data class SkinPickerState(
    val saved: List<SavedSkin> = emptyList(),
    val level: SkinSelection = SkinSelection.UseEngine,
    val engine: SkinSelection = SkinSelection.Original,
    val engineKnown: Boolean = false,
)

/** The saved skin to use: the level's choice, else the engine's; null keeps the level's own skin. */
fun resolveSkinId(level: SkinSelection, engine: SkinSelection): String? = when (level) {
    is SkinSelection.Saved -> level.id
    SkinSelection.Original -> null
    SkinSelection.UseEngine -> (engine as? SkinSelection.Saved)?.id
}

/** The rows of the "This level" picker, in display order. [title] resolves a skin's title for display. */
fun levelSkinOptions(saved: List<SavedSkin>, title: (String) -> String): List<Pair<SkinSelection, String>> =
    listOf<Pair<SkinSelection, String>>(SkinSelection.UseEngine to "Use engine's skin") + engineSkinOptions(saved, title)

/** The rows of the "This engine" picker, in display order. [title] resolves a skin's title for display. */
fun engineSkinOptions(saved: List<SavedSkin>, title: (String) -> String): List<Pair<SkinSelection, String>> =
    listOf<Pair<SkinSelection, String>>(SkinSelection.Original to "Original skin") +
        saved.map { SkinSelection.Saved(it.id) to title(it.title) }
