#include "modes/replay_view.h"

#include <algorithm>

#include "time/frame_clock.h"

namespace sr::modes {

std::vector<ReplayMarker> replay_markers(const Replay& replay, double time, float aspect) {
    std::vector<ReplayMarker> out;
    float scale = 1;
    if (replay.width > 0 && replay.height > 0)
        scale = aspect / float(double(replay.width) / double(replay.height));
    for (const ReplayTouch& touch : replay.touches) {
        const std::vector<ReplayPoint>& p = touch.points;
        if (p.empty() || !(time >= p.front().t) || !(time <= p.back().t)) continue;
        // The first point after `time` (points are in time order); `time` lies between it and the one before.
        const auto after = std::upper_bound(p.begin(), p.end(), time,
                                            [](double t, const ReplayPoint& q) { return t < q.t; });
        float x, y;
        if (after == p.end()) {
            x = p.back().x;
            y = p.back().y;
        } else {
            const ReplayPoint& a = *(after - 1);  // after != begin: time >= the first point's t
            const ReplayPoint& b = *after;
            const double f = b.t > a.t ? (time - a.t) / (b.t - a.t) : 0.0;
            x = float(double(a.x) + (double(b.x) - double(a.x)) * f);
            y = float(double(a.y) + (double(b.y) - double(a.y)) * f);
        }
        out.push_back(ReplayMarker{x * scale, y});
    }
    return out;
}

std::vector<WatchTick> replay_ticks(const Replay& replay) {
    std::vector<WatchTick> out;
    for (const ReplayInput& in : replay.inputs) {
        if (in.judgment == 0) out.push_back(WatchTick{in.time, true});
        else if (in.judgment == 3) out.push_back(WatchTick{in.time, false});
    }
    return out;
}

double clamp_seek(double t, double end) {
    const double lo = -time::kPreRoll;
    if (!(t > lo)) return lo;  // NaN included
    return t < end ? t : std::max(end, lo);
}

}  // namespace sr::modes
