package my.fumui.sairitsu.text

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class LocalizerTest {
    private val clap = "##LOCALIZE:{\"en\":\"Execution Clap\",\"ja\":\"処刑拍手\"}"

    @Test
    fun localizePicksLanguageThenEnglish() {
        assertEquals("処刑拍手", Localizer("ja").text(clap))
        assertEquals("Execution Clap", Localizer("fr").text(clap))
        assertEquals("처형", Localizer("fr").text("##LOCALIZE:{\"ko\":\"처형\"}"))
    }

    @Test
    fun localizeKeepsTextAfterTheMap() {
        // Servers append plain text after the map, e.g. a link on the next line.
        val raw = "##LOCALIZE:{\"en\":\"Custom charts {and more}\",\"ja\":\"創作譜面\"}\nhttps://discord.gg/x"
        assertEquals("Custom charts {and more}\nhttps://discord.gg/x", Localizer("en").text(raw))
        assertEquals("創作譜面\nhttps://discord.gg/x", Localizer("ja").text(raw))
        assertEquals("a \"q\" b tail", Localizer("en").text("##LOCALIZE:{\"en\":\"a \\\"q\\\" b\"} tail"))
    }

    @Test
    fun escapeShowsTheArgumentAsIs() {
        // `##:` escapes its argument: user names and other text that must not be read as keys.
        assertEquals("Astro Rice#1141129", Localizer("en").text("##:Astro Rice#1141129"))
        assertEquals("#TITLE", Localizer("en").text("##:#TITLE"))
    }

    @Test
    fun eachLineIsResolvedOnItsOwn() {
        val raw = "##:Astro Rice#1\n##LOCALIZE:{\"en\":\"Hi\",\"ja\":\"やあ\"}\n#LONG_VERSION\nplain"
        assertEquals("Astro Rice#1\nやあ\nLong version\nplain", Localizer("ja").text(raw))
    }

    @Test
    fun localizeFallbacks() {
        assertEquals("{not json", Localizer("en").text("##LOCALIZE:{not json"))
        assertEquals("", Localizer("en").text("##LOCALIZE:{}"))
    }

    @Test
    fun keysAndComposites() {
        val localizer = Localizer("en")
        assertEquals("Keywords", localizer.text("#KEYWORDS"))
        assertEquals("Include tag", localizer.text("#INCLUDE_TAG"))
        assertEquals("Grade + Accuracy score", localizer.text("#GRADE:#SEPARATOR_PLUS:#ACCURACY_SCORE"))
        assertEquals("Plain title", localizer.text("Plain title"))
    }

    @Test
    fun userTextStartingWithHashUntouched() {
        val localizer = Localizer("en")
        assertEquals("#Hashtag Love", localizer.text("#Hashtag Love"))
        assertEquals("#Title: Remix", localizer.text("#Title: Remix"))
        assertEquals("#あくあ色ぱれっと", localizer.text("#あくあ色ぱれっと"))
        assertEquals("Long version", localizer.text("#LONG_VERSION"))
    }

    @Test
    fun relativeTime() {
        val now = 1_000_000_000_000L
        val localizer = Localizer("en") { now }
        fun ago(ms: Long) = localizer.text("##TIME_RELATIVE:${now - ms}")
        assertEquals("just now", ago(30_000))
        assertEquals("1 minute ago", ago(90_000))
        assertEquals("3 days ago", ago(3 * 86_400_000L))
        assertEquals("1 year ago", ago(400 * 86_400_000L))
        assertEquals("just now", ago(-60_000))
    }

    @Test
    fun serverLanguageMapping() {
        assertEquals("zhs", Localizer.serverLanguage(Locale.SIMPLIFIED_CHINESE))
        assertEquals("zht", Localizer.serverLanguage(Locale.TRADITIONAL_CHINESE))
        assertEquals("ja", Localizer.serverLanguage(Locale.JAPAN))
    }
}
