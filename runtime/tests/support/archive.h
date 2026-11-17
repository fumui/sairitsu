#pragma once

// Test helpers that build gzip / zip / png byte streams with miniz.

#include <cstdint>
#include <cstring>
#include <map>
#include <string>
#include <vector>

#include "miniz.h"
#include "miniz_tdef.h"
#include "miniz_zip.h"

namespace sr::test {

inline std::vector<uint8_t> make_gzip(const std::string& payload) {
    size_t deflated_len = 0;
    void* deflated = tdefl_compress_mem_to_heap(payload.data(), payload.size(), &deflated_len, 0);
    std::vector<uint8_t> out = {0x1f, 0x8b, 8, 0, 0, 0, 0, 0, 0, 3};
    out.insert(out.end(), static_cast<uint8_t*>(deflated),
               static_cast<uint8_t*>(deflated) + deflated_len);
    mz_free(deflated);
    const uint32_t crc = static_cast<uint32_t>(mz_crc32(
        MZ_CRC32_INIT, reinterpret_cast<const unsigned char*>(payload.data()), payload.size()));
    const uint32_t size = static_cast<uint32_t>(payload.size());
    for (uint32_t v : {crc, size}) {
        for (int i = 0; i < 4; ++i) out.push_back(uint8_t(v >> (8 * i)));
    }
    return out;
}

inline std::vector<uint8_t> make_zip(const std::map<std::string, std::string>& files) {
    mz_zip_archive zip;
    std::memset(&zip, 0, sizeof zip);
    mz_zip_writer_init_heap(&zip, 0, 0);
    for (const auto& [name, data] : files) {
        mz_zip_writer_add_mem(&zip, name.c_str(), data.data(), data.size(), MZ_DEFAULT_COMPRESSION);
    }
    void* buf = nullptr;
    size_t size = 0;
    mz_zip_writer_finalize_heap_archive(&zip, &buf, &size);
    std::vector<uint8_t> out(static_cast<uint8_t*>(buf), static_cast<uint8_t*>(buf) + size);
    mz_zip_writer_end(&zip);
    return out;
}

inline std::vector<uint8_t> make_png(int w, int h, const std::vector<uint8_t>& rgba) {
    size_t size = 0;
    void* png = tdefl_write_image_to_png_file_in_memory(rgba.data(), w, h, 4, &size);
    std::vector<uint8_t> out(static_cast<uint8_t*>(png), static_cast<uint8_t*>(png) + size);
    mz_free(png);
    return out;
}

}  // namespace sr::test
