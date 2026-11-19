package my.fumui.sairitsu.gameplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class SpriteSheetTest {
    private fun gzip(text: String): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(text.toByteArray()) } }.toByteArray()

    private fun named(name: String?, x: Double, y: Double = 0.0, w: Double = 16.0, h: Double = 16.0) =
        SheetSprite(name, x, y, w, h)

    @Test
    fun parsePlainAndGzip() {
        val json = """{"width":256,"height":128,"interpolation":true,"sprites":[
            {"name":"#NOTE_HEAD_RED","x":0,"y":0,"w":64,"h":32,"transform":{}}]}"""
        val expected = SpriteSheet(256, 128, listOf(SheetSprite("#NOTE_HEAD_RED", 0.0, 0.0, 64.0, 32.0)))
        assertEquals(expected, SpriteSheets.parse(json.toByteArray()))
        assertEquals(expected, SpriteSheets.parse(gzip(json)))
    }

    @Test
    fun parseParticleSpritesHaveNoName() {
        val sheet = SpriteSheets.parse("""{"width":64,"height":64,"sprites":[{"x":0,"y":0,"w":32,"h":32}],"effects":[]}""".toByteArray())!!
        assertEquals(listOf(SheetSprite(null, 0.0, 0.0, 32.0, 32.0)), sheet.sprites)
    }

    @Test
    fun parseMissingSpritesIsEmpty() {
        assertEquals(SpriteSheet(8, 8, emptyList()), SpriteSheets.parse("""{"width":8,"height":8}""".toByteArray()))
    }

    @Test
    fun parseRejectsGarbage() {
        assertNull(SpriteSheets.parse("not json".toByteArray()))
        assertNull(SpriteSheets.parse(ByteArray(0)))
        assertNull(SpriteSheets.parse("[1,2]".toByteArray()))
    }

    @Test
    fun pickOrdersNoteHeadsThenNotesThenRest() {
        val sheet = SpriteSheet(
            256, 256,
            listOf(
                named("stage", 0.0), named("Sekai Note Red", 20.0), named("#NOTE_HEAD_BLUE", 40.0),
                named("judgment", 60.0), named("#NOTE_HEAD_RED", 80.0),
            ),
        )
        assertEquals(
            listOf("#NOTE_HEAD_BLUE", "#NOTE_HEAD_RED", "Sekai Note Red", "stage", "judgment"),
            SpriteSheets.pick(sheet).map { it.name },
        )
    }

    @Test
    fun pickDropsTinyAndOutside() {
        val kept = SheetSprite("kept", 92.0, 92.0, 8.0, 8.0)
        val sheet = SpriteSheet(
            100, 100,
            listOf(
                SheetSprite("narrow", 0.0, 0.0, 7.0, 20.0),
                SheetSprite("flat", 10.0, 0.0, 20.0, 7.0),
                SheetSprite("right", 90.0, 30.0, 20.0, 20.0),
                SheetSprite("above", 30.0, -1.0, 20.0, 20.0),
                kept,
            ),
        )
        assertEquals(listOf(kept), SpriteSheets.pick(sheet))
    }

    @Test
    fun pickDropsDuplicateRects() {
        val sheet = SpriteSheet(100, 100, listOf(named("a", 0.0), named("b", 0.0)))
        assertEquals(listOf("a"), SpriteSheets.pick(sheet).map { it.name })
    }

    @Test
    fun pickRespectsLimit() {
        val sheet = SpriteSheet(1000, 100, List(9) { named("s$it", it * 20.0) })
        assertEquals(5, SpriteSheets.pick(sheet).size)
        assertEquals(2, SpriteSheets.pick(sheet, limit = 2).size)
    }

    @Test
    fun pickKeepsDataOrderWithoutNames() {
        val sprites = listOf(named(null, 40.0), named(null, 0.0), named(null, 20.0))
        assertEquals(sprites, SpriteSheets.pick(SpriteSheet(100, 100, sprites)))
    }

    @Test
    fun textureRectScalesToTexture() {
        val sheet = SpriteSheet(256, 128, emptyList())
        val sprite = SheetSprite(null, 10.0, 20.0, 30.0, 40.0)
        assertEquals(TexelRect(20, 40, 80, 120), SpriteSheets.textureRect(sprite, sheet, 512, 256))
        assertEquals(TexelRect(10, 20, 40, 60), SpriteSheets.textureRect(sprite, sheet, 256, 128))
    }

    @Test
    fun textureRectRoundsOutwardAndClamps() {
        val sheet = SpriteSheet(100, 100, emptyList())
        assertEquals(TexelRect(1, 2, 12, 100), SpriteSheets.textureRect(SheetSprite(null, 1.5, 2.2, 10.1, 97.9), sheet, 100, 100))
    }

    @Test
    fun sampleSizeKeepsWidthUnderTarget() {
        assertEquals(1, SpriteSheets.sampleSize(512))
        assertEquals(2, SpriteSheets.sampleSize(513))
        assertEquals(4, SpriteSheets.sampleSize(1920))
        assertEquals(8, SpriteSheets.sampleSize(4096))
    }
}
