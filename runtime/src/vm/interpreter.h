#pragma once

#include <cstdint>
#include <string>
#include <string_view>

#include "data/engine_data.h"
#include "vm/functions.h"
#include "vm/memory.h"
#include "vm/program.h"

namespace sr::vm {

class Host;

// Block access never faults (Get of a missing cell is 0, a write that can't happen is a no-op; wiki).
// What does: a runaway loop, a stack function outside Temporary Memory, a Break whose run-time count
// exceeds its enclosing blocks, and misuse (no host, memory of another mode, unknown entry) as Other.
struct Fault {
    enum Kind { None, RunawayLoop, StackOverflow, Other } kind = None;
    std::string detail;  // what failed, in which archetype and callback
};

// One callback invocation. run() binds `memory` to (callback, entity) before it starts, in `lane` when one is given
// (callbacks running at the same time on several threads each need their own), else in the Memory's own lane.
struct ExecContext {
    Memory* memory = nullptr;
    Memory::Lane* lane = nullptr;
    Host* host = nullptr;
    data::CallbackKind callback = data::CallbackKind::Preprocess;
    int32_t entity = -1;
    std::string_view archetype;  // goes into fault.detail
    Fault fault;
};

// The branch a SwitchInteger* / JumpLoop index names, or -1 (default / branch not found) when it is not an
// integer in [0, count): NaN and fractions included, as the wiki ("discriminant equals to n") and
// sonolus.py's interpret.py have it. Shared by the interpreter and the optimizer's folding.
inline int32_t branch_index(float v, int32_t count) {
    if (!(v >= 0.0f && v < float(count))) return -1;
    const int32_t i = int32_t(v);
    return float(i) == v ? i : -1;
}

// Loops (While, DoWhile, JumpLoop back-jumps) may run this many iterations per callback; one more faults.
inline constexpr int64_t kMaxLoopIterations = int64_t(1) << 26;

// Runs one entry. On a fault returns 0 and sets ctx.fault (ctx.fault is cleared on entry).
// Temporary Memory starts every run zeroed (the wiki leaves its initial values unspecified).
float run(const Program& program, Entry entry, ExecContext& ctx);

// The float32 code of a foldable function (fn_foldable), shared by the interpreter and the optimizer.
float eval_pure(Fn fn, const float* args, int n);

}  // namespace sr::vm
