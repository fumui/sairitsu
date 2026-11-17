package my.fumui.sairitsu.gameplay.model

import my.fumui.sairitsu.data.ServerAddress
import my.fumui.sairitsu.gameplay.ResourceKind
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** One downloadable resource: an absolute URL and the sha1 the server advertised, when it did. */
data class ResourceRef(val kind: ResourceKind, val url: String, val hash: String?)

/** The level details are missing the SRL for [kind]. */
class MissingResource(val kind: ResourceKind) : Exception("Level details have no ${kind.wireName}")

/**
 * Resolves the resources of a level item (the `item` object of a level details response): all fifteen, less an
 * optional one (the engine rom) the item does not have. A skin, background, effect or particle with `useDefault`
 * comes from the engine's own item.
 */
fun resolveResources(item: JsonObject, server: ServerAddress): List<ResourceRef> =
    ResourceKind.entries.mapNotNull { kind ->
        val (group, field) = kind.wireName.split('.')
        val owner: JsonObject? = when (group) {
            "level" -> item
            "engine" -> item.obj("engine")
            else -> {
                val use = item.obj("use" + group.replaceFirstChar { it.uppercase() })
                if ((use?.get("useDefault") as? JsonPrimitive)?.booleanOrNull == true) {
                    item.obj("engine")?.obj(group)
                } else {
                    use?.obj("item")
                }
            }
        }
        val srl = owner?.obj(field)
        val url = (srl?.get("url") as? JsonPrimitive)?.contentOrNull
        if (srl == null || url == null) {
            if (kind.optional) return@mapNotNull null
            throw MissingResource(kind)
        }
        val hash = (srl["hash"] as? JsonPrimitive)?.contentOrNull
        ResourceRef(kind, server.resolve(url)!!, hash)
    }

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
