#pragma once

#include <cstdint>
#include <string>
#include <string_view>
#include <vector>

namespace sr::data {

// A particle expression is a linear combination over one flat term vector:
//   [0]      c
//   [1..4]   x1..x4
//   [5..8]   y1..y4
//   [9..16]  r1..r8
//   [17..24] sinr1..sinr8
//   [25..32] cosr1..cosr8
// Terms absent from the JSON are 0.
inline constexpr int kParticleTerms = 33;

struct ParticleExpr {
    float term[kParticleTerms] = {};
};

// Output rows ordered x1,y1,x2,y2,x3,y3,x4,y4 (same as SkinTransform).
struct ParticleTransform {
    ParticleExpr row[8];
};

struct ParticleSprite {
    float x = 0, y = 0, w = 0, h = 0;
};

struct ParticleProperty {
    bool has_from = false, has_to = false;
    ParticleExpr from, to;
    std::string ease;  // empty when absent
};

struct ParticleItem {
    int32_t sprite = 0;
    std::string color;
    float start = 0, duration = 0;
    ParticleProperty x, y, w, h, r, a;
};

struct ParticleGroup {
    int32_t count = 0;
    std::vector<ParticleItem> particles;
};

struct ParticleEffect {
    std::string name;
    ParticleTransform transform;
    std::vector<ParticleGroup> groups;
};

struct ParticleData {
    int32_t width = 0;
    int32_t height = 0;
    bool interpolation = false;
    std::vector<ParticleSprite> sprites;
    std::vector<ParticleEffect> effects;
};

// `json` is the decompressed particle data. Throws LoadError.
ParticleData parse_particle_data(std::string_view json);

}  // namespace sr::data
