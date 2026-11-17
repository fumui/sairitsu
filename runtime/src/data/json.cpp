#include "data/json.h"

#include <cmath>
#include <limits>

#include "data/load_error.h"

namespace sr::data {

[[noreturn]] void JsonValue::fail(const std::string& what) const {
    throw LoadError(*resource_ + ": " + what + (path_.empty() ? "" : " '" + path_ + "'"));
}

std::string JsonValue::child_path(const char* key) const {
    return path_.empty() ? std::string(key) : path_ + "." + key;
}

void JsonValue::expect_object() const {
    if (!yyjson_is_obj(v_)) fail("expected object at");
}

void JsonValue::expect_array() const {
    if (!yyjson_is_arr(v_)) fail("expected array at");
}

bool JsonValue::has(const char* key) const {
    return yyjson_is_obj(v_) && yyjson_obj_get(v_, key) != nullptr;
}

JsonValue JsonValue::field(const char* key) const {
    expect_object();
    yyjson_val* child = yyjson_obj_get(v_, key);
    if (child == nullptr) {
        throw LoadError(*resource_ + ": missing field '" + child_path(key) + "'");
    }
    return JsonValue(child, resource_, child_path(key));
}

std::optional<JsonValue> JsonValue::opt(const char* key) const {
    expect_object();
    yyjson_val* child = yyjson_obj_get(v_, key);
    if (child == nullptr || yyjson_is_null(child)) return std::nullopt;
    return JsonValue(child, resource_, child_path(key));
}

size_t JsonValue::size() const {
    expect_array();
    return yyjson_arr_size(v_);
}

JsonValue JsonValue::at(size_t index) const {
    expect_array();
    return JsonValue(yyjson_arr_get(v_, index), resource_, path_ + "[" + std::to_string(index) + "]");
}

std::vector<JsonValue> JsonValue::array() const {
    expect_array();
    std::vector<JsonValue> out;
    out.reserve(yyjson_arr_size(v_));
    size_t idx, max;
    yyjson_val* item;
    yyjson_arr_foreach(v_, idx, max, item) {
        out.emplace_back(item, resource_, path_ + "[" + std::to_string(idx) + "]");
    }
    return out;
}

double JsonValue::number_double() const {
    if (!yyjson_is_num(v_)) fail("expected number at");
    return yyjson_get_num(v_);
}

float JsonValue::number() const { return static_cast<float>(number_double()); }

int32_t JsonValue::integer() const {
    const double d = number_double();
    // Range first (also rejects NaN), then integrality without std::floor (no platform libm).
    if (!(d >= double(std::numeric_limits<int32_t>::min()) && d <= double(std::numeric_limits<int32_t>::max())) ||
        double(static_cast<int32_t>(d)) != d) {
        fail("expected 32-bit integer at");
    }
    return static_cast<int32_t>(d);
}

bool JsonValue::boolean() const {
    if (!yyjson_is_bool(v_)) fail("expected boolean at");
    return yyjson_get_bool(v_);
}

std::string JsonValue::string() const {
    if (!yyjson_is_str(v_)) fail("expected string at");
    return std::string(yyjson_get_str(v_), yyjson_get_len(v_));
}

std::optional<float> JsonValue::opt_number(const char* key) const {
    auto v = opt(key);
    return v ? std::optional<float>(v->number()) : std::nullopt;
}
std::optional<int32_t> JsonValue::opt_integer(const char* key) const {
    auto v = opt(key);
    return v ? std::optional<int32_t>(v->integer()) : std::nullopt;
}
std::optional<bool> JsonValue::opt_boolean(const char* key) const {
    auto v = opt(key);
    return v ? std::optional<bool>(v->boolean()) : std::nullopt;
}
std::optional<std::string> JsonValue::opt_string(const char* key) const {
    auto v = opt(key);
    return v ? std::optional<std::string>(v->string()) : std::nullopt;
}

JsonDocument::JsonDocument(std::string_view json, std::string resource)
    : doc_(nullptr), resource_(std::make_shared<const std::string>(std::move(resource))) {
    yyjson_read_err err;
    doc_ = yyjson_read_opts(const_cast<char*>(json.data()), json.size(), 0, nullptr, &err);
    if (doc_ == nullptr) {
        throw LoadError(*resource_ + ": invalid JSON: " + err.msg + " at byte " +
                        std::to_string(err.pos));
    }
}

JsonDocument::~JsonDocument() { yyjson_doc_free(doc_); }

JsonValue JsonDocument::root() const {
    return JsonValue(yyjson_doc_get_root(doc_), resource_, "");
}

}  // namespace sr::data
