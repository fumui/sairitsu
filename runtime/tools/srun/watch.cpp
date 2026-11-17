#include "watch.h"

#include <algorithm>
#include <cstdlib>
#include <fstream>
#include <istream>
#include <iterator>
#include <memory>
#include <ostream>
#include <stdexcept>

#include "data/json.h"
#include "data/load_error.h"
#include "dump.h"
#include "sha256.h"
#include "time/frame_clock.h"

namespace sr::tools {

FrameClock fixed_rate(double fps) {
    return [fps](int64_t frame, bool ended) -> std::optional<double> {
        const double t = -time::kPreRoll + double(frame) / fps;
        if (ended || t > 3600) return std::nullopt;
        return t;
    };
}

FrameClock listed(std::vector<double> times) {
    auto shared = std::make_shared<const std::vector<double>>(std::move(times));
    return [shared](int64_t frame, bool) -> std::optional<double> {
        if (frame < 0 || size_t(frame) >= shared->size()) return std::nullopt;
        return (*shared)[size_t(frame)];
    };
}

std::vector<double> read_times(std::istream& in) {
    std::vector<double> out;
    std::string line;
    int line_no = 0;
    while (std::getline(in, line)) {
        ++line_no;
        const size_t first = line.find_first_not_of(" \t\r");
        if (first == std::string::npos) continue;
        const char* start = line.c_str() + first;
        char* end = nullptr;
        const double value = std::strtod(start, &end);  // the program never sets a locale: "C"
        if (end == start || line.find_first_not_of(" \t\r", size_t(end - line.c_str())) != std::string::npos)
            throw std::runtime_error("times line " + std::to_string(line_no) + ": not a number: " + line);
        out.push_back(value);
    }
    return out;
}

time::FrameTime frame_time(double t, std::optional<double> previous, const time::TimeTables& times) {
    // Headless clock. Time is the music time rounded to float; delta
    // the difference from the previous frame (0 for the first, negative after a step backwards); scaled time from
    // the timescale table; skip is never set.
    time::FrameTime f;
    f.time = float(t);
    f.delta = previous ? float(t - *previous) : 0.0f;
    f.scaled = times.time_to_scaled_time(f.time);
    f.skip = false;
    return f;
}

WatchSummary run_watch(const data::LoadedLevel& level, const modes::WatchConfig& config, const FrameClock& clock,
                       std::ostream* dump) {
    const time::TimeTables times = time::TimeTables::build(level.level, level.watch);
    std::unique_ptr<modes::WatchSession> session = modes::WatchSession::create(level, config);
    session->prepare();

    WatchSummary s;
    s.level_entities = session->entity_count();
    Sha256 hash;
    std::string line;
    std::vector<uint8_t> was_active;
    std::optional<double> previous;
    bool ended = false;
    for (int64_t i = 0;; ++i) {
        const std::optional<double> t = clock(i, ended);
        if (!t) break;
        const time::FrameTime frame = frame_time(*t, previous, times);
        previous = t;
        const modes::FrameOutput& out = session->step(frame);
        if (session->faulted()) {
            s.fault = session->fault().detail;
            if (s.fault.empty()) s.fault = "fault";
            break;
        }
        line.clear();
        append_frame(line, frame.time, out);
        hash.update(line);
        if (dump != nullptr) dump->write(line.data(), std::streamsize(line.size()));
        ++s.frames;
        ended = s.ended = out.ended;
        if (!out.ended) s.last_time = frame.time;

        const size_t n = size_t(session->entity_count() + session->spawned_count());
        was_active.resize(n, 0);
        s.spawns.resize(n, 0);
        int64_t active = 0;
        for (size_t e = 0; e < n; ++e) {
            const bool now = session->active(int32_t(e));
            if (now && !was_active[e]) ++s.spawned, ++s.spawns[e];
            if (!now && was_active[e]) ++s.despawned;
            was_active[e] = now ? 1 : 0;
            active += now ? 1 : 0;
        }
        s.max_active = std::max(s.max_active, active);
        s.active_at_end = active;
    }
    for (size_t e = 0; e < was_active.size(); ++e)
        if (was_active[e]) s.still_active.push_back(int32_t(e));
    s.hash = hash.hex();
    const size_t n = size_t(session->entity_count() + session->spawned_count());
    s.spawns.resize(n, 0);
    s.spawn_times.assign(session->spawn_times().begin(), session->spawn_times().end());
    s.despawn_times.assign(session->despawn_times().begin(), session->despawn_times().end());
    return s;
}

namespace {

void put_json_string(std::string& out, const std::string& text) {
    out += '"';
    for (const char ch : text) {
        const auto c = static_cast<unsigned char>(ch);
        if (c == '"' || c == '\\') {
            out += '\\';
            out += ch;
        } else if (c < 0x20) {
            static const char* digits = "0123456789abcdef";
            out += "\\u00";
            out += digits[c >> 4];
            out += digits[c & 0xF];
        } else {
            out += ch;
        }
    }
    out += '"';
}

}  // namespace

std::string summary_line(const WatchSummary& s) {
    std::string out = "{\"hash\":\"" + s.hash + "\",\"frames\":" + std::to_string(s.frames) +
                      ",\"spawned\":" + std::to_string(s.spawned) + ",\"despawned\":" + std::to_string(s.despawned) +
                      ",\"max_active\":" + std::to_string(s.max_active) +
                      ",\"active_at_end\":" + std::to_string(s.active_at_end) + ",\"fault\":";
    if (s.fault.empty()) out += "null";
    else put_json_string(out, s.fault);
    out += "}\n";
    return out;
}

}  // namespace sr::tools
