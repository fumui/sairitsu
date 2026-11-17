package my.fumui.sairitsu.library

import my.fumui.sairitsu.data.LevelKey
import kotlinx.serialization.Serializable

/** A user-named group of downloaded levels. [id] never changes; [levels] are in the order they were added. */
@Serializable
data class LevelCollection(
    val id: String,
    val name: String,
    val createdAt: Long,
    val levels: List<LevelKey>,
)

/** How many of some levels a collection holds: every one, some, or none. */
enum class Membership { All, Some, None }

/** Whether [collection] holds all, some or none of [keys] (None when [keys] is empty). */
fun membership(collection: LevelCollection, keys: Set<LevelKey>): Membership {
    if (keys.isEmpty()) return Membership.None
    val inside = collection.levels.count { it in keys }
    return when (inside) {
        0 -> Membership.None
        keys.size -> Membership.All
        else -> Membership.Some
    }
}

/** What tapping a box does: true = add the keys, false = remove them. */
fun Membership.tapAdds(): Boolean = this != Membership.All

const val MAX_COLLECTION_NAME = 40

/** Why [name] can't be used (blank, too long, or taken ignoring case), or null when it can. */
fun collectionNameError(name: String, existing: List<LevelCollection>, excludingId: String? = null): String? {
    val trimmed = name.trim()
    return when {
        trimmed.isEmpty() -> "Enter a name"
        trimmed.length > MAX_COLLECTION_NAME -> "Use $MAX_COLLECTION_NAME characters or fewer"
        existing.any { it.id != excludingId && it.name.equals(trimmed, ignoreCase = true) } ->
            "A collection with this name already exists"
        else -> null
    }
}

fun List<LevelCollection>.sortedByName(): List<LevelCollection> = sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

/** A card on the Collections grid: [count] members in the library, up to four [coverKeys] of the newest ones. */
data class CollectionCard(val id: String, val name: String, val count: Int, val coverKeys: List<String>)

fun collectionCards(collections: List<LevelCollection>, entries: List<LibraryEntry>): List<CollectionCard> {
    val byKey = entries.associateBy { it.key }
    return collections.sortedByName().map { c ->
        val members = c.levels.mapNotNull(byKey::get)
        CollectionCard(
            id = c.id,
            name = c.name,
            count = members.size,
            coverKeys = members.asReversed().mapNotNull { it.coverKey }.take(4),
        )
    }
}
