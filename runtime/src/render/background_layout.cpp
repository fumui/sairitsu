#include "render/background_layout.h"

#include <algorithm>
#include <cmath>

#include "render/background_blur.h"

namespace sr::render {

namespace {

int hex_digit(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

}  // namespace

std::optional<std::array<uint8_t, 4>> parse_color(std::string_view text) {
    if (text.empty() || text[0] != '#') return std::nullopt;
    const std::string_view hex = text.substr(1);
    const size_t n = hex.size();
    if (n != 3 && n != 4 && n != 6 && n != 8) return std::nullopt;
    for (char c : hex)
        if (hex_digit(c) < 0) return std::nullopt;
    std::array<uint8_t, 4> out{0, 0, 0, 0xff};
    const bool short_form = n <= 4;
    const size_t channels = (n == 4 || n == 8) ? 4 : 3;
    for (size_t i = 0; i < channels; ++i) {
        if (short_form) {
            const int d = hex_digit(hex[i]);
            out[i] = uint8_t(d * 17);
        } else {
            out[i] = uint8_t(hex_digit(hex[2 * i]) * 16 + hex_digit(hex[2 * i + 1]));
        }
    }
    return out;
}

void initial_runtime_background(const data::BackgroundData& data, int32_t image_w, int32_t image_h, float aspect,
                                float out[8]) {
    // Float32 in this order (bit-exact).
    float ar;
    if (data.aspect_ratio) {
        ar = *data.aspect_ratio;
    } else if (image_w > 0 && image_h > 0) {
        ar = float(image_w) / float(image_h);
    } else {
        ar = 1;  // no image size: not observed; a square keeps the quad finite
    }
    float h;
    if (data.fit == "width") {
        h = aspect / ar;
    } else if (data.fit == "height") {
        h = 1;
    } else if (data.fit == "cover") {
        h = std::max(1.0f, aspect / ar);
    } else {  // contain, and any unknown fit (not observed)
        h = std::min(1.0f, aspect / ar);
    }
    float w = h * ar;
    w = w * data.scale_x.value_or(1.0f);
    h = h * data.scale_y.value_or(1.0f);
    const float quad[8] = {-w, -h, -w, h, w, h, w, -h};
    std::copy(quad, quad + 8, out);
}

void projective_weights(const Quad& quad, float q[4]) {
    // From the diagonals' intersection c = p0 + s (p2 - p0) = p1 + t (p3 - p1): q_i = (d_i + d_{i+2}) / d_{i+2}
    // with d_i = |p_i - c|, i.e. q0 = 1 / (1 - s), q2 = 1 / s, q1 = 1 / (1 - t), q3 = 1 / t. Interpolating
    // (u q, v q, q) linearly in screen space then gives the homography of the unit square.
    const double x0 = quad.x[0], y0 = quad.y[0], x1 = quad.x[1], y1 = quad.y[1];
    const double ax = quad.x[2] - x0, ay = quad.y[2] - y0;  // p2 - p0
    const double bx = quad.x[3] - x1, by = quad.y[3] - y1;  // p3 - p1
    const double cross = ax * by - ay * bx;
    const double ox = x1 - x0, oy = y1 - y0;  // p1 - p0
    if (std::abs(cross) > 1e-12) {
        const double s = (ox * by - oy * bx) / cross;
        const double t = (ox * ay - oy * ax) / cross;
        if (s > 0 && s < 1 && t > 0 && t < 1) {
            q[0] = float(1 / (1 - s));
            q[1] = float(1 / (1 - t));
            q[2] = float(1 / s);
            q[3] = float(1 / t);
            return;
        }
    }
    std::fill(q, q + 4, 1.0f);  // degenerate or not convex: affine per triangle
}

BackgroundQuad layout_background(const data::BackgroundData& data, const data::BackgroundConfiguration& configuration,
                                 const float runtime_background[8]) {
    BackgroundQuad b{};
    for (int i = 0; i < 4; ++i) {
        b.screen.x[i] = runtime_background[2 * i];
        b.screen.y[i] = runtime_background[2 * i + 1];
    }
    const float u[4] = {0, 0, 1, 1}, v[4] = {1, 0, 0, 1};
    std::copy(u, u + 4, b.u);
    std::copy(v, v + 4, b.v);
    projective_weights(b.screen, b.q);

    b.color = parse_color(data.color).value_or(std::array<uint8_t, 4>{0, 0, 0, 0xff});
    b.mask = parse_color(configuration.mask).value_or(std::array<uint8_t, 4>{0, 0, 0, 0});
    return b;
}

data::Image background_image(const data::Image& image, const data::BackgroundConfiguration& configuration,
                             float aspect) {
    // The texture's pixels are blurred once, at load, with the configured blur.
    return blur_background(image, aspect, configuration.blur);
}

}  // namespace sr::render
