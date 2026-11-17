package my.fumui.sairitsu.library

import my.fumui.sairitsu.data.LevelKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelCollectionTest {
    private fun key(name: String) = LevelKey("sonolus.sbuga.com", name)

    private fun collection(name: String, vararg levels: String, id: String = name) =
        LevelCollection(id = id, name = name, createdAt = 0, levels = levels.map(::key))

    @Test
    fun membershipAllSomeNone() {
        val c = collection("x", "a", "b")
        assertEquals(Membership.All, membership(c, setOf(key("a"), key("b"))))
        assertEquals(Membership.Some, membership(c, setOf(key("a"), key("c"))))
        assertEquals(Membership.None, membership(c, setOf(key("c"))))
        assertEquals(Membership.None, membership(c, emptySet()))
    }

    @Test
    fun tapAddsUnlessAll() {
        assertFalse(Membership.All.tapAdds())
        assertTrue(Membership.Some.tapAdds())
        assertTrue(Membership.None.tapAdds())
    }

    @Test
    fun nameErrors() {
        val existing = listOf(collection("Favorites"))
        assertEquals("Enter a name", collectionNameError("", existing))
        assertEquals("Enter a name", collectionNameError("   ", existing))
        assertEquals("Use 40 characters or fewer", collectionNameError("x".repeat(41), existing))
        assertNull(collectionNameError("x".repeat(40), existing))
        assertEquals("A collection with this name already exists", collectionNameError(" favorites ", existing))
        assertNull(collectionNameError("trill practice", existing))
    }

    @Test
    fun renameToOwnNameInOtherCaseIsAllowed() {
        val fav = collection("Favorites")
        assertNull(collectionNameError("favorites", listOf(fav), excludingId = fav.id))
    }

    @Test
    fun sortedByNameIgnoresCase() {
        val sorted = listOf(collection("beta"), collection("Alpha"), collection("gamma")).sortedByName()
        assertEquals(listOf("Alpha", "beta", "gamma"), sorted.map { it.name })
    }

    @Test
    fun cardsCountOnlyLibraryMembersAndUseNewestCovers() {
        val c = collection("x", "a", "b", "c", "d", "e", "gone")
        val entries = listOf("a", "b", "c", "d", "e").map { entry(it).copy(coverKey = "c-$it") } + entry("f")
        val card = collectionCards(listOf(c), entries).single()
        assertEquals(5, card.count)
        assertEquals(listOf("c-e", "c-d", "c-c", "c-b"), card.coverKeys)
    }

    @Test
    fun cardsSkipMissingCoversAndSortByName() {
        val b = collection("beta", "a", "b")
        val a = collection("Alpha")
        val entries = listOf(entry("a").copy(coverKey = "c-a"), entry("b"))
        val cards = collectionCards(listOf(b, a), entries)
        assertEquals(listOf("Alpha", "beta"), cards.map { it.name })
        assertEquals(listOf("c-a"), cards[1].coverKeys)
        assertEquals(0, cards[0].count)
    }
}
