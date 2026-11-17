package my.fumui.sairitsu.gameplay

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import my.fumui.sairitsu.data.ServerAddress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultStoreTest {
    private class MemoryStore : DataStore<Preferences> {
        val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    private val server = ServerAddress("https://sonolus.sbuga.com".toHttpUrl())
    private val other = ServerAddress("https://example.org".toHttpUrl())

    private fun result(arcade: Int, accuracy: Int = 900000, grade: Grade = Grade.Pass) =
        PlayResult(grade, arcade, accuracy, 100, 80, 15, 3, 2, 100)

    @Test
    fun statsJoinsBestAndLastPlayed() = runBlocking {
        val store = DataStoreResultStore(MemoryStore())
        val horizon = my.fumui.sairitsu.data.ServerAddress.parse("coconut.sonolus.com/horizon")!!
        store.record(horizon, "a", result(100))
        store.markPlayed(horizon, "a", 55)
        store.markPlayed(horizon, "b", 66)
        val stats = store.stats()
        assertEquals(
            mapOf(
                my.fumui.sairitsu.data.LevelKey("coconut.sonolus.com/horizon", "a") to
                    my.fumui.sairitsu.library.LevelStats(result(100), 55),
                my.fumui.sairitsu.data.LevelKey("coconut.sonolus.com/horizon", "b") to
                    my.fumui.sairitsu.library.LevelStats(null, 66),
            ),
            stats,
        )
    }

    @Test
    fun markPlayedOverwrites() = runBlocking {
        val store = DataStoreResultStore(MemoryStore())
        val s = my.fumui.sairitsu.data.ServerAddress.parse("sonolus.sbuga.com")!!
        store.markPlayed(s, "a", 1)
        store.markPlayed(s, "a", 2)
        assertEquals(2L, store.stats().values.single().lastPlayed)
    }

    @Test
    fun firstResultIsBest() = runBlocking {
        val store = DataStoreResultStore(MemoryStore())
        assertNull(store.best(server, "lvl"))
        assertTrue(store.record(server, "lvl", result(500000, grade = Grade.Fail)))
        assertEquals(result(500000, grade = Grade.Fail), store.best(server, "lvl"))
    }

    @Test
    fun higherArcadeReplaces() = runBlocking {
        val store = DataStoreResultStore(MemoryStore())
        store.record(server, "lvl", result(900000))
        assertFalse(store.record(server, "lvl", result(800000, accuracy = 999999)))
        assertTrue(store.record(server, "lvl", result(950000, accuracy = 1)))
        assertEquals(950000, store.best(server, "lvl")!!.arcadeScore)
        assertNull(store.best(other, "lvl"))
        assertNull(store.best(server, "other"))
    }

    @Test
    fun tieGoesToAccuracy() = runBlocking {
        val store = DataStoreResultStore(MemoryStore())
        store.record(server, "lvl", result(900000, accuracy = 800000))
        assertFalse(store.record(server, "lvl", result(900000, accuracy = 800000)))
        assertFalse(store.record(server, "lvl", result(900000, accuracy = 700000)))
        assertTrue(store.record(server, "lvl", result(900000, accuracy = 810000)))
        assertEquals(810000, store.best(server, "lvl")!!.accuracyScore)
    }

    @Test
    fun failNeverReplacesPass() = runBlocking {
        for (kept in listOf(Grade.Pass, Grade.FullCombo, Grade.AllPerfect)) {
            val store = DataStoreResultStore(MemoryStore())
            store.record(server, "lvl", result(100, grade = kept))
            assertFalse(store.record(server, "lvl", result(999999, grade = Grade.Fail)))
            assertEquals(kept, store.best(server, "lvl")!!.grade)
        }
    }

    @Test
    fun bestSurvivesNewStoreInstance() = runBlocking {
        val memory = MemoryStore() // stands for the same DataStore file
        DataStoreResultStore(memory).record(server, "lvl", result(912345, accuracy = 987654, grade = Grade.FullCombo))
        assertEquals(
            result(912345, accuracy = 987654, grade = Grade.FullCombo),
            DataStoreResultStore(memory).best(server, "lvl"),
        )
        assertTrue(memory.state.value.asMap().keys.any { it.name == "best:sonolus.sbuga.com/lvl" })
    }

    @Test
    fun fromNativeReadsLayout() {
        val out = floatArrayOf(3f, 1f, 983430f, 990000f, 321f, 200f, 90f, 20f, 11f, 321f, 0f)
        assertEquals(PlayResult(Grade.FullCombo, 983430, 990000, 321, 200, 90, 20, 11, 321), PlayResult.fromNative(out))
        assertEquals(Grade.AllPerfect, PlayResult.fromNative(floatArrayOf(3f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)).grade)
        assertEquals(Grade.Fail, PlayResult.fromNative(floatArrayOf(3f, 3f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)).grade)
    }

    @Test
    fun resultTextFormats() {
        val r = PlayResult(Grade.Pass, 983430, 5, 321, 200, 90, 20, 11, 321)
        assertEquals("983,430", r.arcadeText())
        assertEquals("5", r.accuracyText())
        assertEquals("1,000,000", scoreText(1_000_000))
        assertEquals("0", scoreText(0))
        assertEquals("94.2%", PlayResult(Grade.Pass, 0, 0, 0, 942, 0, 0, 0, 1000).percent(942))
        assertEquals("0.0%", PlayResult(Grade.Pass, 0, 0, 0, 0, 0, 0, 0, 0).percent(0))
    }
}
