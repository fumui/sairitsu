#include "modes/replay_file.h"

#include <fstream>
#include <vector>

namespace sr::modes {

namespace fs = std::filesystem;

namespace {

fs::path with_suffix(const fs::path& base, const char* suffix) {
    fs::path p = base;
    p += suffix;
    return p;
}

bool write_file(const fs::path& path, const std::vector<uint8_t>& bytes) {
    std::ofstream out(path, std::ios::binary | std::ios::trunc);
    if (!out) return false;
    out.write(reinterpret_cast<const char*>(bytes.data()), std::streamsize(bytes.size()));
    out.close();
    return !out.fail();
}

}  // namespace

bool write_replay_files(const fs::path& base, const ReplayData& data, std::span<const float> options) {
    const fs::path json = with_suffix(base, ".json.gz"), config = with_suffix(base, ".config.gz");
    const fs::path json_part = with_suffix(json, ".part"), config_part = with_suffix(config, ".part");
    bool ok = false;
    try {
        const std::vector<uint8_t> replay_bytes = encode_replay(data);
        const std::vector<uint8_t> config_bytes = encode_replay_config(options);
        std::error_code ec;
        ok = write_file(json_part, replay_bytes) && write_file(config_part, config_bytes);
        if (ok) {
            fs::rename(json_part, json, ec);
            ok = !ec;
        }
        if (ok) {
            fs::rename(config_part, config, ec);
            ok = !ec;
        }
    } catch (...) {  // an unwritable value (encode_replay), an allocation
        ok = false;
    }
    if (!ok) {
        std::error_code ignored;
        for (const fs::path& p : {json_part, config_part, json, config}) fs::remove(p, ignored);
    }
    return ok;
}

uint64_t ReplaySlot::begin() {
    std::lock_guard<std::mutex> lock(mutex_);
    latest_ = next_++;
    status_ = ReplayStatus::Writing;
    return latest_;
}

void ReplaySlot::finish(uint64_t token, bool ok) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (token == 0 || token != latest_) return;
    status_ = ok ? ReplayStatus::Ready : ReplayStatus::Failed;
}

void ReplaySlot::reset() {
    std::lock_guard<std::mutex> lock(mutex_);
    latest_ = 0;
    status_ = ReplayStatus::None;
}

ReplayStatus ReplaySlot::status() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return status_;
}

bool ReplaySlot::current(uint64_t token) const {
    std::lock_guard<std::mutex> lock(mutex_);
    return token != 0 && token == latest_;
}

bool ReplaySlot::write(uint64_t token, const fs::path& base, const ReplayData& data, std::span<const float> options) {
    std::lock_guard<std::mutex> lock(write_mutex_);
    // A newer run began (its write waits on this lock) or a retry reset the slot: this run's files are not wanted.
    if (!current(token)) return false;
    const bool ok = write_replay_files(base, data, options);
    finish(token, ok);
    return ok;
}

}  // namespace sr::modes
