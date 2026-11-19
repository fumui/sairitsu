package my.fumui.sairitsu.gameplay

import my.fumui.sairitsu.data.model.ServerJson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ItemMigrationTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val manifestSerializer = ListSerializer(SavedItem.serializer())

    private fun skinsJson(dir: File, text: String) = File(dir, "skins.json").writeText(text)

    private fun items(dir: File): List<SavedItem> =
        ServerJson.decodeFromString(manifestSerializer, File(dir, "items.json").readText())

    private val full = """[{"id":"s1","title":"Neon","server":"https://x","files":{
        "skin.data":"k1","skin.texture":"k2","background.image":"k3","background.data":"k4","background.configuration":"k5",
        "particle.data":"k6","particle.texture":"k7"}}]"""

    @Test
    fun skinWithPartsBecomesThreeItems() {
        val dir = folder.newFolder()
        skinsJson(dir, full)
        ItemMigration.migrateFiles(dir)
        val byKind = items(dir).associateBy { it.kind }
        assertEquals(3, byKind.size)
        assertEquals(SavedItem("s1", ItemKind.Skin, "Neon", "https://x", mapOf("skin.data" to "k1", "skin.texture" to "k2")), byKind[ItemKind.Skin])
        assertEquals(
            SavedItem(
                sha1Hex("background|s1"), ItemKind.Background, "Neon", "https://x",
                mapOf("background.image" to "k3", "background.data" to "k4", "background.configuration" to "k5"),
            ),
            byKind[ItemKind.Background],
        )
        assertEquals(
            SavedItem(sha1Hex("particle|s1"), ItemKind.Particles, "Neon", "https://x", mapOf("particle.data" to "k6", "particle.texture" to "k7")),
            byKind[ItemKind.Particles],
        )
        assertFalse(File(dir, "skins.json").exists())
    }

    @Test
    fun skinWithoutPartsBecomesOne() {
        val dir = folder.newFolder()
        skinsJson(dir, """[{"id":"s1","title":"Plain","server":"https://x","files":{"skin.data":"k1","skin.texture":"k2"}}]""")
        ItemMigration.migrateFiles(dir)
        assertEquals(listOf("s1"), items(dir).map { it.id })
        assertFalse(File(dir, "skins.json").exists())
    }

    @Test
    fun existingItemsKept() {
        val dir = folder.newFolder()
        val other = SavedItem("other", ItemKind.Sfx, "Sfx", "https://y", mapOf("effect.data" to "e1"))
        File(dir, "items.json").writeText(ServerJson.encodeToString(manifestSerializer, listOf(other)))
        skinsJson(dir, full)
        ItemMigration.migrateFiles(dir)
        val ids = items(dir).map { it.id }
        assertTrue("other" in ids)
        assertEquals(4, ids.size)
    }

    @Test
    fun failedWriteKeepsSkinsJson() {
        val dir = folder.newFolder()
        skinsJson(dir, full)
        File(dir, "items.json.tmp").mkdirs()
        try {
            ItemMigration.migrateFiles(dir)
            fail("expected the write to fail")
        } catch (_: Exception) {
        }
        assertTrue(File(dir, "skins.json").isFile)
    }

    @Test
    fun noSkinsJsonDoesNothing() {
        val dir = folder.newFolder()
        ItemMigration.migrateFiles(dir)
        assertFalse(File(dir, "items.json").exists())
    }

    private fun settingsWithChoices(): SettingsStore = runBlocking {
        SettingsStore(MemoryPreferences()).also {
            it.saveLevelItem(ItemKind.Skin, "srv", "lvl", ItemSelection.Saved("s1"))
            it.saveEngineItem(ItemKind.Skin, "eng", ItemSelection.Saved("s1"))
        }
    }

    private val bg = SavedItem(sha1Hex("background|s1"), ItemKind.Background, "Neon", "https://x", mapOf("background.image" to "k3"))
    private val pt = SavedItem(sha1Hex("particle|s1"), ItemKind.Particles, "Neon", "https://x", mapOf("particle.data" to "k6"))

    @Test
    fun choicesCopiedToBothScopes() = runBlocking {
        val settings = settingsWithChoices()
        ItemMigration.migrateChoices(settings, listOf(bg, pt), false)
        assertEquals(ItemSelection.Saved(bg.id), settings.levelItem(ItemKind.Background, "srv", "lvl").first())
        assertEquals(ItemSelection.Saved(pt.id), settings.levelItem(ItemKind.Particles, "srv", "lvl").first())
        assertEquals(ItemSelection.Saved(bg.id), settings.engineItem(ItemKind.Background, "eng").first())
        assertEquals(ItemSelection.Saved(pt.id), settings.engineItem(ItemKind.Particles, "eng").first())
        assertTrue(settings.itemsMigrated.first())
    }

    @Test
    fun existingChoiceNotOverwritten() = runBlocking {
        val settings = settingsWithChoices()
        settings.saveLevelItem(ItemKind.Background, "srv", "lvl", ItemSelection.Original)
        ItemMigration.migrateChoices(settings, listOf(bg, pt), false)
        assertEquals(ItemSelection.Original, settings.levelItem(ItemKind.Background, "srv", "lvl").first())
        assertEquals(ItemSelection.Saved(pt.id), settings.levelItem(ItemKind.Particles, "srv", "lvl").first())
    }

    @Test
    fun pendingFileMigrationLeavesFlagUnset() = runBlocking {
        val settings = settingsWithChoices()
        ItemMigration.migrateChoices(settings, emptyList(), true)
        assertEquals(ItemSelection.UseEngine, settings.levelItem(ItemKind.Background, "srv", "lvl").first())
        assertFalse(settings.itemsMigrated.first())
        ItemMigration.migrateChoices(settings, listOf(bg, pt), false)
        assertEquals(ItemSelection.Saved(bg.id), settings.levelItem(ItemKind.Background, "srv", "lvl").first())
        assertTrue(settings.itemsMigrated.first())
    }

    @Test
    fun levelOriginalSkinKeepsOwnBackground() = runBlocking {
        val settings = SettingsStore(MemoryPreferences())
        settings.saveEngineItem(ItemKind.Skin, "eng", ItemSelection.Saved("s1"))
        settings.saveLevelItem(ItemKind.Skin, "srv", "lvl", ItemSelection.Original)
        ItemMigration.migrateChoices(settings, listOf(bg, pt), false)
        assertEquals(ItemSelection.Original, settings.levelItem(ItemKind.Background, "srv", "lvl").first())
        assertEquals(ItemSelection.Original, settings.levelItem(ItemKind.Particles, "srv", "lvl").first())
        assertEquals(ItemSelection.Saved(bg.id), settings.engineItem(ItemKind.Background, "eng").first())
        assertEquals(ItemSelection.Saved(pt.id), settings.engineItem(ItemKind.Particles, "eng").first())
    }

    @Test
    fun levelSkinWithoutPartsKeepsOwnBackground() = runBlocking {
        val settings = SettingsStore(MemoryPreferences())
        settings.saveEngineItem(ItemKind.Skin, "eng", ItemSelection.Saved("s1"))
        settings.saveLevelItem(ItemKind.Skin, "srv", "lvl", ItemSelection.Saved("s2"))
        ItemMigration.migrateChoices(settings, listOf(bg, pt), false)
        assertEquals(ItemSelection.Original, settings.levelItem(ItemKind.Background, "srv", "lvl").first())
        assertEquals(ItemSelection.Original, settings.levelItem(ItemKind.Particles, "srv", "lvl").first())
        assertEquals(ItemSelection.Saved("s2"), settings.levelItem(ItemKind.Skin, "srv", "lvl").first())
    }

    @Test
    fun choicesRunOnce()= runBlocking {
        val settings = settingsWithChoices()
        ItemMigration.migrateChoices(settings, listOf(bg, pt), false)
        settings.saveLevelItem(ItemKind.Background, "srv", "lvl", ItemSelection.UseEngine)
        ItemMigration.migrateChoices(settings, listOf(bg, pt), false)
        assertEquals(ItemSelection.UseEngine, settings.levelItem(ItemKind.Background, "srv", "lvl").first())
    }
}
