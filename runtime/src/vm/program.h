#pragma once

#include <cstdint>
#include <string>
#include <utility>
#include <vector>

#include "data/engine_data.h"
#include "data/load_error.h"
#include "vm/functions.h"

namespace sr::vm {

// Instruction set of a stack machine over float32 values. Every Fn below kFnCount is its own opcode:
// it pops its `n` arguments and pushes its result. Control-flow functions (Execute, If, And, Or, Switch*,
// While, DoWhile, JumpLoop, Block, Break) never appear as Fn opcodes; they compile to the internal ops.
enum class Op : uint16_t {
    Push = kFnCount,    // push bit_cast<float>(a)
    Pop,                // drop the top value
    Jump,               // pc = a
    JumpIfZero,         // pop v; if v == 0, pc = a
    AndJump,            // if top == 0, pc = a (keeping it); else pop
    OrJump,             // if top != 0, pc = a (keeping it); else pop
    LoopJump,           // counted back-jump: pc = a
    LoopJumpIfNonZero,  // pop v; if v != 0, counted back-jump: pc = a
    SwitchCase,         // pop c; if c == top, pop the test and pc = a
    SwitchTable,        // pop v; i = to_index(v); table at a: [count, default pc, pc_0 .. pc_count-1]
    JumpLoopNext,       // pop v; i = to_index(v); table at a: [count, end pc, pc_0 ..]; n = current branch;
                        //   out of range: push 0, pc = end; i <= n counts as a loop iteration
    Break,              // pop value, pop count; breaks[a .. a+n) are the enclosing Blocks, innermost first
    Return,             // the entry's result is on top
};

constexpr uint16_t op_code(Fn fn) { return uint16_t(fn); }
constexpr uint16_t op_code(Op op) { return uint16_t(op); }

struct Ins {
    uint16_t op;
    uint16_t n;  // argument count of a Fn opcode; see Op for the others
    int32_t a;
};

// Where a Break lands: the end of its Block's code, with the value stack cut back to the Block's height.
struct BreakTarget {
    int32_t pc;
    int32_t height;
};

struct CompileOptions {
    data::Mode mode;
    bool optimize = true;
};

// The engine uses something this runtime can't run: an unknown function, a function the mode doesn't
// allow, a call with an argument count its wiki page doesn't allow, a constant block id the mode
// doesn't define, or a Break with a constant count larger than its enclosing Blocks (function "Break").
// `function` is the function name, or "block <id>".
struct CompileError : data::LoadError {
    CompileError(const std::string& message, std::string what)
        : data::LoadError(message), function(std::move(what)) {}
    std::string function;
};

using Entry = int32_t;  // a node index that is a callback root

// Compiled entry points of one EngineModeData: flat code, compiled once at load.
class Program {
public:
    data::Mode mode() const { return mode_; }
    bool has_entry(Entry e) const {
        return e >= 0 && size_t(e) < entry_pc_.size() && entry_pc_[size_t(e)] >= 0;
    }
    int32_t entry_pc(Entry e) const { return entry_pc_[size_t(e)]; }
    int32_t entry_count() const { return entries_; }

    // Internals shared by the compiler and the interpreter.
    const std::vector<Ins>& code() const { return code_; }
    const std::vector<int32_t>& tables() const { return tables_; }
    const std::vector<BreakTarget>& breaks() const { return breaks_; }
    int32_t max_stack() const { return max_stack_; }

private:
    friend class Compiler;
    data::Mode mode_ = data::Mode::Watch;
    std::vector<Ins> code_;
    std::vector<int32_t> tables_;
    std::vector<BreakTarget> breaks_;
    std::vector<int32_t> entry_pc_;  // node index -> pc, -1 if not an entry
    int32_t entries_ = 0;
    int32_t max_stack_ = 0;          // deepest value stack over all entries
};

// Compiles every callback of every archetype, and the global updateSpawn. Throws CompileError (see above),
// or data::LoadError for a malformed node graph (a cycle, nesting deeper than the compiler allows).
Program compile(const data::EngineModeData& data, const CompileOptions& options);

}  // namespace sr::vm
