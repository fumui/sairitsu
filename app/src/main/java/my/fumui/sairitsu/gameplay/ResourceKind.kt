package my.fumui.sairitsu.gameplay

/**
 * The fifteen resources of a level. Order and `ordinal` match C++ `sr::data::ResourceKind`. An [optional] one may be
 * absent from the level details (the runtime then uses an empty one).
 */
enum class ResourceKind(val wireName: String, val optional: Boolean = false) {
    ENGINE_WATCH_DATA("engine.watchData"),
    ENGINE_PLAY_DATA("engine.playData"),
    ENGINE_CONFIGURATION("engine.configuration"),
    ENGINE_ROM("engine.rom", optional = true),
    SKIN_DATA("skin.data"),
    SKIN_TEXTURE("skin.texture"),
    EFFECT_DATA("effect.data"),
    EFFECT_AUDIO("effect.audio"),
    PARTICLE_DATA("particle.data"),
    PARTICLE_TEXTURE("particle.texture"),
    BACKGROUND_IMAGE("background.image"),
    BACKGROUND_DATA("background.data"),
    BACKGROUND_CONFIGURATION("background.configuration"),
    LEVEL_DATA("level.data"),
    LEVEL_BGM("level.bgm"),
}
