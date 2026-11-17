package my.fumui.sairitsu.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

val ServerJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
}

/** A resource reference. */
@Serializable
data class Srl(val url: String? = null, val hash: String? = null)

/** A level tag. Both fields are optional: some servers send icon-only badges (`{"icon":"trophy"}`). */
@Serializable
data class Tag(val title: String = "", val icon: String? = null)

@Serializable
data class EngineRef(val name: String, val title: String, val subtitle: String? = null)

@Serializable
data class LevelItem(
    val name: String,
    val title: String,
    val version: Int = 1,
    val rating: Double = 0.0,
    val artists: String = "",
    val author: String = "",
    val tags: List<Tag> = emptyList(),
    val engine: EngineRef? = null,
    val cover: Srl? = null,
    val bgm: Srl? = null,
    val preview: Srl? = null,
)

@Serializable
data class ItemSection(
    val title: String,
    val itemType: String,
    val items: List<JsonElement> = emptyList(),
) {
    /**
     * The section's items as levels, or empty when the section holds another item type.
     * Items that don't parse are skipped, and repeated names keep their first occurrence.
     */
    fun levels(json: Json): List<LevelItem> {
        if (itemType != "level") return emptyList()
        return items
            .mapNotNull { runCatching { json.decodeFromJsonElement(LevelItem.serializer(), it) }.getOrNull() }
            .distinctBy { it.name }
    }
}

@Serializable
data class OptionValue(val name: String, val title: String)

@Serializable
data class SearchOption(
    val query: String,
    val name: String,
    val type: String,
    val required: Boolean = false,
    val def: JsonElement? = null,
    val placeholder: String? = null,
    val limit: Int? = null,
    val values: List<OptionValue> = emptyList(),
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
)

@Serializable
data class Search(
    val type: String,
    val title: String,
    val icon: String? = null,
    val options: List<SearchOption> = emptyList(),
)

@Serializable
data class ServerInfo(val title: String, val description: String? = null, val banner: Srl? = null)

@Serializable
data class ItemInfo(
    val sections: List<ItemSection> = emptyList(),
    val searches: List<Search> = emptyList(),
)

@Serializable
data class ItemList(
    val pageCount: Int = -1,
    val cursor: String? = null,
    val items: List<LevelItem> = emptyList(),
    val searches: List<Search> = emptyList(),
)

@Serializable
data class ItemDetails(
    val item: LevelItem,
    val description: String? = null,
    val sections: List<ItemSection> = emptyList(),
)
