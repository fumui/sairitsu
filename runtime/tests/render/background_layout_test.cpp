// Background layout (render/background_layout): Runtime Background's initial value and the background pass's quad.
#include "render/background_layout.h"

#include <bit>
#include <cmath>
#include <cstdint>
#include <optional>
#include <string>
#include <utility>
#include <vector>

#include "doctest.h"

using namespace sr;
using render::BackgroundQuad;

namespace {

uint32_t bits(float v) { return std::bit_cast<uint32_t>(v); }

constexpr float kAspect = 3200.0f / 2136.0f;  // Float(width) / float(height)

data::BackgroundData background(std::optional<float> ar, const char* fit, std::optional<float> sx = {},
                                std::optional<float> sy = {}) {
    data::BackgroundData d;
    d.aspect_ratio = ar;
    d.fit = fit;
    d.color = "#000000";
    d.scale_x = sx;
    d.scale_y = sy;
    return d;
}

// Image point of screen point (x, y) in the background pass: (u q, v q, q) interpolated linearly over the
// triangle holding the point, then divided. `diagonal` picks the triangulation (0-2 or 1-3).
std::optional<std::pair<double, double>> sample(const BackgroundQuad& b, double x, double y, bool diagonal02) {
    const int tris[2][2][3] = {{{0, 1, 2}, {0, 2, 3}}, {{0, 1, 3}, {1, 2, 3}}};
    for (const auto& t : tris[diagonal02 ? 0 : 1]) {
        const double x0 = b.screen.x[t[0]], y0 = b.screen.y[t[0]];
        const double x1 = b.screen.x[t[1]], y1 = b.screen.y[t[1]];
        const double x2 = b.screen.x[t[2]], y2 = b.screen.y[t[2]];
        const double den = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2);
        const double l0 = ((y1 - y2) * (x - x2) + (x2 - x1) * (y - y2)) / den;
        const double l1 = ((y2 - y0) * (x - x2) + (x0 - x2) * (y - y2)) / den;
        const double l2 = 1 - l0 - l1;
        if (l0 < -1e-9 || l1 < -1e-9 || l2 < -1e-9) continue;
        const double l[3] = {l0, l1, l2};
        double s = 0, tt = 0, q = 0;
        for (int i = 0; i < 3; ++i) {
            s += l[i] * double(b.u[t[i]]) * double(b.q[t[i]]);
            tt += l[i] * double(b.v[t[i]]) * double(b.q[t[i]]);
            q += l[i] * double(b.q[t[i]]);
        }
        return std::pair{s / q, tt / q};
    }
    return std::nullopt;
}

// The homography of the image's unit square onto the quad (Heckbert): image (0, 0) at the top-left corner (1),
// (1, 0) top-right (2), (1, 1) bottom-right (3), (0, 1) bottom-left (0).
std::pair<double, double> project(const float* qx, const float* qy, double u, double v) {
    const double x0 = qx[1], y0 = qy[1], x1 = qx[2], y1 = qy[2], x2 = qx[3], y2 = qy[3], x3 = qx[0], y3 = qy[0];
    const double sx = x0 - x1 + x2 - x3, sy = y0 - y1 + y2 - y3;
    const double dx1 = x1 - x2, dx2 = x3 - x2, dy1 = y1 - y2, dy2 = y3 - y2;
    const double den = dx1 * dy2 - dx2 * dy1;
    const double g = (sx * dy2 - dx2 * sy) / den, h = (dx1 * sy - sx * dy1) / den;
    const double a = x1 - x0 + g * x1, b = x3 - x0 + h * x3, c = x0;
    const double d = y1 - y0 + g * y1, e = y3 - y0 + h * y3, f = y0;
    const double w = g * u + h * v + 1;
    return {(a * u + b * v + c) / w, (d * u + e * v + f) / w};
}

}  // namespace

TEST_SUITE("render") {
TEST_CASE("aspect is float(3200) / float(2136)") {
    CHECK(bits(kAspect) == 0x3FBFC2A3u);
}

TEST_CASE("background fit height at 3200x2136") {
    // image aspect 1 (256 x 256, no aspectRatio): the quad spans y -1..1 and x -1..1
    float rb[8];
    render::initial_runtime_background(background({}, "height"), 256, 256, kAspect, rb);
    const float want[8] = {-1, -1, -1, 1, 1, 1, 1, -1};
    for (int i = 0; i < 8; ++i) {
        CAPTURE(i);
        CHECK(bits(rb[i]) == bits(want[i]));
    }
}

TEST_CASE("background initial values are bit-exact for every fit") {
    struct Case {
        const char* name;
        data::BackgroundData data;
        int32_t w, h;
        uint32_t want[8];
    };
    const Case cases[] = {
        {"width", background(1.0f, "width"), 256, 256,
         {0xBFBFC2A3u, 0xBFBFC2A3u, 0xBFBFC2A3u, 0x3FBFC2A3u, 0x3FBFC2A3u, 0x3FBFC2A3u, 0x3FBFC2A3u, 0xBFBFC2A3u}},
        {"height", background(2.5f, "height"), 256, 256,
         {0xC0200000u, 0xBF800000u, 0xC0200000u, 0x3F800000u, 0x40200000u, 0x3F800000u, 0x40200000u, 0xBF800000u}},
        {"contain", background(2.5f, "contain"), 256, 256,
         {0xBFBFC2A2u, 0xBF196882u, 0xBFBFC2A2u, 0x3F196882u, 0x3FBFC2A2u, 0x3F196882u, 0x3FBFC2A2u, 0xBF196882u}},
        {"cover", background({}, "cover"), 192, 256,
         {0xBFBFC2A3u, 0xBFFFAE2Fu, 0xBFBFC2A3u, 0x3FFFAE2Fu, 0x3FBFC2A3u, 0x3FFFAE2Fu, 0x3FBFC2A3u, 0xBFFFAE2Fu}},
        {"scale", background(1.0f, "contain", 0.5f, 0.8f), 256, 256,
         {0xBF000000u, 0xBF4CCCCDu, 0xBF000000u, 0x3F4CCCCDu, 0x3F000000u, 0x3F4CCCCDu, 0x3F000000u, 0xBF4CCCCDu}},
        {"width 1.5", background(1.5f, "width"), 256, 256,
         {0xBFBFC2A3u, 0xBF7FAE2Fu, 0xBFBFC2A3u, 0x3F7FAE2Fu, 0x3FBFC2A3u, 0x3F7FAE2Fu, 0x3FBFC2A3u, 0xBF7FAE2Fu}},
    };
    for (const Case& c : cases) {
        CAPTURE(c.name);
        float rb[8];
        render::initial_runtime_background(c.data, c.w, c.h, kAspect, rb);
        for (int i = 0; i < 8; ++i) {
            CAPTURE(i);
            CHECK(bits(rb[i]) == c.want[i]);
        }
    }
    // aspectRatio 1, cover, an 8 x 8 image: x1, y1 .. x3 (corners 0-2)
    const uint32_t want[6] = {0xBFBFC2A3u, 0xBFBFC2A3u, 0xBFBFC2A3u, 0x3FBFC2A3u, 0x3FBFC2A3u, 0x3FBFC2A3u};
    float rb[8];
    render::initial_runtime_background(background(1.0f, "cover"), 8, 8, kAspect, rb);
    for (int i = 0; i < 6; ++i) {
        CAPTURE(i);
        CHECK(bits(rb[i]) == want[i]);
    }
}

TEST_CASE("colours parse in their four forms") {
    using C = std::array<uint8_t, 4>;
    CHECK(render::parse_color("#204060") == C{0x20, 0x40, 0x60, 0xff});
    CHECK(render::parse_color("#ffffff80") == C{0xff, 0xff, 0xff, 0x80});
    CHECK(render::parse_color("#0006") == C{0, 0, 0, 0x66});
    CHECK(render::parse_color("#F0a") == C{0xff, 0x00, 0xaa, 0xff});
    CHECK(render::parse_color("#00000000") == C{0, 0, 0, 0});
    CHECK_FALSE(render::parse_color("").has_value());
    CHECK_FALSE(render::parse_color("204060").has_value());
    CHECK_FALSE(render::parse_color("#20406").has_value());
    CHECK_FALSE(render::parse_color("#2040zz").has_value());
    CHECK_FALSE(render::parse_color("red").has_value());
}

TEST_CASE("background quad is runtime background with color and mask") {
    data::BackgroundData d = background(1.0f, "width");
    d.color = "#406020";
    data::BackgroundConfiguration conf;
    conf.mask = "#0006";
    const float rb[8] = {-1.5f, -1.0f, -1.5f, 1.0f, 1.5f, 1.0f, 1.5f, -1.0f};
    const BackgroundQuad b = render::layout_background(d, conf, rb);
    for (int i = 0; i < 4; ++i) {
        CAPTURE(i);
        CHECK(b.screen.x[i] == rb[2 * i]);
        CHECK(b.screen.y[i] == rb[2 * i + 1]);
        CHECK(b.q[i] == b.q[0]);  // a rectangle maps affinely
    }
    CHECK(b.u[0] == 0.0f);
    CHECK(b.v[0] == 1.0f);
    CHECK(b.u[1] == 0.0f);
    CHECK(b.v[1] == 0.0f);
    CHECK(b.u[2] == 1.0f);
    CHECK(b.v[2] == 0.0f);
    CHECK(b.u[3] == 1.0f);
    CHECK(b.v[3] == 1.0f);
    CHECK(b.color == std::array<uint8_t, 4>{0x40, 0x60, 0x20, 0xff});
    CHECK(b.mask == std::array<uint8_t, 4>{0, 0, 0, 0x66});

    d.color = "nonsense";
    conf.mask = "";
    const BackgroundQuad fallback = render::layout_background(d, conf, rb);
    CHECK(fallback.color == std::array<uint8_t, 4>{0, 0, 0, 0xff});
    CHECK(fallback.mask == std::array<uint8_t, 4>{0, 0, 0, 0});
}

TEST_CASE("background trapezoid maps projectively") {
    // The image follows the homography of the unit square, either way the
    // quad is split into triangles.
    const float rb[8] = {-1.2f, -0.9f, -0.6f, 0.9f, 0.6f, 0.9f, 1.2f, -0.9f};
    const BackgroundQuad b = render::layout_background(background(1.0f, "width"), {}, rb);
    const std::pair<double, double> points[] = {{0.5, 0.5}, {0.25, 0.75}, {0.9, 0.1}, {0.1, 0.6}, {0.7, 0.95}};
    for (auto [u, v] : points) {
        CAPTURE(u);
        CAPTURE(v);
        const auto [x, y] = project(b.screen.x, b.screen.y, u, v);
        for (bool diagonal02 : {true, false}) {
            CAPTURE(diagonal02);
            const auto got = sample(b, x, y, diagonal02);
            REQUIRE(got.has_value());
            CHECK(got->first == doctest::Approx(u).epsilon(1e-5));
            CHECK(got->second == doctest::Approx(v).epsilon(1e-5));
        }
    }
    CHECK(b.q[0] != doctest::Approx(b.q[1]));  // wide bottom, narrow top: not affine
}

TEST_CASE("degenerate background quad maps affinely") {
    const float flat[8] = {0, 0, 0, 0, 1, 0, 1, 0};  // zero height
    const BackgroundQuad b = render::layout_background(background(1.0f, "width"), {}, flat);
    for (int i = 0; i < 4; ++i) CHECK(b.q[i] == 1.0f);
    const float bowtie[8] = {-1, -1, 1, 1, -1, 1, 1, -1};  // self-intersecting: diagonals do not cross inside
    const BackgroundQuad c = render::layout_background(background(1.0f, "width"), {}, bowtie);
    for (int i = 0; i < 4; ++i) CHECK(c.q[i] == 1.0f);
}
}  // TEST_SUITE
