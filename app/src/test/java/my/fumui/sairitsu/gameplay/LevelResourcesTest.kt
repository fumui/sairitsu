package my.fumui.sairitsu.gameplay

import my.fumui.sairitsu.data.ServerAddress
import my.fumui.sairitsu.data.model.ServerJson
import my.fumui.sairitsu.gameplay.model.MissingResource
import my.fumui.sairitsu.gameplay.model.resolveResources
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LevelResourcesTest {
    private val server = ServerAddress.parse("https://sonolus.sbuga.com")!!

    private fun item(): JsonObject {
        val text = javaClass.getResource("/fixtures/gameplay/bench_details.json")!!.readText(Charsets.UTF_8)
        return ServerJson.parseToJsonElement(text).jsonObject["item"]!!.jsonObject
    }

    @Test
    fun resolvesAllFifteenKinds() {
        val refs = resolveResources(item(), server)
        assertEquals(ResourceKind.entries, refs.map { it.kind })
        assertEquals(15, refs.size)
        assertTrue(refs.all { it.url.startsWith("https://") && !it.hash.isNullOrEmpty() })
    }

    @Test
    fun relativeUrlsResolveAgainstServer() {
        val ref = resolveResources(item(), server).first { it.kind == ResourceKind.ENGINE_WATCH_DATA }
        assertEquals("https://sonolus.sbuga.com/sonolus/repository/c2bb90e3d501a441081f0d6f75deb34453d0c593", ref.url)
    }

    @Test
    fun absoluteUrlsKept() {
        val ref = resolveResources(item(), server).first { it.kind == ResourceKind.LEVEL_DATA }
        assertEquals("https://sbugasbugasbuga.sbuga.com/leveldata/722/expert.gz", ref.url)
        assertEquals("d7605f8e7d72dd1e26657a938eaaaca05d03cfae", ref.hash)
    }

    @Test
    fun useBackgroundOverrideWins() {
        val ref = resolveResources(item(), server).first { it.kind == ResourceKind.BACKGROUND_IMAGE }
        assertEquals("52c11cc65b224c42972b5db6c523022e3388b158", ref.hash)
        assertEquals(
            "https://sbugaisthemostsbuga.sbuga.com/pjsk_data/jp/music/jacket/jacket_s_722/jacket_s_722_v3.png",
            ref.url,
        )
    }

    @Test
    fun engineWithoutRomResolvesTheOtherFourteen() {
        val item = item()
        val engine = JsonObject(item["engine"]!!.jsonObject.filterKeys { it != "rom" })
        val refs = resolveResources(JsonObject(item + ("engine" to engine)), server)
        assertEquals(ResourceKind.entries - ResourceKind.ENGINE_ROM, refs.map { it.kind })
    }

    @Test
    fun missingLevelDataIsError() {
        val without = JsonObject(item().filterKeys { it != "data" })
        try {
            resolveResources(without, server)
            fail("expected MissingResource")
        } catch (e: MissingResource) {
            assertEquals(ResourceKind.LEVEL_DATA, e.kind)
        }
    }
}
