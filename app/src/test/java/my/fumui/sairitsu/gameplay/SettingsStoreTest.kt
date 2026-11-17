package my.fumui.sairitsu.gameplay

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsStoreTest {
    /** DataStore's file store cannot rename over an open file on Windows, so the test keeps preferences in memory. */
    private class MemoryStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    @Test
    fun settingsDefaultsFromQ16() {
        // BGM 100 %, SFX (effect) 25 %, no audio or input offset.
        val d = AppSettings()
        assertEquals(0.25f, d.effectVolume, 0f)
        assertEquals(1f, d.bgmVolume, 0f)
        assertEquals(0f, d.audioOffset, 0f)
        assertEquals(0f, d.inputOffset, 0f)
        assertEquals(listOf(0.25f, 1f, 0f, 0f), d.toNative().toList())
        assertEquals(d.toNative().toList(), NativeSettings.defaults().toList())
    }

    @Test
    fun storeRoundTripsOptionsAndSettings() = runBlocking {
        val store = SettingsStore(MemoryStore())
        assertEquals(AppSettings(), store.settings.first())
        assertEquals(emptyMap<String, Float>(), store.options("rush").first())
        store.saveOption("rush", "#NOTE_SPEED", 9.8f)
        store.saveOption("other", "#NOTE_SPEED", 3f)
        store.saveSettings(AppSettings(effectVolume = 0.5f, audioOffset = 0.02f))
        assertEquals(mapOf("#NOTE_SPEED" to 9.8f), store.options("rush").first())
        assertEquals(AppSettings(effectVolume = 0.5f, bgmVolume = 1f, audioOffset = 0.02f), store.settings.first())
    }

    @Test
    fun saveLevelsIPlayDefaultsOffAndRoundTrips() = runBlocking {
        val store = SettingsStore(MemoryStore())
        assertEquals(false, store.saveLevelsIPlay.first())
        store.setSaveLevelsIPlay(true)
        assertEquals(true, store.saveLevelsIPlay.first())
        assertEquals(AppSettings(), store.settings.first())
    }
}
