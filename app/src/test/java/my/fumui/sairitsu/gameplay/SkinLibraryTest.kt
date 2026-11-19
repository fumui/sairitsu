package my.fumui.sairitsu.gameplay

import my.fumui.sairitsu.data.ServerAddress
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class SkinLibraryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var web: MockWebServer
    private lateinit var server: ServerAddress
    private val bodies = ConcurrentHashMap<String, String>()

    private fun srl(path: String, body: String) =
        """{"url":"$path","hash":"${sha1Hex(body)}"}""".also { bodies[path] = body }

    private fun skin(name: String) =
        """{"name":"$name","title":"Title $name","data":${srl("/s/data-$name", "skin data $name")},
            "texture":${srl("/s/texture-$name", "skin texture $name")}}"""

    /** Serves the background [name] at its details endpoint. */
    private fun background(name: String) {
        bodies["/sonolus/backgrounds/$name"] =
            """{"item":{"name":"$name","image":${srl("/b/image", "bg image")},"data":${srl("/b/data", "bg data")},
                "configuration":${srl("/b/config", "bg config")}}}"""
    }

    /** Serves the particles [name] at its details endpoint. */
    private fun particles(name: String) {
        bodies["/sonolus/particles/$name"] =
            """{"item":{"name":"$name","data":${srl("/p/data", "particle data")},
                "texture":${srl("/p/texture", "particle texture")}}}"""
    }

    private fun serve(skins: List<String>, backgrounds: List<String>) {
        bodies.keys.removeAll { it.startsWith("/sonolus/backgrounds/") || it.startsWith("/sonolus/particles/") }
        bodies["/sonolus/skins/list"] = """{"pageCount":1,"items":[${skins.joinToString(",")}]}"""
        backgrounds.forEach(::background)
    }

    @Before
    fun setUp() {
        web = MockWebServer()
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                bodies[request.path!!]?.let { MockResponse().setBody(it) } ?: MockResponse().setResponseCode(404)
        }
        web.start()
        server = ServerAddress(web.url("/"))
        serve(listOf(skin("look")), listOf("look"))
    }

    @After
    fun tearDown() {
        runCatching { web.shutdown() }
    }

    private val dir: File
        get() = tmp.root.resolve("skins")

    private fun library() = SkinLibrary(dir, OkHttpClient())

    private val savedFiles: List<File>
        get() = dir.resolve("files").listFiles()!!.toList()

    private fun level(): LoadedResources {
        val files = ResourceKind.entries.associateWith { kind ->
            tmp.root.resolve("level-${kind.name}").apply { writeText("level ${kind.wireName}") }
        }
        return LoadedResources(files, ResourceKind.entries.associateWith { "key-${it.name}" }, "details")
    }

    private suspend fun SkinLibrary.addNamed(name: String): SavedSkin =
        add(server, offers(server).first { it.name == name })

    @Test
    fun offersListTheServersSkins() = runTest {
        val offer = library().offers(server).single()
        assertEquals("look", offer.name)
        assertEquals("Title look", offer.title)
        assertEquals(setOf(ResourceKind.SKIN_DATA, ResourceKind.SKIN_TEXTURE), offer.refs.map { it.kind }.toSet())
    }

    @Test
    fun offersIncludeEveryPage() = runTest {
        bodies["/sonolus/skins/list"] = """{"pageCount":3,"items":[${skin("a")}]}"""
        bodies["/sonolus/skins/list?page=1"] = """{"pageCount":3,"items":[${skin("b")}]}"""
        bodies["/sonolus/skins/list?page=2"] = """{"pageCount":3,"items":[${skin("c")}]}"""
        assertEquals(listOf("a", "b", "c"), library().offers(server).map { it.name })
    }

    @Test
    fun offersFollowACursor() = runTest {
        bodies["/sonolus/skins/list"] = """{"pageCount":-1,"cursor":"next","items":[${skin("a")}]}"""
        bodies["/sonolus/skins/list?cursor=next"] = """{"pageCount":-1,"items":[${skin("b")}]}"""
        assertEquals(listOf("a", "b"), library().offers(server).map { it.name })
    }

    @Test
    fun serverWithoutBackgroundsStillSavesItsSkins() = runTest {
        serve(listOf(skin("look")), emptyList())
        val library = library()
        val saved = library.addNamed("look")
        assertEquals(setOf("skin.data", "skin.texture"), saved.files.keys)
    }

    @Test
    fun backgroundThatCannotBeReadSavesNothing() = runTest {
        bodies.remove("/sonolus/backgrounds/look")
        val library = library()
        val offer = library.offers(server).single()
        bodies["/sonolus/backgrounds/look"] = "<html>portal</html>"
        try {
            library.add(server, offer)
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(library.skins.value.isEmpty())
        }
        assertTrue(savedFiles.isEmpty())
    }

    @Test
    fun addKeepsSeveralSkins() = runTest {
        serve(listOf(skin("a"), skin("b")), emptyList())
        val library = library()
        library.addNamed("a")
        library.addNamed("b")
        assertEquals(listOf("Title a", "Title b"), library.skins.value.map { it.title })
    }

    @Test
    fun savedSkinsSurviveARestart() = runTest {
        val saved = library().addNamed("look")
        assertEquals(listOf(saved), library().skins.value)
    }

    @Test
    fun applyToReplacesOnlyTheLookAndKeepsTheKeys() = runTest {
        val library = library()
        val saved = library.addNamed("look")
        val level = level()
        val result = library.applyTo(level, saved.id)

        assertEquals("skin data look", result.files.getValue(ResourceKind.SKIN_DATA).readText())
        assertEquals("skin texture look", result.files.getValue(ResourceKind.SKIN_TEXTURE).readText())
        assertEquals("bg image", result.files.getValue(ResourceKind.BACKGROUND_IMAGE).readText())
        assertEquals("bg data", result.files.getValue(ResourceKind.BACKGROUND_DATA).readText())
        assertEquals("bg config", result.files.getValue(ResourceKind.BACKGROUND_CONFIGURATION).readText())
        assertEquals(level.files[ResourceKind.LEVEL_DATA], result.files[ResourceKind.LEVEL_DATA])
        assertEquals(level.files[ResourceKind.EFFECT_AUDIO], result.files[ResourceKind.EFFECT_AUDIO])
        assertEquals(level.keys, result.keys)
        assertEquals(level.detailsKey, result.detailsKey)
    }

    @Test
    fun applyToWithNullOrUnknownIdReturnsTheLevel() = runTest {
        val library = library()
        library.addNamed("look")
        val level = level()
        assertSame(level, library.applyTo(level, null))
        assertSame(level, library.applyTo(level, "missing"))
    }

    @Test
    fun applyToWithAMissingFileReturnsTheLevel() = runTest {
        val library = library()
        val saved = library.addNamed("look")
        savedFiles.first().delete()
        val level = level()
        assertSame(level, library.applyTo(level, saved.id))
    }

    @Test
    fun particlesOfTheSameNameAreSavedAndApplied() = runTest {
        particles("look")
        val library = library()
        val saved = library.addNamed("look")
        assertTrue(saved.files.keys.containsAll(setOf("particle.data", "particle.texture")))
        val result = library.applyTo(level(), saved.id)
        assertEquals("particle data", result.files.getValue(ResourceKind.PARTICLE_DATA).readText())
        assertEquals("particle texture", result.files.getValue(ResourceKind.PARTICLE_TEXTURE).readText())
        assertEquals("skin data look", result.files.getValue(ResourceKind.SKIN_DATA).readText())
    }

    @Test
    fun skinWithoutParticlesKeepsTheLevelsParticles() = runTest {
        particles("other")
        val library = library()
        val saved = library.addNamed("look")
        assertFalse(saved.files.containsKey("particle.data"))
        val level = level()
        val result = library.applyTo(level, saved.id)
        assertEquals(level.files[ResourceKind.PARTICLE_DATA], result.files[ResourceKind.PARTICLE_DATA])
        assertEquals(level.files[ResourceKind.PARTICLE_TEXTURE], result.files[ResourceKind.PARTICLE_TEXTURE])
    }

    @Test
    fun particlesThatCannotBeReadSaveNothing() = runTest {
        val library = library()
        val offer = library.offers(server).single()
        bodies["/sonolus/particles/look"] = "<html>portal</html>"
        try {
            library.add(server, offer)
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(library.skins.value.isEmpty())
        }
        assertTrue(savedFiles.isEmpty())
    }

    @Test
    fun skinWithoutBackgroundKeepsTheLevelsBackground() = runTest {
        serve(listOf(skin("look")), listOf("other"))
        val library = library()
        val saved = library.addNamed("look")
        val level = level()
        val result = library.applyTo(level, saved.id)
        assertEquals("skin data look", result.files.getValue(ResourceKind.SKIN_DATA).readText())
        assertEquals(level.files[ResourceKind.BACKGROUND_IMAGE], result.files[ResourceKind.BACKGROUND_IMAGE])
    }

    @Test
    fun corruptDownloadSavesNothing() = runTest {
        val library = library()
        val offer = library.offers(server).single()
        bodies["/s/texture-look"] = "corrupt"
        try {
            library.add(server, offer)
            fail("expected HashMismatch")
        } catch (e: HashMismatch) {
            assertTrue(library.skins.value.isEmpty())
        }
        assertTrue(savedFiles.isEmpty())
    }

    @Test
    fun removeKeepsFilesAnotherSkinStillUses() = runTest {
        serve(listOf(skin("a"), skin("b")), listOf("a", "b"))
        val library = library()
        val first = library.addNamed("a")
        val second = library.addNamed("b")
        library.remove(first.id)
        val result = library.applyTo(level(), second.id)
        assertEquals("bg image", result.files.getValue(ResourceKind.BACKGROUND_IMAGE).readText())
        assertEquals(listOf(second), library.skins.value)
    }

    @Test
    fun removeDeletesFilesNoSkinUses() = runTest {
        val library = library()
        library.remove(library.addNamed("look").id)
        assertTrue(library.skins.value.isEmpty())
        assertTrue(savedFiles.isEmpty())
        assertTrue(library().skins.value.isEmpty())
    }

    @Test
    fun corruptManifestStartsEmpty() {
        dir.mkdirs()
        dir.resolve("skins.json").writeText("not json")
        assertTrue(library().skins.value.isEmpty())
    }

    @Test
    fun addingTheSameSkinTwiceKeepsOneEntry() = runTest {
        val library = library()
        val first = library.addNamed("look")
        val second = library.addNamed("look")
        assertEquals(first.id, second.id)
        assertEquals(1, library.skins.value.size)
        assertEquals("skin data look", library.applyTo(level(), first.id).files.getValue(ResourceKind.SKIN_DATA).readText())
    }

    @Test
    fun unreadableSkinListIsAnIOException() = runTest {
        bodies["/sonolus/skins/list"] = "<html>portal</html>"
        val library = library()
        try {
            library.offers(server)
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(library.skins.value.isEmpty())
        }
    }
}
