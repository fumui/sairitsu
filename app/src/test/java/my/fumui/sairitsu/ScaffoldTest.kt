package my.fumui.sairitsu

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScaffoldTest {
    @Test
    fun fixturesPresent() {
        for (name in listOf("list", "info", "details")) {
            val resource = javaClass.getResource("/fixtures/$name.json")
            assertNotNull("missing fixture $name.json", resource)
            assertTrue("$name.json is not a JSON object", resource!!.readText().trimStart().startsWith("{"))
        }
    }
}
