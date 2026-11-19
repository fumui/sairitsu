package my.fumui.sairitsu.gameplay

import kotlinx.coroutines.flow.first

/** The saved skins together with the level and engine choices that use them. */
class ScopedSkins(val library: SkinLibrary, private val settings: SettingsStore) {
    /** [loaded] with the skin chosen for [level] on [server] or for [engine]; unchanged when none applies. */
    suspend fun apply(loaded: LoadedResources, server: String, level: String, engine: String?): LoadedResources {
        val levelChoice = settings.levelSkin(server, level).first()
        val engineChoice = engine?.let { settings.engineSkin(it).first() } ?: SkinSelection.Original
        return library.applyTo(loaded, resolveSkinId(levelChoice, engineChoice))
    }

    /** Deletes a saved skin; the levels and engines that used it go back to their defaults. */
    suspend fun remove(id: String) {
        settings.clearSkin(id)
        library.remove(id)
    }
}
