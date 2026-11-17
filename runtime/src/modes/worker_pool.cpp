#include "modes/worker_pool.h"

namespace sr::modes {

// One thread. Benchmark sss-722-1279-expert on a 120 Hz tablet, three
// 30 s SurfaceFlinger-latch traces per count, drops (interval over 1.5 vsync) of about 10,760 intervals each: 1 thread 9 (0.084 %),
// 2 threads 13 (0.121 %), 4 threads 13 (0.121 %). The counts are not separable (per-run 0..8, 1..10, 3..6). More threads buy nothing at the benchmark's
// 1,080 entities, so the simplest setting is the default. Output is bit-identical for any count (Determinism).
int WorkerPool::default_threads() { return 1; }

WorkerPool::WorkerPool(int threads) {
    for (int w = 1; w < threads; ++w) threads_.emplace_back([this, w] { work(w); });
}

WorkerPool::~WorkerPool() {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        stop_ = true;
    }
    start_.notify_all();
    for (std::thread& t : threads_) t.join();
}

void WorkerPool::run(size_t count, const std::function<void(size_t, int)>& fn) {
    if (threads_.empty() || count <= 1) {
        for (size_t i = 0; i < count; ++i) fn(i, 0);
        return;
    }
    {
        std::lock_guard<std::mutex> lock(mutex_);
        job_ = &fn;
        count_ = count;
        next_ = 0;
        busy_ = size();
        ++generation_;
    }
    start_.notify_all();
    drain(0);
    std::unique_lock<std::mutex> lock(mutex_);
    --busy_;
    // Every worker takes part in every run, so none can still be looking at this job once busy_ is 0.
    done_.wait(lock, [this] { return busy_ == 0; });
    job_ = nullptr;
}

void WorkerPool::work(int worker) {
    unsigned seen = 0;
    for (;;) {
        {
            std::unique_lock<std::mutex> lock(mutex_);
            start_.wait(lock, [&] { return stop_ || generation_ != seen; });
            if (stop_) return;
            seen = generation_;
        }
        drain(worker);
        std::lock_guard<std::mutex> lock(mutex_);
        if (--busy_ == 0) done_.notify_all();
    }
}

void WorkerPool::drain(int worker) {
    for (;;) {
        size_t item;
        const std::function<void(size_t, int)>* job;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (next_ >= count_) return;
            item = next_++;
            job = job_;
        }
        (*job)(item, worker);
    }
}

}  // namespace sr::modes
