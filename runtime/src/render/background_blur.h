#pragma once

#include <cstdint>
#include <vector>

#include "data/png.h"

namespace sr::render {

// The background blur: a CPU pass on the image's 8-bit channels, run once
// when the level loads, before the texture is uploaded. Three box blurs approximate a Gaussian (the "boxes for
// Gauss" method): each is a horizontal pass then a vertical pass with clamp-to-edge, every output channel
// rint(float(1 / float(2r + 1)) * sum) of the 2r + 1 window. All float32, in a fixed order.

// Box radii for a Gaussian of `sigma` approximated by `n` box blurs:
// wIdeal = sqrt(12 sigma^2 / n + 1); wl = the largest odd integer <= (int)wIdeal; wu = wl + 2;
// m = round_half_even((12 sigma^2 - n wl^2 - 4 n wl - 3 n) / (-4 wl - 4)); box i is wl wide when i < m, else wu;
// radius = (width - 1) / 2.
std::vector<int32_t> gaussian_box_radii(float sigma, int32_t n);

// One box blur of an image with `channels` interleaved 8-bit channels (each blurred on its own): horizontal
// radius `rw` from `data` into `temp`, then vertical radius `rh` from `temp` back into `data` (the
// horizontal and vertical box passes, in their generic or 4-channel form). `temp` is resized as needed. A radius
// with 2r + 1 above the row (column) length would make the passes read and write outside the row; there the
// window is clamped to the edge instead.
void box_blur(std::vector<uint8_t>& data, std::vector<uint8_t>& temp, int32_t width, int32_t height,
              int32_t channels, int32_t rw, int32_t rh);

// The blur entry point: blur > 0 (NaN and <= 0 leave the image as
// is); p = blur * 0.1; W = max(h * aspect, w), H = max(w / aspect, h) (the image grown to the screen's aspect);
// s = min(W, H) * p; sigma x = s * w / W, sigma y = s * h / H; radii of 3 boxes per axis; 3 box_blur passes.
// `aspect` is the play area's width / height (Runtime Environment's aspect ratio).
data::Image blur_background(const data::Image& image, float aspect, float blur);

}  // namespace sr::render
