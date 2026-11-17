package my.fumui.sairitsu.gameplay

import my.fumui.sairitsu.data.ServerAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameplayIntentTest {
    private val server = ServerAddress.parse("sonolus.sbuga.com")!!

    @Test
    fun extrasCarryServerLevelMode() {
        val extras = GameplayIntent.extras(server, "sss-722-1279-expert", GameplayMode.Watch)
        assertEquals(
            mapOf("server" to "https://sonolus.sbuga.com", "level" to "sss-722-1279-expert", "mode" to "watch"),
            extras,
        )
        assertEquals(
            GameplayRequest(server, "sss-722-1279-expert", GameplayMode.Watch),
            GameplayIntent.parse(extras::get),
        )
    }

    @Test
    fun parseKeepsServerPath() {
        val horizon = ServerAddress.parse("coconut.sonolus.com/horizon")!!
        val extras = GameplayIntent.extras(horizon, "x", GameplayMode.Play)
        assertEquals(GameplayRequest(horizon, "x", GameplayMode.Play), GameplayIntent.parse(extras::get))
    }

    @Test
    fun parseRejectsMissingOrUnknownValues() {
        val ok = GameplayIntent.extras(server, "lvl", GameplayMode.Watch)
        assertNull(GameplayIntent.parse((ok - GameplayIntent.LEVEL)::get))
        assertNull(GameplayIntent.parse((ok - GameplayIntent.SERVER)::get))
        assertNull(GameplayIntent.parse((ok + (GameplayIntent.MODE to "fly"))::get))
        assertEquals(GameplayMode.Watch, GameplayIntent.parse((ok - GameplayIntent.MODE)::get)?.mode)
    }

    @Test
    fun replayExtrasRoundTrip() {
        val ref = ReplayRef("0b6e2f0a-7d36-4c1c-9a53-6f3f4d2c1a10", "level-key", "p\nw\nc")
        val extras = GameplayIntent.extras(server, "lvl", GameplayMode.Watch, replay = ref)
        assertEquals(ref.id, extras[GameplayIntent.REPLAY])
        assertEquals(ref.levelData, extras[GameplayIntent.REPLAY_LEVEL_DATA])
        assertEquals(ref.engine, extras[GameplayIntent.REPLAY_ENGINE])
        assertEquals(ref, GameplayIntent.parse(extras::get)?.replay)
        val bare = ReplayRef("0b6e2f0a-7d36-4c1c-9a53-6f3f4d2c1a10", null, null)
        val parsed = GameplayIntent.parse(GameplayIntent.extras(server, "lvl", GameplayMode.Watch, replay = bare)::get)
        assertEquals(bare, parsed?.replay)
    }

    @Test
    fun replayWithPlayModeIsRejected() {
        val ref = ReplayRef("0b6e2f0a-7d36-4c1c-9a53-6f3f4d2c1a10", "a", "b")
        assertNull(GameplayIntent.parse(GameplayIntent.extras(server, "lvl", GameplayMode.Play, replay = ref)::get))
    }

    @Test
    fun debugAudioSettingsOverrideTheDefaults() {
        val none = GameplayIntent.settings(emptyMap<String, String>()::get)
        assertEquals(NativeSettings.defaults().toList(), none.toList())
        val extras = mapOf("effectVolume" to "0.25", "bgmVolume" to "0", "audioOffset" to "-0.05")
        val s = GameplayIntent.settings(extras::get)
        assertEquals(0.25f, s[NativeSettings.EFFECT_VOLUME])
        assertEquals(0f, s[NativeSettings.BGM_VOLUME])
        assertEquals(-0.05f, s[NativeSettings.AUDIO_OFFSET])
        assertEquals(0f, s[NativeSettings.INPUT_OFFSET])
        val bad = GameplayIntent.settings(mapOf("effectVolume" to "loud")::get)
        assertEquals(0.25f, bad[NativeSettings.EFFECT_VOLUME]) // default
    }
}
