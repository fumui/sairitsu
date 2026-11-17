package my.fumui.sairitsu.gameplay

import my.fumui.sairitsu.data.OptionState
import my.fumui.sairitsu.data.SearchForms
import my.fumui.sairitsu.data.model.Search
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineOptionsTest {
    private fun fixture(): String =
        javaClass.getResourceAsStream("/engine-configuration-test.json")!!.readBytes().toString(Charsets.UTF_8)

    @Test
    fun parsesSliderToggleAndSelectOptions() {
        // The hand-written fixture holds 6 options: sliders, selects and a toggle.
        val options = EngineOptions.parse(fixture())
        assertEquals(listOf("#SPEED", "Test speed", "Test alpha", "Test choice", "Test toggle", "Test mode"), options.map { it.name })
        val speed = options[1]
        assertEquals("slider", speed.type)
        assertEquals(6f, speed.def, 0f)
        assertEquals(1f, speed.min)
        assertEquals(12f, speed.max)
        assertEquals(0.01f, speed.step)
        val fever = options[3]
        assertEquals("select", fever.type)
        assertEquals(3, fever.values.size)
        assertEquals("toggle", options[4].type)
        assertNull(options[4].min)
    }

    @Test
    fun gzippedConfigurationIsAccepted() {
        val gz = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.GZIPOutputStream(out).use { it.write(fixture().toByteArray()) }
        }.toByteArray()
        assertEquals(6, EngineOptions.parse(EngineOptions.decode(gz)).size)
    }

    @Test
    fun optionBlockUsesDefaultsAndOrder() {
        val options = EngineOptions.parse(fixture())
        val block = optionBlock(options, mapOf("Test speed" to 9.8f, "Test choice" to 2f, "Unknown" to 5f), watch = false)
        // #SPEED default, Test speed saved, Test alpha default, Test choice saved, Test toggle default, Test mode default.
        val expected = options.map { it.def }.toFloatArray().also { it[1] = 9.8f; it[3] = 2f }
        assertArrayEquals(expected, block, 0f)
        assertEquals(6, block.size)
    }

    @Test
    fun speedLockedInWatch() {
        val options = EngineOptions.parse(fixture())
        val block = optionBlock(options, mapOf("#SPEED" to 1.5f), watch = true)
        assertEquals(1f, block[0], 0f)
        assertEquals(1.5f, optionBlock(options, mapOf("#SPEED" to 1.5f), watch = false)[0], 0f)
    }

    @Test
    fun searchOptionRendersWithOptionControlStates() {
        val options = EngineOptions.parse(fixture())
        val search = Search("level", "Level options", options = options.map { it.toSearchOption() })
        val states = SearchForms.defaults(search)
        assertTrue(states[0] is OptionState.Slider)
        assertTrue(states[3] is OptionState.Select)
        assertTrue(states[4] is OptionState.Toggle)
        // States convert back to the stored float.
        assertEquals(6f, states[1].toStoredFloat()!!, 0f)
        assertEquals(0f, states[3].toStoredFloat()!!, 0f)
        assertEquals(1f, OptionState.Toggle(true).toStoredFloat()!!, 0f)
        assertEquals(2f, OptionState.Select("2").toStoredFloat()!!, 0f)
        assertEquals(OptionState.Select("2"), options[3].toState(2f))
    }

    @Test
    fun replayConfigRoundTrips() {
        val options = EngineOptions.parse(fixture())
        // A replay config in another order than the configuration, with one value changed from its default.
        val json = """{"options":[2,9.8,1,6,0,0],"optionNames":["Test choice","Test speed","#SPEED","Test alpha","Test toggle","Test mode"]}"""
        val gz = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.GZIPOutputStream(out).use { it.write(json.toByteArray()) }
        }.toByteArray()
        val config = ReplayConfig.parse(EngineOptions.decode(gz))
        assertEquals(6, config.values.size)
        val block = optionBlock(options, config.byName(), watch = false)
        // The values by name land in the configuration's order.
        assertEquals(1f, block[0], 0f)
        assertEquals(9.8f, block[1], 1e-6f)
        assertEquals(2f, block[3], 0f)
    }
}
