#include "modes/replay_writer.h"

#include <cstdlib>
#include <memory>
#include <stdexcept>
#include <string>

#include "data/gzip.h"
#include "yyjson.h"

namespace sr::modes {

namespace {

struct DocFree {
    void operator()(yyjson_mut_doc* doc) const { yyjson_mut_doc_free(doc); }
};
using MutDoc = std::unique_ptr<yyjson_mut_doc, DocFree>;

// A float written as the double of its float difference from the one before (from 0); parse_replay sums them
// again in float.
void add_float_deltas(yyjson_mut_doc* doc, yyjson_mut_val* arr, const std::vector<float>& values) {
    float previous = 0;
    for (const float v : values) {
        yyjson_mut_arr_add_real(doc, arr, double(v - previous));
        previous = v;
    }
}

// (int)(X - cx), from screen units. A touch's units are float(px / (H/2)) (to_touch_event; the replay feed alike),
// off by up to half a float ulp, so units x (H/2) can land just short of a whole px and truncate 1 px toward zero (80
// of the benchmark's 525 touches did, one of them losing points to the replacement rule). A value within 2^-23
// (relative) of the next integer away from zero is taken as that integer: the round trip gives back the original px,
// and a real sub-pixel position moves by 0.0002 px at most.
int32_t to_px(float units, float half_height) {
    const double v = double(units) * double(half_height);
    const double tolerance = (v < 0 ? -v : v) * 0x1p-23;
    return int32_t(v < 0 ? v - tolerance : v + tolerance);
}

std::vector<uint8_t> write_gzip(yyjson_mut_doc* doc, const char* what) {
    size_t len = 0;
    yyjson_write_err err;
    char* json = yyjson_mut_write_opts(doc, YYJSON_WRITE_NOFLAG, nullptr, &len, &err);
    if (!json) throw std::runtime_error(std::string(what) + ": " + (err.msg ? err.msg : "write failed"));
    const std::unique_ptr<char, decltype(&std::free)> owned(json, &std::free);
    return data::gzip(std::span<const uint8_t>(reinterpret_cast<const uint8_t*>(json), len));
}

}  // namespace

TouchRecorder::TouchRecorder(int32_t /*width*/, int32_t height, float input_offset)
    : half_height_(float(height) / 2), input_offset_(input_offset) {}

void TouchRecorder::frame(double frame_time, std::span<const Touch> touches) {
    // (int)((Time - inputOffset) x 1000), computed in float.
    const int32_t t = int32_t((float(frame_time) - input_offset_) * 1000.0f);
    for (const Touch& touch : touches) {
        const RecordedPoint p{t, to_px(touch.x, half_height_), to_px(touch.y, half_height_)};
        const auto [it, added] = by_id_.try_emplace(int32_t(touch.id), touches_.size());
        if (added) touches_.emplace_back();
        std::vector<RecordedPoint>& points = touches_[it->second].points;
        const size_t n = points.size();
        if (n >= 2 && points[n - 1].x == p.x && points[n - 1].y == p.y && points[n - 2].x == p.x &&
            points[n - 2].y == p.y) {
            points.back() = p;
        } else {
            points.push_back(p);
        }
    }
}

void TouchRecorder::clear() {
    touches_.clear();
    by_id_.clear();
}

ReplayData replay_data(const PlaySession& session, const data::LoadedLevel& level, const TouchRecorder& touches,
                       double duration, int32_t width, int32_t height, float input_offset) {
    ReplayData d;
    d.duration = duration;
    d.input_offset = input_offset;
    d.width = width;
    d.height = height;
    d.result = session.result();
    d.inputs = session.judgments();
    d.streams = session.streams().dump();
    d.touches = touches.touches();
    d.entities.resize(size_t(session.entity_count()));
    for (const auto& [entity, values] : session.exports()) {
        if (entity < 0 || entity >= session.entity_count()) continue;  // Spawn-function entities aren't in the file
        const std::vector<data::Import>& names = level.play.archetypes[size_t(session.archetype_of(entity))].exports;
        std::vector<std::pair<std::string, float>>& out = d.entities[size_t(entity)];
        for (const auto& [index, value] : values)
            if (index >= 0 && size_t(index) < names.size()) out.emplace_back(names[size_t(index)].name, value);
    }
    return d;
}

std::vector<uint8_t> encode_replay(const ReplayData& data) {
    const MutDoc owner(yyjson_mut_doc_new(nullptr));
    yyjson_mut_doc* doc = owner.get();
    yyjson_mut_val* root = yyjson_mut_obj(doc);
    yyjson_mut_doc_set_root(doc, root);

    yyjson_mut_obj_add_real(doc, root, "duration", data.duration);
    yyjson_mut_obj_add_real(doc, root, "inputOffset", double(data.input_offset));
    yyjson_mut_val* area = yyjson_mut_obj_add_obj(doc, root, "playArea");
    yyjson_mut_obj_add_int(doc, area, "width", data.width);
    yyjson_mut_obj_add_int(doc, area, "height", data.height);

    const GameplayResult& g = data.result;
    yyjson_mut_val* result = yyjson_mut_obj_add_obj(doc, root, "result");
    yyjson_mut_obj_add_str(doc, result, "grade", grade_name(g.grade));
    yyjson_mut_obj_add_int(doc, result, "arcadeScore", g.arcade_score);
    yyjson_mut_obj_add_int(doc, result, "accuracyScore", g.accuracy_score);
    yyjson_mut_obj_add_int(doc, result, "combo", g.combo);
    yyjson_mut_obj_add_int(doc, result, "perfect", g.perfect);
    yyjson_mut_obj_add_int(doc, result, "great", g.great);
    yyjson_mut_obj_add_int(doc, result, "good", g.good);
    yyjson_mut_obj_add_int(doc, result, "miss", g.miss);
    yyjson_mut_obj_add_int(doc, result, "totalCount", g.total);

    // EntityIndex an int difference from the previous (from 0), time a float difference; judgment and accuracy
    // as they are.
    yyjson_mut_val* inputs = yyjson_mut_obj_add_obj(doc, root, "inputs");
    yyjson_mut_val* entity = yyjson_mut_obj_add_arr(doc, inputs, "entityIndex");
    yyjson_mut_val* time = yyjson_mut_obj_add_arr(doc, inputs, "time");
    yyjson_mut_val* judgment = yyjson_mut_obj_add_arr(doc, inputs, "judgment");
    yyjson_mut_val* accuracy = yyjson_mut_obj_add_arr(doc, inputs, "accuracy");
    int32_t previous_entity = 0;
    float previous_time = 0;
    for (const StatInput& in : data.inputs) {
        yyjson_mut_arr_add_int(doc, entity, int64_t(in.entity) - previous_entity);
        yyjson_mut_arr_add_real(doc, time, double(in.time - previous_time));
        yyjson_mut_arr_add_real(doc, judgment, double(in.judgment));
        yyjson_mut_arr_add_real(doc, accuracy, double(in.accuracy));
        previous_entity = in.entity;
        previous_time = in.time;
    }

    yyjson_mut_val* entities = yyjson_mut_obj_add_arr(doc, root, "entities");
    for (const auto& exports : data.entities) {
        yyjson_mut_val* e = yyjson_mut_arr_add_obj(doc, entities);
        yyjson_mut_val* list = yyjson_mut_obj_add_arr(doc, e, "data");
        for (const auto& [name, value] : exports) {
            yyjson_mut_val* item = yyjson_mut_arr_add_obj(doc, list);
            yyjson_mut_obj_add_strncpy(doc, item, "name", name.data(), name.size());
            yyjson_mut_obj_add_real(doc, item, "value", double(value));
        }
    }

    // L = points per touch; t, x and y int differences from the touch's previous point (from 0).
    yyjson_mut_val* touches = yyjson_mut_obj_add_obj(doc, root, "touches");
    yyjson_mut_val* l = yyjson_mut_obj_add_arr(doc, touches, "l");
    yyjson_mut_val* ts = yyjson_mut_obj_add_arr(doc, touches, "t");
    yyjson_mut_val* xs = yyjson_mut_obj_add_arr(doc, touches, "x");
    yyjson_mut_val* ys = yyjson_mut_obj_add_arr(doc, touches, "y");
    for (const RecordedTouch& touch : data.touches) {
        yyjson_mut_arr_add_int(doc, l, int64_t(touch.points.size()));
        int64_t t = 0, x = 0, y = 0;
        for (const RecordedPoint& p : touch.points) {
            yyjson_mut_arr_add_int(doc, ts, p.t_ms - t);
            yyjson_mut_arr_add_int(doc, xs, p.x - x);
            yyjson_mut_arr_add_int(doc, ys, p.y - y);
            t = p.t_ms, x = p.x, y = p.y;
        }
    }

    // Keys and values both float differences.
    yyjson_mut_val* streams = yyjson_mut_obj_add_arr(doc, root, "streams");
    for (const ReplayStream& s : data.streams) {
        yyjson_mut_val* item = yyjson_mut_arr_add_obj(doc, streams);
        yyjson_mut_obj_add_int(doc, item, "id", s.id);
        add_float_deltas(doc, yyjson_mut_obj_add_arr(doc, item, "keys"), s.keys);
        add_float_deltas(doc, yyjson_mut_obj_add_arr(doc, item, "values"), s.values);
    }
    return write_gzip(doc, "replay");
}

std::vector<uint8_t> encode_replay_config(std::span<const float> options) {
    const MutDoc owner(yyjson_mut_doc_new(nullptr));
    yyjson_mut_doc* doc = owner.get();
    yyjson_mut_val* root = yyjson_mut_obj(doc);
    yyjson_mut_doc_set_root(doc, root);
    yyjson_mut_val* list = yyjson_mut_obj_add_arr(doc, root, "options");
    for (const float v : options) yyjson_mut_arr_add_real(doc, list, double(v));
    return write_gzip(doc, "replay config");
}

}  // namespace sr::modes
