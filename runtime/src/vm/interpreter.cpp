// Float32 stack-machine interpreter for programs from compiler.cpp. The hot loop allocates nothing: the
// value stack is a per-thread buffer grown to the program's maximum once, and strings are built only when
// a fault is reported.

#include "vm/interpreter.h"

#include <algorithm>
#include <bit>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#include "vm/blocks.h"
#include "vm/convert.h"
#include "vm/easing.h"
#include "vm/fmath.h"
#include "vm/host.h"

namespace sr::vm {

namespace {

constexpr int32_t kTemporaryMemoryId = 10000;

// math.degrees / math.radians constants in float32 (Python's math.degrees uses 180/pi in double).
constexpr float kDegreesPerRadian = 57.29578f;
constexpr float kRadiansPerDegree = 0.0174532924f;

static_assert(int(Fn::EaseOutInElastic) - int(Fn::EaseInSine) + 1 == kEaseKindCount - 1);

const char* callback_name(data::CallbackKind k) {
    switch (k) {
        case data::CallbackKind::Preprocess: return "preprocess";
        case data::CallbackKind::SpawnTime: return "spawnTime";
        case data::CallbackKind::DespawnTime: return "despawnTime";
        case data::CallbackKind::SpawnOrder: return "spawnOrder";
        case data::CallbackKind::ShouldSpawn: return "shouldSpawn";
        case data::CallbackKind::Initialize: return "initialize";
        case data::CallbackKind::UpdateSequential: return "updateSequential";
        case data::CallbackKind::Touch: return "touch";
        case data::CallbackKind::UpdateParallel: return "updateParallel";
        case data::CallbackKind::Terminate: return "terminate";
        case data::CallbackKind::UpdateSpawn: return "updateSpawn";
    }
    return "?";
}

inline float clamp01(float v) { return std::max(0.0f, std::min(1.0f, v)); }

inline float remap(float a, float b, float c, float d, float v) { return c + ((v - a) / (b - a)) * (d - c); }

// sonolus.py interpret.py _judge; diff is source - target.
inline float judge(float diff, float perfect_min, float perfect_max, float great_min, float great_max,
                   float good_min, float good_max) {
    if (perfect_min <= diff && diff <= perfect_max) return 1.0f;
    if (great_min <= diff && diff <= great_max) return 2.0f;
    if (good_min <= diff && diff <= good_max) return 3.0f;
    return 0.0f;
}

template <class Op>
inline float fold_left(const float* x, int n, Op op) {
    if (n == 0) return 0.0f;
    float r = x[0];
    for (int i = 1; i < n; ++i) r = op(r, x[i]);
    return r;
}

inline float pure_op(Fn fn, const float* x, int n) {
    switch (fn) {
        case Fn::Abs: return __builtin_fabsf(x[0]);
        case Fn::Add: return fold_left(x, n, [](float a, float b) { return a + b; });
        case Fn::Subtract: return fold_left(x, n, [](float a, float b) { return a - b; });
        case Fn::Multiply: return fold_left(x, n, [](float a, float b) { return a * b; });
        case Fn::Divide: return fold_left(x, n, [](float a, float b) { return a / b; });
        case Fn::Mod: return fold_left(x, n, [](float a, float b) { return fm::mod(a, b); });
        case Fn::Rem: return fold_left(x, n, [](float a, float b) { return fm::rem(a, b); });
        case Fn::Power: return fold_left(x, n, [](float a, float b) { return fm::pow(a, b); });
        case Fn::Arccos: return fm::acos(x[0]);
        case Fn::Arcsin: return fm::asin(x[0]);
        case Fn::Arctan: return fm::atan(x[0]);
        case Fn::Arctan2: return fm::atan2(x[0], x[1]);
        case Fn::Ceil: return fm::ceil(x[0]);
        case Fn::Floor: return fm::floor(x[0]);
        case Fn::Trunc: return fm::trunc(x[0]);
        case Fn::Round: return fm::round(x[0]);
        case Fn::Frac: return fm::frac(x[0]);
        case Fn::Sign: return fm::sign(x[0]);
        case Fn::Cos: return fm::cos(x[0]);
        case Fn::Cosh: return fm::cosh(x[0]);
        case Fn::Sin: return fm::sin(x[0]);
        case Fn::Sinh: return fm::sinh(x[0]);
        case Fn::Tan: return fm::tan(x[0]);
        case Fn::Tanh: return fm::tanh(x[0]);
        case Fn::Log: return fm::log(x[0]);
        case Fn::Degree: return x[0] * kDegreesPerRadian;
        case Fn::Radian: return x[0] * kRadiansPerDegree;
        // Clamp(x, a, b) = max(a, min(b, x)) with IEEE maxNum/minNum.
        case Fn::Clamp: return fm::max(x[1], fm::min(x[2], x[0]));
        case Fn::Max: return fm::max(x[0], x[1]);
        case Fn::Min: return fm::min(x[0], x[1]);
        case Fn::Equal: return x[0] == x[1] ? 1.0f : 0.0f;
        case Fn::NotEqual: return x[0] != x[1] ? 1.0f : 0.0f;
        case Fn::Greater: return x[0] > x[1] ? 1.0f : 0.0f;
        case Fn::GreaterOr: return x[0] >= x[1] ? 1.0f : 0.0f;
        case Fn::Less: return x[0] < x[1] ? 1.0f : 0.0f;
        case Fn::LessOr: return x[0] <= x[1] ? 1.0f : 0.0f;
        case Fn::Not: return x[0] == 0.0f ? 1.0f : 0.0f;
        case Fn::Negate: return -x[0];
        case Fn::Lerp: return x[0] + (x[1] - x[0]) * x[2];
        case Fn::LerpClamped: return x[0] + (x[1] - x[0]) * clamp01(x[2]);
        case Fn::Unlerp: return (x[2] - x[0]) / (x[1] - x[0]);
        case Fn::UnlerpClamped: return clamp01((x[2] - x[0]) / (x[1] - x[0]));
        // Remap(a, b, c, d, v) = c + ((v - a) / (b - a)) * (d - c);
        // RemapClamped clamps that to [min(c, d), max(c, d)].
        case Fn::Remap: return remap(x[0], x[1], x[2], x[3], x[4]);
        case Fn::RemapClamped:
            return fm::max(fm::min(x[2], x[3]), fm::min(fm::max(x[2], x[3]), remap(x[0], x[1], x[2], x[3], x[4])));
        case Fn::Judge: return judge(x[0] - x[1], x[2], x[3], x[4], x[5], x[6], x[7]);
        case Fn::JudgeSimple: return judge(x[0] - x[1], -x[2], x[2], -x[3], x[3], -x[4], x[4]);
        default:
            if (fn >= Fn::EaseInSine && fn <= Fn::EaseOutInElastic)
                return ease(EaseKind(1 + int(fn) - int(Fn::EaseInSine)), x[0]);
            return 0.0f;  // not foldable; never compiled to a pure call
    }
}

// How a memory function addresses its cell and what it does there.
enum class Addr : uint8_t { Plain, Pointed, Shifted };
enum class Action : uint8_t { Get, Set, Add, Subtract, Multiply, Divide, Mod, Rem, Power, IncPre, IncPost, DecPre, DecPost };
struct MemoryOp {
    Addr addr;
    Action action;
};

constexpr int address_args(Addr a) { return a == Addr::Plain ? 2 : a == Addr::Pointed ? 3 : 4; }

MemoryOp memory_op(Fn fn) {
    switch (fn) {
#define SR_RMW(name, action)                                         \
    case Fn::name: return {Addr::Plain, Action::action};             \
    case Fn::name##Pointed: return {Addr::Pointed, Action::action};  \
    case Fn::name##Shifted: return {Addr::Shifted, Action::action};
        SR_RMW(Get, Get)
        SR_RMW(Set, Set)
        SR_RMW(SetAdd, Add)
        SR_RMW(SetSubtract, Subtract)
        SR_RMW(SetMultiply, Multiply)
        SR_RMW(SetDivide, Divide)
        SR_RMW(SetMod, Mod)
        SR_RMW(SetRem, Rem)
        SR_RMW(SetPower, Power)
        SR_RMW(IncrementPre, IncPre)
        SR_RMW(IncrementPost, IncPost)
        SR_RMW(DecrementPre, DecPre)
        SR_RMW(DecrementPost, DecPost)
#undef SR_RMW
        default: return {Addr::Plain, Action::Get};
    }
}

class Machine {
public:
    Machine(const Program& program, ExecContext& ctx)
        : p_(program), ctx_(ctx), mem_(*ctx.memory), lane_(ctx.lane ? *ctx.lane : mem_.own_lane()),
          tm_(mem_.view(kTemporaryMemoryId, lane_)) {
        stack_reg_ = frame_reg_ = tm_ ? tm_->size : 0;
    }

    float exec(int32_t start);

    // Restores Temporary Memory to zeros for the next run.
    void finish() {
        if (tm_ != nullptr && tm_lo_ < tm_hi_)
            std::memset(tm_->data + tm_lo_, 0, size_t(tm_hi_ - tm_lo_) * sizeof(float));
    }

    bool faulted() const { return ctx_.fault.kind != Fault::None; }

private:
    bool fail(Fault::Kind kind, Fn fn, const std::string& what) {
        ctx_.fault.kind = kind;
        ctx_.fault.detail = std::string(fn_info(fn).name) + ": " + what + " (archetype '" +
                            std::string(ctx_.archetype) + "', " + callback_name(ctx_.callback) + ")";
        return false;
    }

    // Get semantics (functions__get.txt): "Value at index in block of id, or 0 if index is out of range or
    // block does not exist". A block the callback may not read reads as 0 too.
    float read(int32_t id, int64_t index) const {
        const BlockView* v = mem_.view(id, lane_);
        if (v == nullptr || !v->readable || index < 0 || index >= v->size) return 0.0f;
        return v->data[index];
    }

    // The cell a write-family function may write, or nullptr when the store is skipped: unknown block, index
    // out of range, or a block the callback may not write (see memory()).
    float* writable_cell(int32_t id, int64_t index) {
        BlockView* v = mem_.view(id, lane_);
        if (v == nullptr || !v->writable || index < 0 || index >= v->size) return nullptr;
        if (v == tm_) touch_temporary(index, index + 1);
        return v->data + index;
    }

    void touch_temporary(int64_t lo, int64_t hi) {
        tm_lo_ = std::min(tm_lo_, int32_t(lo));
        tm_hi_ = std::max(tm_hi_, int32_t(hi));
    }

    // Integer address arithmetic (sonolus.py converts every address argument first).
    // Block ids must be exact integers (to_id: anything else names no block); indices truncate (to_index).
    void address(Addr addr, const float* a, int32_t& id, int64_t& index) const {
        id = to_id(a[0]);
        switch (addr) {
            case Addr::Plain:
                index = to_index(a[1]);
                return;
            case Addr::Shifted:
                index = int64_t(to_index(a[1])) + int64_t(to_index(a[2])) * int64_t(to_index(a[3]));
                return;
            case Addr::Pointed: {
                // Get(Get(id, index), Get(id, index + 1) + offset)
                const int64_t at = to_index(a[1]);
                const float block = read(id, at), base = read(id, at + 1);
                id = to_id(block);
                index = int64_t(to_index(base)) + int64_t(to_index(a[2]));
                return;
            }
        }
    }

    // Get*, Set*, Increment*, Decrement*. Never faults. A write-family call computes as usual from what Get
    // reads (0 for an unknown block or an index out of range); if the cell can't be written it skips the store
    // and still returns what it would have returned.
    float memory(Fn fn, const float* a, int n) {
        const MemoryOp op = memory_op(fn);
        int32_t id;
        int64_t index;
        address(op.addr, a, id, index);
        if (op.action == Action::Get) return read(id, index);
        const int at = address_args(op.addr);
        const float value = n > at ? a[at] : 0.0f;  // Increment/Decrement have no value argument
        const float old = op.action == Action::Set ? 0.0f : read(id, index);
        float next = value;
        switch (op.action) {
            case Action::Add: next = old + value; break;
            case Action::Subtract: next = old - value; break;
            case Action::Multiply: next = old * value; break;
            case Action::Divide: next = old / value; break;
            case Action::Mod: next = fm::mod(old, value); break;
            case Action::Rem: next = fm::rem(old, value); break;
            case Action::Power: next = fm::pow(old, value); break;
            case Action::IncPre: case Action::IncPost: next = old + 1.0f; break;
            case Action::DecPre: case Action::DecPost: next = old - 1.0f; break;
            default: break;
        }
        if (float* cell = writable_cell(id, index)) *cell = next;
        // Post returns the new value and Pre the old one.
        return (op.action == Action::IncPre || op.action == Action::DecPre) ? old : next;
    }

    // Copy(srcId, srcIndex, dstId, dstIndex, count), element by element with Get / Set semantics: a source
    // element that can't be read is 0, a destination element that can't be written is skipped, a negative
    // count copies nothing. Values are read before they are overwritten ("correctly copied, even if source
    // and destination ranges overlap"), including when two block ids view the same storage.
    void copy(const float* a) {
        const int32_t src = to_id(a[0]), dst = to_id(a[2]);
        const int64_t si = to_index(a[1]), di = to_index(a[3]), count = to_index(a[4]);
        BlockView* d = mem_.view(dst, lane_);
        if (count <= 0 || d == nullptr || !d->writable) return;
        // Only destination elements inside the block are written; that bounds the loop.
        const int64_t lo = std::max<int64_t>(di, 0), hi = std::min<int64_t>(di + count, d->size);
        if (lo >= hi) return;
        const BlockView* s = mem_.view(src, lane_);
        const bool readable = s != nullptr && s->readable;
        auto value = [&](int64_t j) {  // source element for destination index j
            const int64_t k = si + (j - di);
            return readable && k >= 0 && k < s->size ? s->data[k] : 0.0f;
        };
        // Copy backward when the destination starts above the source, like memmove.
        const bool backward =
            readable && reinterpret_cast<intptr_t>(d->data + lo) >
                            reinterpret_cast<intptr_t>(s->data) + intptr_t(si + (lo - di)) * intptr_t(sizeof(float));
        if (backward) {
            for (int64_t j = hi; j-- > lo;) d->data[j] = value(j);
        } else {
            for (int64_t j = lo; j < hi; ++j) d->data[j] = value(j);
        }
        if (d == tm_) touch_temporary(lo, hi);
    }

    // Stack functions. The wiki only says the stack lives in Temporary Memory "starting from the end"; the
    // layout here is a conventional downward stack with the stack and frame pointers held by the
    // interpreter (initially Temporary Memory's size, as after StackInit). Untested.
    // Unlike Get/Set, the stack functions check their own bounds and fault (StackOverflow) on overflow,
    // underflow, or a StackGet/StackSet (frame or not) outside Temporary Memory.
    bool stack_cell(Fn fn, int64_t index, float*& cell) {
        const int64_t size = tm_ ? tm_->size : 0;
        if (index < 0 || index >= size) {
            const char* what = index < 0 ? "stack overflow" : index == size ? "stack underflow" : "stack access";
            return fail(Fault::StackOverflow, fn,
                        std::string(what) + ": Temporary Memory index " + std::to_string(index) + " (size " +
                            std::to_string(size) + ")");
        }
        touch_temporary(index, index + 1);
        cell = tm_->data + index;
        return true;
    }

    bool stack(Fn fn, const float* a, float& r) {
        float* cell = nullptr;
        auto push = [&](float v) {
            if (!stack_cell(fn, stack_reg_ - 1, cell)) return false;
            --stack_reg_;
            *cell = v;
            return true;
        };
        auto pop = [&](float& v) {
            if (!stack_cell(fn, stack_reg_, cell)) return false;
            ++stack_reg_;
            v = *cell;
            return true;
        };
        auto get = [&](int64_t index) {
            if (!stack_cell(fn, index, cell)) return false;
            r = *cell;
            return true;
        };
        auto set = [&](int64_t index, float v) {
            if (!stack_cell(fn, index, cell)) return false;
            *cell = v;
            r = v;
            return true;
        };
        r = 0.0f;
        switch (fn) {
            case Fn::StackInit: stack_reg_ = frame_reg_ = tm_ ? tm_->size : 0; return true;
            case Fn::StackPush:
                if (!push(a[0])) return false;
                r = float(stack_reg_);
                return true;
            case Fn::StackPop: return pop(r);
            case Fn::StackGet: return get(stack_reg_ + to_index(a[0]));
            case Fn::StackSet: return set(stack_reg_ + to_index(a[0]), a[1]);
            case Fn::StackGetFrame: return get(frame_reg_ + to_index(a[0]));
            case Fn::StackSetFrame: return set(frame_reg_ + to_index(a[0]), a[1]);
            case Fn::StackGrow: {
                const int64_t next = stack_reg_ - to_index(a[0]);
                const int64_t size = tm_ ? tm_->size : 0;
                if (next < 0 || next > size) {
                    return fail(Fault::StackOverflow, fn,
                                std::string(next < 0 ? "stack overflow" : "stack underflow") + ": pointer " +
                                    std::to_string(next) + " (size " + std::to_string(size) + ")");
                }
                stack_reg_ = next;
                r = float(stack_reg_);
                return true;
            }
            case Fn::StackEnter: {
                if (!push(float(frame_reg_))) return false;
                const int64_t next = stack_reg_ - to_index(a[0]);
                if (next < 0 || next > (tm_ ? tm_->size : 0)) {
                    return fail(Fault::StackOverflow, fn,
                                "stack overflow: frame of " + std::to_string(to_index(a[0])) + " at pointer " +
                                    std::to_string(stack_reg_));
                }
                frame_reg_ = stack_reg_;
                stack_reg_ = next;
                return true;
            }
            case Fn::StackLeave: {
                stack_reg_ = frame_reg_;
                float fp;
                if (!pop(fp)) return false;
                frame_reg_ = to_index(fp);
                return true;
            }
            case Fn::StackGetPointer: r = float(stack_reg_); return true;
            case Fn::StackSetPointer:
                stack_reg_ = to_index(a[0]);
                r = float(stack_reg_);
                return true;
            case Fn::StackGetFramePointer: r = float(frame_reg_); return true;
            case Fn::StackSetFramePointer:
                frame_reg_ = to_index(a[0]);
                r = float(frame_reg_);
                return true;
            default: return true;
        }
    }

    bool host(Fn fn, const float* a, int n, float& r) {
        Host* h = ctx_.host;
        if (h == nullptr) return fail(Fault::Other, fn, "no host for a side effect");
        const std::span<const float> args(a, size_t(n));
        r = 0.0f;
        switch (fn) {
            case Fn::Draw: case Fn::DrawCurvedB: case Fn::DrawCurvedBT: case Fn::DrawCurvedL:
            case Fn::DrawCurvedLR: case Fn::DrawCurvedR: case Fn::DrawCurvedT:
                h->draw(DrawCall{fn, args});
                return true;
            case Fn::Play: case Fn::PlayLooped: case Fn::StopLooped: case Fn::PlayScheduled:
            case Fn::PlayLoopedScheduled: case Fn::StopLoopedScheduled: case Fn::HasEffectClip:
                r = h->audio(AudioCall{fn, args});
                return true;
            case Fn::SpawnParticleEffect: case Fn::MoveParticleEffect: case Fn::DestroyParticleEffect:
            case Fn::HasParticleEffect:
                r = h->particle(ParticleCall{fn, args});
                return true;
            case Fn::StreamGetNextKey: case Fn::StreamGetPreviousKey: case Fn::StreamGetValue:
            case Fn::StreamHas: case Fn::StreamSet:
                r = h->stream(StreamCall{fn, args});
                return true;
            case Fn::BeatToBPM: case Fn::BeatToStartingBeat: case Fn::BeatToStartingTime: case Fn::BeatToTime:
            case Fn::TimeToScaledTime: case Fn::TimeToStartingScaledTime: case Fn::TimeToStartingTime:
            case Fn::TimeToTimeScale:
                r = h->time(TimeCall{fn, a[0]});
                return true;
            case Fn::HasSkinSprite: {
                // A sprite id that isn't an exact integer names no sprite.
                const int32_t id = to_id(a[0]);
                r = id == kNoId ? 0.0f : h->has_skin_sprite(id);
                return true;
            }
            case Fn::Spawn: h->spawn(to_index(a[0]), args.subspan(1)); return true;
            case Fn::AddLifeScheduled: h->add_life_scheduled(a[0], a[1]); return true;
            case Fn::Random: r = a[0] + (a[1] - a[0]) * h->random(); return true;
            case Fn::RandomInteger: {
                const int64_t lo = to_index(a[0]), hi = to_index(a[1]);
                r = float(lo + int64_t(fm::floor(float(hi - lo) * h->random())));
                return true;
            }
            case Fn::ExportValue: h->export_value(to_index(a[0]), a[1]); return true;
            default:  // DebugLog, DebugPause, Paint, Print
                h->debug(DebugCall{fn, args});
                return true;
        }
    }

    bool count_loop(Fn fn) {
        if (++loops_ <= kMaxLoopIterations) return true;
        return fail(Fault::RunawayLoop, fn,
                    "more than " + std::to_string(kMaxLoopIterations) + " loop iterations in one callback");
    }

    const Program& p_;
    ExecContext& ctx_;
    Memory& mem_;
    Memory::Lane& lane_;
    BlockView* tm_;
    int32_t tm_lo_ = INT32_MAX, tm_hi_ = 0;  // Temporary Memory written this run
    int64_t stack_reg_ = 0, frame_reg_ = 0;
    int64_t loops_ = 0;
};

float Machine::exec(int32_t start) {
    thread_local std::vector<float> storage;
    const size_t need = size_t(p_.max_stack()) + 4;
    if (storage.size() < need) storage.resize(need);
    float* s = storage.data();
    int32_t sp = 0;
    const Ins* code = p_.code().data();
    const int32_t* tables = p_.tables().data();
    const BreakTarget* breaks = p_.breaks().data();
    int32_t pc = start;

    for (;;) {
        const Ins in = code[pc++];
        const int n = in.n;
        float r = 0.0f;
        switch (in.op) {
            case op_code(Op::Push): s[sp++] = std::bit_cast<float>(in.a); continue;
            case op_code(Op::Pop): --sp; continue;
            case op_code(Op::Jump): pc = in.a; continue;
            case op_code(Op::JumpIfZero):
                if (s[--sp] == 0.0f) pc = in.a;
                continue;
            case op_code(Op::AndJump):
                if (s[sp - 1] == 0.0f) {
                    pc = in.a;
                } else {
                    --sp;
                }
                continue;
            case op_code(Op::OrJump):
                if (s[sp - 1] != 0.0f) {
                    pc = in.a;
                } else {
                    --sp;
                }
                continue;
            case op_code(Op::LoopJump):
                if (!count_loop(Fn::While)) return 0.0f;
                pc = in.a;
                continue;
            case op_code(Op::LoopJumpIfNonZero):
                if (s[--sp] != 0.0f) {
                    if (!count_loop(Fn::DoWhile)) return 0.0f;
                    pc = in.a;
                }
                continue;
            case op_code(Op::SwitchCase): {
                const float c = s[--sp];
                if (c == s[sp - 1]) {
                    --sp;
                    pc = in.a;
                }
                continue;
            }
            case op_code(Op::SwitchTable): {
                const int32_t* t = tables + in.a;
                const int32_t i = branch_index(s[--sp], t[0]);
                pc = i >= 0 ? t[2 + i] : t[1];
                continue;
            }
            case op_code(Op::JumpLoopNext): {
                const int32_t* t = tables + in.a;
                const int32_t i = branch_index(s[--sp], t[0]);
                if (i < 0) {  // branch not found: JumpLoop returns 0
                    s[sp++] = 0.0f;
                    pc = t[1];
                    continue;
                }
                if (i <= n && !count_loop(Fn::JumpLoop)) return 0.0f;
                pc = t[2 + i];
                continue;
            }
            case op_code(Op::Break): {
                const float value = s[--sp];
                const int32_t count = to_index(s[--sp]);
                const int32_t k = count <= 1 ? 0 : count - 1;  // interpret.py: Break(0) acts as Break(1)
                if (k >= n) {
                    fail(Fault::Other, Fn::Break,
                         "Break(" + std::to_string(count) + ") with " + std::to_string(n) + " enclosing blocks");
                    return 0.0f;
                }
                const BreakTarget& target = breaks[in.a + k];
                sp = target.height;
                s[sp++] = value;
                pc = target.pc;
                continue;
            }
            case op_code(Op::Return): return s[sp - 1];

            case op_code(Fn::Get): case op_code(Fn::GetPointed): case op_code(Fn::GetShifted):
            case op_code(Fn::Set): case op_code(Fn::SetPointed): case op_code(Fn::SetShifted):
            case op_code(Fn::SetAdd): case op_code(Fn::SetAddPointed): case op_code(Fn::SetAddShifted):
            case op_code(Fn::SetSubtract): case op_code(Fn::SetSubtractPointed): case op_code(Fn::SetSubtractShifted):
            case op_code(Fn::SetMultiply): case op_code(Fn::SetMultiplyPointed): case op_code(Fn::SetMultiplyShifted):
            case op_code(Fn::SetDivide): case op_code(Fn::SetDividePointed): case op_code(Fn::SetDivideShifted):
            case op_code(Fn::SetMod): case op_code(Fn::SetModPointed): case op_code(Fn::SetModShifted):
            case op_code(Fn::SetRem): case op_code(Fn::SetRemPointed): case op_code(Fn::SetRemShifted):
            case op_code(Fn::SetPower): case op_code(Fn::SetPowerPointed): case op_code(Fn::SetPowerShifted):
            case op_code(Fn::IncrementPre): case op_code(Fn::IncrementPrePointed): case op_code(Fn::IncrementPreShifted):
            case op_code(Fn::IncrementPost): case op_code(Fn::IncrementPostPointed):
            case op_code(Fn::IncrementPostShifted):
            case op_code(Fn::DecrementPre): case op_code(Fn::DecrementPrePointed): case op_code(Fn::DecrementPreShifted):
            case op_code(Fn::DecrementPost): case op_code(Fn::DecrementPostPointed):
            case op_code(Fn::DecrementPostShifted):
                r = memory(Fn(in.op), s + sp - n, n);
                break;
            case op_code(Fn::Copy):
                copy(s + sp - n);
                break;
            case op_code(Fn::StackEnter): case op_code(Fn::StackGet): case op_code(Fn::StackGetFrame):
            case op_code(Fn::StackGetFramePointer): case op_code(Fn::StackGetPointer): case op_code(Fn::StackGrow):
            case op_code(Fn::StackInit): case op_code(Fn::StackLeave): case op_code(Fn::StackPop):
            case op_code(Fn::StackPush): case op_code(Fn::StackSet): case op_code(Fn::StackSetFrame):
            case op_code(Fn::StackSetFramePointer): case op_code(Fn::StackSetPointer):
                if (!stack(Fn(in.op), s + sp - n, r)) return 0.0f;
                break;
            case op_code(Fn::Draw): case op_code(Fn::DrawCurvedB): case op_code(Fn::DrawCurvedBT):
            case op_code(Fn::DrawCurvedL): case op_code(Fn::DrawCurvedLR): case op_code(Fn::DrawCurvedR):
            case op_code(Fn::DrawCurvedT): case op_code(Fn::Play): case op_code(Fn::PlayLooped):
            case op_code(Fn::StopLooped): case op_code(Fn::PlayScheduled): case op_code(Fn::PlayLoopedScheduled):
            case op_code(Fn::StopLoopedScheduled): case op_code(Fn::HasEffectClip):
            case op_code(Fn::SpawnParticleEffect): case op_code(Fn::MoveParticleEffect):
            case op_code(Fn::DestroyParticleEffect): case op_code(Fn::HasParticleEffect):
            case op_code(Fn::StreamGetNextKey): case op_code(Fn::StreamGetPreviousKey):
            case op_code(Fn::StreamGetValue): case op_code(Fn::StreamHas): case op_code(Fn::StreamSet):
            case op_code(Fn::BeatToBPM): case op_code(Fn::BeatToStartingBeat): case op_code(Fn::BeatToStartingTime):
            case op_code(Fn::BeatToTime): case op_code(Fn::TimeToScaledTime):
            case op_code(Fn::TimeToStartingScaledTime): case op_code(Fn::TimeToStartingTime):
            case op_code(Fn::TimeToTimeScale): case op_code(Fn::HasSkinSprite): case op_code(Fn::Spawn):
            case op_code(Fn::AddLifeScheduled): case op_code(Fn::Random): case op_code(Fn::RandomInteger):
            case op_code(Fn::ExportValue): case op_code(Fn::DebugLog): case op_code(Fn::DebugPause):
            case op_code(Fn::Paint): case op_code(Fn::Print):
                if (!host(Fn(in.op), s + sp - n, n, r)) return 0.0f;
                break;
            default:
                r = pure_op(Fn(in.op), s + sp - n, n);
                break;
        }
        sp -= n;
        s[sp++] = r;
    }
}

}  // namespace

float eval_pure(Fn fn, const float* args, int n) { return pure_op(fn, args, n); }

float run(const Program& program, Entry entry, ExecContext& ctx) {
    ctx.fault = Fault{};
    if (!program.has_entry(entry)) {
        ctx.fault = Fault{Fault::Other, "no compiled callback at node " + std::to_string(entry)};
        return 0.0f;
    }
    if (ctx.memory == nullptr || ctx.memory->mode() != program.mode()) {
        ctx.fault = Fault{Fault::Other, "memory missing or for another mode"};
        return 0.0f;
    }
    if (ctx.lane != nullptr)
        ctx.memory->bind(ctx.callback, ctx.entity, *ctx.lane);
    else
        ctx.memory->bind(ctx.callback, ctx.entity);
    Machine machine(program, ctx);
    const float result = machine.exec(program.entry_pc(entry));
    machine.finish();
    return machine.faulted() ? 0.0f : result;
}

}  // namespace sr::vm
