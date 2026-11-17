#include "audio/platform_decode.h"

namespace sr::audio {

std::optional<Pcm> platform_decode(const std::string&, std::string& error) {
    error = "no platform decoder on this host";
    return std::nullopt;
}

}  // namespace sr::audio
