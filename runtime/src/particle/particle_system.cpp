#include "particle/particle_system.h"

#include <algorithm>
#include <bit>
#include <cmath>
#include <string_view>

#include "render/background_layout.h"
#include "vm/easing.h"
#include "vm/fmath.h"

namespace sr::particle {

namespace {

constexpr float kTwoPi = 6.2831855f;  // float(2 pi); r * 2pi equals 2 * (r * pi) exactly in float

using vm::ease_kind;

// The order an expression is summed in: c, x1, y1, ..., x4, y4, then r, sin r, cos r of each index.
constexpr int kSumOrder[data::kParticleTerms] = {
    0, 1, 5, 2, 6, 3, 7, 4, 8,
    9, 17, 25, 10, 18, 26, 11, 19, 27, 12, 20, 28, 13, 21, 29, 14, 22, 30, 15, 23, 31, 16, 24, 32,
};

// The expression over the term vector (terms[0] is 1: the sum starts at c's coefficient).
float eval(const data::ParticleExpr& e, const float terms[data::kParticleTerms]) {
    float sum = e.term[0];
    for (int k = 1; k < data::kParticleTerms; ++k) sum = sum + e.term[kSumOrder[k]] * terms[kSumOrder[k]];
    return sum;
}

// A looped particle's local time: as is below the loop duration, else the remainder computed in double (the
// a float % does this), rounded back to float.
float wrap(float local, float loop) {
    if (std::fabs(local) < std::fabs(loop)) return local;
    const double l = local, d = loop;
    double q = l / d;
    if (std::fabs(q) < 0x1p52) q = double(int64_t(q));  // truncate toward zero (larger values are whole already)
    return float(l - q * d);
}

}  // namespace

ParticleRandom::ParticleRandom(uint32_t seed) : state_(seed) { next_state(); }

uint32_t ParticleRandom::next_state() {
    const uint32_t t = state_;
    state_ ^= state_ << 13;
    state_ ^= state_ >> 17;
    state_ ^= state_ << 5;
    return t;
}

float ParticleRandom::next() { return std::bit_cast<float>(0x3f800000u | (next_state() >> 9)) - 1.0f; }

ParticleSystem::ParticleSystem(const data::ParticleData& data, uint32_t seed) : data_(data), random_(seed) {
    items_.resize(data.effects.size());
    for (size_t e = 0; e < data.effects.size(); ++e) {
        for (const data::ParticleGroup& g : data.effects[e].groups) {
            std::vector<ItemInfo>& infos = items_[e].emplace_back();
            for (const data::ParticleItem& it : g.particles) {
                ItemInfo info;
                const std::array<uint8_t, 4> rgba =
                    render::parse_color(it.color).value_or(std::array<uint8_t, 4>{0xff, 0xff, 0xff, 0xff});
                for (int k = 0; k < 4; ++k) info.color[size_t(k)] = float(rgba[size_t(k)]) / 255.0f;
                const data::ParticleProperty* props[6] = {&it.x, &it.y, &it.w, &it.h, &it.r, &it.a};
                for (int k = 0; k < 6; ++k) info.ease[size_t(k)] = uint8_t(ease_kind(props[k]->ease));
                infos.push_back(info);
            }
        }
    }
}

void ParticleSystem::make_terms(const modes::ParticleCmd& cmd, float terms[data::kParticleTerms]) {
    terms[0] = 1;
    for (int i = 0; i < 4; ++i) {
        terms[1 + i] = cmd.x[i];
        terms[5 + i] = cmd.y[i];
    }
    for (int k = 0; k < 8; ++k) {
        const float r = random_.next();
        terms[9 + k] = r;
        terms[17 + k] = fm::sin(r * kTwoPi);
        terms[25 + k] = fm::cos(r * kTwoPi);
    }
}

void ParticleSystem::map_quad(Instance& instance, const float terms[data::kParticleTerms]) const {
    const data::ParticleTransform& t = data_.effects[size_t(instance.effect)].transform;
    for (int i = 0; i < 4; ++i) {
        instance.quad_x[i] = eval(t.row[2 * i], terms);
        instance.quad_y[i] = eval(t.row[2 * i + 1], terms);
    }
}

void ParticleSystem::apply(const modes::ParticleCmd& cmd, float now) {
    const float id = cmd.handle;  // compared as a float (any value, NaN never matches)
    float terms[data::kParticleTerms];
    // Spawn and Move draw r1..r8 whether or not the effect or the id exists; Destroy draws nothing.
    if (cmd.op != modes::ParticleCmd::Destroy) make_terms(cmd, terms);
    switch (cmd.op) {
        case modes::ParticleCmd::Spawn: {
            if (cmd.effect < 0 || size_t(cmd.effect) >= data_.effects.size()) return;
            Instance inst{id, cmd.effect, cmd.looped, {}, {}, {}};
            map_quad(inst, terms);
            const data::ParticleEffect& effect = data_.effects[size_t(cmd.effect)];
            const float d = cmd.duration;
            for (size_t g = 0; g < effect.groups.size(); ++g) {
                const data::ParticleGroup& group = effect.groups[g];
                for (int32_t n = 0; n < group.count; ++n) {
                    float copy_terms[data::kParticleTerms];
                    make_terms(cmd, copy_terms);
                    for (int i = 1; i <= 8; ++i) copy_terms[i] = 0;  // properties see the quad terms as 0
                    for (size_t p = 0; p < group.particles.size(); ++p) {
                        const data::ParticleItem& item = group.particles[p];
                        Particle pt{};
                        pt.group = int32_t(g);
                        pt.item = int32_t(p);
                        pt.start_time = d * item.start + now;
                        pt.duration = d * item.duration;
                        pt.loop_duration = cmd.looped ? d : pt.duration;
                        const data::ParticleProperty* props[6] = {&item.x, &item.y, &item.w,
                                                                  &item.h, &item.r, &item.a};
                        // A missing `from` or `to` is the zero expression (Bestdori's effects leave out `to` to
                        // shrink or fade to 0, often with an ease that only means something that way).
                        for (int k = 0; k < 6; ++k) {
                            pt.from[k] = eval(props[k]->from, copy_terms);
                            pt.to[k] = eval(props[k]->to, copy_terms);
                        }
                        inst.particles.push_back(pt);
                    }
                }
            }
            instances_.push_back(std::move(inst));
            return;
        }
        case modes::ParticleCmd::Move:
            for (Instance& inst : instances_)
                if (inst.id == id) map_quad(inst, terms);
            return;
        case modes::ParticleCmd::Destroy:
            std::erase_if(instances_, [&](const Instance& i) { return i.id == id; });
            return;
    }
}

void ParticleSystem::emit(float now, const float particle_transform[16], std::vector<render::SpriteQuad>& out) {
    for (Instance& inst : instances_)
        if (!inst.looped)
            std::erase_if(inst.particles, [&](const Particle& p) { return !(now < p.start_time + p.duration); });
    std::erase_if(instances_, [](const Instance& i) { return !i.looped && i.particles.empty(); });

    for (const Instance& inst : instances_) {
        for (const Particle& pt : inst.particles) {
            const data::ParticleItem& item = data_.effects[size_t(inst.effect)].groups[size_t(pt.group)]
                                                 .particles[size_t(pt.item)];
            if (item.sprite < 0 || size_t(item.sprite) >= data_.sprites.size()) continue;
            if (now < pt.start_time) continue;
            const float progress = wrap(now - pt.start_time, pt.loop_duration) / pt.duration;
            if (!(progress <= 1)) continue;  // over, or NaN (a zero duration at its start)
            const ItemInfo& info = items_[size_t(inst.effect)][size_t(pt.group)][size_t(pt.item)];
            float v[6];
            for (int k = 0; k < 6; ++k)
                v[k] = pt.from[k] +
                       (pt.to[k] - pt.from[k]) * vm::ease(vm::EaseKind(info.ease[size_t(k)]), progress);
            const float w = v[2], h = v[3];
            const float c = fm::cos(v[4]), s = fm::sin(v[4]);
            const float ox[4] = {-w, -w, w, w}, oy[4] = {-h, h, h, -h};
            render::Quad q;
            for (int i = 0; i < 4; ++i) {
                // Local [-1, 1] to (u, v) in [0, 1], then bilinear in the quad: bl -> tl and br -> tr along v,
                // then between them along u.
                const float u = (v[0] + (ox[i] * c - oy[i] * s)) * 0.5f + 0.5f;
                const float t = (v[1] + (oy[i] * c + ox[i] * s)) * 0.5f + 0.5f;
                const float ax = inst.quad_x[0] + (inst.quad_x[1] - inst.quad_x[0]) * t;
                const float ay = inst.quad_y[0] + (inst.quad_y[1] - inst.quad_y[0]) * t;
                const float bx = inst.quad_x[3] + (inst.quad_x[2] - inst.quad_x[3]) * t;
                const float by = inst.quad_y[3] + (inst.quad_y[2] - inst.quad_y[3]) * t;
                q.x[i] = ax + (bx - ax) * u;
                q.y[i] = ay + (by - ay) * u;
            }
            const data::ParticleSprite& sprite = data_.sprites[size_t(item.sprite)];
            render::SpriteQuad sq{};
            sq.screen = render::apply_matrix(particle_transform, q);
            sq.u0 = sprite.x;
            sq.v0 = sprite.y;
            sq.u1 = sprite.x + sprite.w;
            sq.v1 = sprite.y + sprite.h;
            sq.entity = 0;
            sq.a = info.color[3] * v[5];
            for (int k = 0; k < 3; ++k) sq.color[k] = info.color[size_t(k)];
            sq.sprite = item.sprite;
            sq.interpolation = data_.interpolation;
            out.push_back(sq);
        }
    }
}

}  // namespace sr::particle
