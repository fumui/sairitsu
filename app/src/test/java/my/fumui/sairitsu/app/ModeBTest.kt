package my.fumui.sairitsu.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeBTest {
    @Test
    fun savesOnlyWhenEnabledAndNotInLibrary() = runBlocking {
        var saves = 0
        val save: suspend () -> Unit = { saves++ }
        assertFalse(saveIfWanted(enabled = false, inLibrary = false, save))
        assertFalse(saveIfWanted(enabled = true, inLibrary = true, save))
        assertFalse(saveIfWanted(enabled = false, inLibrary = true, save))
        assertEquals(0, saves)
        assertTrue(saveIfWanted(enabled = true, inLibrary = false, save))
        assertEquals(1, saves)
    }
}
