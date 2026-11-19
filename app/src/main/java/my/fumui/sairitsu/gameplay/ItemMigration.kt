package my.fumui.sairitsu.gameplay

import my.fumui.sairitsu.data.model.ServerJson
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** The one-time move from saved skins (a skin bundled the background and particles of its name) to [ItemLibrary]'s items. */
object ItemMigration {
    /** The skins manifest of earlier versions. */
    @Serializable
    private data class SavedSkin(val id: String, val title: String, val server: String, val files: Map<String, String>)

    private val LEGACY = ListSerializer(SavedSkin.serializer())
    private val MANIFEST = ListSerializer(SavedItem.serializer())

    /** The id of the background migrated from skin [skinId]. */
    private fun backgroundId(skinId: String) = sha1Hex("${ItemKind.Background.wireType}|$skinId")

    /** The id of the particles migrated from skin [skinId]. */
    private fun particlesId(skinId: String) = sha1Hex("${ItemKind.Particles.wireType}|$skinId")

    /** skins.json → items.json in [dir]; no-op when skins.json is absent. An exception leaves skins.json in place. */
    fun migrateFiles(dir: File) {
        val legacy = File(dir, "skins.json")
        if (!legacy.isFile) return
        val skins = ServerJson.decodeFromString(LEGACY, legacy.readText(Charsets.UTF_8))
        val manifest = File(dir, "items.json")
        val existing = try {
            if (manifest.isFile) ServerJson.decodeFromString(MANIFEST, manifest.readText(Charsets.UTF_8)) else emptyList()
        } catch (e: SerializationException) {
            emptyList()
        } catch (e: IllegalArgumentException) {
            emptyList()
        }
        val known = existing.mapTo(HashSet()) { it.id }
        val migrated = skins.flatMap { skin ->
            listOf(
                SavedItem(skin.id, ItemKind.Skin, skin.title, skin.server, filesOf(skin, "skin.")),
                SavedItem(backgroundId(skin.id), ItemKind.Background, skin.title, skin.server, filesOf(skin, "background.")),
                SavedItem(particlesId(skin.id), ItemKind.Particles, skin.title, skin.server, filesOf(skin, "particle.")),
            ).filter { it.kind == ItemKind.Skin || it.files.isNotEmpty() }
        }.filter { known.add(it.id) }
        write(manifest, existing + migrated)
        Files.delete(legacy.toPath())
    }

    private fun filesOf(skin: SavedSkin, prefix: String) = skin.files.filterKeys { it.startsWith(prefix) }

    private fun write(manifest: File, items: List<SavedItem>) {
        val tmp = File(manifest.parentFile, "${manifest.name}.tmp")
        tmp.writeText(ServerJson.encodeToString(MANIFEST, items), Charsets.UTF_8)
        try {
            Files.move(tmp.toPath(), manifest.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), manifest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * Copies each Saved skin choice to the background and particles migrated from that skin; once.
     * While [legacyPending] (skins.json not migrated yet) it does nothing and leaves the flag unset, so a later start retries.
     */
    suspend fun migrateChoices(settings: SettingsStore, items: List<SavedItem>, legacyPending: Boolean) {
        if (legacyPending || settings.itemsMigrated.first()) return
        val ids = items.mapTo(HashSet()) { it.id }
        for ((scope, skin) in settings.skinChoices()) {
            val skinId = (skin as? ItemSelection.Saved)?.id
            for ((kind, id) in listOf(
                ItemKind.Background to skinId?.let(::backgroundId),
                ItemKind.Particles to skinId?.let(::particlesId),
            )) {
                if (id == null || id !in ids) {
                    // The level's skin brought no such part before, so the level showed its own; keep that, not the engine's.
                    if (scope is SkinScope.Level && settings.levelItem(kind, scope.server, scope.level).first() == ItemSelection.UseEngine) {
                        settings.saveLevelItem(kind, scope.server, scope.level, ItemSelection.Original)
                    }
                    continue
                }
                when (scope) {
                    is SkinScope.Level ->
                        if (settings.levelItem(kind, scope.server, scope.level).first() == ItemSelection.UseEngine) {
                            settings.saveLevelItem(kind, scope.server, scope.level, ItemSelection.Saved(id))
                        }
                    is SkinScope.Engine ->
                        if (settings.engineItem(kind, scope.engine).first() == ItemSelection.Original) {
                            settings.saveEngineItem(kind, scope.engine, ItemSelection.Saved(id))
                        }
                }
            }
        }
        settings.markItemsMigrated()
    }
}
