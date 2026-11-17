#pragma once

#include <cstdint>
#include <optional>
#include <string_view>

#include "data/engine_data.h"

namespace sr::vm {

// One enumerator per runtime function (functions.inc).
enum class Fn : uint16_t {
#define FN(id, name, min_args, max_args, modes) id,
#include "vm/functions.inc"
#undef FN
};

inline constexpr int kFnCount = 0
#define FN(id, name, min_args, max_args, modes) +1
#include "vm/functions.inc"
#undef FN
    ;

// Bits of FnInfo::modes, one per data::Mode.
constexpr uint8_t mode_bit(data::Mode m) { return uint8_t(1u << unsigned(m)); }

struct FnInfo {
    std::string_view name;
    int8_t min_args, max_args;  // max_args -1 = variadic
    uint8_t modes;              // mode_bit() of each mode the function is legal in
};

const FnInfo& fn_info(Fn fn);
std::optional<Fn> fn_from_name(std::string_view name);
inline bool fn_allowed(Fn fn, data::Mode mode) { return (fn_info(fn).modes & mode_bit(mode)) != 0; }

// Functions the optimizer may evaluate at compile time when every argument is a constant: pure float32
// math with no memory, host or control-flow behaviour. (sonolus.py marks more ops pure, e.g. BeatToTime
// or StreamGetValue, but those depend on level data or replay streams the compiler doesn't have.)
bool fn_foldable(Fn fn);

}  // namespace sr::vm
