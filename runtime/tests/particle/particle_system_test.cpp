// Particle simulation (particle/particle_system): spawn, move, destroy, expiry and the random source.
#include "particle/particle_system.h"

#include <cmath>
#include <cstdint>
#include <vector>

#include "doctest.h"
#include "vm/easing.h"
#include "vm/fmath.h"

using namespace sr;
using data::ParticleExpr;
using modes::ParticleCmd;
using particle::ParticleRandom;
using particle::ParticleSystem;
using render::SpriteQuad;

namespace {

constexpr int kC = 0;
int r_term(int i) { return 8 + i; }      // r1..r8
int sin_term(int i) { return 16 + i; }   // sinr1..sinr8
int cos_term(int i) { return 24 + i; }   // cosr1..cosr8

ParticleExpr expr(std::initializer_list<std::pair<int, float>> terms) {
    ParticleExpr e;
    for (auto [i, k] : terms) e.term[i] = k;
    return e;
}

data::ParticleProperty prop(ParticleExpr from, ParticleExpr to, const char* ease = "linear") {
    data::ParticleProperty p;
    p.has_from = p.has_to = true;
    p.from = from;
    p.to = to;
    p.ease = ease;
    return p;
}

data::ParticleProperty constant(float c) { return prop(expr({{kC, c}}), expr({{kC, c}})); }

// One effect, identity transform, one group of `count` copies of `item`.
data::ParticleData one_effect(const data::ParticleItem& item, int32_t count = 1) {
    data::ParticleData d;
    d.width = d.height = 64;
    d.sprites.push_back({10, 20, 30, 40});
    data::ParticleEffect e;
    e.name = "test";
    for (int i = 0; i < 4; ++i) {
        e.transform.row[2 * i].term[1 + i] = 1;      // xi <- xi
        e.transform.row[2 * i + 1].term[5 + i] = 1;  // yi <- yi
    }
    data::ParticleGroup g;
    g.count = count;
    g.particles.push_back(item);
    e.groups.push_back(g);
    d.effects.push_back(e);
    return d;
}

// x from 0 to 1 over the whole instance, a 0.2 x 0.4 white sprite.
data::ParticleItem sweep_item() {
    data::ParticleItem it;
    it.color = "#fff";
    it.start = 0;
    it.duration = 1;
    it.x = prop(expr({{kC, 0}}), expr({{kC, 1}}));
    it.y = constant(0);
    it.w = constant(0.2f);
    it.h = constant(0.4f);
    it.r = constant(0);
    it.a = constant(1);
    return it;
}

// The square (-1, -1) .. (1, 1) offset by dx: local p maps to dx + 2 p.
ParticleCmd spawn(float handle, float duration, bool looped, float dx = 0) {
    ParticleCmd c{};
    c.op = ParticleCmd::Spawn;
    c.handle = handle;
    c.effect = 0;
    const float x[4] = {-1, -1, 1, 1}, y[4] = {-1, 1, 1, -1};
    for (int i = 0; i < 4; ++i) {
        c.x[i] = x[i] + dx;
        c.y[i] = y[i];
    }
    c.duration = duration;
    c.looped = looped;
    return c;
}

ParticleCmd op(ParticleCmd::Op o, float handle, float dx = 0) {
    ParticleCmd c = spawn(handle, 0, false, dx);
    c.op = o;
    c.effect = -1;
    return c;
}

const float kIdentity[16] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};

std::vector<SpriteQuad> emit(ParticleSystem& s, float now, const float* m = kIdentity) {
    std::vector<SpriteQuad> out;
    s.emit(now, m, out);
    return out;
}

float centre_x(const SpriteQuad& q) { return (q.screen.x[0] + q.screen.x[2]) / 2; }

}  // namespace

TEST_SUITE("particle") {
TEST_CASE("particle evaluates from to with ease") {
    data::ParticleItem it;
    it.color = "#ff8000";
    it.start = 0.25f;
    it.duration = 0.5f;
    it.x = prop(expr({{kC, 0.1f}, {r_term(1), 0.2f}}), expr({{kC, 0.3f}, {sin_term(2), 0.5f}}), "outQuad");
    it.y = prop(expr({{kC, -0.1f}}), expr({{cos_term(3), 0.2f}}));
    it.w = constant(0.2f);
    it.h = constant(0.4f);
    it.r = constant(0);
    it.a = prop(expr({{kC, 1}}), expr({{kC, 0.5f}}));
    const data::ParticleData d = one_effect(it);

    ParticleRandom rng(42);
    for (int i = 0; i < 8; ++i) rng.next();  // the spawn's r1..r8 (the effect transform)
    float r[8];                               // the copy's r1..r8 (its particles' properties)
    for (float& v : r) v = rng.next();
    const float two_pi = 6.2831855f;
    const float x_from = 0.1f + 0.2f * r[0], x_to = 0.3f + 0.5f * fm::sin(r[1] * two_pi);
    const float y_from = -0.1f, y_to = 0.2f * fm::cos(r[2] * two_pi);
    // age 1 of 2: 0.5 of the instance, (0.5 - 0.25) / 0.5 = 0.5 of the particle
    const float x = x_from + (x_to - x_from) * vm::ease(vm::EaseKind::OutQuad, 0.5f);
    const float y = y_from + (y_to - y_from) * 0.5f;

    ParticleSystem s(d, 42);
    s.apply(spawn(257, 2, false), 10);
    CHECK(emit(s, 10.4f).empty());  // progress -0.1: not started
    const std::vector<SpriteQuad> out = emit(s, 11);
    REQUIRE(out.size() == 1);
    const SpriteQuad& q = out[0];
    // local space is [-1, 1] across the quad and w, h are half sizes: a corner is (x +- w) / 2, (y +- h) / 2
    const float ex[4] = {x / 2 - 0.1f, x / 2 - 0.1f, x / 2 + 0.1f, x / 2 + 0.1f};
    const float ey[4] = {y / 2 - 0.2f, y / 2 + 0.2f, y / 2 + 0.2f, y / 2 - 0.2f};
    for (int i = 0; i < 4; ++i) {
        CHECK(q.screen.x[i] == doctest::Approx(2 * ex[i]).epsilon(1e-5));
        CHECK(q.screen.y[i] == doctest::Approx(2 * ey[i]).epsilon(1e-5));
    }
    CHECK(q.u0 == 10);
    CHECK(q.v0 == 20);
    CHECK(q.u1 == 40);
    CHECK(q.v1 == 60);
    CHECK(q.a == doctest::Approx(0.75f));
    CHECK(q.color[0] == 1.0f);
    CHECK(q.color[1] == doctest::Approx(128 / 255.0f));
    CHECK(q.color[2] == 0.0f);
    CHECK(emit(s, 11.9f).empty());  // progress 1.4: over
}

TEST_CASE("a missing from or to is the zero expression") {
    // Bestdori's #SLOT_LINEAR glow: h {from 0.5} and a {from 1, ease inCubic} with no `to` shrink and fade to 0
    // (an ease is only meaningful that way); x {} stays at 0.
    data::ParticleItem it = sweep_item();
    it.x = data::ParticleProperty{};
    it.h = data::ParticleProperty{};
    it.h.has_from = true;
    it.h.from = expr({{kC, 0.5f}});
    it.a = data::ParticleProperty{};
    it.a.has_from = true;
    it.a.from = expr({{kC, 1}});
    it.a.ease = "inCubic";
    const data::ParticleData d = one_effect(it);
    ParticleSystem s(d, 42);
    s.apply(spawn(1, 2, false), 0);
    const std::vector<SpriteQuad> out = emit(s, 1);  // progress 0.5
    REQUIRE(out.size() == 1);
    const SpriteQuad& q = out[0];
    CHECK(centre_x(q) == doctest::Approx(0.0f));
    CHECK(q.screen.y[1] - q.screen.y[0] == doctest::Approx(0.5f));  // 2 h = 0.25 in local space; the quad doubles it
    CHECK(q.a == doctest::Approx(1 - vm::ease(vm::EaseKind::InCubic, 0.5f)));
}

TEST_CASE("particle rotates counterclockwise about its centre") {
    data::ParticleItem it = sweep_item();
    it.x = constant(0);
    it.r = constant(1.5707964f);  // a quarter turn: w and h swap
    const data::ParticleData d = one_effect(it);
    ParticleSystem s(d, 1);
    s.apply(spawn(1, 1, false), 0);
    const std::vector<SpriteQuad> out = emit(s, 0.5f);
    REQUIRE(out.size() == 1);
    // bl (-0.1, -0.2) turns to (0.2, -0.1), tl (-0.1, 0.2) to (-0.2, -0.1); times 2 on screen
    CHECK(out[0].screen.x[0] == doctest::Approx(0.4f));
    CHECK(out[0].screen.y[0] == doctest::Approx(-0.2f));
    CHECK(out[0].screen.x[1] == doctest::Approx(-0.4f));
    CHECK(out[0].screen.y[1] == doctest::Approx(-0.2f));
}

TEST_CASE("looped effect repeats until destroyed") {
    const data::ParticleData d = one_effect(sweep_item());
    ParticleSystem s(d, 1);
    s.apply(spawn(257, 1, true), 0);
    std::vector<SpriteQuad> out = emit(s, 0.25f);
    REQUIRE(out.size() == 1);
    CHECK(centre_x(out[0]) == doctest::Approx(0.25f));  // x 0.25 in [-1, 1] -> screen 0.25
    out = emit(s, 5.25f);
    REQUIRE(out.size() == 1);
    CHECK(centre_x(out[0]) == doctest::Approx(0.25f));
    CHECK(s.live() == 1);
    s.apply(op(ParticleCmd::Destroy, 257), 5.3f);
    CHECK(emit(s, 5.3f).empty());
    CHECK(s.live() == 0);
}

TEST_CASE("non-looped effect expires") {
    const data::ParticleData d = one_effect(sweep_item(), 3);
    ParticleSystem s(d, 1);
    s.apply(spawn(257, 1, false), 0);
    CHECK(emit(s, 0.5f).size() == 3);  // three copies of the group
    CHECK(s.live() == 1);
    CHECK(emit(s, 1.0f).empty());
    CHECK(s.live() == 0);
}

TEST_CASE("move retargets") {
    const data::ParticleData d = one_effect(sweep_item());
    ParticleSystem s(d, 1);
    s.apply(spawn(513, 1, true), 0);
    std::vector<SpriteQuad> out = emit(s, 0.25f);
    REQUIRE(out.size() == 1);
    CHECK(centre_x(out[0]) == doctest::Approx(0.25f));
    s.apply(op(ParticleCmd::Move, 513, 3), 0.25f);
    out = emit(s, 0.25f);
    REQUIRE(out.size() == 1);
    CHECK(centre_x(out[0]) == doctest::Approx(3.25f));
    // Runtime Particle Transform applies after the quad: x' = 2 x + 0.5
    float m[16] = {2, 0, 0, 0.5f, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    out = emit(s, 0.25f, m);
    REQUIRE(out.size() == 1);
    CHECK(centre_x(out[0]) == doctest::Approx(7.0f));
    // a Move to an unknown id changes nothing
    s.apply(op(ParticleCmd::Move, 999, -10), 0.25f);
    CHECK(centre_x(emit(s, 0.25f)[0]) == doctest::Approx(3.25f));
}

TEST_CASE("a spawn is drawn in its own frame") {
    const data::ParticleData d = one_effect(sweep_item());
    ParticleSystem s(d, 1);
    s.apply(spawn(1, 1, false), 2.5f);
    const std::vector<SpriteQuad> out = emit(s, 2.5f);
    REQUIRE(out.size() == 1);
    CHECK(centre_x(out[0]) == doctest::Approx(0.0f));  // progress 0: x 0
}

TEST_CASE("positions outside the quad extrapolate, unclamped") {
    data::ParticleItem it = sweep_item();
    it.x = constant(0);
    it.y = constant(3);  // v (3 +- 0.4) / 2 + 1/2 = 1.8 .. 2.2: past the top edge
    const data::ParticleData d = one_effect(it);
    ParticleSystem s(d, 1);
    s.apply(spawn(1, 1, false), 0);
    const std::vector<SpriteQuad> out = emit(s, 0.5f);
    REQUIRE(out.size() == 1);
    CHECK(out[0].screen.y[0] == doctest::Approx(2.6f));  // -1 + 2 * 1.8
    CHECK(out[0].screen.y[1] == doctest::Approx(3.4f));
}

TEST_CASE("properties see zero for the quad terms") {
    data::ParticleItem it = sweep_item();
    it.x = prop(expr({{1, 1}}), expr({{1, 1}}));  // x = x1 of the call: read as 0
    const data::ParticleData d = one_effect(it);
    ParticleSystem s(d, 1);
    s.apply(spawn(1, 1, false, 0.5f), 0);  // x1 = -0.5
    const std::vector<SpriteQuad> out = emit(s, 0.5f);
    REQUIRE(out.size() == 1);
    CHECK(centre_x(out[0]) == doctest::Approx(0.5f));  // the quad's centre, not shifted by x1 / 2
}

TEST_CASE("expressions sum c, the quad terms, then r, sin r, cos r of each index in turn") {
    data::ParticleItem it = sweep_item();
    ParticleExpr e = expr({{kC, 0.5f}});
    for (int k = 1; k <= 8; ++k) {
        e.term[r_term(k)] = 3.0e7f * float(k % 2 ? 1 : -1);
        e.term[sin_term(k)] = 1.7f;
        e.term[cos_term(k)] = -2.9e7f * float(k % 3 ? 1 : -1);
    }
    it.x = prop(e, e);
    const data::ParticleData d = one_effect(it);
    ParticleRandom rng(5);
    for (int i = 0; i < 8; ++i) rng.next();
    float sum = 0.5f, term_order = 0.5f, r[8];
    for (float& v : r) v = rng.next();
    for (int k = 0; k < 8; ++k) {
        const float a = r[k] * 6.2831855f;
        sum = sum + e.term[r_term(k + 1)] * r[k];
        sum = sum + e.term[sin_term(k + 1)] * fm::sin(a);
        sum = sum + e.term[cos_term(k + 1)] * fm::cos(a);
    }
    for (int k = 0; k < 8; ++k) term_order = term_order + e.term[r_term(k + 1)] * r[k];
    for (int k = 0; k < 8; ++k) term_order = term_order + e.term[sin_term(k + 1)] * fm::sin(r[k] * 6.2831855f);
    for (int k = 0; k < 8; ++k) term_order = term_order + e.term[cos_term(k + 1)] * fm::cos(r[k] * 6.2831855f);
    REQUIRE(sum != term_order);  // the case tells the orders apart
    ParticleSystem s(d, 5);
    s.apply(spawn(1, 1, false), 0);
    const std::vector<SpriteQuad> out = emit(s, 0.5f);
    REQUIRE(out.size() == 1);
    // corner 0: u = (x - 0.2) / 2 + 1/2 on the square -1 .. 1 -> screen 2u - 1
    CHECK(out[0].screen.x[0] == ((sum + -0.2f) * 0.5f + 0.5f) * 2.0f - 1.0f);
}

TEST_CASE("spawn under a live id keeps both; move and destroy reach both") {
    const data::ParticleData d = one_effect(sweep_item());
    ParticleSystem s(d, 1);
    s.apply(spawn(257, 1, true), 0);
    s.apply(spawn(257, 1, true, 3), 0);
    CHECK(s.live() == 2);
    std::vector<SpriteQuad> out = emit(s, 0.25f);
    REQUIRE(out.size() == 2);
    CHECK(centre_x(out[0]) == doctest::Approx(0.25f));
    CHECK(centre_x(out[1]) == doctest::Approx(3.25f));
    s.apply(op(ParticleCmd::Move, 257, 5), 0.25f);
    out = emit(s, 0.25f);
    REQUIRE(out.size() == 2);
    CHECK(centre_x(out[0]) == doctest::Approx(5.25f));
    CHECK(centre_x(out[1]) == doctest::Approx(5.25f));
    s.apply(op(ParticleCmd::Destroy, 257), 0.25f);
    CHECK(s.live() == 0);
}

TEST_CASE("particle position is half its local offset: y 0.9 stays inside the quad") {
    data::ParticleItem it = sweep_item();
    it.x = constant(0);
    it.y = constant(0.9f);
    it.h = constant(0.1f);
    const data::ParticleData d = one_effect(it);
    ParticleSystem s(d, 1);
    s.apply(spawn(1, 1, false), 0);
    const std::vector<SpriteQuad> out = emit(s, 0.5f);
    REQUIRE(out.size() == 1);
    // the quad spans y -1 .. 1; local y 0.9 +- 0.1 is v 0.9 .. 1.0, i.e. screen 0.8 .. 1.0
    CHECK(out[0].screen.y[0] == doctest::Approx(0.8f));
    CHECK(out[0].screen.y[1] == doctest::Approx(1.0f));
}

TEST_CASE("particle maps bilinearly into a trapezoid") {
    data::ParticleItem it = sweep_item();
    it.x = constant(0.5f);
    it.y = constant(0.5f);
    it.w = constant(0.5f);
    it.h = constant(0.5f);
    const data::ParticleData d = one_effect(it);
    ParticleSystem s(d, 1);
    ParticleCmd c = spawn(1, 1, false);
    const float x[4] = {-2, -1, 1, 2}, y[4] = {0, 1, 1, 0};  // bl, tl, tr, br: narrow top edge
    for (int i = 0; i < 4; ++i) {
        c.x[i] = x[i];
        c.y[i] = y[i];
    }
    s.apply(c, 0);
    const std::vector<SpriteQuad> out = emit(s, 0.5f);
    REQUIRE(out.size() == 1);
    // corners at (u, v) = (0.5, 0.5), (0.5, 1), (1, 1), (1, 0.5)
    const float ex[4] = {0, 0, 1, 1.5f}, ey[4] = {0.5f, 1, 1, 0.5f};
    for (int i = 0; i < 4; ++i) {
        CHECK(out[0].screen.x[i] == doctest::Approx(ex[i]));
        CHECK(out[0].screen.y[i] == doctest::Approx(ey[i]));
    }
}

TEST_CASE("random source is a 32-bit xorshift (13, 17, 5) read before each step") {
    // seed 1: the constructor steps once (state 0x42021); next() takes the state before its step, top 23 bits
    ParticleRandom rng(1);
    CHECK(rng.next() == 0x210 * 0x1p-23f);
    uint32_t t = 0x42021;
    t ^= t << 13;
    t ^= t >> 17;
    t ^= t << 5;
    CHECK(rng.next() == float(t >> 9) * 0x1p-23f);
}

TEST_CASE("the transform's r values are drawn once per spawn, the properties' per copy") {
    data::ParticleItem it = sweep_item();
    it.x = prop(expr({{r_term(1), 1}}), expr({{r_term(1), 1}}));
    data::ParticleData d = one_effect(it, 2);
    d.effects[0].transform.row[0].term[r_term(2)] = 1;  // x1' = x1 + r2 of the spawn
    ParticleRandom rng(9);
    float spawn_r[8], copy_r[2][8];
    for (float& v : spawn_r) v = rng.next();
    for (auto& copy : copy_r)
        for (float& v : copy) v = rng.next();
    ParticleSystem s(d, 9);
    s.apply(spawn(1, 1, false), 0);
    const std::vector<SpriteQuad> out = emit(s, 0.5f);
    REQUIRE(out.size() == 2);
    for (int k = 0; k < 2; ++k) {
        // bl moves by spawn r2 (the same for both copies); the copy's x is its own r1
        const float u = (copy_r[k][0] - 0.2f) * 0.5f + 0.5f;
        const float a = (-1 + spawn_r[1]) + (-1 - (-1 + spawn_r[1])) * 0.3f;  // left edge at v 0.3 (corner y -0.4)
        const float b = 1 + (1 - 1) * 0.3f;
        CHECK(out[size_t(k)].screen.x[0] == doctest::Approx(a + (b - a) * u));
    }
}

TEST_CASE("move draws new r values for the transform") {
    data::ParticleItem it = sweep_item();
    it.x = constant(0);
    data::ParticleData d = one_effect(it);
    for (int i = 0; i < 4; ++i) d.effects[0].transform.row[2 * i].term[r_term(1)] = 1;  // every x moves by r1
    ParticleRandom rng(3);
    for (int i = 0; i < 16; ++i) rng.next();  // the spawn's 8 and the copy's 8
    const float move_r1 = rng.next();
    ParticleSystem s(d, 3);
    s.apply(spawn(1, 1, true), 0);
    s.apply(op(ParticleCmd::Move, 1), 0.1f);
    const std::vector<SpriteQuad> out = emit(s, 0.1f);
    REQUIRE(out.size() == 1);
    CHECK(centre_x(out[0]) == doctest::Approx(move_r1));
}

TEST_CASE("a non-looped particle ends at its own end, not the effect's") {
    data::ParticleItem it = sweep_item();
    it.start = 0.5f;
    it.duration = 0.8f;
    const data::ParticleData d = one_effect(it);
    ParticleSystem s(d, 1);
    s.apply(spawn(1, 1, false), 0);
    CHECK(emit(s, 0.4f).empty());       // before its start
    CHECK(emit(s, 1.2f).size() == 1);   // past the effect's duration, before 0.5 + 0.8
    CHECK(emit(s, 1.31f).empty());      // ended
    CHECK(s.live() == 0);
}

TEST_CASE("a looped particle wraps by the effect's duration from its own start") {
    data::ParticleItem it = sweep_item();
    it.start = 0.5f;
    it.duration = 0.8f;
    const data::ParticleData d = one_effect(it);
    ParticleSystem s(d, 1);
    s.apply(spawn(1, 1, true), 0);
    CHECK(emit(s, 0.2f).empty());       // before its first start
    CHECK(emit(s, 1.2f).size() == 1);   // 0.7 after its start: progress 0.875
    CHECK(emit(s, 2.1f).size() == 1);   // 1.6 after its start, wrapped to 0.6: progress 0.75
    CHECK(emit(s, 1.4f).empty());       // 0.9 after its start: progress 1.125
}

TEST_CASE("same seed gives the same particles") {
    data::ParticleItem it = sweep_item();
    it.x = prop(expr({{r_term(1), 1}}), expr({{sin_term(4), 1}, {cos_term(8), 1}}), "inOutElastic");
    const data::ParticleData d = one_effect(it, 5);
    ParticleSystem a(d, 7), b(d, 7), c(d, 8);
    for (ParticleSystem* s : {&a, &b, &c}) s->apply(spawn(1, 1, false), 0);
    const std::vector<SpriteQuad> qa = emit(a, 0.3f), qb = emit(b, 0.3f), qc = emit(c, 0.3f);
    REQUIRE(qa.size() == 5);
    bool same = true, same_c = true;
    for (size_t i = 0; i < qa.size(); ++i)
        for (int k = 0; k < 4; ++k) {
            same = same && qa[i].screen.x[k] == qb[i].screen.x[k];
            same_c = same_c && qa[i].screen.x[k] == qc[i].screen.x[k];
        }
    CHECK(same);
    CHECK_FALSE(same_c);
}

TEST_CASE("r values are uniform") {
    ParticleRandom rng(0x5eed);
    int decile[10] = {};
    constexpr int kDraws = 100000;
    bool in_range = true;
    for (int i = 0; i < kDraws; ++i) {
        const float r = rng.next();
        in_range = in_range && r >= 0.0f && r < 1.0f;
        if (r >= 0.0f && r < 1.0f) ++decile[int(r * 10)];
    }
    CHECK(in_range);
    for (int k = 0; k < 10; ++k) {
        INFO("decile " << k << ": " << decile[k]);
        CHECK(decile[k] >= 9500);
        CHECK(decile[k] <= 10500);
    }
}
}
