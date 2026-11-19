package my.fumui.sairitsu.gameplay

import my.fumui.sairitsu.gameplay.ResourceKind.BACKGROUND_CONFIGURATION
import my.fumui.sairitsu.gameplay.ResourceKind.BACKGROUND_DATA
import my.fumui.sairitsu.gameplay.ResourceKind.BACKGROUND_IMAGE
import my.fumui.sairitsu.gameplay.ResourceKind.EFFECT_AUDIO
import my.fumui.sairitsu.gameplay.ResourceKind.EFFECT_DATA
import my.fumui.sairitsu.gameplay.ResourceKind.PARTICLE_DATA
import my.fumui.sairitsu.gameplay.ResourceKind.PARTICLE_TEXTURE
import my.fumui.sairitsu.gameplay.ResourceKind.SKIN_DATA
import my.fumui.sairitsu.gameplay.ResourceKind.SKIN_TEXTURE

/**
 * The kinds of look a level can be given from a saved item. [wireType]: the item type in server links;
 * [listType]: its list endpoint; [resources]: the level resources it replaces.
 */
enum class ItemKind(
    val wireType: String,
    val listType: String,
    val label: String,
    val plural: String,
    val resources: List<ResourceKind>,
) {
    Skin("skin", "skins", "skin", "skins", listOf(SKIN_DATA, SKIN_TEXTURE)),
    Background(
        "background", "backgrounds", "background", "backgrounds",
        listOf(BACKGROUND_IMAGE, BACKGROUND_DATA, BACKGROUND_CONFIGURATION),
    ),
    Sfx("effect", "effects", "SFX", "SFX", listOf(EFFECT_DATA, EFFECT_AUDIO)),
    Particles("particle", "particles", "particles", "particles", listOf(PARTICLE_DATA, PARTICLE_TEXTURE));

    companion object {
        /** The kind for a server item type such as `effect`; null for types that are not a look (`room`). */
        fun ofWire(wire: String): ItemKind? = entries.firstOrNull { it.wireType == wire }
    }
}
