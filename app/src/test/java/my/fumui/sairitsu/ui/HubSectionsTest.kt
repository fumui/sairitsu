package my.fumui.sairitsu.ui

import my.fumui.sairitsu.data.model.SearchOption
import my.fumui.sairitsu.data.model.ServerConfiguration
import my.fumui.sairitsu.data.model.ServerInfo
import my.fumui.sairitsu.data.model.ServerInfoButton
import my.fumui.sairitsu.text.Localizer
import org.junit.Assert.assertEquals
import org.junit.Test

class HubSectionsTest {
    private val l = Localizer("en")
    private val config = ServerConfiguration(listOf(SearchOption("q", "Q", "text")))

    private fun info(vararg types: String, configuration: ServerConfiguration? = null) =
        ServerInfo("T", buttons = types.map { ServerInfoButton(it) }, configuration = configuration)

    @Test
    fun fixedOrderRegardlessOfServerOrder() {
        val sections = hubButtons(info("configuration", "skin", "post", "level", configuration = config), l).map { it.section }
        assertEquals(listOf(HubSection.Levels, HubSection.Posts, HubSection.Skins, HubSection.Configuration), sections)
    }

    @Test
    fun allSevenInOrder() {
        val types = listOf("configuration", "particle", "effect", "background", "skin", "post", "level").toTypedArray()
        val buttons = hubButtons(info(*types, configuration = config), l)
        assertEquals(
            listOf(
                HubSection.Levels, HubSection.Posts, HubSection.Skins, HubSection.Backgrounds,
                HubSection.Sfx, HubSection.Particles, HubSection.Configuration,
            ),
            buttons.map { it.section },
        )
        assertEquals("SFX", buttons.first { it.section == HubSection.Sfx }.label)
    }

    @Test
    fun unsupportedTypesAreIgnored() {
        assertEquals(listOf(HubSection.Levels), hubButtons(info("room", "user", "level", "replay"), l).map { it.section })
    }

    @Test
    fun serverTitleOverridesLabel() {
        val info = ServerInfo("T", buttons = listOf(ServerInfoButton("level", "Charts"), ServerInfoButton("post")))
        assertEquals(listOf("Charts", "Posts"), hubButtons(info, l).map { it.label })
    }

    @Test
    fun configurationHiddenWithoutOptions() {
        assertEquals(emptyList<HubButton>(), hubButtons(info("configuration"), l))
        assertEquals(emptyList<HubButton>(), hubButtons(info("configuration", configuration = ServerConfiguration(emptyList())), l))
    }

    @Test
    fun playlistsBetweenParticlesAndConfiguration() {
        val buttons = hubButtons(info("configuration", "playlist", "particle", "level", configuration = config), l)
        assertEquals(
            listOf(HubSection.Levels, HubSection.Particles, HubSection.Playlists, HubSection.Configuration),
            buttons.map { it.section },
        )
        assertEquals("Playlists", buttons.first { it.section == HubSection.Playlists }.label)
    }

    @Test
    fun unavailableInfoShowsLevelsOnly() {
        assertEquals(listOf(HubButton(HubSection.Levels, "Levels")), hubButtons(null, l))
    }
}
