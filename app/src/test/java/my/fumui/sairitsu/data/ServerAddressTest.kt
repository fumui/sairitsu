package my.fumui.sairitsu.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerAddressTest {
    @Test
    fun parse_normalizesAndRejects() {
        val horizon = ServerAddress.parse(" coconut.sonolus.com/horizon/ ")!!
        assertEquals("https://coconut.sonolus.com/horizon", horizon.baseUrl.toString())
        assertEquals("coconut.sonolus.com/horizon", horizon.linkAddress)

        val local = ServerAddress.parse("http://10.0.2.2:3000")!!
        assertEquals("http", local.baseUrl.scheme)
        assertEquals("10.0.2.2:3000", local.linkAddress)

        assertNull(ServerAddress.parse(""))
        assertNull(ServerAddress.parse("   "))
        assertNull(ServerAddress.parse("not a server"))
    }

    @Test
    fun resolveRelativeResourceUrls() {
        val server = ServerAddress.parse("example.com/sub")!!
        assertEquals("https://example.com/sub/sonolus/repository/abc", server.resolve("/sonolus/repository/abc"))
        assertEquals("https://cdn.example.com/a.png", server.resolve("https://cdn.example.com/a.png"))
        assertNull(server.resolve(null))
        val root = ServerAddress.parse("http://10.0.2.2:3000")!!
        assertEquals("http://10.0.2.2:3000/sonolus/repository/abc", root.resolve("/sonolus/repository/abc"))
    }
}
