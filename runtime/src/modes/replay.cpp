#include "modes/replay.h"

#include <cstddef>

#include "data/gzip.h"
#include "data/json.h"
#include "data/load_error.h"

namespace sr::modes {

namespace {

using data::JsonDocument;
using data::JsonValue;

constexpr const char* kResource = "replay";

std::vector<JsonValue> expect_size(const JsonValue& v, const char* key, size_t n) {
    std::vector<JsonValue> a = v.field(key).array();
    if (a.size() != n) v.field(key).fail("expected " + std::to_string(n) + " elements");
    return a;
}

}  // namespace

Replay parse_replay(std::span<const uint8_t> file) {
    const std::vector<uint8_t> bytes = data::gunzip(file);
    const JsonDocument doc(data::as_text(bytes), kResource);
    const JsonValue root = doc.root();

    Replay r;
    r.duration = root.field("duration").number_double();
    r.input_offset = root.opt_number("inputOffset").value_or(0.0f);
    const JsonValue area = root.field("playArea");
    r.width = area.integer("width");
    r.height = area.integer("height");
    if (r.height <= 0) area.field("height").fail("expected a positive height");

    const JsonValue res = root.field("result");
    r.result.grade = res.string("grade");
    r.result.arcade = res.integer("arcadeScore");
    r.result.accuracy = res.integer("accuracyScore");
    r.result.combo = res.integer("combo");
    r.result.perfect = res.integer("perfect");
    r.result.great = res.integer("great");
    r.result.good = res.integer("good");
    r.result.miss = res.integer("miss");
    r.result.total = res.integer("totalCount");

    // Inputs are delta-encoded: entityIndex a running int sum, time a running float32 sum;
    // judgment and accuracy are taken as they are.
    const JsonValue in = root.field("inputs");
    const std::vector<JsonValue> entity = in.field("entityIndex").array();
    const size_t n = entity.size();
    const std::vector<JsonValue> time = expect_size(in, "time", n);
    const std::vector<JsonValue> judgment = expect_size(in, "judgment", n);
    const std::vector<JsonValue> accuracy = expect_size(in, "accuracy", n);
    r.inputs.reserve(n);
    int32_t entity_sum = 0;
    float time_sum = 0;
    for (size_t i = 0; i < n; ++i) {
        entity_sum += entity[i].integer();
        time_sum += time[i].number();
        r.inputs.push_back({entity_sum, time_sum, judgment[i].number(), accuracy[i].number()});
    }

    // Touch i takes l[i] points; t (ms of Play time, input offset removed), x and y (integer pixels from the
    // play-area centre, y up) are running sums from 0 within the touch. A point becomes seconds and
    // screen units x / (H / 2), y / (H / 2).
    const JsonValue touches = root.field("touches");
    const std::vector<JsonValue> l = touches.field("l").array();
    size_t total = 0;
    std::vector<int32_t> counts;
    counts.reserve(l.size());
    for (const JsonValue& c : l) {
        const int32_t k = c.integer();
        if (k < 0) c.fail("expected a point count of 0 or more");
        counts.push_back(k);
        total += size_t(k);
    }
    const std::vector<JsonValue> ts = expect_size(touches, "t", total);
    const std::vector<JsonValue> xs = expect_size(touches, "x", total);
    const std::vector<JsonValue> ys = expect_size(touches, "y", total);
    const double half_h = double(r.height) / 2;
    r.touches.resize(counts.size());
    size_t at = 0;
    for (size_t i = 0; i < counts.size(); ++i) {
        int64_t t = 0, x = 0, y = 0;
        std::vector<ReplayPoint>& pts = r.touches[i].points;
        pts.reserve(size_t(counts[i]));
        for (int32_t k = 0; k < counts[i]; ++k, ++at) {
            t += ts[at].integer();
            x += xs[at].integer();
            y += ys[at].integer();
            pts.push_back({double(t) / 1000.0, float(double(x) / half_h), float(double(y) / half_h)});
        }
    }

    const std::vector<JsonValue> entities = root.field("entities").array();
    r.exports.reserve(entities.size());
    for (const JsonValue& e : entities) {
        std::vector<std::pair<std::string, float>> list;
        for (const JsonValue& d : e.field("data").array()) list.emplace_back(d.string("name"), d.number("value"));
        r.exports.push_back(std::move(list));
    }

    for (const JsonValue& s : root.field("streams").array()) {
        ReplayStream out;
        out.id = s.integer("id");
        // Keys and values are both delta-encoded like the input times: running float32 sums (keys ascend only
        // as sums, the last reaching the level's end; Play's own stream values match only as sums).
        float key_sum = 0, value_sum = 0;
        for (const JsonValue& k : s.field("keys").array()) {
            key_sum += k.number();
            out.keys.push_back(key_sum);
        }
        for (const JsonValue& v : s.field("values").array()) {
            value_sum += v.number();
            out.values.push_back(value_sum);
        }
        if (out.keys.size() != out.values.size()) s.fail("keys and values differ in length");
        r.streams.push_back(std::move(out));
    }
    return r;
}

std::vector<float> parse_replay_config(std::span<const uint8_t> file) {
    const std::vector<uint8_t> bytes = data::gunzip(file);
    const JsonDocument doc(data::as_text(bytes), "replay config");
    std::vector<float> options;
    for (const JsonValue& v : doc.root().field("options").array()) options.push_back(v.number());
    return options;
}

}  // namespace sr::modes
