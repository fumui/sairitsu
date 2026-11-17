#pragma once

#include <cstdint>
#include <string>
#include <string_view>

namespace sr::tools {

// SHA-256 (FIPS 180-4), for dump hashes that host and device must agree on.
class Sha256 {
public:
    Sha256();
    void update(std::string_view bytes);
    // The digest as 64 lowercase hex digits. Finishes the hash: call once.
    std::string hex();

private:
    void block(const uint8_t* p);

    uint32_t h_[8];
    uint8_t buffer_[64];
    size_t buffered_ = 0;
    uint64_t length_ = 0;  // bytes
};

std::string sha256_hex(std::string_view bytes);

}  // namespace sr::tools
