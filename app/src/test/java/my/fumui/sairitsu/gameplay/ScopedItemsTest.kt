package my.fumui.sairitsu.gameplay

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import my.fumui.sairitsu.data.ServerAddress
import my.fumui.sairitsu.data.model.ServerJson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScopedItemsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var web: MockWebServer
    private lateinit var address: ServerAddress
    private lateinit var library: ItemLibrary
    private lateinit var scoped: ScopedItems
    private val memory = MemoryPreferences()
    private val settings = SettingsStore(memory)
    private val bodies = HashMap<String, String>()

    private fun srl(path: String, body: String) =
        """{"url":"$path","hash":"${sha1Hex(body)}"}""".also { bodies[path] = body }

    private fun skin(name: String) =
        """{"name":"$name","title":"$name","data":${srl("/d-$name", "data $name")},
            "texture":${srl("/t-$name", "texture $name")}}"""

    @Before
    fun setUp() {
        bodies["/sonolus/skins/list"] = """{"pageCount":1,"items":[${skin("a")},${skin("b")}]}"""
        web = MockWebServer()
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                bodies[request.path!!]?.let { MockResponse().setBody(it) } ?: MockResponse().setResponseCode(404)
        }
        web.start()
        address = ServerAddress(web.url("/"))
        library = ItemLibrary(tmp.root.resolve("skins"), OkHttpClient())
        scoped = ScopedItems(library, settings)
    }

    @After
    fun tearDown() {
        runCatching { web.shutdown() }
    }

    private suspend fun saved(name: String): ItemSelection.Saved =
        ItemSelection.Saved(library.add(address, library.offer(ItemKind.Skin, address, ServerJson.parseToJsonElement(skin(name)).jsonObject)!!).id)

    private fun loaded(): LoadedResources {
        val files = ResourceKind.entries.associateWith { kind ->
            tmp.root.resolve("level-${kind.name}").apply { writeText("level ${kind.wireName}") }
        }
        return LoadedResources(files, ResourceKind.entries.associateWith { "key-${it.name}" }, "details")
    }

    private fun LoadedResources.texture() = files.getValue(ResourceKind.SKIN_TEXTURE).readText()

    @Test
    fun resolvesTheNarrowestChoice() {
        assertEquals("a", resolveItemId(ItemSelection.Saved("a"), ItemSelection.Saved("b")))
        assertEquals("a", resolveItemId(ItemSelection.Saved("a"), ItemSelection.Original))
        assertNull(resolveItemId(ItemSelection.Original, ItemSelection.Saved("b")))
        assertEquals("b", resolveItemId(ItemSelection.UseEngine, ItemSelection.Saved("b")))
        assertNull(resolveItemId(ItemSelection.UseEngine, ItemSelection.Original))
    }

    @Test
    fun pickerRowsMatchTheSpec() {
        val saved = listOf(SavedItem("a", ItemKind.Skin, "Moonlit Lantern", "s", emptyMap()))
        assertEquals(
            listOf(
                ItemSelection.UseEngine to "Use engine's skin",
                ItemSelection.Original to "Original skin",
                ItemSelection.Saved("a") to "Moonlit Lantern",
            ),
            levelItemOptions(ItemKind.Skin, saved) { it },
        )
        assertEquals(
            listOf(ItemSelection.Original to "Original skin", ItemSelection.Saved("a") to "Moonlit Lantern"),
            engineItemOptions(ItemKind.Skin, saved) { it },
        )
    }

    @Test
    fun defaultsAreUseEngineAndOriginal() = runTest {
        assertEquals(ItemSelection.UseEngine, settings.levelItem(ItemKind.Skin, "s", "l").first())
        assertEquals(ItemSelection.Original, settings.engineItem(ItemKind.Skin, "e").first())
    }

    @Test
    fun choicesAreSavedPerLevelAndPerEngine() = runTest {
        settings.saveLevelItem(ItemKind.Skin, "s", "l", ItemSelection.Saved("a"))
        settings.saveEngineItem(ItemKind.Skin, "e", ItemSelection.Saved("b"))
        assertEquals(ItemSelection.Saved("a"), settings.levelItem(ItemKind.Skin, "s", "l").first())
        assertEquals(ItemSelection.Saved("b"), settings.engineItem(ItemKind.Skin, "e").first())
        assertEquals(ItemSelection.UseEngine, settings.levelItem(ItemKind.Skin, "s", "other").first())
        assertEquals(ItemSelection.UseEngine, settings.levelItem(ItemKind.Skin, "other", "l").first())
        assertEquals(ItemSelection.Original, settings.engineItem(ItemKind.Skin, "other").first())
    }

    @Test
    fun originalOnALevelIsRemembered() = runTest {
        settings.saveLevelItem(ItemKind.Skin, "s", "l", ItemSelection.Original)
        assertEquals(ItemSelection.Original, settings.levelItem(ItemKind.Skin, "s", "l").first())
    }

    @Test
    fun savingADefaultRemovesTheKey() = runTest {
        settings.saveLevelItem(ItemKind.Skin, "s", "l", ItemSelection.Saved("a"))
        settings.saveEngineItem(ItemKind.Skin, "e", ItemSelection.Saved("a"))
        settings.saveLevelItem(ItemKind.Skin, "s", "l", ItemSelection.UseEngine)
        settings.saveEngineItem(ItemKind.Skin, "e", ItemSelection.Original)
        assertTrue(memory.data.first().asMap().keys.none { it.name.startsWith("skin|") })
    }

    @Test
    fun unknownStoredValueReadsAsTheDefault() = runTest {
        memory.edit {
            it[stringPreferencesKey("skin|level|s|l")] = "from the future"
            it[stringPreferencesKey("skin|engine|e")] = "from the future"
        }
        assertEquals(ItemSelection.UseEngine, settings.levelItem(ItemKind.Skin, "s", "l").first())
        assertEquals(ItemSelection.Original, settings.engineItem(ItemKind.Skin, "e").first())
    }

    @Test
    fun applyUsesTheEnginesSkin() = runTest {
        settings.saveEngineItem(ItemKind.Skin, "e", saved("a"))
        assertEquals("texture a", scoped.apply(loaded(), "s", "l", "e").texture())
    }

    @Test
    fun applyPrefersTheLevelsSkinOverTheEngines() = runTest {
        settings.saveEngineItem(ItemKind.Skin, "e", saved("a"))
        settings.saveLevelItem(ItemKind.Skin, "s", "l", saved("b"))
        assertEquals("texture b", scoped.apply(loaded(), "s", "l", "e").texture())
    }

    @Test
    fun originalOnALevelIgnoresTheEnginesSkin() = runTest {
        settings.saveEngineItem(ItemKind.Skin, "e", saved("a"))
        settings.saveLevelItem(ItemKind.Skin, "s", "l", ItemSelection.Original)
        val level = loaded()
        assertSame(level, scoped.apply(level, "s", "l", "e"))
    }

    @Test
    fun anotherEngineIsLeftAlone() = runTest {
        settings.saveEngineItem(ItemKind.Skin, "e", saved("a"))
        val level = loaded()
        assertSame(level, scoped.apply(level, "s", "l", "other"))
    }

    @Test
    fun unknownEngineStillAppliesTheLevelsChoice() = runTest {
        settings.saveLevelItem(ItemKind.Skin, "s", "l", saved("a"))
        assertEquals("texture a", scoped.apply(loaded(), "s", "l", null).texture())
    }

    @Test
    fun removingASkinClearsItsChoicesAndFiles() = runTest {
        val skin = saved("a")
        val other = saved("b")
        settings.saveLevelItem(ItemKind.Skin, "s", "l", skin)
        settings.saveEngineItem(ItemKind.Skin, "e", skin)
        settings.saveLevelItem(ItemKind.Skin, "s", "kept", other)

        scoped.remove(skin.id)

        assertEquals(ItemSelection.UseEngine, settings.levelItem(ItemKind.Skin, "s", "l").first())
        assertEquals(ItemSelection.Original, settings.engineItem(ItemKind.Skin, "e").first())
        assertEquals(other, settings.levelItem(ItemKind.Skin, "s", "kept").first())
        assertEquals(listOf(other.id), library.items.value.map { it.id })
        val level = loaded()
        assertSame(level, scoped.apply(level, "s", "l", "e"))
    }

    private fun background(name: String) =
        """{"name":"$name","title":"$name","image":${srl("/i-$name", "image $name")},
            "data":${srl("/bd-$name", "bgdata $name")},"configuration":${srl("/c-$name", "config $name")}}"""

    private suspend fun savedBackground(name: String): ItemSelection.Saved =
        ItemSelection.Saved(
            library.add(
                address,
                library.offer(ItemKind.Background, address, ServerJson.parseToJsonElement(background(name)).jsonObject)!!,
            ).id,
        )

    private fun LoadedResources.image() = files.getValue(ResourceKind.BACKGROUND_IMAGE).readText()

    @Test
    fun eachKindResolvedIndependently() = runTest {
        settings.saveLevelItem(ItemKind.Skin, "s", "l", saved("a"))
        settings.saveEngineItem(ItemKind.Background, "e", savedBackground("x"))
        val out = scoped.apply(loaded(), "s", "l", "e")
        assertEquals("texture a", out.texture())
        assertEquals("image x", out.image())
    }

    @Test
    fun previewOverridesOnlyItsKind() = runTest {
        settings.saveLevelItem(ItemKind.Skin, "s", "l", saved("a"))
        settings.saveLevelItem(ItemKind.Background, "s", "l", savedBackground("x"))
        val skinB = saved("b")
        val out = scoped.apply(loaded(), "s", "l", "e", ItemPreview(ItemKind.Skin, skinB.id))
        assertEquals("texture b", out.texture())
        assertEquals("image x", out.image())
    }

    @Test
    fun previewAppliesWithoutAnyChoice() = runTest {
        val bg = savedBackground("x")
        assertEquals("image x", scoped.apply(loaded(), "s", "l", null, ItemPreview(ItemKind.Background, bg.id)).image())
    }

    @Test
    fun missingPreviewKeepsSavedChoice() = runTest {
        settings.saveLevelItem(ItemKind.Skin, "s", "l", saved("a"))
        val out = scoped.apply(loaded(), "s", "l", "e", ItemPreview(ItemKind.Skin, "gone"))
        assertEquals("texture a", out.texture())
    }

    @Test
    fun removedItemFallsBackToOwn() = runTest {
        settings.saveLevelItem(ItemKind.Background, "s", "l", ItemSelection.Saved("gone"))
        settings.saveLevelItem(ItemKind.Skin, "s", "l", saved("a"))
        val out = scoped.apply(loaded(), "s", "l", "e")
        assertEquals("level background.image", out.image())
        assertEquals("texture a", out.texture())
    }

    @Test
    fun removeClearsChoices() = runTest {
        val bg = savedBackground("x")
        settings.saveLevelItem(ItemKind.Background, "s", "l", bg)
        settings.saveEngineItem(ItemKind.Background, "e", bg)
        scoped.remove(bg.id)
        assertEquals(ItemSelection.UseEngine, settings.levelItem(ItemKind.Background, "s", "l").first())
        assertEquals(ItemSelection.Original, settings.engineItem(ItemKind.Background, "e").first())
        assertTrue(library.items.value.isEmpty())
    }

    @Test
    fun choiceOfAMissingSkinKeepsTheLevelsOwnSkin() = runTest {
        settings.saveLevelItem(ItemKind.Skin, "s", "l", ItemSelection.Saved("gone"))
        val level = loaded()
        assertSame(level, scoped.apply(level, "s", "l", "e"))
    }
}
