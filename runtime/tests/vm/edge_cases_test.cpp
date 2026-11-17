// Edge cases of the VM's number semantics, pinned as unit tests: float to int conversion and ids, writes that
// can't store, rounding, signs of zero, NaN operands, Rem/Mod and Remap, and the transcendental and easing bits.
#include <bit>
#include <cmath>
#include <cstdint>
#include <limits>
#include <string>

#include "doctest.h"
#include "support/node_builder.h"
#include "support/recording_host.h"
#include "vm/convert.h"
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

constexpr int32_t kLevelMemory = 2000;
constexpr int32_t kLevelData = 2001;
constexpr int32_t kLevelOption = 2002;
constexpr int32_t kEntityData = 4001;
constexpr float kNaN = std::numeric_limits<float>::quiet_NaN();
constexpr float kInf = std::numeric_limits<float>::infinity();

uint32_t bits(float x) { return std::bit_cast<uint32_t>(x); }

// Same bits, any NaN equal to any NaN.
bool same(float a, float b) { return (std::isnan(a) && std::isnan(b)) || bits(a) == bits(b); }

struct Vm {
    explicit Vm(bool optimize) : optimize(optimize), memory(Mode::Watch, config()) {}

    static MemoryConfig config() {
        MemoryConfig c;
        c.entity_count = 2;
        c.option_count = 1;
        c.archetype_count = 1;
        return c;
    }

    float run(Ref root, CallbackKind kind = CallbackKind::UpdateSequential) {
        const int32_t entry = b.entry(root, kind, "Probe");
        const sr::vm::Program program = sr::vm::compile(b.data(), {Mode::Watch, optimize});
        ExecContext ctx;
        ctx.memory = &memory;
        ctx.host = &host;
        ctx.callback = kind;
        ctx.entity = 0;
        ctx.archetype = "Probe";
        const float r = sr::vm::run(program, entry, ctx);
        fault = ctx.fault;
        return r;
    }

    float& at(int32_t block, int32_t index) { return memory.raw(block)[size_t(index)]; }

    bool optimize;
    NodeBuilder b;
    Memory memory;
    RecordingHost host;
    Fault fault;
};

const bool kBoth[] = {false, true};

float pure(Fn fn, std::initializer_list<float> args) {
    return sr::vm::eval_pure(fn, args.begin(), int(args.size()));
}

}  // namespace

TEST_SUITE("vm") {

// ---- conversion ----------------------------------------------------------------------------------------------

TEST_CASE("Get and Set indices truncate toward zero, NaN is index 0") {
    struct Case {
        float x;
        int32_t index;
    };
    const Case cases[] = {{-1.5f, -1},       {-0.5f, 0},         {-0.0f, 0},          {0.49999997f, 0},
                          {0.5f, 0},         {0.99999994f, 0},   {1.5f, 1},           {2.5f, 2},
                          {3.5f, 3},         {kNaN, 0},          {4.5f, 4},           {4.9999995f, 4},
                          {5.5f, 5},         {4095.5f, 4095},    {4095.99976f, 4095}, {-0.99999994f, 0},
                          {8.5f, 8},         {8.999999f, 8},     {31.5f, 31},         {30.999998f, 30}};
    for (const Case& c : cases) {
        CAPTURE(c.x);
        CHECK(sr::vm::to_index(c.x) == c.index);
    }
    // Far out of range reads 0 and writes nowhere; the saturated value is out of every block.
    CHECK(sr::vm::to_index(1e10f) == INT32_MAX);
    CHECK(sr::vm::to_index(-kInf) == INT32_MIN);
}

TEST_CASE("a switch value selects a branch only when it is an exact integer") {
    // -0 selects branch 0; -0.5, 0.5, 1.5, NaN, 4.5 ... take the default.
    CHECK(sr::vm::branch_index(-0.0f, 5) == 0);
    for (float x : {-1.5f, -0.5f, 0.49999997f, 0.5f, 0.99999994f, 1.5f, 2.5f, 3.5f, 4.5f, kNaN, kInf, 1e10f})
        CHECK(sr::vm::branch_index(x, 5) == -1);
}

TEST_CASE("block and sprite ids must be exact integers") {
    // Draw and HasSkinSprite: only -0 names sprite 0; block ids (2000.5, 2002.5, 2000.25 and
    // 1999.5 read 0).
    CHECK(sr::vm::to_id(-0.0f) == 0);
    CHECK(sr::vm::to_id(2000.0f) == 2000);
    for (float x : {0.5f, 1.5f, 4.5f, 2000.5f, 2000.25f, 1999.5f, kNaN, kInf, -kInf, 1e10f, -1e10f})
        CHECK(sr::vm::to_id(x) == sr::vm::kNoId);
    for (bool opt : kBoth) {
        CAPTURE(opt);
        Vm vm(opt);
        vm.at(kLevelMemory, 0) = 1;
        vm.at(kLevelOption, 0) = 0.75f;
        CHECK(vm.run(vm.b("Get", vm.b("Add", 2000, 0.5f), 0)) == 0.0f);
        CHECK(vm.run(vm.b("Get", vm.b("Add", 2002, 0.5f), 0)) == 0.0f);
        CHECK(vm.run(vm.b("Get", vm.b("Add", 2000, -0.0f), 0)) == 1.0f);
        CHECK(vm.fault.kind == Fault::None);
        // A non-integer sprite id names no sprite: the host isn't asked.
        vm.host.result = 1;
        CHECK(vm.run(vm.b("HasSkinSprite", vm.b("Add", 0, 0.5f))) == 0.0f);
        CHECK(vm.host.calls.empty());
        CHECK(vm.run(vm.b("HasSkinSprite", vm.b("Add", 0, -0.0f))) == 1.0f);
        REQUIRE(vm.host.calls.size() == 1);
        CHECK(vm.host.calls[0].args[0] == 0.0f);
    }
}

// ---- writes --------------------------------------------------------------------------------------------------

TEST_CASE("a write that can't store computes as usual and returns what it would have") {
    for (bool opt : kBoth) {
        CAPTURE(opt);
        // Set on an unwritable block or out of range is ignored and returns its value.
        Vm vm(opt);
        vm.at(kLevelOption, 0) = 0.75f;
        CHECK(vm.run(vm.b("Set", kLevelOption, 0, 7)) == 7.0f);
        CHECK(vm.at(kLevelOption, 0) == 0.75f);
        CHECK(vm.run(vm.b("Set", kLevelMemory, 4096, 7)) == 7.0f);
        // Row 4: SetAdd on own Entity Data in updateSequential returns old + value (0.5 + 2) and stores nothing.
        vm.at(kEntityData, 6) = 0.5f;
        CHECK(vm.run(vm.b("SetAdd", kEntityData, 6, 2)) == 2.5f);
        CHECK(vm.at(kEntityData, 6) == 0.5f);
        // IncrementPost returns the new value (as on a writable block); IncrementPre the old.
        vm.at(kEntityData, 7) = 0.125f;
        CHECK(vm.run(vm.b("IncrementPost", kEntityData, 7)) == 1.125f);
        vm.at(kEntityData, 4) = 0.375f;
        CHECK(vm.run(vm.b("IncrementPre", kEntityData, 4)) == 0.375f);
        CHECK(vm.at(kEntityData, 7) == 0.125f);
        CHECK(vm.at(kEntityData, 4) == 0.375f);
        // Row 9: SetAdd on Level Memory from updateParallel reads the real value (0.5) and returns 2.5.
        vm.at(kLevelMemory, 101) = 0.5f;
        CHECK(vm.run(vm.b("SetAdd", kLevelMemory, 101, 2), CallbackKind::UpdateParallel) == 2.5f);
        CHECK(vm.at(kLevelMemory, 101) == 0.5f);
        // Out of range / unknown block reads 0, so IncrementPost returns 1.
        CHECK(vm.run(vm.b("IncrementPost", kLevelMemory, -1)) == 1.0f);
        CHECK(vm.run(vm.b("IncrementPost", vm.b("Add", 9998, 1), 0)) == 1.0f);
        CHECK(vm.fault.kind == Fault::None);
        // Set on an unknown block returns its value.
        CHECK(vm.run(vm.b("Set", vm.b("Add", 9998, 1), 0, 7)) == 7.0f);
        // Level Data in updateSequential.
        CHECK(vm.run(vm.b("Set", kLevelData, 0, 7)) == 7.0f);
        CHECK(vm.at(kLevelData, 0) == 0.0f);
    }
}

TEST_CASE("writable controls store and return as before") {
    for (bool opt : kBoth) {
        CAPTURE(opt);
        Vm vm(opt);
        CHECK(vm.run(vm.b("Set", kLevelMemory, 102, 7)) == 7.0f);
        CHECK(vm.at(kLevelMemory, 102) == 7.0f);
        vm.at(kLevelMemory, 103) = 0.5f;
        CHECK(vm.run(vm.b("SetAdd", kLevelMemory, 103, 2)) == 2.5f);
        CHECK(vm.at(kLevelMemory, 103) == 2.5f);
        vm.at(kLevelMemory, 104) = 0.125f;
        CHECK(vm.run(vm.b("IncrementPost", kLevelMemory, 104)) == 1.125f);
        CHECK(vm.at(kLevelMemory, 104) == 1.125f);
        vm.at(kLevelMemory, 105) = 0.375f;
        CHECK(vm.run(vm.b("IncrementPre", kLevelMemory, 105)) == 0.375f);
        CHECK(vm.at(kLevelMemory, 105) == 1.375f);
    }
}

// ---- math ----------------------------------------------------------------------------------------------------

TEST_CASE("Round is half to even and keeps the sign of a zero result") {
    namespace fm = sr::fm;
    CHECK(bits(fm::round(-0.0f)) == bits(-0.0f));
    CHECK(bits(fm::round(-0.5f)) == bits(-0.0f));
    CHECK(bits(fm::round(-0.49999997f)) == bits(-0.0f));
    CHECK(bits(fm::round(-1e-45f)) == bits(-0.0f));
    CHECK(bits(fm::round(0.0f)) == bits(0.0f));
    CHECK(bits(fm::round(0.5f)) == bits(0.0f));
    CHECK(fm::round(1.5f) == 2.0f);
    CHECK(fm::round(2.5f) == 2.0f);
    CHECK(fm::round(-2.5f) == -2.0f);
    CHECK(std::isnan(fm::round(kNaN)));
}

TEST_CASE("Sign of a zero or NaN is +0") {
    // (x > 0) - (x < 0).
    namespace fm = sr::fm;
    CHECK(bits(fm::sign(-0.0f)) == bits(0.0f));
    CHECK(bits(fm::sign(0.0f)) == bits(0.0f));
    CHECK(bits(fm::sign(kNaN)) == bits(0.0f));
    CHECK(fm::sign(-1e-45f) == -1.0f);
    CHECK(fm::sign(kInf) == 1.0f);
}

TEST_CASE("Max, Min and Clamp ignore a NaN operand and order -0 below +0") {
    // IEEE maxNum/minNum (arm64 fmaxnm/fminnm).
    CHECK(bits(pure(Fn::Max, {0.0f, -0.0f})) == bits(0.0f));
    CHECK(bits(pure(Fn::Max, {-0.0f, 0.0f})) == bits(0.0f));
    CHECK(pure(Fn::Max, {kNaN, 1}) == 1.0f);
    CHECK(pure(Fn::Max, {1, kNaN}) == 1.0f);
    CHECK(pure(Fn::Max, {kNaN, -1}) == -1.0f);
    CHECK(std::isnan(pure(Fn::Max, {kNaN, kNaN})));
    CHECK(bits(pure(Fn::Min, {0.0f, -0.0f})) == bits(-0.0f));
    CHECK(bits(pure(Fn::Min, {-0.0f, 0.0f})) == bits(-0.0f));
    CHECK(pure(Fn::Min, {kNaN, 1}) == 1.0f);
    CHECK(pure(Fn::Min, {-1, kNaN}) == -1.0f);
    // Clamp(x, a, b) = max(a, min(b, x)).
    CHECK(pure(Fn::Clamp, {kNaN, 0, 1}) == 1.0f);
    CHECK(pure(Fn::Clamp, {0.5f, kNaN, 1}) == 0.5f);
    CHECK(pure(Fn::Clamp, {0.5f, 0, kNaN}) == 0.5f);
    CHECK(bits(pure(Fn::Clamp, {0.0f, -0.0f, 1})) == bits(0.0f));
    CHECK(bits(pure(Fn::Clamp, {-0.0f, 0.0f, 1})) == bits(0.0f));
    CHECK(bits(pure(Fn::Clamp, {-0.0f, -0.0f, 0.0f})) == bits(-0.0f));
    CHECK(pure(Fn::Clamp, {2, 1, 0}) == 1.0f);
    CHECK(pure(Fn::Clamp, {-1, 1, 0}) == 1.0f);
}

TEST_CASE("Rem is x - y * (int64)(x / y) in double; Mod is Rem(Rem(x, y) + y, y)") {
    // C fmod and
    // a float32 or fused x - y * trunc(x / y) each disagree somewhere.
    namespace fm = sr::fm;
    struct Case {
        float x, y, rem, mod;
    };
    const Case cases[] = {
        {-0.0f, 1, -0.0f, 0.0f}, {0.0f, -1, 0.0f, 0.0f}, {-4, 2, 0.0f, 0.0f},       {4, -2, 0.0f, 0.0f},
        {-1e-45f, 1, -1e-45f, 0.0f}, {1e-45f, -1, 1e-45f, 0.0f}, {-5, 3, -2, 1}, {5, -3, 2, -1},
        {-6, 3, 0.0f, 0.0f},    {6, -3, 0.0f, 0.0f},  {0.0f, -3, 0.0f, 0.0f},
        {1, 0.0f, kNaN, kNaN},  {kInf, 2, kNaN, kNaN},
        // Rem by an infinity is x; Mod by an infinity is NaN.
        {5, kInf, 5, kNaN},     {-5, kInf, -5, kNaN}, {5, -kInf, 5, kNaN},
        // 1e30 / 7 is past int64, so q is used as it is: x - y * q is 0 where fmod gives 1 (a
        // saturating int64 cast would give 1e30).
        {std::bit_cast<float>(0x7149f2cau), 7, 0.0f, 0.0f},
        // 7 / 0.1f is just under 70 in double: the remainder is 0.0999999 (3dccccbf). A zero x is returned as is.
        {7, 0.1f, std::bit_cast<float>(0x3dccccbfu), std::bit_cast<float>(0x3dccccbfu)},
        {-0.0f, -3, -0.0f, 0.0f},
        {0.0f, 0.0f, kNaN, kNaN},  // both NaN
    };
    for (const Case& c : cases) {
        CAPTURE(c.x);
        CAPTURE(c.y);
        CHECK(same(fm::rem(c.x, c.y), c.rem));
        CHECK(same(fm::mod(c.x, c.y), c.mod));
    }
}

TEST_CASE("Remap is c + ((v - a) / (b - a)) * (d - c); RemapClamped clamps that to [c, d]") {
    auto f = [](uint32_t b) { return std::bit_cast<float>(b); };
    CHECK(bits(pure(Fn::Remap, {f(0xc2b57e04), f(0xc2a75302), f(0xc0bbd2ab), f(0xc2746d0e), f(0x41b3dea6)})) ==
          0xc45e3167u);
    CHECK(bits(pure(Fn::Remap, {f(0xc21ef0b7), f(0xc2862104), f(0xc21b5855), f(0x42aa91a6), f(0x42762a98)})) ==
          0xc3f96663u);
    CHECK(bits(pure(Fn::RemapClamped,
                    {f(0x42c1ad88), f(0x4274edb1), f(0xc2bf36d4), f(0xc1c20e15), f(0xc2a165b7)})) == 0xc1c20e15u);
    CHECK(bits(pure(Fn::RemapClamped,
                    {f(0xc12387e0), f(0x4204dd13), f(0xc2a11e4c), f(0xbfdd929b), f(0x424db47a)})) == 0xbfdd929bu);
    CHECK(pure(Fn::RemapClamped, {0, 10, 100, 200, 2.5f}) == 125.0f);
    CHECK(pure(Fn::RemapClamped, {0, 10, 200, 100, 20}) == 100.0f);
}

// ---- bits ----------------------------------------------------------------------------------------------------

namespace {

struct Expected {
    const char* fn;
    std::initializer_list<uint32_t> args;
    uint32_t result;
};

const Expected kExpected[] = {
#include "q2b_expected_values.inc"
};

}  // namespace

TEST_CASE("transcendentals and easings give the expected bits") {
    // Expected values: q2b_expected_values.inc.
    int checked = 0;
    for (const Expected& c : kExpected) {
        const auto fn = sr::vm::fn_from_name(c.fn);
        REQUIRE(fn);
        float args[3] = {};
        int n = 0;
        for (uint32_t a : c.args) args[n++] = std::bit_cast<float>(a);
        const float got = sr::vm::eval_pure(*fn, args, n);
        const std::string name = c.fn;
        const uint32_t first_arg = *c.args.begin();
        CAPTURE(name);
        CAPTURE(first_arg);
        CHECK(same(got, std::bit_cast<float>(c.result)));
        ++checked;
    }
    CHECK(checked == 227);
}

}  // TEST_SUITE
