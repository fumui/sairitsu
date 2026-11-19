package my.fumui.sairitsu.gameplay

import kotlinx.serialization.Serializable

/** An item saved on this device. [files]: cache key by [ResourceKind.wireName]. */
@Serializable
data class SavedItem(
    val id: String,
    val kind: ItemKind,
    val title: String,
    val server: String,
    val files: Map<String, String>,
)

/** Where a saved skin choice applies. */
sealed interface SkinScope {
    data class Level(val server: String, val level: String) : SkinScope

    data class Engine(val engine: String) : SkinScope
}
