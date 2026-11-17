#pragma once

#include <array>
#include <cstdint>
#include <optional>
#include <string_view>

#include "data/background.h"
#include "data/png.h"
#include "render/geometry.h"

namespace sr::render {

// A CSS-style colour: `#RGB`, `#RGBA`, `#RRGGBB` or `#RRGGBBAA` (hex digits of either case; alpha ff when absent). Empty for anything else.
std::optional<std::array<uint8_t, 4>> parse_color(std::string_view text);

// Runtime Background's initial value, x1, y1 .. x4, y4: ar = data.aspect_ratio, or image_w / image_h when
// absent; h = aspect / ar (fit width), 1 (height), min(1, aspect / ar) (contain), max(1, aspect / ar) (cover);
// w = h * ar; then w *= scale_x, h *= scale_y (default 1); quad (-w,-h) (-w,h) (w,h) (w,-h). All float32 in this
// order, so the values are bit-exact. `aspect` is Runtime
// Environment's aspect ratio. An unknown fit is treated as contain.
void initial_runtime_background(const data::BackgroundData& data, int32_t image_w, int32_t image_h, float aspect,
                                float out[8]);

// Per-corner weights q for a projective (homography) mapping of the unit square onto `quad`: a vertex attribute
// (u q, v q, q) interpolated linearly in screen space, sampled at (s / q, t / q), gives the homography, the same for
// either triangulation. All 1 when the diagonals do not cross inside the quad (degenerate or not convex): then the
// mapping is affine per triangle. Used for the background and for lightweight-mode sprites.
void projective_weights(const Quad& quad, float q[4]);

// What the background pass draws each frame, under every skin sprite: clear the screen to `color`; draw the
// image into `screen` with perspective-correct mapping and GL_LINEAR + GL_CLAMP_TO_EDGE; then blend `mask` over the
// whole screen with straight alpha.
struct BackgroundQuad {
    Quad screen;  // Runtime Background as it stands after updateSequential
    // Corner i maps to image point (u[i], v[i]) in [0, 1], v = 0 the image's top row: (0, 1) (0, 0) (1, 0) (1, 1).
    // Perspective mapping: the vertex attribute is (u * q, v * q, q), interpolated linearly in screen space, and
    // the fragment samples at (s / q, t / q). That is the homography of the unit square onto the quad, the
    // same for either triangulation. q is 1 at every corner when the quad has no diagonal intersection inside it
    // (degenerate or not convex): then the mapping is affine per triangle.
    float u[4], v[4], q[4];
    std::array<uint8_t, 4> color;  // data.color; opaque black when it does not parse
    std::array<uint8_t, 4> mask;   // configuration.mask; transparent when it does not parse
};

BackgroundQuad layout_background(const data::BackgroundData& data, const data::BackgroundConfiguration& configuration,
                                 const float runtime_background[8]);

// The image the background pass samples: background.image with configuration.blur applied by
// blur_background (render/background_blur.h), computed once when the level loads, and
// uploaded as the background texture. `aspect` is Runtime Environment's aspect ratio.
data::Image background_image(const data::Image& image, const data::BackgroundConfiguration& configuration,
                             float aspect);

}  // namespace sr::render
