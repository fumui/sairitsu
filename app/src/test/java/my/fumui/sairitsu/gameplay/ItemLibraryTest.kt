package my.fumui.sairitsu.gameplay

import my.fumui.sairitsu.data.ServerAddress
import my.fumui.sairitsu.data.model.ServerJson
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class ItemLibraryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var web: MockWebServer
    private lateinit var server: ServerAddress
    private val bodies = ConcurrentHashMap<String, String>()
    private val requested = ConcurrentHashMap.newKeySet<String>()

    private fun srl(path: String, body: String) =
        """{"url":"$path","hash":"${sha1Hex(body)}"}""".also { bodies[path] = body }

    private fun skin(name: String) =
        """{"name":"$name","title":"Title $name","data":${srl("/s/data-$name", "skin data $name")},
            "texture":${srl("/s/texture-$name", "skin texture $name")}}"""

    private fun background(name: String) =
        """{"name":"$name","title":"Bg $name","image":${srl("/b/image-$name", "bg image $name")},
            "data":${srl("/b/data-$name", "bg data $name")},"configuration":${srl("/b/config-$name", "bg config $name")}}"""

    private fun json(raw: String): JsonObject = ServerJson.parseToJsonElement(raw).jsonObject

    @Before
    fun setUp() {
        web = MockWebServer()
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requested += request.path!!
                return bodies[request.path!!]?.let { MockResponse().setBody(it) } ?: MockResponse().setResponseCode(404)
            }
        }
        web.start()
        server = ServerAddress(web.url("/"))
    }

    @After
    fun tearDown() {
        runCatching { web.shutdown() }
    }

    private val dir: File
        get() = tmp.root.resolve("skins")

    private fun library() = ItemLibrary(dir, OkHttpClient())

    private val savedFiles: List<File>
        get() = dir.resolve("files").listFiles()!!.toList()

    private fun level(): LoadedResources {
        val files = ResourceKind.entries.associateWith { kind ->
            tmp.root.resolve("level-${kind.name}").apply { writeText("level ${kind.wireName}") }
        }
        return LoadedResources(files, ResourceKind.entries.associateWith { "key-${it.name}" }, "details")
    }

    private fun ItemLibrary.skinOffer(name: String): ItemOffer = offer(ItemKind.Skin, server, json(skin(name)))!!

    private fun ItemLibrary.backgroundOffer(name: String): ItemOffer =
        offer(ItemKind.Background, server, json(background(name)))!!

    @Test
    fun offerFromItem() {
        val offer = library().offer(ItemKind.Skin, server, json(skin("look")))!!
        assertEquals(ItemKind.Skin, offer.kind)
        assertEquals("look", offer.name)
        assertEquals("Title look", offer.title)
        assertEquals(setOf(ResourceKind.SKIN_DATA, ResourceKind.SKIN_TEXTURE), offer.refs.map { it.kind }.toSet())
        assertEquals(server.resolve("/s/data-look"), offer.refs.first { it.kind == ResourceKind.SKIN_DATA }.url)
    }

    @Test
    fun offerWithoutTextureIsNull() {
        val item = json("""{"name":"x","title":"X","data":{"url":"/s/d"}}""")
        assertNull(library().offer(ItemKind.Skin, server, item))
    }

    @Test
    fun addDownloadsOnlyItsKind() = runTest {
        val library = library()
        val skin = library.add(server, library.skinOffer("look"))
        assertEquals(setOf("skin.data", "skin.texture"), skin.files.keys)
        assertTrue(requested.none { it.startsWith("/sonolus/backgrounds/") || it.startsWith("/sonolus/particles/") })

        val bg = library.add(server, library.backgroundOffer("night"))
        assertEquals(setOf("background.image", "background.data", "background.configuration"), bg.files.keys)
        assertEquals(ItemKind.Background, bg.kind)
        assertEquals(server.linkAddress, bg.server)
        assertEquals(listOf(skin, bg), library.items.value)
    }

    @Test
    fun idsPerKind() {
        assertEquals(sha1Hex("a.com|x"), ItemLibrary.idOf(ItemKind.Skin, "a.com", "x"))
        assertEquals(sha1Hex("background|a.com|x"), ItemLibrary.idOf(ItemKind.Background, "a.com", "x"))
        assertEquals(sha1Hex("effect|a.com|x"), ItemLibrary.idOf(ItemKind.Sfx, "a.com", "x"))
    }

    @Test
    fun fileReturnsCachedFile() = runTest {
        val library = library()
        val skin = library.add(server, library.skinOffer("look"))
        assertEquals("skin texture look", library.file(skin, ResourceKind.SKIN_TEXTURE)!!.readText())
        assertNull(library.file(skin, ResourceKind.BACKGROUND_IMAGE))
    }

    @Test
    fun savedItemsSurviveARestart() = runTest {
        val library = library()
        val saved = library.add(server, library.skinOffer("look"))
        assertEquals(listOf(saved), library().items.value)
    }

    @Test
    fun addingTheSameItemTwiceKeepsOneEntry() = runTest {
        val library = library()
        val first = library.add(server, library.skinOffer("look"))
        val second = library.add(server, library.skinOffer("look"))
        assertEquals(first.id, second.id)
        assertEquals(1, library.items.value.size)
    }

    @Test
    fun previewIsNotSaved() = runTest {
        val library = library()
        val preview = library.preview(server, library.skinOffer("look"))
        assertTrue(library.items.value.isEmpty())
        assertTrue(library().items.value.isEmpty())
        assertEquals(preview, library.find(preview.id))
    }

    @Test
    fun promoteSavesWithoutDownloading() = runTest {
        val library = library()
        val preview = library.preview(server, library.skinOffer("look"))
        val before = web.requestCount
        val promoted = library.promote(preview.id)
        assertEquals(preview, promoted)
        assertEquals(before, web.requestCount)
        assertEquals(listOf(preview), library.items.value)
        assertEquals(listOf(preview), library().items.value)
        assertNull(library.promote("missing"))
    }

    @Test
    fun startupSweepKeepsSavedFiles() = runTest {
        val library = library()
        val shared = "/s/shared"
        val sharedSrl = srl(shared, "shared bytes")
        val a = """{"name":"a","title":"A","data":$sharedSrl,"texture":${srl("/s/ta", "ta")}}"""
        val b = """{"name":"b","title":"B","data":$sharedSrl,"texture":${srl("/s/tb", "tb")}}"""
        library.add(server, library.offer(ItemKind.Skin, server, json(a))!!)
        library.add(server, library.offer(ItemKind.Skin, server, json(b))!!)
        val saved = savedFiles.map { it.name }.toSet()
        val preview = library.preview(server, library.skinOffer("look"))
        val previewOnly = preview.files.values.toSet() - saved
        assertEquals(2, previewOnly.size)
        assertTrue(savedFiles.map { it.name }.containsAll(previewOnly))

        val restarted = library()
        assertEquals(2, restarted.items.value.size)
        assertEquals(saved, savedFiles.map { it.name }.toSet())
    }

    private fun seedCache(vararg keys: String) {
        val files = dir.resolve("files").apply { mkdirs() }
        keys.forEach { files.resolve(it).writeText("x") }
    }

    @Test
    fun sweepKeepsLegacySkinFiles() {
        seedCache("legacy1", "legacy2", "orphan")
        dir.resolve("skins.json").writeText(
            """[{"id":"i","title":"T","server":"s","files":{"skin.data":"legacy1","skin.texture":"legacy2"}}]""",
        )
        library()
        assertEquals(setOf("legacy1", "legacy2"), savedFiles.map { it.name }.toSet())
    }

    @Test
    fun unreadableManifestSkipsSweep() {
        seedCache("a", "orphan")
        dir.resolve("items.json").writeText("not json")
        library()
        assertEquals(setOf("a", "orphan"), savedFiles.map { it.name }.toSet())
    }

    @Test
    fun unreadableLegacyManifestSkipsSweep() {
        seedCache("a", "orphan")
        dir.resolve("skins.json").writeText("not json")
        library()
        assertEquals(setOf("a", "orphan"), savedFiles.map { it.name }.toSet())
    }

    @Test
    fun applyToSeveralKinds() = runTest {
        val library = library()
        val skin = library.add(server, library.skinOffer("look"))
        val bg = library.add(server, library.backgroundOffer("night"))
        val level = level()
        val result = library.applyTo(level, mapOf(ItemKind.Skin to skin.id, ItemKind.Background to bg.id, ItemKind.Sfx to null))

        assertEquals("skin data look", result.files.getValue(ResourceKind.SKIN_DATA).readText())
        assertEquals("skin texture look", result.files.getValue(ResourceKind.SKIN_TEXTURE).readText())
        assertEquals("bg image night", result.files.getValue(ResourceKind.BACKGROUND_IMAGE).readText())
        assertEquals("bg data night", result.files.getValue(ResourceKind.BACKGROUND_DATA).readText())
        assertEquals("bg config night", result.files.getValue(ResourceKind.BACKGROUND_CONFIGURATION).readText())
        assertEquals(level.files[ResourceKind.EFFECT_DATA], result.files[ResourceKind.EFFECT_DATA])
        assertEquals(level.files[ResourceKind.EFFECT_AUDIO], result.files[ResourceKind.EFFECT_AUDIO])
        assertEquals(level.files[ResourceKind.LEVEL_DATA], result.files[ResourceKind.LEVEL_DATA])
        assertEquals(level.keys, result.keys)
        assertEquals(level.detailsKey, result.detailsKey)
    }

    @Test
    fun applyToUsesPreviewsAndIgnoresUnknownOrWrongKind() = runTest {
        val library = library()
        val preview = library.preview(server, library.skinOffer("look"))
        val level = level()
        assertEquals(
            "skin data look",
            library.applyTo(level, mapOf(ItemKind.Skin to preview.id)).files.getValue(ResourceKind.SKIN_DATA).readText(),
        )
        assertSame(level, library.applyTo(level, mapOf(ItemKind.Skin to "missing")))
        assertSame(level, library.applyTo(level, mapOf(ItemKind.Background to preview.id)))
        assertSame(level, library.applyTo(level, emptyMap()))
    }

    @Test
    fun applyToSkipsIncompleteItem() = runTest {
        val library = library()
        val skin = library.add(server, library.skinOffer("look"))
        val bg = library.add(server, library.backgroundOffer("night"))
        dir.resolve("files").resolve(bg.files.getValue("background.data")).delete()
        val level = level()
        val result = library.applyTo(level, mapOf(ItemKind.Skin to skin.id, ItemKind.Background to bg.id))
        assertEquals("skin data look", result.files.getValue(ResourceKind.SKIN_DATA).readText())
        assertEquals(level.files[ResourceKind.BACKGROUND_IMAGE], result.files[ResourceKind.BACKGROUND_IMAGE])
        assertEquals(level.files[ResourceKind.BACKGROUND_DATA], result.files[ResourceKind.BACKGROUND_DATA])
        assertEquals(level.files[ResourceKind.BACKGROUND_CONFIGURATION], result.files[ResourceKind.BACKGROUND_CONFIGURATION])
    }

    @Test
    fun previewFailureKeepsNothing() = runTest {
        val library = library()
        val offer = library.skinOffer("look")
        bodies.remove("/s/texture-look")
        try {
            library.preview(server, offer)
            fail("expected an error")
        } catch (e: Exception) {
            assertNull(library.find(offer.let { ItemLibrary.idOf(ItemKind.Skin, server.linkAddress, it.name) }))
        }
        assertTrue(savedFiles.isEmpty())
    }

    @Test
    fun corruptDownloadSavesNothing() = runTest {
        val library = library()
        val offer = library.skinOffer("look")
        bodies["/s/texture-look"] = "corrupt"
        try {
            library.add(server, offer)
            fail("expected HashMismatch")
        } catch (e: HashMismatch) {
            assertTrue(library.items.value.isEmpty())
        }
        assertTrue(savedFiles.isEmpty())
    }

    @Test
    fun removeKeepsFilesAnotherItemStillUses() = runTest {
        val library = library()
        val sharedSrl = srl("/s/shared", "shared bytes")
        val a = """{"name":"a","title":"A","data":$sharedSrl,"texture":${srl("/s/ta", "ta")}}"""
        val b = """{"name":"b","title":"B","data":$sharedSrl,"texture":${srl("/s/tb", "tb")}}"""
        val first = library.add(server, library.offer(ItemKind.Skin, server, json(a))!!)
        val second = library.add(server, library.offer(ItemKind.Skin, server, json(b))!!)
        library.remove(first.id)
        assertEquals(listOf(second), library.items.value)
        assertEquals(
            "shared bytes",
            library.applyTo(level(), mapOf(ItemKind.Skin to second.id)).files.getValue(ResourceKind.SKIN_DATA).readText(),
        )
        library.remove(second.id)
        assertTrue(savedFiles.isEmpty())
    }

    @Test
    fun corruptManifestStartsEmpty() {
        dir.mkdirs()
        dir.resolve("items.json").writeText("not json")
        assertTrue(library().items.value.isEmpty())
    }
}
