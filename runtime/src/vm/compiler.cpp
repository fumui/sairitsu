// Node graph -> flat stack-machine code (program.h), once per EngineModeData at load.
//
// Two passes per callback root:
//   validate  iterative DFS over the reachable graph: every function is known, legal in the mode, called
//             with an argument count its wiki page allows, and constant block ids exist in the mode. Also
//             rejects cycles and nesting deeper than kMaxHeight (codegen recurses once per level).
//   emit      recursive codegen. Shared nodes (the graph is a DAG) are emitted at each use, as they must run
//             at each use. With `optimize`, constant-argument foldable functions are evaluated with
//             eval_pure (the interpreter's own float32 code) and branches with constant tests are dropped.

#include <algorithm>
#include <bit>
#include <cstdio>
#include <optional>
#include <string>
#include <vector>

#include "vm/blocks.h"
#include "vm/convert.h"
#include "vm/interpreter.h"
#include "vm/program.h"

namespace sr::vm {

namespace {

constexpr int32_t kMaxHeight = 1024;          // deepest node nesting (Next RUSH: 20)
constexpr size_t kMaxCode = size_t(1) << 24;  // instructions per program (Next RUSH watch: ~1.1M)
constexpr size_t kMaxArgs = 65535;            // Ins::n

const char* resource_name(data::Mode mode) {
    return mode == data::Mode::Play ? "engine.playData" : "engine.watchData";
}

const char* mode_name(data::Mode mode) { return mode == data::Mode::Play ? "play" : "watch"; }

// Bit k set: argument k is a block id.
unsigned block_arg_mask(Fn fn) {
    switch (fn) {
        case Fn::Copy: return 0b101;
        case Fn::Get: case Fn::GetPointed: case Fn::GetShifted:
        case Fn::Set: case Fn::SetPointed: case Fn::SetShifted:
        case Fn::SetAdd: case Fn::SetAddPointed: case Fn::SetAddShifted:
        case Fn::SetSubtract: case Fn::SetSubtractPointed: case Fn::SetSubtractShifted:
        case Fn::SetMultiply: case Fn::SetMultiplyPointed: case Fn::SetMultiplyShifted:
        case Fn::SetDivide: case Fn::SetDividePointed: case Fn::SetDivideShifted:
        case Fn::SetMod: case Fn::SetModPointed: case Fn::SetModShifted:
        case Fn::SetRem: case Fn::SetRemPointed: case Fn::SetRemShifted:
        case Fn::SetPower: case Fn::SetPowerPointed: case Fn::SetPowerShifted:
        case Fn::IncrementPre: case Fn::IncrementPrePointed: case Fn::IncrementPreShifted:
        case Fn::IncrementPost: case Fn::IncrementPostPointed: case Fn::IncrementPostShifted:
        case Fn::DecrementPre: case Fn::DecrementPrePointed: case Fn::DecrementPreShifted:
        case Fn::DecrementPost: case Fn::DecrementPostPointed: case Fn::DecrementPostShifted:
            return 0b1;
        default:
            return 0;
    }
}

}  // namespace

class Compiler {
public:
    Compiler(const data::EngineModeData& data, const CompileOptions& options, Program& program)
        : options_(options), p_(program), nodes_(data.nodes) {
        const size_t n = nodes_.size();
        fn_.assign(n, Fn::Abs);
        color_.assign(n, 0);
        height_.assign(n, 0);
        fold_state_.assign(n, 0);
        fold_value_.assign(n, 0.0f);
        p_.mode_ = options.mode;
        p_.entry_pc_.assign(n, -1);
    }

    void compile_root(int32_t root) {
        if (p_.entry_pc_[size_t(root)] >= 0) return;
        validate(root);
        p_.entry_pc_[size_t(root)] = pc();
        ++p_.entries_;
        depth_ = 0;
        emit(root);
        ins(Op::Return);
    }

private:
    const data::Node& node(int32_t i) const { return nodes_[size_t(i)]; }
    std::string where(int32_t i) const { return std::string(resource_name(options_.mode)) + ": node " + std::to_string(i); }

    // ---- validation ----

    void check_node(int32_t i) {
        const data::Node& n = node(i);
        const auto fn = fn_from_name(n.func);
        if (!fn) throw CompileError(where(i) + ": unknown function '" + n.func + "'", n.func);
        const FnInfo& info = fn_info(*fn);
        if (!fn_allowed(*fn, options_.mode)) {
            throw CompileError(where(i) + ": function '" + n.func + "' is not available in " +
                                   mode_name(options_.mode) + " mode",
                               n.func);
        }
        const size_t argc = n.args.size();
        bool ok = argc >= size_t(info.min_args) && (info.max_args < 0 || argc <= size_t(info.max_args)) &&
                  argc <= kMaxArgs;
        if (*fn == Fn::Switch) ok = ok && (argc - 1) % 2 == 0;
        if (*fn == Fn::SwitchWithDefault) ok = ok && (argc - 2) % 2 == 0;
        if (!ok) {
            throw CompileError(where(i) + ": function '" + n.func + "' called with " + std::to_string(argc) +
                                   " arguments",
                               n.func);
        }
        const unsigned mask = block_arg_mask(*fn);
        for (size_t k = 0; k < argc; ++k) {
            if (!((mask >> k) & 1u)) continue;
            const data::Node& arg = node(n.args[k]);
            if (!arg.is_value) continue;  // computed ids are checked at run time
            const int32_t id = to_id(arg.value);  // a non-integer constant id names no block
            if (find_block(options_.mode, id) == nullptr) {
                char shown[32];
                std::snprintf(shown, sizeof shown, "%.9g", double(arg.value));
                throw CompileError(where(i) + ": function '" + n.func + "' uses block " + shown + ", which " +
                                       mode_name(options_.mode) + " mode doesn't define",
                                   std::string("block ") + shown);
            }
        }
        fn_[size_t(i)] = *fn;
    }

    // Iterative DFS: 0 unvisited, 1 on the current path, 2 done (height_ set).
    void validate(int32_t root) {
        struct Frame {
            int32_t node;
            size_t next;
        };
        std::vector<Frame> stack{{root, 0}};
        color_[size_t(root)] = 1;
        if (!node(root).is_value) check_node(root);
        while (!stack.empty()) {
            Frame& f = stack.back();
            const data::Node& n = node(f.node);
            if (f.next < n.args.size()) {
                const int32_t child = n.args[f.next++];
                if (color_[size_t(child)] == 1) throw data::LoadError(where(child) + ": node graph has a cycle");
                if (color_[size_t(child)] == 0) {
                    color_[size_t(child)] = 1;
                    if (!node(child).is_value) check_node(child);
                    stack.push_back({child, 0});
                }
                continue;
            }
            int32_t h = 0;
            for (int32_t a : n.args) h = std::max(h, height_[size_t(a)]);
            height_[size_t(f.node)] = h + 1;
            if (h + 1 > kMaxHeight) throw data::LoadError(where(f.node) + ": nodes nested too deeply");
            color_[size_t(f.node)] = 2;
            stack.pop_back();
        }
    }

    // ---- constant folding ----

    // The value of node i if the optimizer can compute it now, with the interpreter's semantics.
    std::optional<float> fold(int32_t i) {
        uint8_t& state = fold_state_[size_t(i)];
        if (state == 1) return fold_value_[size_t(i)];
        if (state == 2) return std::nullopt;
        std::optional<float> v = compute_fold(i);
        state = v ? 1 : 2;
        if (v) fold_value_[size_t(i)] = *v;
        return v;
    }

    std::optional<float> compute_fold(int32_t i) {
        const data::Node& n = node(i);
        if (n.is_value) return n.value;
        const Fn fn = fn_[size_t(i)];
        const auto& a = n.args;
        if (fn_foldable(fn)) {
            std::vector<float> values;
            values.reserve(a.size());
            for (int32_t x : a) {
                auto v = fold(x);
                if (!v) return std::nullopt;
                values.push_back(*v);
            }
            return eval_pure(fn, values.data(), int(values.size()));
        }
        switch (fn) {
            case Fn::Execute:
            case Fn::Execute0: {
                float last = 0.0f;
                for (int32_t x : a) {
                    auto v = fold(x);
                    if (!v) return std::nullopt;
                    last = *v;
                }
                return fn == Fn::Execute ? last : 0.0f;
            }
            case Fn::If: {
                auto t = fold(a[0]);
                if (!t) return std::nullopt;
                return fold(*t != 0.0f ? a[1] : a[2]);
            }
            case Fn::And:
            case Fn::Or: {
                float last = 0.0f;
                for (int32_t x : a) {
                    auto v = fold(x);
                    if (!v) return std::nullopt;
                    last = *v;
                    if ((fn == Fn::And) == (last == 0.0f)) return last;
                }
                return last;
            }
            case Fn::Block: return fold(a[0]);
            case Fn::Switch:
            case Fn::SwitchWithDefault: {
                auto choice = constant_switch(fn, a);
                if (!choice) return std::nullopt;
                return *choice < 0 ? std::optional<float>(0.0f) : fold(*choice);
            }
            case Fn::SwitchInteger:
            case Fn::SwitchIntegerWithDefault: {
                auto choice = constant_switch_integer(fn, a);
                if (!choice) return std::nullopt;
                return *choice < 0 ? std::optional<float>(0.0f) : fold(*choice);
            }
            case Fn::While: {
                auto t = fold(a[0]);
                if (t && *t == 0.0f) return 0.0f;
                return std::nullopt;
            }
            case Fn::DoWhile: {
                auto body = fold(a[0]);
                auto t = fold(a[1]);
                if (body && t && *t == 0.0f) return 0.0f;
                return std::nullopt;
            }
            default:
                return std::nullopt;
        }
    }

    // For a Switch / SwitchWithDefault whose test and cases (up to the match) are constants: the node to
    // run, or -1 for "no match and no default" (result 0). nullopt if it can't be decided now.
    std::optional<int32_t> constant_switch(Fn fn, const std::vector<int32_t>& a) {
        const bool has_default = fn == Fn::SwitchWithDefault;
        auto t = fold(a[0]);
        if (!t) return std::nullopt;
        const size_t pairs = (a.size() - 1 - (has_default ? 1 : 0)) / 2;
        for (size_t k = 0; k < pairs; ++k) {
            auto c = fold(a[1 + 2 * k]);
            if (!c) return std::nullopt;
            if (*t == *c) return a[2 + 2 * k];
        }
        return has_default ? a.back() : -1;
    }

    std::optional<int32_t> constant_switch_integer(Fn fn, const std::vector<int32_t>& a) {
        const bool has_default = fn == Fn::SwitchIntegerWithDefault;
        auto t = fold(a[0]);
        if (!t) return std::nullopt;
        const int32_t branches = int32_t(a.size()) - 1 - (has_default ? 1 : 0);
        const int32_t k = branch_index(*t, branches);
        if (k >= 0) return a[size_t(1 + k)];
        return has_default ? a.back() : -1;
    }

    // ---- codegen ----

    int32_t pc() const { return int32_t(p_.code_.size()); }

    int32_t ins(Op op, uint16_t n = 0, int32_t a = 0) { return ins(op_code(op), n, a); }
    int32_t ins(uint16_t op, uint16_t n, int32_t a) {
        if (p_.code_.size() >= kMaxCode) throw data::LoadError(std::string(resource_name(options_.mode)) + ": program too large");
        p_.code_.push_back(Ins{op, n, a});
        return pc() - 1;
    }
    void patch(int32_t at, int32_t target) { p_.code_[size_t(at)].a = target; }

    void set_depth(int32_t d) {
        depth_ = d;
        p_.max_stack_ = std::max(p_.max_stack_, d);
    }

    void push_const(float v) {
        ins(Op::Push, 0, std::bit_cast<int32_t>(v));
        set_depth(depth_ + 1);
    }

    void emit(int32_t i) {
        const data::Node& n = node(i);
        if (n.is_value) {
            push_const(n.value);
            return;
        }
        if (options_.optimize) {
            if (auto v = fold(i)) {
                push_const(*v);
                return;
            }
        }
        const Fn fn = fn_[size_t(i)];
        const auto& a = n.args;
        switch (fn) {
            case Fn::Execute: emit_execute(a, false); return;
            case Fn::Execute0: emit_execute(a, true); return;
            case Fn::If: emit_if(a); return;
            case Fn::And: emit_and_or(a, true); return;
            case Fn::Or: emit_and_or(a, false); return;
            case Fn::Switch: emit_switch(fn, a); return;
            case Fn::SwitchWithDefault: emit_switch(fn, a); return;
            case Fn::SwitchInteger: emit_switch_integer(fn, a); return;
            case Fn::SwitchIntegerWithDefault: emit_switch_integer(fn, a); return;
            case Fn::While: emit_while(a); return;
            case Fn::DoWhile: emit_do_while(a); return;
            case Fn::JumpLoop: emit_jump_loop(a); return;
            case Fn::Block: emit_block(a); return;
            case Fn::Break: emit_break(i, a); return;
            default: break;
        }
        for (int32_t x : a) emit(x);
        ins(op_code(fn), uint16_t(a.size()), 0);
        set_depth(depth_ - int32_t(a.size()) + 1);
    }

    void emit_execute(const std::vector<int32_t>& args, bool zero) {
        std::vector<int32_t> list;
        for (size_t k = 0; k < args.size(); ++k) {
            const bool last = k + 1 == args.size();
            // A constant has no effect unless it is Execute's result.
            if (options_.optimize && (zero || !last) && fold(args[k])) continue;
            list.push_back(args[k]);
        }
        if (list.empty()) {
            push_const(0.0f);
            return;
        }
        for (size_t k = 0; k < list.size(); ++k) {
            emit(list[k]);
            if (zero || k + 1 < list.size()) {
                ins(Op::Pop);
                set_depth(depth_ - 1);
            }
        }
        if (zero) push_const(0.0f);
    }

    void emit_if(const std::vector<int32_t>& a) {
        if (options_.optimize) {
            if (auto t = fold(a[0])) {
                emit(*t != 0.0f ? a[1] : a[2]);
                return;
            }
        }
        emit(a[0]);
        const int32_t to_else = ins(Op::JumpIfZero);
        const int32_t d = depth_ - 1;
        set_depth(d);
        emit(a[1]);
        const int32_t to_end = ins(Op::Jump);
        patch(to_else, pc());
        set_depth(d);
        emit(a[2]);
        patch(to_end, pc());
    }

    void emit_and_or(const std::vector<int32_t>& args, bool is_and) {
        std::vector<int32_t> list;
        for (size_t k = 0; k < args.size(); ++k) {
            if (!options_.optimize) {
                list.push_back(args[k]);
                continue;
            }
            auto c = fold(args[k]);
            if (!c) {
                list.push_back(args[k]);
                continue;
            }
            // A constant that decides the result ends the list; one that doesn't is dropped unless last.
            if (is_and == (*c == 0.0f)) {
                list.push_back(args[k]);
                break;
            }
            if (k + 1 == args.size()) list.push_back(args[k]);
        }
        if (list.empty()) {
            push_const(0.0f);
            return;
        }
        const int32_t d = depth_;
        std::vector<int32_t> to_end;
        for (size_t k = 0; k + 1 < list.size(); ++k) {
            emit(list[k]);
            to_end.push_back(ins(is_and ? Op::AndJump : Op::OrJump));
            set_depth(d);
        }
        emit(list.back());
        for (int32_t at : to_end) patch(at, pc());
    }

    void emit_switch(Fn fn, const std::vector<int32_t>& a) {
        const bool has_default = fn == Fn::SwitchWithDefault;
        if (options_.optimize) {
            if (auto choice = constant_switch(fn, a)) {
                if (*choice < 0) {
                    push_const(0.0f);
                } else {
                    emit(*choice);
                }
                return;
            }
        }
        const size_t pairs = (a.size() - 1 - (has_default ? 1 : 0)) / 2;
        const int32_t d = depth_;
        emit(a[0]);
        std::vector<int32_t> to_branch;
        for (size_t k = 0; k < pairs; ++k) {
            emit(a[1 + 2 * k]);
            to_branch.push_back(ins(Op::SwitchCase));
            set_depth(d + 1);
        }
        ins(Op::Pop);
        set_depth(d);
        if (has_default) {
            emit(a.back());
        } else {
            push_const(0.0f);
        }
        std::vector<int32_t> to_end{ins(Op::Jump)};
        for (size_t k = 0; k < pairs; ++k) {
            patch(to_branch[k], pc());
            set_depth(d);
            emit(a[2 + 2 * k]);
            to_end.push_back(ins(Op::Jump));
        }
        for (int32_t at : to_end) patch(at, pc());
        set_depth(d + 1);
    }

    void emit_switch_integer(Fn fn, const std::vector<int32_t>& a) {
        const bool has_default = fn == Fn::SwitchIntegerWithDefault;
        if (options_.optimize) {
            if (auto choice = constant_switch_integer(fn, a)) {
                if (*choice < 0) {
                    push_const(0.0f);
                } else {
                    emit(*choice);
                }
                return;
            }
        }
        const size_t branches = a.size() - 1 - (has_default ? 1 : 0);
        const int32_t d = depth_;
        emit(a[0]);
        auto& t = p_.tables_;
        const int32_t table = int32_t(t.size());
        t.push_back(int32_t(branches));
        t.push_back(-1);
        t.resize(t.size() + branches, -1);
        ins(Op::SwitchTable, 0, table);
        std::vector<int32_t> to_end;
        for (size_t k = 0; k < branches; ++k) {
            p_.tables_[size_t(table) + 2 + k] = pc();
            set_depth(d);
            emit(a[1 + k]);
            to_end.push_back(ins(Op::Jump));
        }
        p_.tables_[size_t(table) + 1] = pc();
        set_depth(d);
        if (has_default) {
            emit(a.back());
        } else {
            push_const(0.0f);
        }
        for (int32_t at : to_end) patch(at, pc());
    }

    void emit_while(const std::vector<int32_t>& a) {
        const int32_t d = depth_;
        const int32_t top = pc();
        emit(a[0]);
        const int32_t to_end = ins(Op::JumpIfZero);
        set_depth(d);
        emit(a[1]);
        ins(Op::Pop);
        ins(Op::LoopJump, 0, top);
        patch(to_end, pc());
        set_depth(d);
        push_const(0.0f);
    }

    void emit_do_while(const std::vector<int32_t>& a) {
        const int32_t d = depth_;
        if (options_.optimize) {
            if (auto t = fold(a[1]); t && *t == 0.0f) {
                emit(a[0]);
                ins(Op::Pop);
                set_depth(d);
                push_const(0.0f);
                return;
            }
        }
        const int32_t top = pc();
        emit(a[0]);
        ins(Op::Pop);
        set_depth(d);
        emit(a[1]);
        ins(Op::LoopJumpIfNonZero, 0, top);
        set_depth(d);
        push_const(0.0f);
    }

    void emit_jump_loop(const std::vector<int32_t>& a) {
        if (a.size() == 1) {
            emit(a[0]);
            return;
        }
        const int32_t d = depth_;
        auto& t = p_.tables_;
        const int32_t table = int32_t(t.size());
        t.push_back(int32_t(a.size()));
        t.push_back(-1);
        t.resize(t.size() + a.size(), -1);
        for (size_t k = 0; k < a.size(); ++k) {
            p_.tables_[size_t(table) + 2 + k] = pc();
            set_depth(d);
            emit(a[k]);
            if (k + 1 < a.size()) ins(Op::JumpLoopNext, uint16_t(k), table);
        }
        p_.tables_[size_t(table) + 1] = pc();
        set_depth(d + 1);
    }

    struct OpenBlock {
        int32_t height;
        std::vector<int32_t> targets;  // indices into Program::breaks_ that land at this block's end
    };

    void emit_block(const std::vector<int32_t>& a) {
        blocks_.push_back(OpenBlock{depth_, {}});
        emit(a[0]);
        const int32_t end = pc();
        for (int32_t at : blocks_.back().targets) p_.breaks_[size_t(at)].pc = end;
        blocks_.pop_back();
    }

    void emit_break(int32_t i, const std::vector<int32_t>& a) {
        // A constant count must stay within the enclosing Blocks (a run-time count is checked at run time).
        // fold() is analysis only, so this holds with and without the optimizer.
        if (auto count = fold(a[0])) {
            const int32_t c = to_index(*count);
            if (std::max(c, 1) > int32_t(blocks_.size())) {
                throw CompileError(where(i) + ": Break(" + std::to_string(c) + ") with " +
                                       std::to_string(blocks_.size()) + " enclosing blocks",
                                   "Break");
            }
        }
        emit(a[0]);
        emit(a[1]);
        const int32_t first = int32_t(p_.breaks_.size());
        for (size_t k = blocks_.size(); k-- > 0;) {
            blocks_[k].targets.push_back(int32_t(p_.breaks_.size()));
            p_.breaks_.push_back(BreakTarget{-1, blocks_[k].height});
        }
        ins(Op::Break, uint16_t(blocks_.size()), first);
        // Never falls through; account for it as a value, like any other node.
        set_depth(depth_ - 1);
    }

    const CompileOptions& options_;
    Program& p_;
    const std::vector<data::Node>& nodes_;
    std::vector<Fn> fn_;              // resolved function of each validated node
    std::vector<uint8_t> color_;      // validation state
    std::vector<int32_t> height_;     // nesting height of each validated node
    std::vector<uint8_t> fold_state_; // 0 unknown, 1 constant, 2 not constant
    std::vector<float> fold_value_;
    std::vector<OpenBlock> blocks_;   // enclosing Blocks during emit, outermost first
    int32_t depth_ = 0;               // value stack height at the current point of the code
};

Program compile(const data::EngineModeData& data, const CompileOptions& options) {
    Program program;
    Compiler compiler(data, options, program);
    for (const data::Archetype& archetype : data.archetypes)
        for (const auto& [kind, callback] : archetype.callbacks) compiler.compile_root(callback.node);
    if (data.update_spawn >= 0) compiler.compile_root(data.update_spawn);
    return program;
}

}  // namespace sr::vm
