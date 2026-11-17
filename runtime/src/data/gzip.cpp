#include "data/gzip.h"

#include <algorithm>
#include <cstring>
#include <stdexcept>
#include <string>

#include "data/load_error.h"
#include "miniz.h"
#include "miniz_tdef.h"
#include "miniz_tinfl.h"

namespace sr::data {

namespace {

[[noreturn]] void fail(const std::string& why) { throw LoadError("gzip: " + why); }

uint32_t le32(const uint8_t* p) {
    return uint32_t(p[0]) | uint32_t(p[1]) << 8 | uint32_t(p[2]) << 16 | uint32_t(p[3]) << 24;
}

void put_le32(std::vector<uint8_t>& out, uint32_t v) {
    for (int i = 0; i < 4; ++i) out.push_back(uint8_t(v >> (8 * i)));
}

mz_bool append(const void* buf, int len, void* user) {
    auto& out = *static_cast<std::vector<uint8_t>*>(user);
    const auto* p = static_cast<const uint8_t*>(buf);
    out.insert(out.end(), p, p + len);
    return MZ_TRUE;
}

}  // namespace

std::vector<uint8_t> gunzip(std::span<const uint8_t> bytes, size_t max_output) {
    const size_t n = bytes.size();
    if (n < 18) fail("input too short");
    const uint8_t* d = bytes.data();
    if (d[0] != 0x1f || d[1] != 0x8b) fail("bad magic");
    if (d[2] != 8) fail("unsupported compression method");
    const uint8_t flags = d[3];
    size_t pos = 10;
    if (flags & 0x04) {  // FEXTRA
        if (pos + 2 > n) fail("truncated header");
        const size_t xlen = size_t(d[pos]) | size_t(d[pos + 1]) << 8;
        pos += 2 + xlen;
    }
    for (const uint8_t bit : {uint8_t(0x08), uint8_t(0x10)}) {  // FNAME, FCOMMENT
        if (flags & bit) {
            while (pos < n && d[pos] != 0) ++pos;
            ++pos;
        }
    }
    if (flags & 0x02) pos += 2;  // FHCRC
    if (pos + 8 > n) fail("truncated header");

    // Streaming inflate into a growing contiguous buffer (the empty payload is a valid
    // result, which the one-shot tinfl helper reports as failure).
    tinfl_decompressor inflator;
    tinfl_init(&inflator);
    const uint8_t* in = d + pos;
    size_t in_left = n - 8 - pos;
    std::vector<uint8_t> result(std::min<size_t>(max_output, 64 * 1024));
    size_t out_pos = 0;
    for (;;) {
        size_t in_n = in_left;
        size_t out_n = result.size() - out_pos;
        const tinfl_status status = tinfl_decompress(
            &inflator, in, &in_n, result.data(), result.data() + out_pos, &out_n,
            TINFL_FLAG_USING_NON_WRAPPING_OUTPUT_BUF);
        in += in_n;
        in_left -= in_n;
        out_pos += out_n;
        if (status == TINFL_STATUS_DONE) break;
        if (status == TINFL_STATUS_HAS_MORE_OUTPUT) {
            if (result.size() >= max_output) fail("decompressed size exceeds limit");
            result.resize(std::min<size_t>(max_output, result.size() * 2));
            continue;
        }
        fail("corrupt or truncated deflate stream");
    }
    result.resize(out_pos);

    const uint32_t crc = le32(d + n - 8);
    const uint32_t isize = le32(d + n - 4);
    if (isize != uint32_t(result.size())) fail("length mismatch");
    if (crc != uint32_t(mz_crc32(MZ_CRC32_INIT, result.data(), result.size()))) {
        fail("checksum mismatch");
    }
    return result;
}

std::vector<uint8_t> gzip(std::span<const uint8_t> bytes) {
    // Header: magic, deflate, no flags, mtime 0, no extra flags, OS unknown.
    std::vector<uint8_t> out{0x1f, 0x8b, 8, 0, 0, 0, 0, 0, 0, 0xff};
    out.reserve(out.size() + bytes.size() / 2 + 64);
    // Negative window bits: a raw deflate stream (no zlib header), as gzip wants.
    const int flags = int(tdefl_create_comp_flags_from_zip_params(MZ_DEFAULT_LEVEL, -MZ_DEFAULT_WINDOW_BITS,
                                                                  MZ_DEFAULT_STRATEGY));
    if (!tdefl_compress_mem_to_output(bytes.data(), bytes.size(), append, &out, flags)) {
        throw std::runtime_error("gzip: deflate failed");
    }
    put_le32(out, uint32_t(mz_crc32(MZ_CRC32_INIT, bytes.data(), bytes.size())));
    put_le32(out, uint32_t(bytes.size()));
    return out;
}

}  // namespace sr::data
