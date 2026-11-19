package my.fumui.sairitsu.gameplay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Still pictures of saved items, made from their saved files: a background's image, a strip of a skin's or particle
 * set's sprites. SFX have none. Kept in memory by item id and files, so a re-saved item gets a fresh picture.
 */
class ItemPictures(private val library: ItemLibrary) {
    /** Sized in bytes, so a few large strips can't hold more than [CACHE_BYTES]. */
    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** At most two decodes at once: Settings and Level options ask for many pictures together. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val decoding = Dispatchers.IO.limitedParallelism(2)

    /** [item]'s picture; null when its kind has none or its files can't make one. */
    suspend fun picture(item: SavedItem): ImageBitmap? {
        val key = item.id + "|" + item.files.values.sorted().joinToString(",")
        cache.get(key)?.let { return it.asImageBitmap() }
        return withContext(decoding) {
            cache.get(key)?.let { return@withContext it }
            try {
                make(item)?.also { cache.put(key, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
        }?.asImageBitmap()
    }

    private fun make(item: SavedItem): Bitmap? = when (item.kind) {
        ItemKind.Background -> library.file(item, ResourceKind.BACKGROUND_IMAGE)?.let(::background)
        ItemKind.Skin -> strip(item, ResourceKind.SKIN_DATA, ResourceKind.SKIN_TEXTURE)
        ItemKind.Particles -> strip(item, ResourceKind.PARTICLE_DATA, ResourceKind.PARTICLE_TEXTURE)
        ItemKind.Sfx -> null
    }

    private fun background(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        return BitmapFactory.decodeFile(
            file.path,
            BitmapFactory.Options().apply { inSampleSize = SpriteSheets.sampleSize(bounds.outWidth) },
        )
    }

    /**
     * Up to [SPRITES] sprites, trimmed of their clear edges and scaled to [STRIP_HEIGHT] px tall, in the grid that
     * draws them largest in a tile (flat note heads stack in one column, square particles form rows).
     */
    private fun strip(item: SavedItem, data: ResourceKind, texture: ResourceKind): Bitmap? {
        val sheet = library.file(item, data)?.readBytes()?.let(SpriteSheets::parse) ?: return null
        val sprites = SpriteSheets.pick(sheet, SPRITES).ifEmpty { return null }
        val textureFile = library.file(item, texture) ?: return null
        val decoder = regionDecoder(textureFile)
        val parts = try {
            sprites.mapNotNull { sprite ->
                val r = SpriteSheets.textureRect(sprite, sheet, decoder.width, decoder.height)
                if (r.right - r.left <= 0 || r.bottom - r.top <= 0) return@mapNotNull null
                val sampled = BitmapFactory.Options().apply { inSampleSize = SpriteSheets.regionSampleSize(r.bottom - r.top, STRIP_HEIGHT) }
                val region = decoder.decodeRegion(Rect(r.left, r.top, r.right, r.bottom), sampled) ?: return@mapNotNull null
                trimmed(region)?.let(::scaled)
            }
        } finally {
            decoder.recycle()
        }
        if (parts.isEmpty()) return null
        val columns = SpriteSheets.columns(parts.map { it.width }, STRIP_HEIGHT, GAP, TILE_ASPECT)
        val rows = (parts.size + columns - 1) / columns
        val cell = parts.maxOf { it.width }
        val out = Bitmap.createBitmap(
            columns * cell + (columns - 1) * GAP, rows * STRIP_HEIGHT + (rows - 1) * GAP, Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        parts.forEachIndexed { i, part ->
            val x = (i % columns) * (cell + GAP) + (cell - part.width) / 2f
            val y = (i / columns) * (STRIP_HEIGHT + GAP).toFloat()
            canvas.drawBitmap(part, x, y, paint)
            part.recycle()
        }
        return out
    }

    /** [bitmap] cropped to its visible pixels (recycling the original when cropped); null when fully clear. */
    private fun trimmed(bitmap: Bitmap): Bitmap? {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val b = SpriteSheets.opaqueBounds(pixels, bitmap.width, bitmap.height)
        if (b == null) {
            bitmap.recycle()
            return null
        }
        if (b.left == 0 && b.top == 0 && b.right == bitmap.width && b.bottom == bitmap.height) return bitmap
        return Bitmap.createBitmap(bitmap, b.left, b.top, b.right - b.left, b.bottom - b.top).also { bitmap.recycle() }
    }

    /** [bitmap] scaled to [STRIP_HEIGHT] px tall, at most [MAX_PART_WIDTH] px wide (recycling the original). */
    private fun scaled(bitmap: Bitmap): Bitmap {
        val width = (bitmap.width * STRIP_HEIGHT / bitmap.height.toFloat()).toInt().coerceIn(1, MAX_PART_WIDTH)
        return Bitmap.createScaledBitmap(bitmap, width, STRIP_HEIGHT, true).also { if (it !== bitmap) bitmap.recycle() }
    }

    @Suppress("DEPRECATION")
    private fun regionDecoder(file: File): BitmapRegionDecoder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BitmapRegionDecoder.newInstance(file.path)
        } else {
            BitmapRegionDecoder.newInstance(file.path, false)
        } ?: error("undecodable texture")

    private companion object {
        const val STRIP_HEIGHT = 128
        const val GAP = 8

        /** Two stacked note heads (or two particles side by side) make a wide picture that fills a tile's width. */
        const val SPRITES = 2
        const val MAX_PART_WIDTH = 1280
        const val CACHE_BYTES = 24 * 1024 * 1024

        /** The Level options tile's picture (144×84 dp); the Settings row's 96×56 dp is about the same. */
        const val TILE_ASPECT = 144.0 / 84
    }
}
