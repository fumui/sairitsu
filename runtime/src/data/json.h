#pragma once

#include <cstdint>
#include <memory>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <vector>

#include "yyjson.h"

namespace sr::data {

// Thin, path-aware wrapper over yyjson. Every accessor throws LoadError with a
// message of the form "<resource>: <what> '<path>'", so parsers never format
// error text themselves.
//
// JSON numbers are parsed to double, then
// rounded to float.

class JsonDocument;

class JsonValue {
public:
    JsonValue(yyjson_val* v, std::shared_ptr<const std::string> resource, std::string path)
        : v_(v), resource_(std::move(resource)), path_(std::move(path)) {}

    const std::string& path() const { return path_; }

    bool is_object() const { return yyjson_is_obj(v_); }
    bool is_array() const { return yyjson_is_arr(v_); }

    // Required member. Throws "missing field" when absent.
    JsonValue field(const char* key) const;
    // Optional member; std::nullopt when absent (or null).
    std::optional<JsonValue> opt(const char* key) const;
    bool has(const char* key) const;

    // Array access.
    size_t size() const;
    JsonValue at(size_t index) const;
    std::vector<JsonValue> array() const;

    // Typed reads (throw "expected <type> at").
    float number() const;
    double number_double() const;
    int32_t integer() const;
    bool boolean() const;
    std::string string() const;

    // Convenience: field(key).number() etc., with optional variants.
    float number(const char* key) const { return field(key).number(); }
    int32_t integer(const char* key) const { return field(key).integer(); }
    bool boolean(const char* key) const { return field(key).boolean(); }
    std::string string(const char* key) const { return field(key).string(); }
    std::optional<float> opt_number(const char* key) const;
    std::optional<int32_t> opt_integer(const char* key) const;
    std::optional<bool> opt_boolean(const char* key) const;
    std::optional<std::string> opt_string(const char* key) const;

    // Visits object members in document order: fn(key, value).
    template <typename F>
    void each_member(F&& fn) const {
        expect_object();
        size_t idx, max;
        yyjson_val *key, *val;
        yyjson_obj_foreach(v_, idx, max, key, val) {
            const std::string k = yyjson_get_str(key);
            fn(k, JsonValue(val, resource_, path_ + "." + k));
        }
    }

    [[noreturn]] void fail(const std::string& what) const;

    yyjson_val* raw() const { return v_; }
    const std::shared_ptr<const std::string>& resource() const { return resource_; }

private:
    void expect_object() const;
    void expect_array() const;
    std::string child_path(const char* key) const;

    yyjson_val* v_;
    std::shared_ptr<const std::string> resource_;
    std::string path_;
};

class JsonDocument {
public:
    // `resource` is the resource kind name used as the error prefix.
    JsonDocument(std::string_view json, std::string resource);
    ~JsonDocument();
    JsonDocument(const JsonDocument&) = delete;
    JsonDocument& operator=(const JsonDocument&) = delete;

    JsonValue root() const;

private:
    yyjson_doc* doc_;
    std::shared_ptr<const std::string> resource_;
};

inline std::string_view as_text(std::span<const uint8_t> bytes) {
    return {reinterpret_cast<const char*>(bytes.data()), bytes.size()};
}

}  // namespace sr::data
