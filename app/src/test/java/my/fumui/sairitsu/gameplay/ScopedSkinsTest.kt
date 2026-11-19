package my.fumui.sairitsu.gameplay

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import my.fumui.sairitsu.data.ServerAddress
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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

class ScopedSkinsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var web: MockWebServer
    private lateinit var address: ServerAddress
    private lateinit var library: SkinLibrary
    private lateinit var scoped: ScopedSkins
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
        library = SkinLibrary(tmp.root.resolve("skins"), OkHttpClient())
        scoped = ScopedSkins(library, settings)
    }

    @After
    fun tearDown() {
        runCatching { web.shutdown() }
    }

    private suspend fun saved(name: String): SkinSelection.Saved =
        SkinSelection.Saved(library.add(address, library.offers(address).first { it.name == name }).id)

    private fun loaded(): LoadedResources {
        val files = ResourceKind.entries.associateWith { kind ->
            tmp.root.resolve("level-${kind.name}").apply { writeText("level ${kind.wireName}") }
        }
        return LoadedResources(files, ResourceKind.entries.associateWith { "key-${it.name}" }, "details")
    }

    private fun LoadedResources.texture() = files.getValue(ResourceKind.SKIN_TEXTURE).readText()

    @Test
    fun resolvesTheNarrowestChoice() {
        assertEquals("a", resolveSkinId(SkinSelection.Saved("a"), SkinSelection.Saved("b")))
        assertEquals("a", resolveSkinId(SkinSelection.Saved("a"), SkinSelection.Original))
        assertNull(resolveSkinId(SkinSelection.Original, SkinSelection.Saved("b")))
        assertEquals("b", resolveSkinId(SkinSelection.UseEngine, SkinSelection.Saved("b")))
        assertNull(resolveSkinId(SkinSelection.UseEngine, SkinSelection.Original))
    }

    @Test
    fun pickerRowsMatchTheSpec() {
        val saved = listOf(SavedSkin("a", "Moonlit Lantern", "s", emptyMap()))
        assertEquals(
            listOf(
                SkinSelection.UseEngine to "Use engine's skin",
                SkinSelection.Original to "Original skin",
                SkinSelection.Saved("a") to "Moonlit Lantern",
            ),
            levelSkinOptions(saved) { it },
        )
        assertEquals(
            listOf(SkinSelection.Original to "Original skin", SkinSelection.Saved("a") to "Moonlit Lantern"),
            engineSkinOptions(saved) { it },
        )
    }

    @Test
    fun defaultsAreUseEngineAndOriginal() = runTest {
        assertEquals(SkinSelection.UseEngine, settings.levelSkin("s", "l").first())
        assertEquals(SkinSelection.Original, settings.engineSkin("e").first())
    }

    @Test
    fun choicesAreSavedPerLevelAndPerEngine() = runTest {
        settings.saveLevelSkin("s", "l", SkinSelection.Saved("a"))
        settings.saveEngineSkin("e", SkinSelection.Saved("b"))
        assertEquals(SkinSelection.Saved("a"), settings.levelSkin("s", "l").first())
        assertEquals(SkinSelection.Saved("b"), settings.engineSkin("e").first())
        assertEquals(SkinSelection.UseEngine, settings.levelSkin("s", "other").first())
        assertEquals(SkinSelection.UseEngine, settings.levelSkin("other", "l").first())
        assertEquals(SkinSelection.Original, settings.engineSkin("other").first())
    }

    @Test
    fun originalOnALevelIsRemembered() = runTest {
        settings.saveLevelSkin("s", "l", SkinSelection.Original)
        assertEquals(SkinSelection.Original, settings.levelSkin("s", "l").first())
    }

    @Test
    fun savingADefaultRemovesTheKey() = runTest {
        settings.saveLevelSkin("s", "l", SkinSelection.Saved("a"))
        settings.saveEngineSkin("e", SkinSelection.Saved("a"))
        settings.saveLevelSkin("s", "l", SkinSelection.UseEngine)
        settings.saveEngineSkin("e", SkinSelection.Original)
        assertTrue(memory.data.first().asMap().keys.none { it.name.startsWith("skin|") })
    }

    @Test
    fun unknownStoredValueReadsAsTheDefault() = runTest {
        memory.edit {
            it[stringPreferencesKey("skin|level|s|l")] = "from the future"
            it[stringPreferencesKey("skin|engine|e")] = "from the future"
        }
        assertEquals(SkinSelection.UseEngine, settings.levelSkin("s", "l").first())
        assertEquals(SkinSelection.Original, settings.engineSkin("e").first())
    }

    @Test
    fun applyUsesTheEnginesSkin() = runTest {
        settings.saveEngineSkin("e", saved("a"))
        assertEquals("texture a", scoped.apply(loaded(), "s", "l", "e").texture())
    }

    @Test
    fun applyPrefersTheLevelsSkinOverTheEngines() = runTest {
        settings.saveEngineSkin("e", saved("a"))
        settings.saveLevelSkin("s", "l", saved("b"))
        assertEquals("texture b", scoped.apply(loaded(), "s", "l", "e").texture())
    }

    @Test
    fun originalOnALevelIgnoresTheEnginesSkin() = runTest {
        settings.saveEngineSkin("e", saved("a"))
        settings.saveLevelSkin("s", "l", SkinSelection.Original)
        val level = loaded()
        assertSame(level, scoped.apply(level, "s", "l", "e"))
    }

    @Test
    fun anotherEngineIsLeftAlone() = runTest {
        settings.saveEngineSkin("e", saved("a"))
        val level = loaded()
        assertSame(level, scoped.apply(level, "s", "l", "other"))
    }

    @Test
    fun unknownEngineStillAppliesTheLevelsChoice() = runTest {
        settings.saveLevelSkin("s", "l", saved("a"))
        assertEquals("texture a", scoped.apply(loaded(), "s", "l", null).texture())
    }

    @Test
    fun removingASkinClearsItsChoicesAndFiles() = runTest {
        val skin = saved("a")
        val other = saved("b")
        settings.saveLevelSkin("s", "l", skin)
        settings.saveEngineSkin("e", skin)
        settings.saveLevelSkin("s", "kept", other)

        scoped.remove(skin.id)

        assertEquals(SkinSelection.UseEngine, settings.levelSkin("s", "l").first())
        assertEquals(SkinSelection.Original, settings.engineSkin("e").first())
        assertEquals(other, settings.levelSkin("s", "kept").first())
        assertEquals(listOf(other.id), library.skins.value.map { it.id })
        val level = loaded()
        assertSame(level, scoped.apply(level, "s", "l", "e"))
    }

    @Test
    fun choiceOfAMissingSkinKeepsTheLevelsOwnSkin() = runTest {
        settings.saveLevelSkin("s", "l", SkinSelection.Saved("gone"))
        val level = loaded()
        assertSame(level, scoped.apply(level, "s", "l", "e"))
    }
}
