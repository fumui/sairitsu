#include "modes/touch.h"

#include <algorithm>

#include "vm/fmath.h"

namespace sr::modes {

TouchPool::TouchPool(int32_t capacity) : capacity_(capacity) {}

void TouchPool::push(const TouchEvent& e) { pending_.push_back(e); }

void TouchPool::clear() {
    pending_.clear();
    tracks_.clear();
    ignored_.clear();
    done_.clear();
}

// Delta against last frame's record (or the start for a new touch), velocity over that interval.
Touch TouchPool::finish(const Track& tr) const {
    const double pt = tr.reported ? tr.prev_t : tr.st;
    const float px = tr.reported ? tr.prev_x : tr.sx;
    const float py = tr.reported ? tr.prev_y : tr.sy;
    Touch o{};
    o.id = static_cast<float>(tr.id);
    o.started = !tr.reported;
    o.ended = tr.ended;
    o.t = static_cast<float>(tr.t);
    o.st = static_cast<float>(tr.st);
    o.x = tr.x;
    o.y = tr.y;
    o.sx = tr.sx;
    o.sy = tr.sy;
    o.dx = tr.x - px;
    o.dy = tr.y - py;
    // Records hold T as float, so dt is the float difference of the float times.
    const float dt = static_cast<float>(tr.t) - static_cast<float>(pt);
    if (dt > 0.0f) {
        o.vx = o.dx / dt;
        o.vy = o.dy / dt;
        o.vr = fm::sqrt(o.dx * o.dx + o.dy * o.dy) / dt;
        o.vw = fm::atan2(o.dy, o.dx);
    }
    return o;
}

void TouchPool::apply(const TouchEvent& e) {
    auto it = tracks_.find(e.pointer);
    if (e.phase == TouchEvent::Down) {
        if (it != tracks_.end()) {
            // A new touch of a pointer that ended within this frame: report the old one first.
            if (!it->second.ended) return;
            done_.push_back(finish(it->second));
            tracks_.erase(it);
        }
        ignored_.erase(e.pointer);
        if (static_cast<int32_t>(tracks_.size() + done_.size()) >= capacity_) {
            ignored_.insert(e.pointer);
            return;
        }
        Track tr;
        tr.id = next_id_++;
        tr.st = tr.t = e.t;
        tr.sx = tr.x = e.x;
        tr.sy = tr.y = e.y;
        tracks_[e.pointer] = tr;
        return;
    }
    const bool last = e.phase == TouchEvent::Up || e.phase == TouchEvent::Cancel;
    if (ignored_.count(e.pointer)) {
        if (last) ignored_.erase(e.pointer);
        return;
    }
    if (it == tracks_.end() || it->second.ended) return;
    Track& tr = it->second;
    tr.t = e.t;
    tr.x = e.x;
    tr.y = e.y;
    tr.ended = last;
}

std::vector<Touch> TouchPool::frame(double frame_time) {
    std::vector<TouchEvent> later;
    for (const TouchEvent& e : pending_) {
        if (e.t <= frame_time) {
            apply(e);
        } else {
            later.push_back(e);
        }
    }
    pending_ = std::move(later);

    std::vector<Touch> out = std::move(done_);
    done_.clear();
    for (auto it = tracks_.begin(); it != tracks_.end();) {
        Track& tr = it->second;
        out.push_back(finish(tr));
        if (tr.ended) {
            it = tracks_.erase(it);
        } else {
            tr.reported = true;
            tr.prev_t = tr.t;
            tr.prev_x = tr.x;
            tr.prev_y = tr.y;
            ++it;
        }
    }
    std::sort(out.begin(), out.end(), [](const Touch& a, const Touch& b) { return a.id < b.id; });
    return out;
}

TouchEvent to_touch_event(const RawTouch& raw, int32_t width, int32_t height, double heard, float input_offset) {
    const double half_h = double(height) * 0.5, half_w = double(width) * 0.5;
    return TouchEvent{raw.pointer, raw.phase, heard - double(input_offset), float((double(raw.x) - half_w) / half_h),
                      float((half_h - double(raw.y)) / half_h)};
}

void drain_touches(std::vector<RawTouch>& raw, TouchPool& pool, bool paused, int32_t width, int32_t height,
                   float input_offset, int64_t pipeline_ns, const std::function<double(int64_t)>& heard_at) {
    if (paused) {
        raw.clear();
        pool.clear();
        return;
    }
    // A frame's Time is taken when the frame updates and a touch's t on that same
    // clock, so a touch at a frame's vsync reads that frame's time. Our frame time is the music heard at the frame's
    // expected present time, `pipeline_ns` after its vsync, so the event time moves by the same amount; mapping the
    // raw event time put every touch one pipeline latency early against the frames (about -23 ms).
    for (const RawTouch& r : raw)
        pool.push(to_touch_event(r, width, height, heard_at(r.ns + pipeline_ns), input_offset));
    raw.clear();
}

}  // namespace sr::modes
