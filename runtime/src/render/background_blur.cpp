#include "render/background_blur.h"

#include <algorithm>
#include <cstddef>
#include <limits>

#include "vm/convert.h"
#include "vm/fmath.h"

// The blur entry point, its sigma and radius helpers and its horizontal and vertical box passes
//. Every running sum is an integer below 2^24, so float32
// holds it exactly; only iarr = 1 / (2r + 1) and the product iarr * sum round.

namespace sr::render {

namespace {

// The rounding of an output channel: round half to even, then the float -> int conversion (fcvtzs) and the low 8
// bits. x is an average of bytes, so the result is 0..255.
uint8_t round_to_byte(float x) { return uint8_t(uint32_t(vm::to_index(fm::round(x))) & 0xffu); }

// One line (a row or a column): `n` elements `stride` bytes apart, one channel. The loop structure
// (Kutskir's boxBlurH/T): the window starts as (r + 1) copies of the first value plus the first r values; the first
// r + 1 outputs add the next value and drop a copy of the first; the middle ones add the next value and drop the
// oldest; the last r add copies of the last value. Needs 2r + 1 <= n.
void blur_line_unclamped(const uint8_t* src, uint8_t* dst, int32_t n, ptrdiff_t stride, int32_t r) {
    auto get = [&](int32_t i) { return float(src[ptrdiff_t(i) * stride]); };
    auto set = [&](int32_t i, float v) { dst[ptrdiff_t(i) * stride] = round_to_byte(v); };
    int32_t ti = 0, li = 0, ri = r;
    const float fv = get(ti);
    const float lv = get(ti + n - 1);
    float val = (float(r) + 1.0f) * fv;
    for (int32_t j = 0; j < r; ++j) val = val + get(ti + j);
    const float iarr = 1.0f / (float(r * 2) + 1.0f);
    for (int32_t j = 0; j <= r; ++j) {
        val = val + (get(ri++) - fv);
        set(ti++, iarr * val);
    }
    for (int32_t j = r + 1; j < n - r; ++j) {
        val = val + (get(ri++) - get(li++));
        set(ti++, iarr * val);
    }
    for (int32_t j = n - r; j < n; ++j) {
        val = val + (lv - get(li++));
        set(ti++, iarr * val);
    }
}

// 2r + 1 > n: the unclamped loop would read and write outside the line there.
// Ours keeps the same window, clamped to the line's ends, summed exactly.
void blur_line_clamped(const uint8_t* src, uint8_t* dst, int32_t n, ptrdiff_t stride, int64_t r) {
    auto get = [&](int64_t i) { return int64_t(src[ptrdiff_t(std::clamp<int64_t>(i, 0, n - 1)) * stride]); };
    // window of output 0: (r + 1) copies of the first value, values 1 .. min(r, n - 1), then copies of the last
    int64_t sum = (r + 1) * get(0);
    const int64_t inside = std::min<int64_t>(r, n - 1);
    for (int64_t k = 1; k <= inside; ++k) sum += get(k);
    sum += (r - inside) * get(n - 1);
    const float iarr = 1.0f / (float(r * 2) + 1.0f);
    for (int32_t j = 0; j < n; ++j) {
        if (j > 0) sum += get(j + r) - get(j - r - 1);
        dst[ptrdiff_t(j) * stride] = round_to_byte(iarr * float(sum));
    }
}

void blur_line(const uint8_t* src, uint8_t* dst, int32_t n, ptrdiff_t stride, int32_t r) {
    if (r < 0) r = 0;  // never produced by gaussian_box_radii for a finite sigma
    if (int64_t(r) * 2 + 1 <= n)
        blur_line_unclamped(src, dst, n, stride, r);
    else
        blur_line_clamped(src, dst, n, stride, r);
}

}  // namespace

std::vector<int32_t> gaussian_box_radii(float sigma, int32_t n) {
    if (n <= 0) return {};
    const float s12 = (sigma * 12.0f) * sigma;
    const float nf = float(n);
    const float ideal = fm::sqrt(s12 / nf + 1.0f);
    // the float -> int cast here: fcvtzs, except +inf gives INT32_MIN
    const int32_t wi = ideal == std::numeric_limits<float>::infinity() ? std::numeric_limits<int32_t>::min()
                                                                         : vm::to_index(ideal);
    const uint32_t even = uint32_t(wi) + (uint32_t(wi) & 1u);  // wi rounded up to even (int32 wrap-around)
    const int32_t wl = int32_t(even - 1u);                       // the largest odd width <= wi
    const int32_t wu = int32_t(even + 1u);                       // wl + 2
    const int32_t nwl2 = int32_t(uint32_t(wl) * uint32_t(n) * uint32_t(wl));
    const float num = ((s12 - float(nwl2)) + (nf * -4.0f) * float(wl)) + nf * -3.0f;
    const float den = float(wl) * -4.0f + -4.0f;
    // Math.Round((double)m): half to even; i < m compared as `i < (int)m && m != +inf`
    const float m = fm::round(num / den);
    const int32_t mi = vm::to_index(m);
    const bool finite_m = m != std::numeric_limits<float>::infinity();
    std::vector<int32_t> radii(static_cast<size_t>(n));
    for (int32_t i = 0; i < n; ++i) {
        const int32_t width = (i < mi && finite_m) ? wl : wu;
        radii[size_t(i)] = int32_t(uint32_t(width) - 1u) / 2;  // int division: toward zero
    }
    return radii;
}

void box_blur(std::vector<uint8_t>& data, std::vector<uint8_t>& temp, int32_t width, int32_t height,
              int32_t channels, int32_t rw, int32_t rh) {
    if (width <= 0 || height <= 0 || channels <= 0) return;
    temp.resize(data.size());
    const ptrdiff_t pixel = channels, row = ptrdiff_t(width) * channels;
    // horizontal box pass: one job per row, data -> temp
    for (int32_t y = 0; y < height; ++y)
        for (int32_t c = 0; c < channels; ++c)
            blur_line(&data[size_t(y * row + c)], &temp[size_t(y * row + c)], width, pixel, rw);
    // vertical box pass: one job per column, temp -> data
    for (int32_t x = 0; x < width; ++x)
        for (int32_t c = 0; c < channels; ++c)
            blur_line(&temp[size_t(x * pixel + c)], &data[size_t(x * pixel + c)], height, row, rh);
}

data::Image blur_background(const data::Image& image, float aspect, float blur) {
    // The callers (the background preview and the level's background setup) blur only when the
    // configured blur is > 0 (a NaN compares false).
    if (!(blur > 0.0f) || image.width <= 0 || image.height <= 0) return image;
    const float p = blur * 0.1f;  // the entry point passes blur * 0.1 to the sigma helper
    const float w = float(image.width), h = float(image.height);
    // sigma helper
    const float w_over_aspect = w / aspect;
    const float h_times_aspect = h * aspect;
    const float big_w = h_times_aspect < w ? w : h_times_aspect;
    const float big_h = w_over_aspect < h ? h : w_over_aspect;
    const float scale = (big_w < big_h ? big_w : big_h) * p;
    const std::vector<int32_t> rw = gaussian_box_radii((scale * w) / big_w, 3);
    const std::vector<int32_t> rh = gaussian_box_radii((scale * h) / big_h, 3);
    // the texture's mip 0 bytes: data.Length / w / h channels (4 for the RGBA8 images we decode)
    const int32_t channels = int32_t(image.rgba.size() / size_t(image.width) / size_t(image.height));
    data::Image out = image;
    std::vector<uint8_t> temp(out.rgba.size());
    for (size_t i = 0; i < 3; ++i) box_blur(out.rgba, temp, image.width, image.height, channels, rw[i], rh[i]);
    return out;
}

}  // namespace sr::render
