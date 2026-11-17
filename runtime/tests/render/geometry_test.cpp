// Sprite geometry (render/geometry): skin data transforms, Runtime Skin Transform (A2) and the draw order.
#include "render/geometry.h"

#include <algorithm>
#include <bit>
#include <cmath>
#include <cstdint>
#include <limits>
#include <map>
#include <memory>
#include <random>
#include <string>
#include <vector>

#include "data/resources.h"
#include "doctest.h"
#include "modes/watch_session.h"

using namespace sr;
using modes::DrawCmd;
using modes::FrameOutput;
using render::Quad;
using render::SpriteQuad;

namespace {

void identity(float m[16]) {
    for (int i = 0; i < 16; ++i) m[i] = (i % 5 == 0) ? 1.0f : 0.0f;
}

data::SkinTransform identity_transform() {
    data::SkinTransform t;
    for (int i = 0; i < 4; ++i) {
        t.coef[2 * i][i] = 1;          // xi <- xi
        t.coef[2 * i + 1][4 + i] = 1;  // yi <- yi
    }
    return t;
}

Quad box(float l, float b, float r, float t) { return Quad{{l, l, r, r}, {b, t, t, b}}; }

data::SkinData skin(int sprites) {
    data::SkinData s;
    s.width = 512;
    s.height = 128;
    s.interpolation = true;
    for (int i = 0; i < sprites; ++i) {
        data::SkinSprite sp;
        sp.name = "s" + std::to_string(i);
        sp.x = float(16 * i + 4);
        sp.y = 4;
        sp.w = 8;
        sp.h = 8;
        sp.transform = identity_transform();
        s.sprites.push_back(sp);
    }
    return s;
}

DrawCmd draw(int32_t sprite, int32_t entity, float z1, float z2 = 0, float z3 = 0, float z4 = 0,
             Quad q = box(0, 0, 1, 1)) {
    DrawCmd d{};
    d.sprite = sprite;
    for (int i = 0; i < 4; ++i) {
        d.x[i] = q.x[i];
        d.y[i] = q.y[i];
    }
    d.z[0] = z1;
    d.z[1] = z2;
    d.z[2] = z3;
    d.z[3] = z4;
    d.a = 1;
    d.entity = entity;
    return d;
}

FrameOutput frame_of(std::vector<DrawCmd> draws) {
    FrameOutput f;
    f.draws = std::move(draws);
    identity(f.skin_transform);
    return f;
}

uint32_t bits(float v) { return std::bit_cast<uint32_t>(v); }

bool same_float(float a, float b) { return bits(a) == bits(b) || (std::isnan(a) && std::isnan(b)); }

void check_same(const Quad& a, const Quad& b) {
    for (int i = 0; i < 4; ++i) {
        CAPTURE(i);
        CHECK(bits(a.x[i]) == bits(b.x[i]));
        CHECK(bits(a.y[i]) == bits(b.y[i]));
    }
}

// Position of each (entity, sprite) in a sorted list.
std::map<std::pair<int32_t, int32_t>, size_t> positions(const std::vector<SpriteQuad>& list) {
    std::map<std::pair<int32_t, int32_t>, size_t> pos;
    for (size_t i = 0; i < list.size(); ++i) pos[{list[i].entity, list[i].sprite}] = i;
    return pos;
}

// ---- layering layouts, as the draw list a session makes -----------------------------------------------------

constexpr int32_t kA = 0, kB = 1, kFiducial = 2, kOther = 3;  // grey 64, grey 192, fiducial, rest
constexpr float kNan = std::numeric_limits<float>::quiet_NaN();
constexpr float kNextAbove20 = 20.000001907348633f;

struct LayoutCall {
    int32_t sprite;
    float z[4];
};
struct LayoutEntity {
    bool parallel;
    std::vector<LayoutCall> calls;
};

// The frame's initial draw array: updateSequential draws in execution order (callback order, entity
// index breaking ties: every entity has the same archetype), then updateParallel draws in entity-index order.
std::vector<DrawCmd> layout_frame(const std::vector<LayoutEntity>& ents) {
    std::vector<DrawCmd> out;
    for (int pass = 0; pass < 2; ++pass) {
        for (size_t e = 0; e < ents.size(); ++e) {
            if (ents[e].parallel != (pass == 1)) continue;
            for (const LayoutCall& c : ents[e].calls)
                out.push_back(draw(c.sprite, int32_t(e), c.z[0], c.z[1], c.z[2], c.z[3]));
        }
    }
    return out;
}

LayoutEntity fiducials() {  // entity 0: four fiducials at z 1 in updateParallel
    return {true, std::vector<LayoutCall>(4, LayoutCall{kFiducial, {1, 0, 0, 0}})};
}

std::vector<LayoutEntity> layer_order() {
    std::vector<LayoutEntity> e{fiducials()};
    auto one = [](bool par, int32_t sprite, float z1, float z2 = 0, float z3 = 0, float z4 = 0) {
        return LayoutEntity{par, {LayoutCall{sprite, {z1, z2, z3, z4}}}};
    };
    const bool S = false, P = true;
    // row 0, pairs x1..x13 (lower entity index first): entities 1..26
    e.push_back(one(S, kA, 20)), e.push_back(one(S, kB, 20));                 // x1
    e.push_back(one(S, kB, 20)), e.push_back(one(S, kA, 20));                 // x2
    e.push_back(one(P, kA, 20)), e.push_back(one(P, kB, 20));                 // x3
    e.push_back(one(P, kB, 20)), e.push_back(one(P, kA, 20));                 // x4
    e.push_back(one(P, kA, 20)), e.push_back(one(S, kB, 20));                 // x5
    e.push_back(one(P, kB, 20)), e.push_back(one(S, kA, 20));                 // x6
    e.push_back(one(S, kA, 20)), e.push_back(one(S, kB, 20));                 // x7 (spawn order reversed)
    e.push_back(one(S, kA, kNextAbove20)), e.push_back(one(S, kB, 20));       // x8
    e.push_back(one(S, kA, 20, 1)), e.push_back(one(S, kB, 20));              // x9
    e.push_back(one(S, kA, 20, 0, 0, 1)), e.push_back(one(S, kB, 20));        // x10
    e.push_back(one(S, kA, -1e30f)), e.push_back(one(S, kB, kNan));           // x11
    e.push_back(one(S, kA, 0.0f)), e.push_back(one(S, kB, -0.0f));            // x12
    e.push_back(one(S, kA, 20, 0, 1)), e.push_back(one(S, kB, 20, 0, 0, 5));  // x13
    // row 1, w1..w4: one entity, two calls at equal z: entities 27..30
    for (int col = 0; col < 4; ++col) {
        const bool swap = col % 2 == 1;
        const float z = float(30 + col);
        e.push_back(LayoutEntity{col >= 2, {LayoutCall{swap ? kB : kA, {z, 0, 0, 0}},
                                           LayoutCall{swap ? kA : kB, {z, 0, 0, 0}}}});
    }
    // rows 2-3, 22 blends (base z 10, overlay z 11; the last one gets a third draw at z 12 from its own entity);
    // blend 6 is grey 192 over grey 64
    for (int i = 0; i < 22; ++i) {
        const int32_t base = i == 6 ? kA : kOther, over = i == 6 ? kB : kOther;
        e.push_back(LayoutEntity{S, {LayoutCall{base, {10, 0, 0, 0}}, LayoutCall{over, {11, 0, 0, 0}}}});
        if (i == 21) e.push_back(one(S, kOther, 12));
    }
    for (int v = 0; v < 256; v += 16) e.push_back(one(S, v == 64 ? kA : v == 192 ? kB : kOther, 1));  // grey ramp
    for (int i = 0; i < 5; ++i) e.push_back(one(S, kOther, 1));  // colours
    return e;
}

std::vector<LayoutEntity> layer_ties() {
    std::vector<LayoutEntity> e{fiducials()};
    for (int col = 0; col < 4; ++col) {
        const bool swap = col % 2 == 1;
        e.push_back(LayoutEntity{col >= 2, {LayoutCall{swap ? kB : kA, {30, 0, 0, 0}},
                                           LayoutCall{swap ? kA : kB, {30, 0, 0, 0}}}});
    }
    return e;
}

// Which call of a one-entity pair (sprites A and B, call order given by `first`) ends up on top.
const char* on_top(const std::vector<SpriteQuad>& sorted, int32_t entity, int32_t first, int32_t second) {
    const auto pos = positions(sorted);
    return pos.at({entity, second}) > pos.at({entity, first}) ? "second" : "first";
}

// The higher-placed (later drawn) of two entities' draws.
int32_t upper(const std::vector<SpriteQuad>& sorted, int32_t e1, int32_t e2) {
    size_t p1 = 0, p2 = 0;
    for (size_t i = 0; i < sorted.size(); ++i) {
        if (sorted[i].entity == e1) p1 = i;
        if (sorted[i].entity == e2) p2 = i;
    }
    return p1 > p2 ? e1 : e2;
}

void check_layer_order(const std::vector<SpriteQuad>& sorted) {
    REQUIRE(sorted.size() == 104);
    CHECK(upper(sorted, 1, 2) == 2);    // x1: the higher entity index
    CHECK(upper(sorted, 3, 4) == 4);    // x2
    CHECK(upper(sorted, 5, 6) == 6);    // x3
    CHECK(upper(sorted, 7, 8) == 8);    // x4
    CHECK(upper(sorted, 9, 10) == 10);  // x5: lower index in updateParallel
    CHECK(upper(sorted, 11, 12) == 12);  // x6
    CHECK(upper(sorted, 13, 14) == 14);  // x7: spawn order reversed
    CHECK(upper(sorted, 15, 16) == 15);  // x8: the larger z1
    CHECK(upper(sorted, 17, 18) == 17);  // x9: z2
    CHECK(upper(sorted, 19, 20) == 19);  // x10: z4
    CHECK(upper(sorted, 21, 22) == 21);  // x11: NaN sorts first
    CHECK(upper(sorted, 23, 24) == 24);  // x12: -0 == +0, the higher index
    CHECK(upper(sorted, 25, 26) == 25);  // x13: z3 1 beats z4 5
    // w1..w4: pinned so a change of the sort or the initial array shows up here.
    CHECK(std::string(on_top(sorted, 27, kA, kB)) == "second");
    CHECK(std::string(on_top(sorted, 28, kB, kA)) == "first");
    CHECK(std::string(on_top(sorted, 29, kA, kB)) == "second");
    CHECK(std::string(on_top(sorted, 30, kB, kA)) == "second");
}

void check_layer_ties(const std::vector<SpriteQuad>& sorted) {
    REQUIRE(sorted.size() == 12);
    // the second call on top in all four (insertion-sort path)
    CHECK(std::string(on_top(sorted, 1, kA, kB)) == "second");
    CHECK(std::string(on_top(sorted, 2, kB, kA)) == "second");
    CHECK(std::string(on_top(sorted, 3, kA, kB)) == "second");
    CHECK(std::string(on_top(sorted, 4, kB, kA)) == "second");
}

bool keys_ordered(const std::vector<SpriteQuad>& list) {
    for (size_t i = 1; i < list.size(); ++i)
        if (render::compare_sprites(list[i - 1], list[i]) > 0) return false;
    return true;
}

}  // namespace

TEST_SUITE("render") {
TEST_CASE("identity sprite transform keeps corners") {
    const Quad q{{-0.5f, -0.25f, 0.75f, 1.0f / 3.0f}, {-1.0f, 0.5f, 0.6f, -0.1f}};
    check_same(render::apply_sprite_transform(identity_transform(), q), q);
    float m[16];
    identity(m);
    check_same(render::apply_matrix(m, q), q);
}

TEST_CASE("swapping transform mirrors") {
    // x1 <- x4, x2 <- x3, x3 <- x2, x4 <- x1 and the same for y: left and right swap.
    data::SkinTransform t;
    const int from[4] = {3, 2, 1, 0};
    for (int i = 0; i < 4; ++i) {
        t.coef[2 * i][from[i]] = 1;
        t.coef[2 * i + 1][4 + from[i]] = 1;
    }
    const Quad q = box(-0.5f, -0.25f, 0.75f, 0.5f);
    const Quad out = render::apply_sprite_transform(t, q);
    check_same(out, Quad{{0.75f, 0.75f, -0.5f, -0.5f}, {-0.25f, 0.5f, 0.5f, -0.25f}});
}

TEST_CASE("sprite transform sums its terms in column order") {
    // A Next RUSH skin row (sss-722 skin data): x1' = 1.1375 x1 - 0.1375 x4, rounded in float32 step by step.
    data::SkinTransform t = identity_transform();
    t.coef[0][0] = 1.1375f;
    t.coef[0][3] = -0.13749999999999996f;
    const Quad q{{0.3f, 0.3f, 0.7f, 0.7f}, {0.1f, 0.9f, 0.9f, 0.1f}};
    const Quad out = render::apply_sprite_transform(t, q);
    float want = 1.1375f * 0.3f;
    want = want + 0.0f * 0.3f;
    want = want + 0.0f * 0.7f;
    want = want + -0.13749999999999996f * 0.7f;
    CHECK(bits(out.x[0]) == bits(want));
    CHECK(bits(out.x[3]) == bits(0.7f));
}

TEST_CASE("skin matrix applies") {
    // A translation (0.03, 0.02) at row-major indices 3 and 7 moves every draw of the frame, whichever
    // entity drew it; a scale composes after the sprite's own transform.
    FrameOutput f = frame_of({draw(0, 1, 5, 0, 0, 0, box(-0.75f, -0.25f, -0.5f, 0.25f)),
                              draw(1, 2, 5, 0, 0, 0, box(0.0f, -0.25f, 0.25f, 0.25f))});
    f.skin_transform[3] = 0.03f;
    f.skin_transform[7] = 0.02f;
    const std::vector<SpriteQuad> list = render::build_sprite_list(f, skin(2));
    REQUIRE(list.size() == 2);
    check_same(list[0].screen, box(-0.75f + 0.03f, -0.25f + 0.02f, -0.5f + 0.03f, 0.25f + 0.02f));
    check_same(list[1].screen, box(0.0f + 0.03f, -0.25f + 0.02f, 0.25f + 0.03f, 0.25f + 0.02f));

    float m[16];
    identity(m);
    m[0] = 0.5f;
    m[1] = 0.25f;
    m[4] = -0.125f;
    m[5] = 2.0f;
    m[3] = 0.1f;
    m[7] = -0.3f;
    const Quad q = box(0.2f, -0.4f, 0.6f, 0.8f);
    const Quad out = render::apply_matrix(m, q);
    for (int i = 0; i < 4; ++i) {
        CAPTURE(i);
        CHECK(bits(out.x[i]) == bits(0.5f * q.x[i] + 0.25f * q.y[i] + 0.1f));
        CHECK(bits(out.y[i]) == bits(-0.125f * q.x[i] + 2.0f * q.y[i] + -0.3f));
    }

    data::SkinData s = skin(1);
    s.sprites[0].transform.coef[0][0] = 1.1375f;
    s.sprites[0].transform.coef[0][3] = -0.1375f;
    FrameOutput g = frame_of({draw(0, 1, 5, 0, 0, 0, q)});
    for (int i = 0; i < 16; ++i) g.skin_transform[i] = m[i];
    const std::vector<SpriteQuad> composed = render::build_sprite_list(g, s);
    REQUIRE(composed.size() == 1);
    check_same(composed[0].screen, render::apply_matrix(m, render::apply_sprite_transform(s.sprites[0].transform, q)));
}

TEST_CASE("sprite list carries the texture rect, alpha and filter") {
    data::SkinData s = skin(3);
    s.interpolation = false;
    DrawCmd d = draw(2, 7, 3);
    d.a = 1.5f;  // unclamped
    const std::vector<SpriteQuad> list = render::build_sprite_list(frame_of({d}), s);
    REQUIRE(list.size() == 1);
    CHECK(list[0].u0 == 36.0f);
    CHECK(list[0].v0 == 4.0f);
    CHECK(list[0].u1 == 44.0f);
    CHECK(list[0].v1 == 12.0f);
    CHECK(list[0].a == 1.5f);
    CHECK(list[0].sprite == 2);
    CHECK(list[0].entity == 7);
    CHECK(list[0].z[0] == 3.0f);
    CHECK_FALSE(list[0].interpolation);
}

TEST_CASE("sort by z with the entity tie-break") {
    // A short frame takes the insertion-sort path, which keeps equal keys in their initial order.
    const std::vector<SpriteQuad> list = render::build_sprite_list(
        frame_of({
            draw(0, 5, 2),             // 0
            draw(1, 4, 1, 0, 0, 9),    // 1: z1 1, z4 9
            draw(2, 9, kNan),          // 2: NaN below every number
            draw(3, 3, 1, 0, 1),       // 3: z3 1 beats z4 9
            draw(4, 1, 2),             // 4: the z of sprite 0, lower entity
            draw(5, 6, -0.0f),         // 5: -0 == +0, then entity 6
            draw(6, 2, 0.0f),          // 6: +0, entity 2
            draw(7, 5, 2),             // 7: every key equal to sprite 0's: stays after it
            draw(8, 0, -std::numeric_limits<float>::infinity()),
        }),
        skin(9));
    std::vector<int32_t> order;
    for (const SpriteQuad& q : list) order.push_back(q.sprite);
    CHECK(order == std::vector<int32_t>{2, 8, 6, 5, 1, 3, 4, 0, 7});
    CHECK(keys_ordered(list));
}

TEST_CASE("compare follows float.CompareTo") {
    auto with = [](float z1, int32_t entity) {
        SpriteQuad q{};
        q.z[0] = z1;
        q.entity = entity;
        return q;
    };
    CHECK(render::compare_sprites(with(kNan, 0), with(kNan, 0)) == 0);
    CHECK(render::compare_sprites(with(kNan, 9), with(-1e30f, 0)) < 0);
    CHECK(render::compare_sprites(with(-1e30f, 0), with(kNan, 9)) > 0);
    CHECK(render::compare_sprites(with(-0.0f, 1), with(0.0f, 1)) == 0);
    CHECK(render::compare_sprites(with(1, 1), with(1, 2)) < 0);
    CHECK(render::compare_sprites(with(1, 3), with(1, 2)) > 0);
}

TEST_CASE("intro sort orders every size and key mix") {
    std::mt19937 rng(12345);
    for (int n : {0, 1, 2, 3, 4, 16, 17, 31, 104, 227, 1000, 5000}) {
        for (int keys : {1, 3, 50, 100000}) {
            CAPTURE(n);
            CAPTURE(keys);
            std::vector<SpriteQuad> list(static_cast<size_t>(n));
            for (int i = 0; i < n; ++i) {
                list[size_t(i)] = SpriteQuad{};
                list[size_t(i)].z[0] = float(rng() % uint32_t(keys));
                list[size_t(i)].z[1] = float(rng() % 2u);
                list[size_t(i)].entity = int32_t(rng() % 4u);
                list[size_t(i)].sprite = i;
            }
            render::sort_sprites(list);
            CHECK(keys_ordered(list));
            std::vector<bool> seen(size_t(n), false);
            for (const SpriteQuad& q : list) seen[size_t(q.sprite)] = true;
            CHECK(std::find(seen.begin(), seen.end(), false) == seen.end());
        }
    }
}

TEST_CASE("intro sort heap fallback orders") {
    std::mt19937 rng(7);
    for (int n : {17, 40, 300}) {
        CAPTURE(n);
        std::vector<SpriteQuad> list(static_cast<size_t>(n));
        for (int i = 0; i < n; ++i) {
            list[size_t(i)] = SpriteQuad{};
            list[size_t(i)].z[0] = float(rng() % 20u);
            list[size_t(i)].sprite = i;
        }
        render::detail::intro_sort(list, 0);  // depth 0: straight to HeapSort
        CHECK(keys_ordered(list));
    }
}

TEST_CASE("unknown sprite id skipped") {
    const std::vector<SpriteQuad> list =
        render::build_sprite_list(frame_of({draw(-1, 1, 1), draw(2, 1, 1), draw(1, 2, 1), draw(99, 1, 1)}), skin(2));
    REQUIRE(list.size() == 1);
    CHECK(list[0].sprite == 1);
}

TEST_CASE("layered pairs draw in the expected order") {
    const std::vector<DrawCmd> order = layout_frame(layer_order());
    CHECK(order.size() == 104);
    check_layer_order(render::build_sprite_list(frame_of(order), skin(4)));
    check_layer_ties(render::build_sprite_list(frame_of(layout_frame(layer_ties())), skin(4)));
}
}  // TEST_SUITE


namespace {

// A test quad at the slot whose top-left pixel is (left, top), in
// screen units of a 3200 x 2136 screen as level data carries them (float32 of (px - 1600) / 1068, (1068 - py) / 1068).
Quad mapping_quad(double left, double top, const double pts[8]) {
    Quad q;
    for (int i = 0; i < 4; ++i) {
        q.x[i] = float((left + pts[2 * i] - 1600.0) / 1068.0);
        q.y[i] = float((1068.0 - (top + pts[2 * i + 1])) / 1068.0);
    }
    return q;
}

// The reference: q_i = (d_i + d_{i+2}) / d_{i+2}, d_i the distance from corner i to the diagonals' crossing, in double.
void reference_weights(const Quad& quad, double q[4]) {
    const double ax = double(quad.x[2]) - quad.x[0], ay = double(quad.y[2]) - quad.y[0];
    const double bx = double(quad.x[3]) - quad.x[1], by = double(quad.y[3]) - quad.y[1];
    const double ox = double(quad.x[1]) - quad.x[0], oy = double(quad.y[1]) - quad.y[0];
    const double s = (ox * by - oy * bx) / (ax * by - ay * bx);
    const double cx = quad.x[0] + s * ax, cy = quad.y[0] + s * ay;
    double d[4];
    for (int i = 0; i < 4; ++i) d[i] = std::hypot(quad.x[i] - cx, quad.y[i] - cy);
    for (int i = 0; i < 4; ++i) q[i] = (d[i] + d[(i + 2) % 4]) / d[(i + 2) % 4];
}

}  // namespace

TEST_SUITE("render") {
TEST_CASE("lightweight weights follow the distance mapping on convex and non-convex quads") {
    // The homography on convex quads, and on non-convex ones the same distance formula
    // with unsigned distances (dart-tr, dart-tl: the crossing lies outside one diagonal).
    const double trapezoid[8] = {0, 256, 64, 0, 192, 0, 320, 256};
    const double general[8] = {0, 256, 40, 30, 300, 0, 250, 210};
    const double dart_tr[8] = {0, 256, 0, 0, 100, 150, 300, 256};
    const double dart_tl[8] = {0, 256, 200, 150, 300, 0, 300, 256};
    for (const Quad& quad : {mapping_quad(240, 400, trapezoid), mapping_quad(1380, 400, general),
                             mapping_quad(240, 800, dart_tr), mapping_quad(620, 800, dart_tl)}) {
        float q[4];
        REQUIRE(render::lightweight_weights(quad, q));
        double ref[4];
        reference_weights(quad, ref);
        for (int i = 0; i < 4; ++i) CHECK(q[i] == doctest::Approx(ref[i]).epsilon(1e-5));
    }
    // The dart's weights are all positive (the signed form would give q0 = -2.959 for dart-tr).
    float q[4];
    REQUIRE(render::lightweight_weights(mapping_quad(240, 800, dart_tr), q));
    CHECK(q[0] == doctest::Approx(4.96).epsilon(1e-2));
}

TEST_CASE("lightweight weights drop degenerate quads") {
    // Bottom-left == bottom-right and the bow-tie (parallel diagonals) draw nothing;
    // top-left == top-right draws, with the bottom corners' weights huge but finite (u by projection from the apex,
    // v along the bottom edge).
    const double triangle_top[8] = {0, 256, 160, 0, 160, 0, 320, 256};
    const double triangle_bottom[8] = {160, 256, 0, 0, 320, 0, 160, 256};
    const double bowtie[8] = {0, 256, 320, 0, 0, 0, 320, 256};
    float q[4];
    CHECK_FALSE(render::lightweight_weights(mapping_quad(1380, 800, triangle_bottom), q));
    CHECK_FALSE(render::lightweight_weights(mapping_quad(1760, 800, bowtie), q));
    REQUIRE(render::lightweight_weights(mapping_quad(1000, 800, triangle_top), q));
    for (float v : q) CHECK(std::isfinite(v));
    CHECK(q[0] > 1e4f);
    CHECK(q[3] > 1e4f);
    CHECK(q[1] == doctest::Approx(1.0));
    CHECK(q[2] == doctest::Approx(1.0));
}

TEST_CASE("particles map affinely per triangle in every render mode and are never dropped") {
    // The particle mesh carries one UV per vertex and no weight, so a lane trapezoid's texture runs
    // linearly in screen space on each triangle; a projective mapping would push a vertical gradient to the top.
    using render::QuadMapping;
    CHECK(render::particle_mapping() == QuadMapping::AffineTriangles);
    CHECK(render::sprite_mapping(true) == QuadMapping::Projective);
    CHECK(render::sprite_mapping(false) == QuadMapping::Bilinear);
    const double lane[8] = {0, 800, 300, 0, 340, 0, 640, 800};      // a narrow-topped trapezoid
    const double bowtie[8] = {0, 256, 320, 0, 0, 0, 320, 256};      // dropped by the lightweight sprite path
    float q[4];
    for (const double* c : {lane, bowtie}) {
        REQUIRE(render::corner_weights(mapping_quad(1000, 800, c), QuadMapping::AffineTriangles, q));
        for (float v : q) CHECK(v == 1.0f);
    }
    float p[4];
    REQUIRE(render::corner_weights(mapping_quad(1000, 800, lane), QuadMapping::Projective, q));
    REQUIRE(render::lightweight_weights(mapping_quad(1000, 800, lane), p));
    for (int i = 0; i < 4; ++i) CHECK(q[i] == p[i]);
    CHECK(q[0] != 1.0f);
    CHECK_FALSE(render::corner_weights(mapping_quad(1760, 800, bowtie), QuadMapping::Projective, q));
    REQUIRE(render::corner_weights(mapping_quad(1760, 800, bowtie), QuadMapping::Bilinear, q));
    for (float v : q) CHECK(v == 1.0f);
}
}  // TEST_SUITE
