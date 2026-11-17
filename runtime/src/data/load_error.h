#pragma once

#include <stdexcept>

namespace sr::data {

// Thrown by every resource parser. The message names the resource kind and the
// field path, e.g. "engine.playData: missing field 'archetypes[3].name'".
struct LoadError : std::runtime_error {
    using std::runtime_error::runtime_error;
};

}  // namespace sr::data
