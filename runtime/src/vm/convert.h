#pragma once

#include <cstdint>

namespace sr::vm {

// Float to int for indices into a block (Get/Set/Copy/stack positions, Break counts and other counts).
// Truncate toward zero, saturate to int32, NaN -> 0 (arm64 fcvtzs).
int32_t to_index(float x);

// What a block id, sprite id, clip id or particle effect id names nothing as.
inline constexpr int32_t kNoId = -1;

// Float to an id (block, skin sprite, effect clip, particle effect): the id only when x is an exact integer in
// int32 range (-0 is 0), else kNoId: a fractional or NaN id names nothing. Switch indices follow the same rule (branch_index in interpreter.h).
int32_t to_id(float x);

}  // namespace sr::vm
