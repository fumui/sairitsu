#include "data/png.h"

#include <string>

#include "data/load_error.h"
#include "stb_image.h"

namespace sr::data {

Image decode_png(std::span<const uint8_t> bytes) {
    int w = 0, h = 0, channels = 0;
    unsigned char* pixels = stbi_load_from_memory(bytes.data(), static_cast<int>(bytes.size()), &w,
                                                  &h, &channels, 4);
    if (pixels == nullptr) {
        throw LoadError(std::string("png: ") + stbi_failure_reason());
    }
    Image image;
    image.width = w;
    image.height = h;
    image.rgba.assign(pixels, pixels + size_t(w) * size_t(h) * 4);
    stbi_image_free(pixels);
    return image;
}

}  // namespace sr::data
