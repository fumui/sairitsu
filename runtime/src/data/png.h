#pragma once

#include <cstdint>
#include <span>
#include <vector>

namespace sr::data {

struct Image {
    int32_t width = 0;
    int32_t height = 0;
    std::vector<uint8_t> rgba;  // width * height * 4, straight (non-premultiplied) alpha
};

// Decodes a PNG to RGBA8 with stb_image. Throws LoadError on failure.
Image decode_png(std::span<const uint8_t> bytes);

}  // namespace sr::data
