#pragma once

#include <cstdint>
#include <span>

#include "vm/functions.h"

namespace sr::vm {

// Side effects and level-dependent queries of the runtime functions, one virtual per family.
// Each call names its function and carries the arguments as the interpreter evaluated them (plain floats,
// in the wiki's argument order). `args` points into the interpreter's value stack: valid only during the call.
// The interpreter has already checked the argument count against functions.inc.
// A sprite, effect clip or particle effect id names something only when it is an exact
// integer; hosts convert ids with sr::vm::to_id (convert.h) and treat kNoId as naming nothing (no draw, Has* = 0).

struct DrawCall {      // Draw, DrawCurvedB/BT/L/LR/R/T
    Fn fn;
    std::span<const float> args;
};
struct AudioCall {     // Play, PlayLooped, StopLooped, PlayScheduled, PlayLoopedScheduled, StopLoopedScheduled,
    Fn fn;             // HasEffectClip
    std::span<const float> args;
};
struct ParticleCall {  // SpawnParticleEffect, MoveParticleEffect, DestroyParticleEffect, HasParticleEffect
    Fn fn;
    std::span<const float> args;
};
struct StreamCall {    // StreamGetNextKey, StreamGetPreviousKey, StreamGetValue, StreamHas, StreamSet
    Fn fn;
    std::span<const float> args;
};
struct TimeCall {      // BeatToBPM, BeatToStartingBeat, BeatToStartingTime, BeatToTime,
    Fn fn;             // TimeToScaledTime, TimeToStartingScaledTime, TimeToStartingTime, TimeToTimeScale
    float value;
};
struct DebugCall {     // DebugLog, DebugPause (Paint and Print are illegal in Play and Watch)
    Fn fn;
    std::span<const float> args;
};

class Host {
public:
    virtual ~Host() = default;

    virtual void draw(const DrawCall& call) = 0;
    virtual float audio(const AudioCall& call) = 0;        // the function's return value (loop id, 1/0, or 0)
    virtual float particle(const ParticleCall& call) = 0;  // particle id, 1/0, or 0
    virtual void spawn(int32_t archetype, std::span<const float> data) = 0;
    virtual float stream(const StreamCall& call) = 0;
    virtual float time(const TimeCall& call) = 0;
    virtual float has_skin_sprite(int32_t id) = 0;          // 1 if the sprite is available, else 0
    virtual void add_life_scheduled(float value, float time) = 0;
    // Uniform in [0, 1). Random(min, max) is min + (max - min) * random(); RandomInteger(min, max) is
    // min + floor((max - min) * random()) over the to_index'd bounds.
    virtual float random() = 0;
    virtual void debug(const DebugCall& call) = 0;
    virtual void export_value(int32_t index, float value) = 0;
};

}  // namespace sr::vm
