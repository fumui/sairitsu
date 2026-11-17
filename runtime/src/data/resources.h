#pragma once

#include <cstdint>
#include <filesystem>
#include <map>
#include <optional>
#include <string>
#include <vector>

#include "audio/decode.h"
#include "data/background.h"
#include "data/configuration.h"
#include "data/effect_data.h"
#include "data/engine_data.h"
#include "data/level_data.h"
#include "data/load_error.h"
#include "data/particle_data.h"
#include "data/png.h"
#include "data/skin.h"

namespace sr::data {

// Codes 0-14, in this order, are shared with Python and Kotlin.
enum class ResourceKind : int32_t {
    EngineWatchData, EnginePlayData, EngineConfiguration, EngineRom,
    SkinData, SkinTexture, EffectData, EffectAudio, ParticleData, ParticleTexture,
    BackgroundImage, BackgroundData, BackgroundConfiguration, LevelData, LevelBgm
};

inline constexpr int kResourceKindCount = 15;

// Wire name of a kind, e.g. "engine.watchData".
const char* resource_kind_name(ResourceKind kind);
// Inverse of resource_kind_name.
std::optional<ResourceKind> resource_kind_from_name(const std::string& name);

struct LoadedLevel {
    EngineModeData watch;
    EngineModeData play;
    EngineConfiguration configuration;
    std::vector<float> rom;
    SkinData skin;
    Image skin_texture;
    EffectData effect;
    std::map<std::string, std::vector<uint8_t>> effect_clips;  // zip entry name -> MP3 bytes
    ParticleData particle;
    Image particle_texture;
    Image background_image;
    BackgroundData background;
    BackgroundConfiguration background_configuration;
    LevelData level;
    std::vector<uint8_t> bgm;  // raw MP3 bytes
    // Decoded at the files' own rates (resample to the output rate with audio::resample).
    std::map<std::string, audio::Pcm> effect_pcm;  // zip entry name -> decoded clip
    audio::Pcm bgm_pcm;
};

// Reads and parses the resource files: all fifteen, though the engine rom may be absent (an
// empty rom). Throws LoadError for another missing kind,
// an unreadable file, or any parse failure. Every clip named by the effect data must
// be present in the effect audio archive.
LoadedLevel load_level(const std::map<ResourceKind, std::filesystem::path>& files);

}  // namespace sr::data
