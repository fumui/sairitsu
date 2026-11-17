#pragma once

#include <condition_variable>
#include <cstddef>
#include <functional>
#include <mutex>
#include <thread>
#include <vector>

namespace sr::modes {

// A fixed set of threads for the parallel phases of the update cycle. The calling thread is worker 0 and takes
// part; `threads` <= 1 runs everything on it.
class WorkerPool {
public:
    explicit WorkerPool(int threads);
    ~WorkerPool();

    WorkerPool(const WorkerPool&) = delete;
    WorkerPool& operator=(const WorkerPool&) = delete;

    // The thread count a WatchConfig with `threads` 0 gets (chosen from the pacing runs).
    static int default_threads();

    int size() const { return int(threads_.size()) + 1; }

    // Calls fn(item, worker) once for every item in [0, count), worker in [0, size()), and returns when all calls
    // have returned. Which worker takes which item is unspecified: results must depend on the item only.
    // Not reentrant; one run at a time.
    void run(size_t count, const std::function<void(size_t item, int worker)>& fn);

private:
    void work(int worker);
    void drain(int worker);

    std::vector<std::thread> threads_;
    std::mutex mutex_;
    std::condition_variable start_, done_;
    const std::function<void(size_t, int)>* job_ = nullptr;
    size_t count_ = 0, next_ = 0;
    unsigned generation_ = 0;
    int busy_ = 0;
    bool stop_ = false;
};

}  // namespace sr::modes
