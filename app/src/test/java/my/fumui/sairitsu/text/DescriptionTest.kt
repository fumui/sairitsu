package my.fumui.sairitsu.text

import my.fumui.sairitsu.text.DescriptionBlock.Field
import my.fumui.sairitsu.text.DescriptionBlock.Paragraph
import org.junit.Assert.assertEquals
import org.junit.Test

class DescriptionTest {
    private val en = Localizer("en")

    @Test
    fun sbugaFieldsAndKeywordLine() {
        val raw = """
            #AUTHOR:#SEPARATOR_COLON:Ryo Haruka

            #LYRICIST:#SEPARATOR_COLON:遼遼
            #COMPOSER:#SEPARATOR_COLON:遼遼

            #LENGTH:#SEPARATOR_COLON: 2:03
            #COMBO:#SEPARATOR_COLON: 727

            プレデター ぷれでたー puredetaa pu redetaa
        """.trimIndent()
        assertEquals(
            listOf(
                Field("Author", "Ryo Haruka"),
                Field("Lyricist", "遼遼"),
                Field("Composer", "遼遼"),
                Field("Length", "2:03"),
                Field("Combo", "727"),
            ),
            parseDescription(raw, en, title = "プレデター"),
        )
    }

    @Test
    fun localizedValuesAndHashColonKeywords() {
        val raw = "#LYRICIST:#SEPARATOR_COLON:##LOCALIZE:{\"en\":\"Kota\",\"ja\":\"コータ\"}\n\n##:Lyrical Monster りりかる"
        assertEquals(listOf(Field("Lyricist", "Kota")), parseDescription(raw, en, title = "Lyrical Monster"))
        assertEquals(listOf(Field("Lyricist", "コータ")), parseDescription(raw, Localizer("ja")))
    }

    @Test
    fun plainTextStaysParagraphs() {
        val raw = "First line\nsecond line\n\n#Hashtag talk: fun"
        assertEquals(
            listOf(Paragraph("First line\nsecond line"), Paragraph("#Hashtag talk: fun")),
            parseDescription(raw, en),
        )
    }

    @Test
    fun titleParagraphKeptWhenNotTheLastOrMultiLine() {
        val raw = "Song story here\nmore"
        assertEquals(listOf(Paragraph("Song story here\nmore")), parseDescription(raw, en, title = "Song"))
    }
}
