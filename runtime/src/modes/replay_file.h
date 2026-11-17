#pragma once

#include <cstdint>
#include <filesystem>
#include <mutex>
#include <span>

#include "modes/replay_writer.h"

namespace sr::modes {

// Writes `<base>.json.gz` (encode_replay) and `<base>.config.gz` (encode_replay_config), each through
// `<file>.part` and a rename. The parent directory must exist. False on any error, and then neither final file nor a
// `.part` file is left (a pair from an earlier write is removed too: the slot's status says Failed for it anyway).
bool write_replay_files(const std::filesystem::path& base, const ReplayData& data, std::span<const float> options);

// What Kotlin polls (NativeRuntime.replayStatus) about the last Play run's replay files.
enum class ReplayStatus : int32_t { None = 0, Writing = 1, Ready = 2, Failed = 3 };

// The state of the one replay file pair a Runtime writes. Thread-safe: the render thread begins and resets, detached
// writer threads finish, the UI thread reads. Every method but write() takes only a short lock, so a Retry's reset()
// never waits on a write in flight.
class ReplaySlot {
public:
    // A new run's write starts: Writing. Returns its token; any earlier token is stale from here on.
    uint64_t begin();
    // The write of `token` is over: Ready (ok) or Failed. Ignored unless `token` is the latest begin()'s and no reset()
    // came after it.
    void finish(uint64_t token, bool ok);
    // A new run or a retry: None. Every token issued so far is stale (their finish() is ignored).
    void reset();
    ReplayStatus status() const;

    // A writer thread's body: under the slot's write lock (one write at a time, so two writes to the same base never
    // mix their `.part` files), write_replay_files unless `token` is already stale, then finish(token, ok). Returns
    // what it wrote: false for a stale token (nothing written) or a failed write.
    bool write(uint64_t token, const std::filesystem::path& base, const ReplayData& data,
               std::span<const float> options);

private:
    bool current(uint64_t token) const;

    mutable std::mutex mutex_;  // guards status_ and latest_
    ReplayStatus status_ = ReplayStatus::None;
    uint64_t latest_ = 0;  // the latest begin()'s token; 0 after a reset (tokens start at 1)
    uint64_t next_ = 1;
    std::mutex write_mutex_;  // held for a whole write; never taken under mutex_
};

}  // namespace sr::modes
