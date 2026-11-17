#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <vector>

#include "data/particle_data.h"
#include "modes/presentation.h"
#include "render/geometry.h"

namespace sr::particle {

// The particle random source: a 32-bit xorshift (13, 17, 5) whose constructor steps once.
// next() takes the state before its step and returns its top 23 bits as a float in [0, 1). It is seeded with
// kParticleSeed, so a level gives the same particles on every run.
class ParticleRandom {
public:
    explicit ParticleRandom(uint32_t seed);
    float next();

private:
    uint32_t next_state();
    uint32_t state_;
};

// The seed every session uses (live playback and render-at-time alike), so the same level gives the same particles.
// Non-zero: a zero state never leaves zero.
inline constexpr uint32_t kParticleSeed = 0x5eed0001u;

// Particle effect instances of one session.
//
// - Ids: an instance is keyed by the id SpawnParticleEffect returned (1 + entity index * 256 + counter % 256). A Spawn never replaces: two instances may share an id, and Move and Destroy reach every one of them.
// - Spawn draws r1..r8 once for the effect's transform, which maps the call's quad (terms x1..y4, c, and those r
//   terms) to the instance's quad. Then, for every group and each of its `count` copies, it draws another r1..r8
//   (even for a group with no particles) and evaluates each particle's from and to with them and the call's quad.
//   Those values are fixed at spawn. sinrN and cosrN are sin and cos of 2 pi rN (sr::fm).
// - Move draws a new r1..r8 and maps its quad through the transform again for every instance with that id (whether
//   or not one exists). Destroy removes them.
// - Time: a particle starts at spawn time + start * D and lasts duration * D (D: the call's duration). Its local
//   time is now - its start; a looped particle's local time is taken modulo D once it reaches D. It is drawn from
//   its start while local / its duration is at most 1, each property at from + (to - from) * ease(progress). A
//   non-looped particle ends once now reaches its end; an instance ends when its last particle does.
// - Place: local space is [-1, 1] across the quad; x, y are the centre and w, h half sizes; r turns the corner
//   offsets counterclockwise in radians. A corner (u, v) = (x + offset) / 2 + 1/2 is mapped bilinearly into the quad
//   (no clamping: values outside [0, 1] extrapolate), then through Runtime Particle Transform.
// - Colour: the item's colour (render::parse_color; white when it does not parse) multiplies the texel; its alpha
//   times a is the sprite's alpha (unclamped).
// - Order: instances in spawn order, then groups, copies and particles in data order.
class ParticleSystem {
public:
    // `data` must outlive the system. ParticleCmd::effect is already an index into data.effects (SessionHost resolved
    // it through ResourceMaps::effect), so no effect map is taken.
    ParticleSystem(const data::ParticleData& data, uint32_t seed);
    ParticleSystem(data::ParticleData&&, uint32_t) = delete;  // would dangle

    void apply(const modes::ParticleCmd& cmd, float now);
    // Ends the non-looped particles whose end is reached (and instances left with none), then appends every visible
    // particle.
    void emit(float now, const float particle_transform[16], std::vector<render::SpriteQuad>& out);
    size_t live() const { return instances_.size(); }

private:
    struct Particle {
        int32_t group, item;
        float start_time, duration, loop_duration;
        float from[6], to[6];  // x, y, w, h, r, a
    };
    struct Instance {
        float id;
        int32_t effect;
        bool looped;
        float quad_x[4], quad_y[4];  // bl, tl, tr, br after the effect's transform
        std::vector<Particle> particles;
    };

    // Per item, parsed once: its colour (0 to 1) and the ease of x, y, w, h, r, a (vm::EaseKind).
    struct ItemInfo {
        std::array<float, 4> color;
        std::array<uint8_t, 6> ease;
    };

    // Sets c = 1 and x1..y4 from the call, then draws r1..r8 (r, sin r, cos r).
    void make_terms(const modes::ParticleCmd& cmd, float terms[data::kParticleTerms]);
    // Maps the call's quad (in `terms`) through the instance's effect transform.
    void map_quad(Instance& instance, const float terms[data::kParticleTerms]) const;

    const data::ParticleData& data_;
    ParticleRandom random_;
    std::vector<Instance> instances_;
    std::vector<std::vector<std::vector<ItemInfo>>> items_;  // [effect][group][particle]
};

}  // namespace sr::particle
