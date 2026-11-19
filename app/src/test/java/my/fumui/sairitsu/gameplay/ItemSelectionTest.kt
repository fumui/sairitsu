package my.fumui.sairitsu.gameplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ItemSelectionTest {
    @Test
    fun resolveLevelWinsThenEngine() {
        assertEquals("a", resolveItemId(ItemSelection.Saved("a"), ItemSelection.Saved("b")))
        assertEquals("a", resolveItemId(ItemSelection.Saved("a"), ItemSelection.Original))
        assertNull(resolveItemId(ItemSelection.Original, ItemSelection.Saved("b")))
        assertEquals("b", resolveItemId(ItemSelection.UseEngine, ItemSelection.Saved("b")))
        assertNull(resolveItemId(ItemSelection.UseEngine, ItemSelection.Original))
    }

    @Test
    fun optionLabelsPerKind() {
        val saved = listOf(
            SavedItem("s", ItemKind.Skin, "Skin one", "srv", emptyMap()),
            SavedItem("b", ItemKind.Background, "Bg one", "srv", emptyMap()),
            SavedItem("e", ItemKind.Sfx, "Sfx one", "srv", emptyMap()),
        )
        assertEquals(
            listOf(
                ItemSelection.UseEngine to "Use engine's background",
                ItemSelection.Original to "Original background",
                ItemSelection.Saved("b") to "Bg one",
            ),
            levelItemOptions(ItemKind.Background, saved) { it },
        )
        assertEquals(
            listOf(ItemSelection.Original to "Original background", ItemSelection.Saved("b") to "Bg one"),
            engineItemOptions(ItemKind.Background, saved) { it },
        )
        assertEquals("Use engine's SFX", levelItemOptions(ItemKind.Sfx, saved) { it }.first().second)
        assertEquals("Original SFX", engineItemOptions(ItemKind.Sfx, saved) { it }.first().second)
        assertEquals(2, engineItemOptions(ItemKind.Skin, saved) { it }.size)
    }

    private val skinA = SavedItem("a", ItemKind.Skin, "Skin A", "srv", emptyMap())

    @Test
    fun engineItemResolvesSaved() {
        val state = ItemPickerState(saved = listOf(skinA), engine = mapOf(ItemKind.Skin to ItemSelection.Saved("a")))
        assertEquals(skinA, state.engineItem(ItemKind.Skin))
    }

    @Test
    fun engineItemForOriginalIsNull() {
        assertNull(ItemPickerState(saved = listOf(skinA), engine = mapOf(ItemKind.Skin to ItemSelection.Original)).engineItem(ItemKind.Skin))
        assertNull(ItemPickerState(saved = listOf(skinA)).engineItem(ItemKind.Skin))
    }

    @Test
    fun engineItemForMissingIdIsNull() {
        val state = ItemPickerState(saved = listOf(skinA), engine = mapOf(ItemKind.Skin to ItemSelection.Saved("gone")))
        assertNull(state.engineItem(ItemKind.Skin))
    }

    @Test
    fun engineItemOfOtherKindIsNull() {
        val state = ItemPickerState(saved = listOf(skinA), engine = mapOf(ItemKind.Background to ItemSelection.Saved("a")))
        assertNull(state.engineItem(ItemKind.Background))
    }

    @Test
    fun ofWireMapsTypes() {
        assertEquals(ItemKind.Sfx, ItemKind.ofWire("effect"))
        assertEquals(ItemKind.Skin, ItemKind.ofWire("skin"))
        assertNull(ItemKind.ofWire("room"))
    }
}
