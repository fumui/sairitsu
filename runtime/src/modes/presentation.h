#pragma once

#include <cstdint>
#include <vector>

#include "modes/stats.h"

namespace sr::modes {

// What one Watch frame presents (watch-lifecycle__presentation-system): skin sprites, scheduled effect clips and
// particle effects, every call of the frame whether or not its entity is still active, plus the runtime blocks the
// renderer and built-in UI read. Sprite, clip and effect fields are resource indices (into LoadedLevel's
// skin.sprites, effect.clips and particle.effects, via ResourceMaps); a call whose id names nothing (not an exact
// integer, or no such resource) is not recorded.
//
// Order: the clip and particle calls of preparation (first frame only), then terminate, initialize,
// updateSequential and updateParallel; within a parallel callback (initialize, updateParallel, terminate) by entity
// index, within a sequential one in call order.

struct DrawCmd {
    enum Curve : uint8_t { None, B, T, L, R, BT, LR };  // Draw, DrawCurvedB, ..., DrawCurvedLR
    int32_t sprite;
    float x[4], y[4];  // corners bl, tl, tr, br
    float z[4];        // z1..z4; a z the call leaves out is z1 (inference: the wiki only marks z2..z4 optional)
    float a;
    uint8_t curve;          // Curve
    float curve_args[4];    // B/T/L/R: p, q, 0, 0. BT/LR: p1, q1, p2, q2. None: 0
    float segments;         // n of a curved draw, 0 for Draw
    int32_t entity;         // entity_key of the calling entity (kGlobalEntity for a global callback)
    uint32_t seq;           // order among the entity's draws this frame
};

struct AudioCmd {
    // Play, PlayLooped and StopLooped are Play only: time 0, start (or stop) at the next mix callback.
    enum Op : uint8_t { PlayScheduled, PlayLoopedScheduled, StopLoopedScheduled, Play, PlayLooped, StopLooped } op;
    int32_t clip;    // -1 for StopLoopedScheduled and StopLooped
    float time;      // the scheduled time, start time or end time; 0 for Play, PlayLooped, StopLooped
    float distance;  // PlayScheduled and Play only, else 0
    float handle;    // the loop id (PlayLoopedScheduled and PlayLooped return it, the Stop ops name it), else 0
};

struct ParticleCmd {
    enum Op : uint8_t { Spawn, Move, Destroy } op;
    float handle;  // the instance id SpawnParticleEffect returned
    int32_t effect;  // Spawn only, else -1
    float x[4], y[4];  // corners bl, tl, tr, br (Spawn, Move)
    float duration;    // Spawn only
    bool looped;       // Spawn only
};

struct FrameOutput {
    std::vector<DrawCmd> draws;
    std::vector<AudioCmd> audio;
    std::vector<ParticleCmd> particles;
    float skin_transform[16] = {};      // Runtime Skin Transform at the end of the frame (A2)
    float particle_transform[16] = {};  // Runtime Particle Transform
    float background[8] = {};           // Runtime Background
    float ui[100] = {};                 // Runtime UI
    Stats stats;
    bool ended = false;
    uint8_t haptic = 0;  // Play: the largest Entity Input index 4 among the frame's despawned entities, 0 for none
};

}  // namespace sr::modes
