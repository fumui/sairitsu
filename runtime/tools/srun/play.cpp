#include "play.h"

#include <algorithm>
#include <cmath>
#include <map>
#include <memory>
#include <optional>
#include <set>

#include "dump.h"
#include "time/frame_clock.h"
#include "time/time_tables.h"

namespace sr::tools {

namespace {

// A point's t is (int)((frame Time - inputOffset) x 1000).
int64_t point_ms(double t) { return std::llround(t * 1000.0); }

// The frame time the clock gives grid point x (seconds since the run's start): float32 unscaledTime minus
// float32 StartTime, both float, the difference rounded to float.
float quantised(float start, double x) {
    const float u = float(double(start) + x);
    return u - start;
}

// How many known frames (k, t) the clock (start, t0, period) reproduces exactly.
int64_t reproduced(const std::vector<std::pair<int64_t, float>>& known, float start, double t0, double period) {
    int64_t n = 0;
    for (const auto& [k, t] : known) n += quantised(start, t0 + double(k) * period) == t ? 1 : 0;
    return n;
}

// The StartTime that best reproduces the known frames on the grid (t0, period): for each float binade of
// StartTime and each place the clock can cross into the next binade (at a known frame, or not at all), and each
// parity of StartTime in units of its ulp, the candidate reproducing the most frames (the smallest on ties).
float fit_start(const std::vector<std::pair<int64_t, float>>& known, double t0, double period) {
    float best = 0;
    int64_t best_n = -1;
    const double last = t0 + double(known.back().first) * period;
    for (int e = 1; e <= 30; ++e) {
        const double lo = std::ldexp(1.0, e), hi = std::ldexp(1.0, e + 1), q = std::ldexp(1.0, e - 23);
        std::vector<double> targets{hi - last - 1.0};  // no crossing within the run
        for (size_t i = 0; i < known.size(); ++i) {
            const double x = t0 + double(known[i].first) * period;
            targets.push_back(hi - x + period / 2);  // the crossing just before this frame
        }
        for (const double target : targets) {
            for (int parity = 0; parity < 2; ++parity) {
                const double m = std::round((target / q - parity) / 2);
                const double s = q * (2 * m + parity);
                if (s < lo || s >= hi || s < time::kPreRoll) continue;
                const float start = float(s);
                const int64_t n = reproduced(known, start, t0, period);
                if (n > best_n || (n == best_n && start < best)) best = start, best_n = n;
            }
        }
    }
    return best;
}

// Refines (t0, period) for `start` so the grid passes inside every known frame's rounding interval: scans the
// period around the least-squares one and keeps the widest feasible range of t0, taking its middle.
void refine_grid(const std::vector<std::pair<int64_t, float>>& known, float start, double& t0, double& period) {
    struct Bound {
        double k, lo, hi;  // lo <= t0 + k period < hi
    };
    std::vector<Bound> bounds;
    for (const auto& [k, t] : known) {
        const double u = double(start) + double(t);
        if (double(float(u)) != u) continue;  // not an exact difference; no constraint
        const float uf = float(u);
        const double below = (u - double(std::nextafter(uf, -INFINITY))) / 2;
        const double above = (double(std::nextafter(uf, INFINITY)) - u) / 2;
        bounds.push_back({double(k), u - below - double(start), u + above - double(start)});
    }
    double best_width = -INFINITY, best_t0 = t0, best_period = period;
    for (int i = -4000; i <= 4000; ++i) {
        const double p = period + double(i) * 1e-13;
        double lo = -INFINITY, hi = INFINITY;
        for (const Bound& b : bounds) {
            lo = std::max(lo, b.lo - b.k * p);
            hi = std::min(hi, b.hi - b.k * p);
        }
        if (hi - lo > best_width) best_width = hi - lo, best_t0 = (lo + hi) / 2, best_period = p;
    }
    t0 = best_t0;
    period = best_period;
}

}  // namespace

double feed_frame(const ReplayFeed& feed, int64_t k) {
    const auto it = feed.exact.find(k);
    if (it != feed.exact.end()) return double(it->second);
    const double x = feed.t0 + double(k) * feed.period;
    return feed.start > 0 ? double(quantised(feed.start, x)) : x;
}

ReplayFeed replay_feed(const modes::Replay& replay, double fps) {
    ReplayFeed feed;
    feed.period = 1.0 / fps;
    std::set<float> times;
    for (const modes::ReplayInput& in : replay.inputs) times.insert(in.time);
    if (!times.empty()) {
        // Least squares t = t0 + k period over the input times, k counted from the first at the nominal rate.
        const double first = double(*times.begin());
        double n = 0, sk = 0, st = 0, skk = 0, skt = 0;
        std::vector<std::pair<int64_t, float>> known;
        for (const float t : times) {
            const int64_t k = std::llround((double(t) - first) * fps);
            known.emplace_back(k, t);
            n += 1, sk += double(k), st += double(t), skk += double(k) * double(k), skt += double(k) * double(t);
        }
        const double d = n * skk - sk * sk;
        if (d != 0) feed.period = (n * skt - sk * st) / d;
        feed.t0 = (st - feed.period * sk) / n;
        for (const auto& [k, t] : known) {
            feed.exact[k] = t;
            feed.max_residual = std::max(feed.max_residual, std::fabs(double(t) - (feed.t0 + double(k) * feed.period)));
        }
        // The frame times are float differences of float clocks; recover StartTime and the grid under it.
        feed.start = fit_start(known, feed.t0, feed.period);
        refine_grid(known, feed.start, feed.t0, feed.period);
        feed.reproduced = reproduced(known, feed.start, feed.t0, feed.period);
        // Stream keys written at a frame's time pin that frame too. The grid can sit on a rounding tie (it is good to
        // a few 1e-8 s); a key one clock step from its grid frame, and itself a float clock difference, settles it.
        for (const modes::ReplayStream& s : replay.streams)
            for (const float key : s.keys) {
                const int64_t k = std::llround((double(key) - feed.t0) / feed.period);
                if (feed.exact.count(k) != 0) continue;
                const float grid = quantised(feed.start, feed.t0 + double(k) * feed.period);
                const float u = float(double(feed.start) + double(key));
                const double step = double(std::nextafter(u, INFINITY)) - double(u);
                if (key == grid || u - feed.start != key || std::fabs(double(key) - double(grid)) > 1.5 * step) continue;
                feed.exact[k] = key;
                ++feed.stream_frames;
            }
    } else {
        feed.t0 = -time::kPreRoll;
    }
    feed.first = int64_t(std::ceil((-time::kPreRoll - feed.t0) / feed.period - 1e-9));

    struct Keyed {
        int64_t ms;
        modes::TouchEvent event;
    };
    std::vector<Keyed> all;
    for (size_t i = 0; i < replay.touches.size(); ++i) {
        const std::vector<modes::ReplayPoint>& points = replay.touches[i].points;
        for (size_t k = 0; k < points.size(); ++k) {
            const modes::ReplayPoint& p = points[k];
            const int64_t ms = point_ms(p.t);
            // The frame whose time truncates toward zero to the point's millisecond, give or take 20 us (the grid
            // is good to float32 rounding, a few us, which can carry a frame across a millisecond boundary).
            std::optional<double> frame;
            const int64_t guess = int64_t(std::floor((p.t - feed.t0) / feed.period));
            const double lo = ms >= 0 ? double(ms) : double(ms) - 1.0, slack = 0.02;
            for (int64_t c = guess - 1; c <= guess + 2 && !frame; ++c) {
                const double f = feed_frame(feed, c), x = f * 1000.0;
                if (x >= lo - slack && x < lo + 1.0 + slack) frame = f;
            }
            if (!frame) ++feed.unmatched_points;
            modes::TouchEvent::Phase phase = modes::TouchEvent::Move;
            if (k == 0) phase = modes::TouchEvent::Down;
            else if (k + 1 == points.size()) phase = modes::TouchEvent::Up;
            // At its recorded time, but no later than its frame (a negative time truncates up).
            const double t = frame ? std::min(p.t, *frame) : p.t;
            all.push_back({ms, modes::TouchEvent{int32_t(i), phase, t, p.x, p.y}});
        }
    }
    std::stable_sort(all.begin(), all.end(), [](const Keyed& a, const Keyed& b) { return a.ms < b.ms; });
    for (const Keyed& k : all) feed.events.push_back(k.event);
    return feed;
}

modes::PlayConfig replay_play_config(const modes::Replay& replay, std::vector<float> options) {
    modes::PlayConfig config;
    config.options = std::move(options);
    modes::Environment& env = config.env;
    env.aspect = float(replay.width) / float(replay.height);
    env.safe_x_min = -env.aspect;
    env.safe_x_max = env.aspect;
    env.safe_y_min = -1;
    env.safe_y_max = 1;
    env.input_offset = replay.input_offset;
    return config;
}

FrameClock replay_clock(const ReplayFeed& feed) {
    auto shared = std::make_shared<const ReplayFeed>(feed);
    return [shared](int64_t frame, bool ended) -> std::optional<double> {
        if (ended || frame < 0) return std::nullopt;
        const double t = feed_frame(*shared, shared->first + frame);
        if (t > 3600) return std::nullopt;
        return t;
    };
}

std::vector<float> delta_round_trip(const std::vector<float>& values) {
    std::vector<float> out;
    float previous = 0, sum = 0;
    for (const float v : values) {
        sum += v - previous;
        previous = v;
        out.push_back(sum);
    }
    return out;
}

bool within_ulp(float ours, float theirs) {
    if (ours == theirs) return true;
    const float ulp = std::nextafter(std::fabs(theirs), INFINITY) - std::fabs(theirs);
    return std::fabs(ours - theirs) <= ulp;
}

PlayReport run_play(const data::LoadedLevel& level, const modes::Replay& replay, const modes::PlayConfig& config,
                    const FrameClock& clock, const std::vector<modes::TouchEvent>& events,
                    modes::TouchRecorder* recorder) {
    const time::TimeTables times = time::TimeTables::build(level.level, level.play);
    std::unique_ptr<modes::PlaySession> session = modes::PlaySession::create(level, config);
    session->prepare();

    PlayReport r;
    modes::TouchPool pool;
    for (const modes::TouchEvent& e : events) pool.push(e);
    std::optional<double> previous;
    bool ended = false;
    for (int64_t i = 0; !session->faulted(); ++i) {
        const std::optional<double> t = clock(i, ended);
        if (!t) break;
        const std::vector<modes::Touch> touches = pool.frame(*t);
        session->set_touches(touches);
        const modes::FrameOutput& out = session->step(frame_time(*t, previous, times));
        // The ending frame runs no system, so a replay records nothing in it.
        if (recorder && !out.ended) recorder->frame(*t, touches);
        previous = t;
        ++r.frames;
        ended = out.ended;
    }
    if (recorder)
        r.written = modes::replay_data(*session, level, *recorder, previous.value_or(0), replay.width, replay.height,
                                       config.env.input_offset);
    if (session->faulted()) {
        r.fault = session->fault().detail;
        if (r.fault.empty()) r.fault = "fault";
    }
    r.ended = session->ended();
    r.judgments = session->judgments();
    r.result = session->result();

    // Judgments, by entity.
    std::map<int32_t, const modes::StatInput*> ours;
    for (const modes::StatInput& in : r.judgments) ours.emplace(in.entity, &in);
    for (const modes::ReplayInput& in : replay.inputs) {
        const auto it = ours.find(in.entity);
        if (it == ours.end() || it->second->judgment != in.judgment || !within_ulp(it->second->accuracy, in.accuracy))
            ++r.mismatches;
    }
    r.mismatches += int32_t(std::max<size_t>(r.judgments.size(), replay.inputs.size()) - replay.inputs.size());

    // Streams: keys and values exactly, in float.
    // Streams: keys and values exactly, in float, as a replay file would give them back (delta-encoded, then
    // summed by parse_replay; the round trip can round, e.g. 1e8 + 1.8 -> 1e8).
    r.streams = session->streams().dump();
    const std::vector<modes::ReplayStream>& dump = r.streams;
    r.streams_equal = dump.size() == replay.streams.size();
    for (size_t i = 0; r.streams_equal && i < dump.size(); ++i)
        r.streams_equal = dump[i].id == replay.streams[i].id && delta_round_trip(dump[i].keys) == replay.streams[i].keys &&
                          delta_round_trip(dump[i].values) == replay.streams[i].values;

    // Exports by name: an index is the position of its name in the archetype's `exports` list.
    std::set<std::string> skipped;
    const int32_t n = std::min<int32_t>(session->entity_count(), int32_t(replay.exports.size()));
    for (int32_t e = 0; e < n; ++e) {
        const std::vector<data::Import>& names = level.play.archetypes[size_t(session->archetype_of(e))].exports;
        for (const auto& [name, value] : replay.exports[size_t(e)]) {
            const auto at = std::find_if(names.begin(), names.end(),
                                         [&](const data::Import& x) { return x.name == name; });
            if ((e == 0 && name == "replayRevision") || at == names.end()) {
                skipped.insert(name);
                continue;
            }
            ++r.exports_compared;
            const float mine = session->exported(e, int32_t(at - names.begin()));
            if (mine != value) r.export_diffs.push_back({e, name, mine, value});
        }
    }
    r.skipped_exports.assign(skipped.begin(), skipped.end());
    return r;
}

std::string judgment_line(const modes::StatInput& in) {
    return "{\"entity\":" + std::to_string(in.entity) + ",\"time\":\"" + hex_float(in.time) + "\",\"judgment\":\"" +
           hex_float(in.judgment) + "\",\"accuracy\":\"" + hex_float(in.accuracy) + "\"}\n";
}

std::string play_summary_line(const PlayReport& r) {
    const modes::GameplayResult& g = r.result;
    std::string out = "{\"result\":{\"grade\":\"" + std::string(modes::grade_name(g.grade)) +
                      "\",\"arcadeScore\":" + std::to_string(g.arcade_score) +
                      ",\"accuracyScore\":" + std::to_string(g.accuracy_score) +
                      ",\"combo\":" + std::to_string(g.combo) + ",\"perfect\":" + std::to_string(g.perfect) +
                      ",\"great\":" + std::to_string(g.great) + ",\"good\":" + std::to_string(g.good) +
                      ",\"miss\":" + std::to_string(g.miss) + ",\"totalCount\":" + std::to_string(g.total) +
                      "},\"streams_equal\":" + (r.streams_equal ? "true" : "false") +
                      ",\"exports_equal\":" + (r.export_diffs.empty() ? "true" : "false") +
                      ",\"mismatches\":" + std::to_string(r.mismatches) + ",\"frames\":" + std::to_string(r.frames) +
                      ",\"ended\":" + (r.ended ? "true" : "false") + ",\"fault\":";
    if (r.fault.empty()) {
        out += "null";
    } else {
        std::string text = r.fault;
        std::replace_if(text.begin(), text.end(), [](char c) { return c == '"' || c == '\\' || c < 0x20; }, '\'');
        out += "\"" + text + "\"";
    }
    out += "}\n";
    return out;
}

}  // namespace sr::tools
