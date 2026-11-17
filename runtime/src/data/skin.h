#pragma once

#include <cstdint>
#include <string>
#include <string_view>
#include <vector>

namespace sr::data {

// Skin transform: each output coordinate is a linear combination of the input
// coordinates. Outputs are ordered x1,y1,x2,y2,x3,y3,x4,y4 (row); inputs are
// ordered x1,x2,x3,x4,y1,y2,y3,y4 (column). Terms absent from the JSON are 0.
struct SkinTransform {
    float coef[8][8] = {};
};

struct SkinSprite {
    std::string name;
    float x = 0, y = 0, w = 0, h = 0;
    SkinTransform transform;
};

struct SkinData {
    int32_t width = 0;
    int32_t height = 0;
    bool interpolation = false;
    std::vector<SkinSprite> sprites;
};

// `json` is the decompressed skin data. Throws LoadError.
SkinData parse_skin_data(std::string_view json);

// Shared with particle_data: row index of an output key such as "y3" in a transform
// ("x1" -> 0, "y1" -> 1, "x2" -> 2, ...), or -1 when the key is not x1..y4.
int transform_row(const std::string& key);
// Column index of an input key ("x1".."x4" -> 0..3, "y1".."y4" -> 4..7), or -1.
int transform_column(const std::string& key);

}  // namespace sr::data
