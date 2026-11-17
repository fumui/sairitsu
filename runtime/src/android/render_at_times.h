#pragma once

#include <cstdint>
#include <filesystem>
#include <string>
#include <vector>

#include "data/resources.h"
#include "modes/watch_session.h"

namespace sr::android {

// Render-at-time (debug builds only): the offscreen frame size (a 3200 x 2136 landscape screen).
inline constexpr int32_t kRenderWidth = 3200, kRenderHeight = 2136;

// Renders `level` in Watch with `config` (Runtime Environment for kRenderWidth x kRenderHeight) at each of `times`
// into <out_dir>/<index>_<time>.png, with a sidecar <index>_<time>.particles.json: the particle effect instances alive
// at that time (from the session's ParticleCmd stream) and Runtime Particle
// Transform, so a PC-side tool can tell where particles can be. A fresh session steps a 1/120 s grid from
// 0 and lands exactly on each requested time (taken in ascending order; the index is the time's position in `times`).
// No audio. Needs no GL context from the caller (it makes its own). Returns the error, empty on success.
std::string render_at_times(const data::LoadedLevel& level, const modes::WatchConfig& config,
                            const std::vector<float>& times, const std::filesystem::path& out_dir);

}  // namespace sr::android
