#pragma once

#include <cstddef>
#include <cstdint>
#include <span>
#include <vector>

namespace sr::data {

// Decompresses a gzip member (header parsed by hand, deflate by miniz tinfl).
// Verifies the CRC32 and length trailer. Throws LoadError on any malformed or
// truncated input, or output larger than `max_output` bytes (default 512 MiB).
inline constexpr size_t kMaxDecompressedBytes = size_t(512) << 20;
std::vector<uint8_t> gunzip(std::span<const uint8_t> bytes,
                            size_t max_output = kMaxDecompressedBytes);

// Compresses `bytes` into one gzip member (deflate by miniz tdefl at the default level; no name, mtime 0).
std::vector<uint8_t> gzip(std::span<const uint8_t> bytes);

}  // namespace sr::data
