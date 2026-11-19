package my.fumui.sairitsu.gameplay

import kotlinx.coroutines.flow.first

/** One run's trial of the saved item [id] in place of whatever the level and engine choose for its [kind]. */
data class ItemPreview(val kind: ItemKind, val id: String)

/** The saved items together with the level and engine choices that use them. */
class ScopedItems(val library: ItemLibrary, private val settings: SettingsStore) {
    /**
     * [loaded] with, per kind, the item chosen for [level] on [server] or for [engine]; [preview] replaces the choice of
     * its kind. Unchanged where nothing applies.
     */
    suspend fun apply(
        loaded: LoadedResources,
        server: String,
        level: String,
        engine: String?,
        preview: ItemPreview? = null,
    ): LoadedResources {
        val choices = HashMap<ItemKind, String?>()
        for (kind in ItemKind.entries) {
            val levelChoice = settings.levelItem(kind, server, level).first()
            val engineChoice = engine?.let { settings.engineItem(kind, it).first() } ?: ItemSelection.Original
            choices[kind] = resolveItemId(levelChoice, engineChoice)
        }
        if (preview != null && library.find(preview.id) != null) choices[preview.kind] = preview.id
        return library.applyTo(loaded, choices)
    }

    /** Deletes a saved item; the levels and engines that used it go back to their defaults. */
    suspend fun remove(id: String) {
        settings.clearItem(id)
        library.remove(id)
    }
}
