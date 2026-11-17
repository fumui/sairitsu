// Background blur (render/background_blur): the radius helper and the horizontal and vertical box passes.
#include "render/background_blur.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <limits>
#include <string>
#include <unordered_map>
#include <vector>

#include "doctest.h"
#include "render/background_layout.h"

using namespace sr;

namespace {

constexpr float kAspect = 3200.0f / 2136.0f;  // Float(width) / float(height)

data::Image solid(int32_t w, int32_t h, uint8_t v) {
    data::Image img;
    img.width = w;
    img.height = h;
    img.rgba.assign(size_t(w) * size_t(h) * 4, v);
    for (size_t i = 3; i < img.rgba.size(); i += 4) img.rgba[i] = 0xff;
    return img;
}

void put(data::Image& img, int32_t x, int32_t y, uint8_t v) {
    uint8_t* p = &img.rgba[(size_t(y) * size_t(img.width) + size_t(x)) * 4];
    p[0] = p[1] = p[2] = v;
}

// A test image: black with a white 1-texel vertical line (x 64), a white
// 1-texel horizontal line (y 64, x 0-127), one white texel (192, 64), a white 8 x 8 square at (184, 184) and a
// white step in rows 128-159 from x 128 on. Row 0 is the top.
data::Image blur_test_image() {
    data::Image img = solid(256, 256, 0);
    for (int32_t y = 0; y < 256; ++y) put(img, 64, y, 255);
    for (int32_t x = 0; x <= 127; ++x) put(img, x, 64, 255);
    put(img, 192, 64, 255);
    for (int32_t y = 184; y <= 191; ++y)
        for (int32_t x = 184; x <= 191; ++x) put(img, x, y, 255);
    for (int32_t y = 128; y <= 159; ++y)
        for (int32_t x = 128; x <= 255; ++x) put(img, x, y, 255);
    return img;
}

}  // namespace

TEST_SUITE("render") {
TEST_CASE("radius helper gives the boxes-for-Gauss radii") {
    using V = std::vector<int32_t>;
    CHECK(render::gaussian_box_radii(0.0f, 3) == V{0, 0, 0});
    // sigma 1, n 3: wIdeal sqrt(5) -> wl 1, wu 3; m = 1.5 rounds to 2
    CHECK(render::gaussian_box_radii(1.0f, 3) == V{0, 0, 1});
    // sigma 1, n 4: wIdeal 2 -> wl 1; m = 2.5 rounds half to even, to 2 (half up would give {0, 0, 0, 1})
    CHECK(render::gaussian_box_radii(1.0f, 4) == V{0, 0, 1, 1});
    // sigma 10: wIdeal 20.02 -> wl 19, wu 21; m = 1.5 -> 2
    CHECK(render::gaussian_box_radii(10.0f, 3) == V{9, 9, 10});
    CHECK(render::gaussian_box_radii(2.5f, 4) == V{1, 2, 2, 2});
    // blur 4 on a 3200 x 2136 screen: sigma x 68.352, sigma y 102.4
    CHECK(render::gaussian_box_radii(68.352f, 3) == V{68, 68, 68});
    CHECK(render::gaussian_box_radii(102.4f, 3) == V{102, 102, 102});
    CHECK(render::gaussian_box_radii(101.12f, 3) == V{100, 101, 101});
}

TEST_CASE("box blur radius 0 is the identity") {
    std::vector<uint8_t> data(7 * 5 * 3), temp;
    for (size_t i = 0; i < data.size(); ++i) data[i] = uint8_t(i * 37 + 11);
    const std::vector<uint8_t> before = data;
    render::box_blur(data, temp, 7, 5, 3, 0, 0);
    CHECK(data == before);
}

TEST_CASE("box blur spreads a single white pixel over its window") {
    std::vector<uint8_t> data(9 * 9, 0), temp;
    data[4 * 9 + 4] = 255;
    render::box_blur(data, temp, 9, 9, 1, 1, 1);
    // horizontal: 255 * float(1 / 3) = 85; vertical: 85 * float(1 / 3) = 28.33 -> 28
    for (int32_t y = 0; y < 9; ++y)
        for (int32_t x = 0; x < 9; ++x) {
            CAPTURE(x);
            CAPTURE(y);
            const bool in = x >= 3 && x <= 5 && y >= 3 && y <= 5;
            CHECK(int(data[size_t(y * 9 + x)]) == (in ? 28 : 0));
        }
    // wider horizontally than vertically
    std::vector<uint8_t> wide(9 * 9, 0);
    wide[4 * 9 + 4] = 255;
    render::box_blur(wide, temp, 9, 9, 1, 2, 0);
    CHECK(int(wide[4 * 9 + 2]) == 51);
    CHECK(int(wide[4 * 9 + 6]) == 51);
    CHECK(int(wide[4 * 9 + 1]) == 0);
    CHECK(int(wide[3 * 9 + 4]) == 0);
}

TEST_CASE("box blur clamps to the edge pixels") {
    // one row: windows at the ends repeat the edge value
    std::vector<uint8_t> row{10, 20, 30, 40, 50}, temp;
    render::box_blur(row, temp, 5, 1, 1, 1, 0);
    CHECK(row == std::vector<uint8_t>{13, 20, 30, 40, 47});  // (10+10+20)/3 = 13.3, (40+50+50)/3 = 46.7
    // one column, the vertical pass
    std::vector<uint8_t> col{10, 20, 30, 40, 50};
    render::box_blur(col, temp, 1, 5, 1, 0, 1);
    CHECK(col == std::vector<uint8_t>{13, 20, 30, 40, 47});
    // channels are blurred independently (4 interleaved channels)
    std::vector<uint8_t> rgba{0, 100, 200, 255, 30, 100, 0, 255, 60, 100, 100, 255};
    render::box_blur(rgba, temp, 3, 1, 4, 1, 0);
    CHECK(rgba == std::vector<uint8_t>{10, 100, 133, 255, 30, 100, 100, 255, 50, 100, 67, 255});
}

TEST_CASE("box blur wider than the row clamps the whole window") {
    // 2r + 1 = 5 > 3: the window is clamped: (0,0,0,0,255) (0,0,0,255,255) ...
    std::vector<uint8_t> row{0, 0, 255}, temp;
    render::box_blur(row, temp, 3, 1, 1, 2, 0);
    CHECK(row == std::vector<uint8_t>{51, 102, 153});
    // the vertical pass, along a column (stride = row length)
    std::vector<uint8_t> col{0, 0, 255};
    render::box_blur(col, temp, 1, 3, 1, 0, 2);
    CHECK(col == std::vector<uint8_t>{51, 102, 153});
    // two channels, two columns: the column stride is width * channels
    std::vector<uint8_t> grid{0, 255, 10, 0, 0, 255, 20, 0, 255, 0, 30, 0};  // 2 x 3, 2 channels
    render::box_blur(grid, temp, 2, 3, 2, 0, 2);
    // column 0 channel 0: (0, 0, 255) -> 51, 102, 153; channel 1: (255, 255, 0) -> 204, 153, 102
    // column 1 channel 0: (10, 20, 30) -> windows (10,10,10,20,30) (10,10,20,30,30) (10,20,30,30,30) = 16, 20, 24
    CHECK(grid == std::vector<uint8_t>{51, 204, 16, 0, 102, 153, 20, 0, 153, 102, 24, 0});
}

TEST_CASE("blur_background leaves blur 0 and NaN unblurred and runs three box passes otherwise") {
    const data::Image img = blur_test_image();
    CHECK(render::blur_background(img, kAspect, 0.0f).rgba == img.rgba);
    CHECK(render::blur_background(img, kAspect, -1.0f).rgba == img.rgba);
    CHECK(render::blur_background(img, kAspect, std::numeric_limits<float>::quiet_NaN()).rgba == img.rgba);
    const data::Image out = render::blur_background(img, kAspect, 4.0f);
    REQUIRE(out.width == 256);
    REQUIRE(out.height == 256);
    std::vector<uint8_t> data = img.rgba, temp;
    for (int i = 0; i < 3; ++i) render::box_blur(data, temp, 256, 256, 4, 68, 102);
    CHECK(out.rgba == data);
}

TEST_CASE("background image applies the configured blur") {
    const data::Image img = blur_test_image();
    data::BackgroundConfiguration conf;
    conf.blur = 0;
    CHECK(render::background_image(img, conf, kAspect).rgba == img.rgba);
    conf.blur = 4;
    CHECK(render::background_image(img, conf, kAspect).rgba == render::blur_background(img, kAspect, 4.0f).rgba);
}

}  // TEST_SUITE
