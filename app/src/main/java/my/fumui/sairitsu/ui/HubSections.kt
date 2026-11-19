package my.fumui.sairitsu.ui

import my.fumui.sairitsu.data.model.ServerInfo
import my.fumui.sairitsu.gameplay.ItemKind
import my.fumui.sairitsu.text.Localizer

/** The sections a server hub can show, in display order. */
enum class HubSection(val wireType: String, val defaultLabel: String) {
    Levels("level", "Levels"),
    Posts("post", "Posts"),
    Skins("skin", "Skins"),
    Backgrounds("background", "Backgrounds"),
    Sfx("effect", "SFX"),
    Particles("particle", "Particles"),
    Configuration("configuration", "Configuration"),
}

/** The item kind a section lists; null for Levels, Posts and Configuration. */
val HubSection.itemKind: ItemKind?
    get() = when (this) {
        HubSection.Skins -> ItemKind.Skin
        HubSection.Backgrounds -> ItemKind.Background
        HubSection.Sfx -> ItemKind.Sfx
        HubSection.Particles -> ItemKind.Particles
        else -> null
    }

data class HubButton(val section: HubSection, val label: String)

/** [info] null means it couldn't be loaded: Levels alone. */
fun hubButtons(info: ServerInfo?, localizer: Localizer): List<HubButton> {
    if (info == null) return listOf(HubButton(HubSection.Levels, HubSection.Levels.defaultLabel))
    val hasOptions = info.configuration?.options?.isNotEmpty() == true
    return HubSection.entries.mapNotNull { section ->
        val button = info.buttons.firstOrNull { it.type == section.wireType } ?: return@mapNotNull null
        if (section == HubSection.Configuration && !hasOptions) return@mapNotNull null
        val title = button.title?.let(localizer::text)?.takeIf { it.isNotBlank() }
        HubButton(section, title ?: section.defaultLabel)
    }
}

