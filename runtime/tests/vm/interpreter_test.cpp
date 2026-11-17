#include <bit>
#include <cmath>
#include <cstdint>
#include <functional>
#include <iterator>
#include <limits>
#include <random>
#include <string>
#include <vector>

#include "doctest.h"
#include "support/node_builder.h"
#include "support/recording_host.h"
#include "vm/easing.h"
#include "vm/fmath.h"
#include "vm/interpreter.h"
#include "vm/memory.h"
#include "vm/program.h"

using sr::data::CallbackKind;
using sr::data::Mode;
using sr::test::NodeBuilder;
using sr::test::RecordingHost;
using sr::vm::ExecContext;
using sr::vm::Fault;
using sr::vm::Fn;
using sr::vm::Memory;
using sr::vm::MemoryConfig;
using Ref = NodeBuilder::Ref;

namespace {

constexpr int32_t kLevelMemory = 2000;   // writable in updateSequential (watch)
constexpr int32_t kLevelData = 2001;     // writable in preprocess only
constexpr int32_t kEntityMemory = 4000;  // 64 floats per entity
constexpr int32_t kTemporary = 10000;

MemoryConfig config() {
    MemoryConfig c;
    c.entity_count = 4;
    c.spawn_capacity = 2;
    c.option_count = 2;
    c.bucket_count = 1;
    c.archetype_count = 1;
    c.rom_size = 4;
    return c;
}

const bool kBoth[] = {false, true};

// One engine under test: build nodes with `b`, then run() compiles and runs one root.
struct Vm {
    explicit Vm(bool optimize, Mode mode = Mode::Watch) : optimize(optimize), mode(mode), memory(mode, config()) {}

    float run(Ref root, CallbackKind kind = CallbackKind::UpdateSequential, int32_t entity = 0,
              const std::string& archetype = "Test") {
        const int32_t entry = b.entry(root, kind, archetype);
        const sr::vm::Program program = sr::vm::compile(b.data(), {mode, optimize});
        ExecContext ctx;
        ctx.memory = &memory;
        ctx.host = &host;
        ctx.callback = kind;
        ctx.entity = entity;
        ctx.archetype = archetype;
        const float result = sr::vm::run(program, entry, ctx);
        fault = ctx.fault;
        return result;
    }

    float& at(int32_t block, int32_t index) { return memory.raw(block)[size_t(index)]; }

    bool optimize;
    Mode mode;
    NodeBuilder b;
    Memory memory;
    RecordingHost host;
    Fault fault;
};

uint32_t bits(float x) { return std::bit_cast<uint32_t>(x); }

}  // namespace

TEST_SUITE("vm") {

TEST_CASE("arithmetic is float32") {
    for (bool opt : kBoth) {
        CAPTURE(opt);
        Vm vm(opt);
        CHECK(vm.run(vm.b("Add", 16777216, 1)) == 16777216.0f);
        CHECK(vm.fault.kind == Fault::None);
        // The same through memory, so nothing can be folded.
        Vm vm2(opt);
        vm2.at(kLevelMemory, 0) = 16777216.0f;
        CHECK(vm2.run(vm2.b("Add", vm2.b("Get", kLevelMemory, 0), 1)) == 16777216.0f);
        // 0.1f + 0.2f == 0.3f in float32 (not in double).
        Vm vm3(opt);
        CHECK(vm3.run(vm3.b("Equal", vm3.b("Add", 0.1f, 0.2f), 0.3f)) == 1.0f);
        Vm vm4(opt);
        CHECK(vm4.run(vm4.b("Divide", 1, 3)) == 1.0f / 3.0f);
    }
}

TEST_CASE("variadic arithmetic folds left") {
    for (bool opt : kBoth) {
        CAPTURE(opt);
        Vm vm(opt);
        CHECK(vm.run(vm.b("Subtract", 10, 3, 2)) == 5.0f);
        Vm vm2(opt);
        CHECK(vm2.run(vm2.b("Power", 2, 3, 2)) == 64.0f);
        Vm vm3(opt);
        CHECK(vm3.run(vm3.b("Add")) == 0.0f);
        Vm vm4(opt);
        CHECK(vm4.run(vm4.b("Mod", -7, 3)) == 2.0f);
        Vm vm5(opt);
        CHECK(vm5.run(vm5.b("Rem", -7, 3)) == -1.0f);
    }
}

TEST_CASE("execute returns last") {
    for (bool opt : kBoth) {
        CAPTURE(opt);
        Vm vm(opt);
        CHECK(vm.run(vm.b("Execute", vm.b("Set", kLevelMemory, 0, 9), 2, 3)) == 3.0f);
        CHECK(vm.at(kLevelMemory, 0) == 9.0f);
        Vm vm2(opt);
        CHECK(vm2.run(vm2.b("Execute0", 5, vm2.b("Set", kLevelMemory, 1, 4))) == 0.0f);
        CHECK(vm2.at(kLevelMemory, 1) == 4.0f);
        Vm vm3(opt);
        CHECK(vm3.run(vm3.b("Execute")) == 0.0f);
    }
}

TEST_CASE("and or short circuit") {
    for (bool opt : kBoth) {
        CAPTURE(opt);
        Vm vm(opt);
        CHECK(vm.run(vm.b("And", 0, vm.b("Set", kLevelMemory, 0, 1))) == 0.0f);
        CHECK(vm.at(kLevelMemory, 0) == 0.0f);
        Vm vm2(opt);
        CHECK(vm2.run(vm2.b("Or", 2, vm2.b("Set", kLevelMemory, 0, 1))) == 2.0f);
        CHECK(vm2.at(kLevelMemory, 0) == 0.0f);
        // Last value when every value is non-zero / first non-zero value.
        Vm vm3(opt);
        CHECK(vm3.run(vm3.b("And", 1, vm3.b("Get", kLevelMemory, 5), 7)) == 0.0f);
        Vm vm4(opt);
        vm4.at(kLevelMemory, 5) = 3;
        CHECK(vm4.run(vm4.b("And", 1, vm4.b("Get", kLevelMemory, 5), 7)) == 7.0f);
        Vm vm5(opt);
        vm5.at(kLevelMemory, 5) = 3;
        CHECK(vm5.run(vm5.b("Or", 0, vm5.b("Get", kLevelMemory, 5), 7)) == 3.0f);
        Vm vm6(opt);
        CHECK(vm6.run(vm6.b("Or", 0, vm6.b("Get", kLevelMemory, 5))) == 0.0f);
        Vm vm7(opt);
        CHECK(vm7.run(vm7.b("And")) == 0.0f);
        Vm vm8(opt);
        CHECK(vm8.run(vm8.b("Or")) == 0.0f);
    }
}

TEST_CASE("if picks a branch") {
    for (bool opt : kBoth) {
        CAPTURE(opt);
        Vm vm(opt);
        CHECK(vm.run(vm.b("If", 1, vm.b("Set", kLevelMemory, 0, 5), vm.b("Set", kLevelMemory, 1, 6))) == 5.0f);
        CHECK(vm.at(kLevelMemory, 0) == 5.0f);
        CHECK(vm.at(kLevelMemory, 1) == 0.0f);
        Vm vm2(opt);
        const float nan = std::numeric_limits<float>::quiet_NaN();
        CHECK(vm2.run(vm2.b("If", nan, 1, 2)) == 1.0f);  // NaN is truthy
        Vm vm3(opt);
        CHECK(vm3.run(vm3.b("If", -0.0f, 1, 2)) == 2.0f);
    }
}

TEST_CASE("break across two blocks") {
    for (bool opt : kBoth) {
        CAPTURE(opt);
        Vm vm(opt);
        auto inner = vm.b("Block", vm.b("Execute", vm.b("Break", 2, 7), vm.b("Set", kLevelMemory, 0, 1)));
        CHECK(vm.run(vm.b("Block", vm.b("Add", 100, inner))) == 7.0f);
        CHECK(vm.at(kLevelMemory, 0) == 0.0f);
        CHECK(vm.fault.kind == Fault::None);
        // Break(1) leaves only the inner block.
        Vm vm2(opt);
        auto inner2 = vm2.b("Block", vm2.b("Execute", vm2.b("Break", 1, 7), 8));
        CHECK(vm2.run(vm2.b("Block", vm2.b("Add", 100, inner2))) == 107.0f);
        // Out of a loop, with values pending on the stack.
        Vm vm3(opt);
        auto loop = vm3.b("While", 1, vm3.b("Add", 1, vm3.b("Break", 1, 42)));
        CHECK(vm3.run(vm3.b("Add", 1, vm3.b("Block", vm3.b("Execute", loop, 5)))) == 43.0f);
        // A Block without a Break returns its body.
        Vm vm4(opt);
        CHECK(vm4.run(vm4.b("Block", vm4.b("Get", kLevelMemory, 0))) == 0.0f);
    }
}

TEST_CASE("break with a run-time count beyond the enclosing blocks faults") {
    // (A constant count beyond them is a compile error: compiler_test.cpp.)
    for (bool opt : kBoth) {
        CAPTURE(opt);
        Vm vm(opt);
        vm.at(kLevelMemory, 0) = 2;
        CHECK(vm.run(vm.b("Block", vm.b("Break", vm.b("Get", kLevelMemory, 0), 7))) == 0.0f);
        CHECK(vm.fault.kind == Fault::Other);
        Vm vm2(opt);
        vm2.at(kLevelMemory, 0) = 2;
        auto two = vm2.b("Block", vm2.b("Block", vm2.b("Break", vm2.b("Get", kLevelMemory, 0), 7)));
        CHECK(vm2.run(vm2.b("Add", 1, two)) == 8.0f);
        CHECK(vm2.fault.kind == Fault::None);
    }
}

TEST_CASE("break crosses switch, jump loop and and with values pending") {
    for (bool opt : kBoth) {
        CAPTURE(opt);
        // Break inside a Switch case expression, with the test on the stack.
        Vm vm(opt);
        auto sw = vm.b("SwitchWithDefault", vm.b("Get", kLevelMemory, 0), vm.b("Add", 3, vm.b("Break", 1, 5)), 1, 2);
        CHECK(vm.run(vm.b("Add", 1000, vm.b("Block", vm.b("Add", 100, sw)))) == 1005.0f);
        // Break inside a JumpLoop branch, with an Add operand pending.
        Vm vm2(opt);
        auto loop = vm2.b("JumpLoop", vm2.b("Execute", vm2.b("Get", kLevelMemory, 0), 1),
                          vm2.b("Add", 2, vm2.b("Break", 1, 7)), 3);
        CHECK(vm2.run(vm2.b("Add", 1000, vm2.b("Block", vm2.b("Add", 1, loop)))) == 1007.0f);
        // Break inside an And operand.
        Vm vm3(opt);
        vm3.at(kLevelMemory, 5) = 3;
        auto both = vm3.b("And", vm3.b("Get", kLevelMemory, 5), vm3.b("Add", 4, vm3.b("Break", 1, 9)));
        CHECK(vm3.run(vm3.b("Add", 1000, vm3.b("Block", vm3.b("Add", 1, both)))) == 1009.0f);
        // Two levels, from inside an integer switch.
        Vm vm4(opt);
        auto si = vm4.b("SwitchIntegerWithDefault", vm4.b("Get", kLevelMemory, 0), vm4.b("Add", 6, vm4.b("Break", 2, 11)), 0);
        auto nest = vm4.b("Block", vm4.b("Add", 10, vm4.b("Block", vm4.b("Add", 20, si))));
        CHECK(vm4.run(vm4.b("Add", 1000, nest)) == 1011.0f);
        CHECK(vm4.fault.kind == Fault::None);
    }
}

TEST_CASE("a shared node runs at each use") {
    for (bool opt : kBoth) {
        CAPTURE(opt);
        Vm vm(opt);
        auto x = vm.b("IncrementPost", kLevelMemory, 0);
        CHECK(vm.run(vm.b("Add", x, x)) == 3.0f);  // 1 + 2
        CHECK(vm.at(kLevelMemory, 0) == 2.0f);
        Vm vm2(opt);
        auto y = vm2.b("Multiply", 2, vm2.b("IncrementPost", kLevelMemory, 0));
        CHECK(vm2.run(vm2.b("Execute", y, y, vm2.b("Add", y, 1))) == 7.0f);
        CHECK(vm2.at(kLevelMemory, 0) == 3.0f);
    }
}

TEST_CASE("integer switches and jump loops need an integer index") {
    const float nan = std::numeric_limits<float>::quiet_NaN();
    for (bool opt : kBoth) {
        CAPTURE(opt);
        for (float v : {1.5f, 0.5f, -0.5f, nan}) {
            CAPTURE(v);
            Vm vm(opt);
            CHECK(vm.run(vm.b("SwitchIntegerWithDefault", v, 10, 20, 30)) == 30.0f);
            Vm vm2(opt);
            CHECK(vm2.run(vm2.b("SwitchInteger", v, 10, 20)) == 0.0f);
            Vm vm3(opt);
            vm3.at(kLevelMemory, 0) = v;
            CHECK(vm3.run(vm3.b("SwitchIntegerWithDefault", vm3.b("Get", kLevelMemory, 0), 10, 20, 30)) == 30.0f);
            Vm vm4(opt);
            vm4.at(kLevelMemory, 0) = v;
            CHECK(vm4.run(vm4.b("Add", 5, vm4.b("JumpLoop", vm4.b("Get", kLevelMemory, 0), 99))) == 5.0f);
            Vm vm5(opt);
            CHECK(vm5.run(vm5.b("Add", 5, vm5.b("JumpLoop", v, 99))) == 5.0f);
        }
        Vm vm(opt);
        CHECK(vm.run(vm.b("SwitchIntegerWithDefault", -0.0f, 10, 20, 30)) == 10.0f);
    }
}

TEST_CASE("jump loop runs until last branch") {
    for (bool opt : kBoth) {
        CAPTURE(opt);
        Vm vm(opt);
        // 0: i = 0 -> 1; 1: i < 5 ? (i++, 1) : 2; 2: i * 10
        auto b0 = vm.b("Execute", vm.b("Set", kLevelMemory, 0, 0), 1);
        auto b1 = vm.b("If", vm.b("Less", vm.b("Get", kLevelMemory, 0), 5),
                       vm.b("Execute", vm.b("IncrementPost", kLevelMemory, 0), 1), 2);
        auto b2 = vm.b("Multiply", vm.b("Get", kLevelMemory, 0), 10);
        CHECK(vm.run(vm.b("JumpLoop", b0, b1, b2)) == 50.0f);
        CHECK(vm.fault.kind == Fault::None);
        // A single branch is the last branch.
        Vm vm2(opt);
        CHECK(vm2.run(vm2.b("JumpLoop", 9)) == 9.0f);
        // Branch 0 may jump straight to the last.
        Vm vm3(opt);
        CHECK(vm3.run(vm3.b("JumpLoop", 2, vm3.b("Set", kLevelMemory, 0, 1), 4)) == 4.0f);
        CHECK(vm3.at(kLevelMemory, 0) == 0.0f);
    }
}

TEST_CASE("jump loop out of range returns 0") {
    for (bool opt : kBoth) {
        CAPTURE(opt);
        Vm vm(opt);
        CHECK(vm.run(vm.b("JumpLoop", 7, 99)) == 0.0f);
        Vm vm2(opt);
        CHECK(vm2.run(vm2.b("JumpLoop", -1, 99)) == 0.0f);
        Vm vm3(opt);
        CHECK(vm3.run(vm3.b("Add", 5, vm3.b("JumpLoop", 2, 99))) == 5.0f);
        CHECK(vm3.fault.kind == Fault::None);
    }
}

TEST_CASE("switch with default") {
    for (bool opt : kBoth) {
        CAPTURE(opt);
        Vm vm(opt);
        CHECK(vm.run(vm.b("SwitchWithDefault", 2, 1, 10, 2, 20, 99)) == 20.0f);
        Vm vm2(opt);
        CHECK(vm2.run(vm2.b("SwitchWithDefault", 3, 1, 10, 2, 20, 99)) == 99.0f);
        Vm vm3(opt);
        CHECK(vm3.run(vm3.b("Switch", 3, 1, 10, 2, 20)) == 0.0f);
        // Float equality: 0.1f + 0.2f == 0.3f, -0 == 0.
        Vm vm4(opt);
        CHECK(vm4.run(vm4.b("Switch", vm4.b("Add", 0.1f, 0.2f), 0.3f, 1)) == 1.0f);
        Vm vm5(opt);
        CHECK(vm5.run(vm5.b("SwitchWithDefault", -0.0f, 0, 5, 6)) == 5.0f);
        // Cases are evaluated in order, only until one matches; only the matching branch runs.
        Vm vm6(opt);
        vm6.at(kLevelMemory, 9) = 2;
        auto sw = vm6.b("SwitchWithDefault", vm6.b("Get", kLevelMemory, 9), vm6.b("IncrementPost", kLevelMemory, 0),
                        vm6.b("Set", kLevelMemory, 1, 1), vm6.b("IncrementPost", kLevelMemory, 0),
                        vm6.b("Set", kLevelMemory, 2, 1), vm6.b("IncrementPost", kLevelMemory, 0),
                        vm6.b("Set", kLevelMemory, 3, 1), 0);
        CHECK(vm6.run(sw) == 1.0f);
        CHECK(vm6.at(kLevelMemory, 0) == 2.0f);
        CHECK(vm6.at(kLevelMemory, 1) == 0.0f);
        CHECK(vm6.at(kLevelMemory, 2) == 1.0f);
        CHECK(vm6.at(kLevelMemory, 3) == 0.0f);
        // Integer switches index the branches.
        Vm vm7(opt);
        CHECK(vm7.run(vm7.b("SwitchInteger", 1, 10, 20)) == 20.0f);
        Vm vm8(opt);
        CHECK(vm8.run(vm8.b("SwitchIntegerWithDefault", 5, 10, 20, 30)) == 30.0f);
        Vm vm9(opt);
        CHECK(vm9.run(vm9.b("SwitchInteger", -1, 10, 20)) == 0.0f);
        Vm vm10(opt);
        vm10.at(kLevelMemory, 0) = 1;
        CHECK(vm10.run(vm10.b("SwitchIntegerWithDefault", vm10.b("Get", kLevelMemory, 0), 10, 20, 30)) == 20.0f);
    }
}

TEST_CASE("while and do while") {
    for (bool opt : kBoth) {
        CAPTURE(opt);
        Vm vm(opt);
        auto w = vm.b("While", vm.b("Less", vm.b("Get", kLevelMemory, 0), 3), vm.b("IncrementPost", kLevelMemory, 0));
        CHECK(vm.run(w) == 0.0f);
        CHECK(vm.at(kLevelMemory, 0) == 3.0f);
        Vm vm2(opt);
        CHECK(vm2.run(vm2.b("DoWhile", vm2.b("IncrementPost", kLevelMemory, 1), 0)) == 0.0f);
        CHECK(vm2.at(kLevelMemory, 1) == 1.0f);
        Vm vm3(opt);
        auto dw = vm3.b("DoWhile", vm3.b("IncrementPost", kLevelMemory, 0),
                        vm3.b("Less", vm3.b("Get", kLevelMemory, 0), 4));
        CHECK(vm3.run(dw) == 0.0f);
        CHECK(vm3.at(kLevelMemory, 0) == 4.0f);
        Vm vm4(opt);
        CHECK(vm4.run(vm4.b("While", 0, vm4.b("Set", kLevelMemory, 0, 1))) == 0.0f);
        CHECK(vm4.at(kLevelMemory, 0) == 0.0f);
    }
}

TEST_CASE("runaway loop faults") {
    for (bool opt : kBoth) {
        CAPTURE(opt);
        Vm vm(opt);
        CHECK(vm.run(vm.b("While", 1, 0)) == 0.0f);
        CHECK(vm.fault.kind == Fault::RunawayLoop);
        // 2^26 iterations are allowed; the next back-jump faults.
        Vm vm2(opt);
        CHECK(vm2.run(vm2.b("While", 1, vm2.b("DebugLog", 1))) == 0.0f);
        CHECK(vm2.fault.kind == Fault::RunawayLoop);
        CHECK(vm2.host.debug_calls == sr::vm::kMaxLoopIterations + 1);
        Vm vm3(opt);
        CHECK(vm3.run(vm3.b("JumpLoop", 0, 1)) == 0.0f);
        CHECK(vm3.fault.kind == Fault::RunawayLoop);
        Vm vm4(opt);
        CHECK(vm4.run(vm4.b("DoWhile", 0, 1)) == 0.0f);
        CHECK(vm4.fault.kind == Fault::RunawayLoop);
    }
}

TEST_CASE("get and set") {
    for (bool opt : kBoth) {
        CAPTURE(opt);
        Vm vm(opt);
        CHECK(vm.run(vm.b("Set", kEntityMemory, 3, 2.5f), CallbackKind::UpdateSequential, 1) == 2.5f);
        CHECK(vm.memory.entity_block(kEntityMemory, 1)[3] == 2.5f);
        CHECK(vm.memory.entity_block(kEntityMemory, 0)[3] == 0.0f);
        // Indices truncate toward zero (to_index); block ids must be exact integers (to_id).
        Vm vm2(opt);
        vm2.at(kLevelMemory, 3) = 8;
        CHECK(vm2.run(vm2.b("Get", 2000, 3.7f)) == 8.0f);
        CHECK(vm2.run(vm2.b("Get", vm2.b("Add", 2000, 0.9f), 3)) == 0.0f);
        // Shifted: x + y * s. Pointed: Get(Get(id, i), Get(id, i + 1) + offset).
        Vm vm3(opt);
        vm3.at(kLevelMemory, 7) = 5;
        CHECK(vm3.run(vm3.b("GetShifted", kLevelMemory, 1, 2, 3)) == 5.0f);
        Vm vm4(opt);
        vm4.at(kLevelMemory, 0) = float(kLevelMemory);
        vm4.at(kLevelMemory, 1) = 10;
        vm4.at(kLevelMemory, 12) = 6;
        CHECK(vm4.run(vm4.b("GetPointed", kLevelMemory, 0, 2)) == 6.0f);
        Vm vm5(opt);
        vm5.at(kLevelMemory, 0) = float(kLevelMemory);
        vm5.at(kLevelMemory, 1) = 10;
        CHECK(vm5.run(vm5.b("SetPointed", kLevelMemory, 0, 2, 4)) == 4.0f);
        CHECK(vm5.at(kLevelMemory, 12) == 4.0f);
        Vm vm6(opt);
        CHECK(vm6.run(vm6.b("SetShifted", kLevelMemory, 1, 2, 3, 9)) == 9.0f);
        CHECK(vm6.at(kLevelMemory, 7) == 9.0f);
    }
}

TEST_CASE("read modify write returns the stored value") {
    for (bool opt : kBoth) {
        CAPTURE(opt);
        Vm vm(opt);
        vm.at(kLevelMemory, 0) = 10;
        CHECK(vm.run(vm.b("Execute", vm.b("SetAdd", kLevelMemory, 0, 5), vm.b("SetMultiply", kLevelMemory, 0, 2),
                          vm.b("SetSubtract", kLevelMemory, 0, 1), vm.b("SetDivide", kLevelMemory, 0, 2),
                          vm.b("SetMod", kLevelMemory, 0, 4))) == 2.5f);
        CHECK(vm.at(kLevelMemory, 0) == 2.5f);
        Vm vm2(opt);
        vm2.at(kLevelMemory, 0) = -7;
        CHECK(vm2.run(vm2.b("SetRem", kLevelMemory, 0, 3)) == -1.0f);
        Vm vm3(opt);
        vm3.at(kLevelMemory, 0) = 3;
        CHECK(vm3.run(vm3.b("SetPower", kLevelMemory, 0, 2)) == 9.0f);
        Vm vm4(opt);
        vm4.at(kLevelMemory, 7) = 1;
        CHECK(vm4.run(vm4.b("SetAddShifted", kLevelMemory, 1, 2, 3, 4)) == 5.0f);
        CHECK(vm4.at(kLevelMemory, 7) == 5.0f);
        Vm vm5(opt);
        vm5.at(kLevelMemory, 0) = float(kLevelMemory);
        vm5.at(kLevelMemory, 1) = 10;
        vm5.at(kLevelMemory, 12) = 3;
        CHECK(vm5.run(vm5.b("SetSubtractPointed", kLevelMemory, 0, 2, 1)) == 2.0f);
        CHECK(vm5.at(kLevelMemory, 12) == 2.0f);
    }
}

TEST_CASE("increment and decrement") {
    for (bool opt : kBoth) {
        CAPTURE(opt);
        // Post returns the new value and Pre the old one.
        Vm vm(opt);
        vm.at(kLevelMemory, 0) = 5;
        CHECK(vm.run(vm.b("IncrementPost", kLevelMemory, 0)) == 6.0f);
        Vm vm2(opt);
        vm2.at(kLevelMemory, 0) = 5;
        CHECK(vm2.run(vm2.b("IncrementPre", kLevelMemory, 0)) == 5.0f);
        CHECK(vm2.at(kLevelMemory, 0) == 6.0f);
        Vm vm3(opt);
        vm3.at(kLevelMemory, 0) = 5;
        CHECK(vm3.run(vm3.b("DecrementPost", kLevelMemory, 0)) == 4.0f);
        Vm vm4(opt);
        vm4.at(kLevelMemory, 0) = 5;
        CHECK(vm4.run(vm4.b("DecrementPre", kLevelMemory, 0)) == 5.0f);
        CHECK(vm4.at(kLevelMemory, 0) == 4.0f);
        Vm vm5(opt);
        CHECK(vm5.run(vm5.b("IncrementPostShifted", kLevelMemory, 1, 2, 3)) == 1.0f);
        CHECK(vm5.at(kLevelMemory, 7) == 1.0f);
        Vm vm6(opt);
        vm6.at(kLevelMemory, 0) = float(kLevelMemory);
        vm6.at(kLevelMemory, 1) = 10;
        CHECK(vm6.run(vm6.b("DecrementPrePointed", kLevelMemory, 0, 2)) == 0.0f);
        CHECK(vm6.at(kLevelMemory, 12) == -1.0f);
    }
}

TEST_CASE("copy handles overlap") {
    for (bool opt : kBoth) {
        CAPTURE(opt);
        Vm vm(opt);
        for (int i = 0; i < 5; ++i) vm.at(kLevelMemory, i) = float(i + 1);
        CHECK(vm.run(vm.b("Copy", kLevelMemory, 0, kLevelMemory, 2, 5)) == 0.0f);
        const float expect[] = {1, 2, 1, 2, 3, 4, 5};
        for (int i = 0; i < 7; ++i) CHECK(vm.at(kLevelMemory, i) == expect[i]);
        // Downward overlap copies forward.
        Vm vm2(opt);
        for (int i = 0; i < 5; ++i) vm2.at(kLevelMemory, i + 2) = float(i + 1);
        vm2.run(vm2.b("Copy", kLevelMemory, 2, kLevelMemory, 0, 5));
        const float expect2[] = {1, 2, 3, 4, 5, 4, 5};
        for (int i = 0; i < 7; ++i) CHECK(vm2.at(kLevelMemory, i) == expect2[i]);
        // Entity Shared Memory (4002) of entity 1 is a view into Entity Shared Memory Array (4102).
        Vm vm3(opt);
        for (int i = 0; i < 4; ++i) vm3.at(4102, 32 + i) = float(i + 1);
        vm3.run(vm3.b("Copy", 4102, 32, 4002, 1, 4), CallbackKind::UpdateSequential, 1);
        const float expect3[] = {1, 1, 2, 3, 4};
        for (int i = 0; i < 5; ++i) CHECK(vm3.at(4102, 32 + i) == expect3[i]);
        CHECK(vm3.fault.kind == Fault::None);
    }
}

TEST_CASE("copy skips what it can't read or write") {
    for (bool opt : kBoth) {
        CAPTURE(opt);
        // Source past the end reads 0; destination past the end is skipped.
        Vm vm(opt);
        vm.at(kLevelMemory, 4094) = 7;
        vm.at(kLevelMemory, 4095) = 8;
        for (int i = 0; i < 4; ++i) vm.at(kLevelMemory, i) = 9;
        CHECK(vm.run(vm.b("Copy", kLevelMemory, 4094, kLevelMemory, 0, 4)) == 0.0f);
        CHECK(vm.fault.kind == Fault::None);
        const float expect[] = {7, 8, 0, 0};
        for (int i = 0; i < 4; ++i) CHECK(vm.at(kLevelMemory, i) == expect[i]);
        Vm vm2(opt);
        vm2.at(kLevelMemory, 0) = 3;
        vm2.at(kLevelMemory, 1) = 4;
        vm2.run(vm2.b("Copy", kLevelMemory, 0, kLevelMemory, 4095, 2));
        CHECK(vm2.at(kLevelMemory, 4095) == 3.0f);
        CHECK(vm2.fault.kind == Fault::None);
        // Negative destination start: only elements from index 0 on are written.
        Vm vm3(opt);
        vm3.at(kLevelMemory, 10) = 1;
        vm3.at(kLevelMemory, 11) = 2;
        vm3.run(vm3.b("Copy", kLevelMemory, 10, kLevelMemory, -1, 2));
        CHECK(vm3.at(kLevelMemory, 0) == 2.0f);
        // Unknown or unwritable destination, negative count: nothing.
        Vm vm4(opt);
        vm4.at(kLevelMemory, 0) = 5;
        vm4.run(vm4.b("Execute", vm4.b("Copy", kLevelMemory, 0, kLevelData, 0, 1),
                      vm4.b("Copy", kLevelMemory, 0, vm4.b("Add", 4998, 1), 0, 1),
                      vm4.b("Copy", kLevelMemory, 0, kLevelMemory, 1, -3)));
        CHECK(vm4.fault.kind == Fault::None);
        CHECK(vm4.at(kLevelData, 0) == 0.0f);
        CHECK(vm4.at(kLevelMemory, 1) == 0.0f);
        // Unknown source reads 0.
        Vm vm5(opt);
        vm5.at(kLevelMemory, 0) = 5;
        vm5.run(vm5.b("Copy", vm5.b("Add", 4998, 1), 0, kLevelMemory, 0, 1));
        CHECK(vm5.at(kLevelMemory, 0) == 0.0f);
    }
}

TEST_CASE("get outside a block returns 0") {
    for (bool opt : kBoth) {
        CAPTURE(opt);
        Vm vm(opt);
        CHECK(vm.run(vm.b("Add", 1, vm.b("Get", kEntityMemory, 64))) == 1.0f);
        CHECK(vm.fault.kind == Fault::None);
        Vm vm2(opt);
        CHECK(vm2.run(vm2.b("Get", kLevelMemory, -1)) == 0.0f);
        CHECK(vm2.fault.kind == Fault::None);
        // updateSpawn has no entity: per-entity blocks are absent.
        Vm vm3(opt);
        CHECK(vm3.run(vm3.b("Add", 2, vm3.b("Get", kEntityMemory, 0)), CallbackKind::UpdateSpawn, -1) == 2.0f);
        CHECK(vm3.fault.kind == Fault::None);
        // Shifted and pointed reads too; a pointer read from outside a block is 0.
        Vm vm4(opt);
        CHECK(vm4.run(vm4.b("GetShifted", kLevelMemory, 0, 4096, 1)) == 0.0f);
        Vm vm5(opt);
        vm5.at(kLevelMemory, 0) = float(kLevelMemory);
        vm5.at(kLevelMemory, 1) = 5000;
        CHECK(vm5.run(vm5.b("GetPointed", kLevelMemory, 0, 0)) == 0.0f);
        CHECK(vm5.fault.kind == Fault::None);
    }
}

TEST_CASE("faults name the archetype and callback") {
    for (bool opt : kBoth) {
        CAPTURE(opt);
        Vm vm(opt);
        CHECK(vm.run(vm.b("Add", 1, vm.b("While", 1, 0)), CallbackKind::UpdateSequential, 0, "NoteHead") == 0.0f);
        CHECK(vm.fault.kind == Fault::RunawayLoop);
        CHECK(vm.fault.detail.find("NoteHead") != std::string::npos);
        CHECK(vm.fault.detail.find("updateSequential") != std::string::npos);
    }
}

TEST_CASE("unreadable block reads 0") {
    for (bool opt : kBoth) {
        CAPTURE(opt);
        // Play blocks have no access in spawnTime (a watch-only callback).
        Vm vm(opt, Mode::Play);
        vm.at(kLevelMemory, 0) = 4;
        CHECK(vm.run(vm.b("Add", 1, vm.b("Get", kLevelMemory, 0)), CallbackKind::SpawnTime) == 1.0f);
        CHECK(vm.fault.kind == Fault::None);
    }
}

TEST_CASE("a write that can't store skips the store and returns what it would have") {
    for (bool opt : kBoth) {
        CAPTURE(opt);
        // Level Data is writable in preprocess only.
        Vm vm(opt);
        CHECK(vm.run(vm.b("Set", kLevelData, 0, 5)) == 5.0f);
        CHECK(vm.fault.kind == Fault::None);
        CHECK(vm.at(kLevelData, 0) == 0.0f);
        Vm vm2(opt);
        CHECK(vm2.run(vm2.b("Set", kLevelData, 0, 5), CallbackKind::Preprocess) == 5.0f);
        CHECK(vm2.at(kLevelData, 0) == 5.0f);
        // Read-modify-write and increments compute from the value read and return their usual result.
        Vm vm3(opt);
        vm3.at(kLevelData, 0) = 10;
        CHECK(vm3.run(vm3.b("SetAdd", kLevelData, 0, 5)) == 15.0f);
        CHECK(vm3.at(kLevelData, 0) == 10.0f);
        Vm vm4(opt);
        vm4.at(kLevelData, 3) = 10;
        CHECK(vm4.run(vm4.b("IncrementPost", kLevelData, 3)) == 11.0f);
        CHECK(vm4.at(kLevelData, 3) == 10.0f);
        Vm vm5(opt);
        vm5.at(kLevelData, 7) = 4;  // index 1 + 2 * 3
        CHECK(vm5.run(vm5.b("DecrementPreShifted", kLevelData, 1, 2, 3)) == 4.0f);
        CHECK(vm5.at(kLevelData, 7) == 4.0f);
        Vm vm6(opt);
        vm6.at(kLevelMemory, 0) = float(kLevelData);
        vm6.at(kLevelMemory, 1) = 2;
        vm6.at(kLevelData, 3) = 0.5f;
        CHECK(vm6.run(vm6.b("SetMultiplyPointed", kLevelMemory, 0, 1, 6)) == 3.0f);
        CHECK(vm6.at(kLevelData, 3) == 0.5f);
        // Out of range, and an unknown computed block: the read gives 0.
        Vm vm7(opt);
        CHECK(vm7.run(vm7.b("Set", kLevelMemory, 4096, 1)) == 1.0f);
        CHECK(vm7.fault.kind == Fault::None);
        Vm vm8(opt);
        CHECK(vm8.run(vm8.b("IncrementPre", kLevelMemory, -1)) == 0.0f);
        Vm vm9(opt);
        CHECK(vm9.run(vm9.b("SetSubtractShifted", vm9.b("Add", 4998, 1), 0, 1, 2, 8)) == -8.0f);
        CHECK(vm9.fault.kind == Fault::None);
    }
}

TEST_CASE("computed unknown block id reads 0") {
    for (bool opt : kBoth) {
        CAPTURE(opt);
        Vm vm(opt);
        CHECK(vm.run(vm.b("Add", 3, vm.b("Get", vm.b("Add", 4998, 1), 0))) == 3.0f);
        CHECK(vm.fault.kind == Fault::None);
    }
}

TEST_CASE("temporary memory starts zeroed") {
    for (bool opt : kBoth) {
        CAPTURE(opt);
        Vm vm(opt);
        auto write = vm.b("Execute", vm.b("Set", kTemporary, 7, 3), vm.b("Get", kTemporary, 7));
        CHECK(vm.run(write) == 3.0f);
        CHECK(vm.at(kTemporary, 7) == 0.0f);
        auto read = vm.b("Get", kTemporary, 7);
        CHECK(vm.run(read) == 0.0f);
        // Also after a fault part way through.
        auto faulting = vm.b("Execute", vm.b("Set", kTemporary, 9, 3), vm.b("While", 1, 0));
        vm.run(faulting);
        CHECK(vm.fault.kind == Fault::RunawayLoop);
        CHECK(vm.at(kTemporary, 9) == 0.0f);
        auto copied = vm.b("Execute", vm.b("Set", kLevelMemory, 0, 4), vm.b("Copy", kLevelMemory, 0, kTemporary, 100, 1));
        vm.run(copied);
        CHECK(vm.at(kTemporary, 100) == 0.0f);
        auto pushed = vm.b("Execute", vm.b("StackPush", 4), vm.b("StackGet", 0));
        CHECK(vm.run(pushed) == 4.0f);
        CHECK(vm.at(kTemporary, 4095) == 0.0f);
    }
}

TEST_CASE("stack functions live in temporary memory") {
    for (bool opt : kBoth) {
        CAPTURE(opt);
        Vm vm(opt);
        // init, push 5, push 6 -> pointer 4094; pop -> 6; get(0) -> 5
        auto prog = vm.b("Execute", vm.b("StackInit"), vm.b("StackPush", 5),
                         vm.b("Set", kLevelMemory, 0, vm.b("StackPush", 6)),
                         vm.b("Set", kLevelMemory, 1, vm.b("StackPop")),
                         vm.b("Set", kLevelMemory, 2, vm.b("StackGet", 0)),
                         vm.b("Set", kLevelMemory, 3, vm.b("Get", kTemporary, 4095)),
                         // frame: enter 2 locals, set local 0 via frame offset -1, read it back
                         vm.b("StackEnter", 2), vm.b("StackSetFrame", -1, 8),
                         vm.b("Set", kLevelMemory, 4, vm.b("StackGetFrame", -1)),
                         vm.b("Set", kLevelMemory, 5, vm.b("StackGetPointer")), vm.b("StackLeave"),
                         vm.b("StackGetPointer"));
        CHECK(vm.run(prog) == 4095.0f);
        CHECK(vm.fault.kind == Fault::None);
        CHECK(vm.at(kLevelMemory, 0) == 4094.0f);
        CHECK(vm.at(kLevelMemory, 1) == 6.0f);
        CHECK(vm.at(kLevelMemory, 2) == 5.0f);
        CHECK(vm.at(kLevelMemory, 3) == 5.0f);
        CHECK(vm.at(kLevelMemory, 4) == 8.0f);
        CHECK(vm.at(kLevelMemory, 5) == 4092.0f);
    }
}

TEST_CASE("stack functions fault outside temporary memory") {
    for (bool opt : kBoth) {
        CAPTURE(opt);
        auto faults = [&](auto build) {
            Vm vm(opt);
            CHECK(vm.run(build(vm.b)) == 0.0f);
            CHECK(vm.fault.kind == Fault::StackOverflow);
            return vm.fault.detail;
        };
        // Underflow: pop of an empty stack, leave without enter.
        CHECK(faults([](NodeBuilder& b) { return b("Execute", b("StackInit"), b("StackPop")); }).find("underflow") !=
              std::string::npos);
        faults([](NodeBuilder& b) { return b("StackLeave"); });
        // Overflow: push onto a full stack, grow or enter past the start.
        CHECK(faults([](NodeBuilder& b) { return b("Execute", b("StackGrow", 4096), b("StackPush", 1)); })
                  .find("overflow") != std::string::npos);
        faults([](NodeBuilder& b) { return b("StackGrow", 4097); });
        faults([](NodeBuilder& b) { return b("StackGrow", -1); });
        faults([](NodeBuilder& b) { return b("StackEnter", 4096); });
        // Out-of-range access, frame or not.
        faults([](NodeBuilder& b) { return b("StackGet", 0); });
        faults([](NodeBuilder& b) { return b("StackSet", -4097, 1); });
        faults([](NodeBuilder& b) { return b("StackGetFrame", 1); });
        faults([](NodeBuilder& b) { return b("Execute", b("StackSetPointer", -5), b("StackSet", 0, 1)); });
    }
}

TEST_CASE("host calls carry their arguments") {
    for (bool opt : kBoth) {
        CAPTURE(opt);
        Vm vm(opt);
        vm.host.result = 3;
        auto prog = vm.b("Execute", vm.b("Draw", 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11),
                         vm.b("Spawn", 4, vm.b("Add", 1, 1), 7),
                         vm.b("Set", kLevelMemory, 0, vm.b("PlayLoopedScheduled", 2, 1.5f)),
                         vm.b("Set", kLevelMemory, 1, vm.b("BeatToTime", 8)),
                         vm.b("Set", kLevelMemory, 2, vm.b("HasSkinSprite", 12)),
                         vm.b("Set", kLevelMemory, 3, vm.b("Random", 2, 6)),
                         vm.b("Set", kLevelMemory, 4, vm.b("RandomInteger", 2, 6)));
        CHECK(vm.run(prog) == 3.0f);
        REQUIRE(vm.host.calls.size() == 5);
        CHECK(vm.host.calls[0].family == "draw");
        CHECK(vm.host.calls[0].fn == Fn::Draw);
        CHECK(vm.host.calls[0].args == std::vector<float>{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11});
        CHECK(vm.host.calls[1].family == "spawn");
        CHECK(vm.host.calls[1].args == std::vector<float>{4, 2, 7});
        CHECK(vm.host.calls[2].family == "audio");
        CHECK(vm.host.calls[2].fn == Fn::PlayLoopedScheduled);
        CHECK(vm.host.calls[2].args == std::vector<float>{2, 1.5f});
        CHECK(vm.host.calls[3].family == "time");
        CHECK(vm.host.calls[3].fn == Fn::BeatToTime);
        CHECK(vm.host.calls[4].family == "skin");
        CHECK(vm.host.calls[4].args == std::vector<float>{12});
        CHECK(vm.at(kLevelMemory, 0) == 3.0f);
        CHECK(vm.at(kLevelMemory, 1) == 3.0f);
        CHECK(vm.at(kLevelMemory, 3) == 3.0f);  // 2 + 4 * 0.25
        CHECK(vm.at(kLevelMemory, 4) == 3.0f);  // 2 + floor(4 * 0.25)
    }
}

TEST_CASE("play only functions run in play") {
    for (bool opt : kBoth) {
        CAPTURE(opt);
        Vm vm(opt, Mode::Play);
        auto prog = vm.b("Execute", vm.b("Play", 1, 0.02f), vm.b("ExportValue", 3, 0.5f),
                         vm.b("StreamSet", 1, 2, 3));
        CHECK(vm.run(prog) == 0.0f);
        REQUIRE(vm.host.calls.size() == 3);
        CHECK(vm.host.calls[0].fn == Fn::Play);
        CHECK(vm.host.calls[1].args == std::vector<float>{3, 0.5f});
        CHECK(vm.host.calls[2].fn == Fn::StreamSet);
    }
}

TEST_CASE("math functions use sr::fm and easing") {
    for (bool opt : kBoth) {
        CAPTURE(opt);
        Vm vm(opt);
        vm.at(kLevelMemory, 0) = 0.3f;
        auto x = [&] { return vm.b("Get", kLevelMemory, 0); };
        auto prog = vm.b("Execute", vm.b("Set", kLevelMemory, 1, vm.b("EaseInOutQuad", x())),
                         vm.b("Set", kLevelMemory, 2, vm.b("EaseOutInElastic", x())),
                         vm.b("Set", kLevelMemory, 3, vm.b("Sin", x())),
                         vm.b("Set", kLevelMemory, 4, vm.b("Round", 2.5f)),
                         vm.b("Set", kLevelMemory, 5, vm.b("Clamp", 5, 0, 2)),
                         vm.b("Set", kLevelMemory, 6, vm.b("JudgeSimple", 1.05f, 1, 0.02f, 0.06f, 0.1f)),
                         vm.b("Set", kLevelMemory, 7, vm.b("Remap", 0, 10, 100, 200, 2.5f)));
        vm.run(prog);
        CHECK(vm.fault.kind == Fault::None);
        CHECK(bits(vm.at(kLevelMemory, 1)) == bits(sr::vm::ease(sr::vm::EaseKind::InOutQuad, 0.3f)));
        CHECK(bits(vm.at(kLevelMemory, 2)) == bits(sr::vm::ease(sr::vm::EaseKind::OutInElastic, 0.3f)));
        CHECK(bits(vm.at(kLevelMemory, 3)) == bits(sr::fm::sin(0.3f)));
        CHECK(vm.at(kLevelMemory, 4) == 2.0f);
        CHECK(vm.at(kLevelMemory, 5) == 2.0f);
        CHECK(vm.at(kLevelMemory, 6) == 2.0f);
        CHECK(vm.at(kLevelMemory, 7) == 125.0f);
    }
}

TEST_CASE("optimizer is result-identical") {
    // Random pure expression trees over float32-exact and inexact constants, with some leaves read from
    // memory so that folding is partial. Same bits with and without the optimizer.
    std::mt19937 rng(20261017);
    const float constants[] = {0.0f, -0.0f, 1.0f, -1.0f, 2.0f, 0.5f, 3.0f, 16777216.0f, 0.1f, 1.0f / 3.0f,
                               3.14159265f, -2.7182818f, 1e-7f, 1e30f, 0.3f, 7.25f};
    struct F {
        const char* name;
        int args;  // -1: variadic 1..4
    };
    const F funcs[] = {
        {"Add", -1}, {"Subtract", -1}, {"Multiply", -1}, {"Divide", -1}, {"Mod", -1}, {"Rem", -1}, {"Power", 2},
        {"Abs", 1}, {"Negate", 1}, {"Floor", 1}, {"Ceil", 1}, {"Round", 1}, {"Trunc", 1}, {"Frac", 1},
        {"Sign", 1}, {"Sin", 1}, {"Cos", 1}, {"Tan", 1}, {"Arctan", 1}, {"Arctan2", 2}, {"Log", 1},
        {"Sinh", 1}, {"Tanh", 1}, {"Degree", 1}, {"Radian", 1}, {"Min", 2}, {"Max", 2}, {"Clamp", 3},
        {"Lerp", 3}, {"LerpClamped", 3}, {"Unlerp", 3}, {"UnlerpClamped", 3}, {"Remap", 5}, {"RemapClamped", 5},
        {"Equal", 2}, {"NotEqual", 2}, {"Less", 2}, {"LessOr", 2}, {"Greater", 2}, {"GreaterOr", 2},
        {"Not", 1}, {"EaseInOutCubic", 1}, {"EaseOutBack", 1}, {"Judge", 8}, {"JudgeSimple", 5},
        {"If", 3}, {"And", -1}, {"Or", -1}, {"Execute", -1}, {"Block", 1},
    };
    for (int t = 0; t < 200; ++t) {
        CAPTURE(t);
        const uint32_t seed = rng();
        uint32_t results[2] = {};
        for (int pass = 0; pass < 2; ++pass) {
            Vm vm(pass == 1);
            for (int i = 0; i < 8; ++i) vm.at(kLevelMemory, i) = constants[(i * 5 + 3) % std::size(constants)];
            std::mt19937 tree_rng(seed);
            auto tpick = [&](size_t n) { return size_t(tree_rng() % n); };
            std::function<Ref(int)> gen = [&](int depth) -> Ref {
                const size_t leaf = tpick(10);
                if (depth >= 5 || leaf < 3) {
                    if (tpick(4) == 0) return vm.b("Get", kLevelMemory, float(tpick(8)));
                    return vm.b.value(constants[tpick(std::size(constants))]);
                }
                const size_t which = tpick(std::size(funcs) + 2);
                if (which == std::size(funcs)) {  // SwitchWithDefault(test, c0, b0, c1, b1, default)
                    std::vector<Ref> args{gen(depth + 1)};
                    for (int k = 0; k < 2; ++k) {
                        args.push_back(vm.b.value(constants[tpick(4)]));
                        args.push_back(gen(depth + 1));
                    }
                    args.push_back(gen(depth + 1));
                    return vm.b.call("SwitchWithDefault", args);
                }
                if (which == std::size(funcs) + 1) {  // SwitchIntegerWithDefault(test, b0, b1, default)
                    std::vector<Ref> args{gen(depth + 1), gen(depth + 1), gen(depth + 1), gen(depth + 1)};
                    return vm.b.call("SwitchIntegerWithDefault", args);
                }
                const F& f = funcs[which];
                const int n = f.args < 0 ? int(1 + tpick(4)) : f.args;
                std::vector<Ref> args;
                for (int k = 0; k < n; ++k) args.push_back(gen(depth + 1));
                return vm.b.call(f.name, args);
            };
            const Ref root = gen(0);
            const float r = vm.run(root);
            CHECK(vm.fault.kind == Fault::None);
            results[pass] = bits(r);
        }
        CHECK(results[0] == results[1]);
    }
}

}  // TEST_SUITE
