package my.fumui.sairitsu.gameplay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.ceil
import kotlin.math.floor

/** One sprite of a skin or particle atlas: texel coordinates from the top-left. Particle sprites have no [name]. */
data class SheetSprite(val name: String?, val x: Double, val y: Double, val w: Double, val h: Double)

/** The sprite layout of `skin.data` or `particle.data`: the atlas size the coordinates refer to, and its sprites. */
data class SpriteSheet(val width: Int, val height: Int, val sprites: List<SheetSprite>)

/** A pixel rectangle of the texture image: [right] and [bottom] exclusive. */
data class TexelRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Reads sprite layouts and chooses the sprites that show what a saved skin or particle set looks like. */
object SpriteSheets {
    private const val MIN_SIDE = 8.0

    /** The layout in a data file that is gzip or plain JSON; null when it is not a JSON object with a size. */
    fun parse(bytes: ByteArray): SpriteSheet? {
        val root = try {
            Json.parseToJsonElement(EngineOptions.decode(bytes)) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return null
        val width = (root["width"] as? JsonPrimitive)?.intOrNull ?: return null
        val height = (root["height"] as? JsonPrimitive)?.intOrNull ?: return null
        val sprites = (root["sprites"] as? JsonArray).orEmpty().mapNotNull { element ->
            val s = element as? JsonObject ?: return@mapNotNull null
            fun num(key: String) = (s[key] as? JsonPrimitive)?.doubleOrNull
            SheetSprite(
                name = (s["name"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull,
                x = num("x") ?: return@mapNotNull null,
                y = num("y") ?: return@mapNotNull null,
                w = num("w") ?: return@mapNotNull null,
                h = num("h") ?: return@mapNotNull null,
            )
        }
        return SpriteSheet(width, height, sprites)
    }

    /**
     * Up to [limit] sprites to show: note heads (`#NOTE_HEAD…`) first, then other names containing "note", then the
     * rest, each group in the data's order. Sprites under 8 texels a side, reaching outside the atlas, or repeating a
     * rectangle already picked are left out.
     */
    fun pick(sheet: SpriteSheet, limit: Int = 5): List<SheetSprite> = sheet.sprites
        .filter { s ->
            s.w >= MIN_SIDE && s.h >= MIN_SIDE && s.x >= 0 && s.y >= 0 &&
                s.x + s.w <= sheet.width && s.y + s.h <= sheet.height
        }
        .sortedBy { s ->
            val name = s.name.orEmpty()
            when {
                name.startsWith("#NOTE_HEAD") -> 0
                name.contains("note", ignoreCase = true) -> 1
                else -> 2
            }
        }
        .distinctBy { listOf(it.x, it.y, it.w, it.h) }
        .take(limit)

    /** [sprite]'s pixels in a texture of the given size (it can differ from the data's size), rounded outward. */
    fun textureRect(sprite: SheetSprite, sheet: SpriteSheet, textureWidth: Int, textureHeight: Int): TexelRect {
        val sx = if (sheet.width > 0) textureWidth.toDouble() / sheet.width else 1.0
        val sy = if (sheet.height > 0) textureHeight.toDouble() / sheet.height else 1.0
        return TexelRect(
            left = floor(sprite.x * sx).toInt().coerceIn(0, textureWidth),
            top = floor(sprite.y * sy).toInt().coerceIn(0, textureHeight),
            right = ceil((sprite.x + sprite.w) * sx).toInt().coerceIn(0, textureWidth),
            bottom = ceil((sprite.y + sprite.h) * sy).toInt().coerceIn(0, textureHeight),
        )
    }

    /** The smallest power-of-two decode sample size that brings an image [width] pixels wide down to [target] or less. */
    fun sampleSize(width: Int, target: Int = 512): Int {
        var size = 1
        while (width.toDouble() / size > target) size *= 2
        return size
    }
}
