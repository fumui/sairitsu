package my.fumui.sairitsu.gameplay

import org.junit.Assert.assertEquals
import org.junit.Test

class ResourceKindTest {
    @Test
    fun wireNamesPinnedInOrder() {
        assertEquals(
            listOf(
                "engine.watchData", "engine.playData", "engine.configuration", "engine.rom",
                "skin.data", "skin.texture", "effect.data", "effect.audio",
                "particle.data", "particle.texture",
                "background.image", "background.data", "background.configuration",
                "level.data", "level.bgm",
            ),
            ResourceKind.entries.map { it.wireName },
        )
    }

    @Test
    fun codeIsOrdinalZeroToFourteen() {
        assertEquals((0..14).toList(), ResourceKind.entries.map { it.ordinal })
    }
}
