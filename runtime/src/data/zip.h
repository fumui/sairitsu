#pragma once

#include <cstddef>
#include <cstdint>
#include <map>
#include <span>
#include <string>
#include <vector>

namespace sr::data {

// Extracts every file entry of a zip archive held in memory, keyed by entry name.
// Throws LoadError for a malformed archive or an entry whose declared size exceeds
// `max_entry` bytes (default 512 MiB).
inline constexpr size_t kMaxZipEntryBytes = size_t(512) << 20;
std::map<std::string, std::vector<uint8_t>> unzip(std::span<const uint8_t> bytes,
                                                  size_t max_entry = kMaxZipEntryBytes);

}  // namespace sr::data
