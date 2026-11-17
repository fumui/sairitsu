#include "data/resources.h"

#include <fstream>
#include <iterator>
#include <new>
#include <stdexcept>

#include "audio/decode.h"
#include "audio/platform_decode.h"
#include "data/gzip.h"
#include "data/json.h"
#include "data/zip.h"

namespace sr::data {

namespace {

constexpr const char* kNames[kResourceKindCount] = {
    "engine.watchData", "engine.playData", "engine.configuration", "engine.rom",
    "skin.data", "skin.texture", "effect.data", "effect.audio",
    "particle.data", "particle.texture", "background.image", "background.data",
    "background.configuration", "level.data", "level.bgm",
};

std::vector<uint8_t> read_file(const std::map<ResourceKind, std::filesystem::path>& files,
                               ResourceKind kind) {
    const auto it = files.find(kind);
    if (it == files.end()) {
        throw LoadError(std::string(resource_kind_name(kind)) + ": no file given");
    }
    std::ifstream in(it->second, std::ios::binary);
    if (!in) {
        throw LoadError(std::string(resource_kind_name(kind)) + ": cannot read " +
                        it->second.string());
    }
    return std::vector<uint8_t>(std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>());
}

// Server JSON resources are gzip-compressed; plain JSON is accepted too.
std::string read_json(const std::map<ResourceKind, std::filesystem::path>& files,
                      ResourceKind kind) {
    std::vector<uint8_t> bytes = read_file(files, kind);
    try {
        if (bytes.size() >= 2 && bytes[0] == 0x1f && bytes[1] == 0x8b) bytes = gunzip(bytes);
    } catch (const LoadError& e) {
        throw LoadError(std::string(resource_kind_name(kind)) + ": " + e.what());
    }
    return std::string(reinterpret_cast<const char*>(bytes.data()), bytes.size());
}

Image read_image(const std::map<ResourceKind, std::filesystem::path>& files, ResourceKind kind) {
    try {
        return decode_png(read_file(files, kind));
    } catch (const LoadError& e) {
        throw LoadError(std::string(resource_kind_name(kind)) + ": " + e.what());
    }
}

}  // namespace

const char* resource_kind_name(ResourceKind kind) {
    const int32_t code = static_cast<int32_t>(kind);
    return code >= 0 && code < kResourceKindCount ? kNames[code] : "unknown";
}

std::optional<ResourceKind> resource_kind_from_name(const std::string& name) {
    for (int32_t i = 0; i < kResourceKindCount; ++i) {
        if (name == kNames[i]) return static_cast<ResourceKind>(i);
    }
    return std::nullopt;
}

namespace {

// Runs one load step, turning allocation failures into a LoadError naming the kind.
template <typename F>
auto guarded(ResourceKind kind, F&& step) -> decltype(step()) {
    try {
        return step();
    } catch (const std::bad_alloc&) {
        throw LoadError(std::string(resource_kind_name(kind)) + ": out of memory");
    } catch (const std::length_error&) {
        throw LoadError(std::string(resource_kind_name(kind)) + ": size is too large");
    }
}

}  // namespace

LoadedLevel load_level(const std::map<ResourceKind, std::filesystem::path>& files) {
    using K = ResourceKind;
    LoadedLevel out;
    out.watch = guarded(K::EngineWatchData, [&] {
        return parse_engine_mode_data(read_json(files, K::EngineWatchData), Mode::Watch);
    });
    out.play = guarded(K::EnginePlayData, [&] {
        return parse_engine_mode_data(read_json(files, K::EnginePlayData), Mode::Play);
    });
    out.configuration = guarded(K::EngineConfiguration, [&] {
        return parse_engine_configuration(read_json(files, K::EngineConfiguration));
    });
    out.rom = guarded(K::EngineRom, [&] {
        // An engine item's rom is optional; without one the rom block is empty.
        if (!files.count(K::EngineRom)) return std::vector<float>{};
        try {
            return parse_rom(gunzip(read_file(files, K::EngineRom)));
        } catch (const LoadError& e) {
            const std::string what = e.what();
            throw LoadError(what.rfind("engine.rom", 0) == 0 ? what : "engine.rom: " + what);
        }
    });
    out.skin = guarded(K::SkinData, [&] { return parse_skin_data(read_json(files, K::SkinData)); });
    out.skin_texture = guarded(K::SkinTexture, [&] { return read_image(files, K::SkinTexture); });
    out.effect =
        guarded(K::EffectData, [&] { return parse_effect_data(read_json(files, K::EffectData)); });
    out.effect_clips = guarded(K::EffectAudio, [&] {
        try {
            return unzip(read_file(files, K::EffectAudio));
        } catch (const LoadError& e) {
            throw LoadError(std::string("effect.audio: ") + e.what());
        }
    });
    for (const EffectClip& clip : out.effect.clips) {
        if (!out.effect_clips.count(clip.filename)) {
            throw LoadError("effect.audio: clip '" + clip.name + "' file '" + clip.filename +
                            "' is missing from the archive");
        }
    }
    for (const EffectClip& clip : out.effect.clips) {
        if (out.effect_pcm.count(clip.filename)) continue;
        out.effect_pcm[clip.filename] = guarded(K::EffectAudio, [&] {
            try {
                return audio::decode_audio(out.effect_clips.at(clip.filename));
            } catch (const LoadError& e) {
                throw LoadError("effect.audio: clip '" + clip.name + "': " + e.what());
            }
        });
    }
    out.particle = guarded(K::ParticleData,
                           [&] { return parse_particle_data(read_json(files, K::ParticleData)); });
    out.particle_texture =
        guarded(K::ParticleTexture, [&] { return read_image(files, K::ParticleTexture); });
    out.background_image =
        guarded(K::BackgroundImage, [&] { return read_image(files, K::BackgroundImage); });
    out.background = guarded(K::BackgroundData, [&] {
        return parse_background_data(read_json(files, K::BackgroundData));
    });
    out.background_configuration = guarded(K::BackgroundConfiguration, [&] {
        return parse_background_configuration(read_json(files, K::BackgroundConfiguration));
    });
    out.level =
        guarded(K::LevelData, [&] { return parse_level_data(read_json(files, K::LevelData)); });
    out.bgm = guarded(K::LevelBgm, [&] { return read_file(files, K::LevelBgm); });
    out.bgm_pcm = guarded(K::LevelBgm, [&] {
        try {
            return audio::decode_audio(out.bgm);
        } catch (const LoadError& e) {
            // Formats our decoder does not read (M4A / AAC) go to the
            // platform's decoders where there are some (Android). Otherwise the decoder's error stands.
            std::string platform_error;
            if (auto pcm = audio::platform_decode(files.at(K::LevelBgm).string(), platform_error)) return *pcm;
            throw LoadError(std::string("level.bgm: ") + e.what());
        }
    });
    return out;
}

}  // namespace sr::data
