#include <cstdint>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <optional>
#include <set>
#include <string>
#include <vector>

#include "data/engine_data.h"
#include "data/gzip.h"
#include "data/load_error.h"
#include "doctest.h"
#include "support/node_builder.h"
#include "vm/functions.h"
#include "vm/program.h"

using sr::data::CallbackKind;
using sr::data::Mode;
using sr::test::NodeBuilder;
using sr::vm::CompileError;
using sr::vm::Fn;
using sr::vm::fn_from_name;
using sr::vm::fn_info;

namespace fs = std::filesystem;

namespace {

const bool kBoth[] = {false, true};

// The CompileError compile() throws for b's graph, or nullopt if it compiles.
std::optional<CompileError> compile_error(NodeBuilder& b, Mode mode, bool optimize) {
    try {
        sr::vm::compile(b.data(), {mode, optimize});
    } catch (const CompileError& e) {
        return e;
    }
    return std::nullopt;
}

std::string read_gzip(const fs::path& path) {
    std::ifstream in(path, std::ios::binary);
    REQUIRE_MESSAGE(bool(in), "cannot open ", path.string());
    const std::vector<uint8_t> bytes((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    const std::vector<uint8_t> raw = sr::data::gunzip(bytes);
    return std::string(raw.begin(), raw.end());
}

}  // namespace

TEST_SUITE("vm") {

TEST_CASE("function table") {
    CHECK(sr::vm::kFnCount == 191);
    std::set<std::string> names;
    for (int i = 0; i < sr::vm::kFnCount; ++i) {
        const auto& info = fn_info(Fn(i));
        names.insert(std::string(info.name));
        REQUIRE(fn_from_name(info.name).has_value());
        CHECK(*fn_from_name(info.name) == Fn(i));
        CHECK(info.min_args >= 0);
        CHECK((info.max_args == -1 || info.max_args >= info.min_args));
    }
    CHECK(names.size() == 191);
    CHECK_FALSE(fn_from_name("FooBar").has_value());
    CHECK_FALSE(fn_from_name("add").has_value());
    CHECK(fn_info(Fn::Draw).min_args == 11);
    CHECK(fn_info(Fn::Draw).max_args == 14);
    CHECK(fn_info(Fn::Add).max_args == -1);
    CHECK(sr::vm::fn_allowed(Fn::Play, Mode::Play));
    CHECK_FALSE(sr::vm::fn_allowed(Fn::Play, Mode::Watch));
    CHECK_FALSE(sr::vm::fn_allowed(Fn::StreamSet, Mode::Watch));
    CHECK_FALSE(sr::vm::fn_allowed(Fn::ExportValue, Mode::Watch));
    CHECK_FALSE(sr::vm::fn_allowed(Fn::Paint, Mode::Play));
    CHECK_FALSE(sr::vm::fn_allowed(Fn::Print, Mode::Watch));
    CHECK(sr::vm::fn_allowed(Fn::PlayScheduled, Mode::Watch));
    CHECK(sr::vm::fn_foldable(Fn::Add));
    CHECK_FALSE(sr::vm::fn_foldable(Fn::Get));
    CHECK_FALSE(sr::vm::fn_foldable(Fn::BeatToTime));
    CHECK_FALSE(sr::vm::fn_foldable(Fn::Random));
    CHECK_FALSE(sr::vm::fn_foldable(Fn::If));
}

TEST_CASE("unknown function is a compile error naming it") {
    for (bool opt : kBoth) {
        CAPTURE(opt);
        NodeBuilder b;
        b.entry(b("Add", 1, b("FooBar", 2)));
        auto e = compile_error(b, Mode::Watch, opt);
        REQUIRE(e.has_value());
        CHECK(e->function == "FooBar");
        CHECK(std::string(e->what()).find("FooBar") != std::string::npos);
        // Even in a branch the optimizer would drop.
        NodeBuilder b2;
        b2.entry(b2("If", 0, b2("FooBar"), 1));
        auto e2 = compile_error(b2, Mode::Watch, opt);
        REQUIRE(e2.has_value());
        CHECK(e2->function == "FooBar");
    }
}

TEST_CASE("watch rejects Play") {
    for (bool opt : kBoth) {
        CAPTURE(opt);
        NodeBuilder b;
        b.entry(b("Play", 1, 0));
        auto e = compile_error(b, Mode::Watch, opt);
        REQUIRE(e.has_value());
        CHECK(e->function == "Play");
        CHECK_FALSE(compile_error(b, Mode::Play, opt).has_value());
        for (const char* name : {"PlayLooped", "StopLooped"}) {
            NodeBuilder b2;
            b2.entry(b2(name, 1));
            auto e2 = compile_error(b2, Mode::Watch, opt);
            REQUIRE(e2.has_value());
            CHECK(e2->function == name);
        }
        NodeBuilder b3;
        b3.update_spawn(b3("StreamSet", 1, 2, 3));
        auto e3 = compile_error(b3, Mode::Watch, opt);
        REQUIRE(e3.has_value());
        CHECK(e3->function == "StreamSet");
        NodeBuilder b4;
        b4.entry(b4("Paint", 1, 2, 3, 4, 5, 6, 7));
        CHECK(compile_error(b4, Mode::Play, opt).has_value());
    }
}

TEST_CASE("arg count checked at compile") {
    for (bool opt : kBoth) {
        CAPTURE(opt);
        NodeBuilder b;
        b.entry(b("Get", 2000));
        auto e = compile_error(b, Mode::Watch, opt);
        REQUIRE(e.has_value());
        CHECK(e->function == "Get");
        NodeBuilder b2;
        b2.entry(b2("Draw", 1, 2, 3, 4, 5, 6, 7, 8, 9, 10));
        CHECK(compile_error(b2, Mode::Watch, opt).has_value());
        NodeBuilder b3;
        b3.entry(b3("Draw", 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14));
        CHECK_FALSE(compile_error(b3, Mode::Watch, opt).has_value());
        NodeBuilder b4;
        b4.entry(b4("Draw", 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15));
        CHECK(compile_error(b4, Mode::Watch, opt).has_value());
        NodeBuilder b5;
        b5.entry(b5("Add", 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20));
        CHECK_FALSE(compile_error(b5, Mode::Watch, opt).has_value());
        // Switch takes the test and (case, branch) pairs; SwitchWithDefault adds the default.
        NodeBuilder b6;
        b6.entry(b6("Switch", 1, 2));
        auto e6 = compile_error(b6, Mode::Watch, opt);
        REQUIRE(e6.has_value());
        CHECK(e6->function == "Switch");
        NodeBuilder b7;
        b7.entry(b7("SwitchWithDefault", 1, 2, 3));
        CHECK(compile_error(b7, Mode::Watch, opt).has_value());
        NodeBuilder b8;
        b8.entry(b8("SwitchWithDefault", 1, 2, 3, 4));
        CHECK_FALSE(compile_error(b8, Mode::Watch, opt).has_value());
    }
}

TEST_CASE("unknown block id is a compile error") {
    for (bool opt : kBoth) {
        CAPTURE(opt);
        NodeBuilder b;
        b.entry(b("Get", 4999, 0));
        auto e = compile_error(b, Mode::Watch, opt);
        REQUIRE(e.has_value());
        CHECK(e->function == "block 4999");
        // Block ids differ by mode: 4007 is Entity Life in play only.
        NodeBuilder b2;
        b2.entry(b2("Set", 4007, 0, 1));
        auto e2 = compile_error(b2, Mode::Watch, opt);
        REQUIRE(e2.has_value());
        CHECK(e2->function == "block 4007");
        CHECK_FALSE(compile_error(b2, Mode::Play, opt).has_value());
        NodeBuilder b3;
        b3.entry(b3("Copy", 2000, 0, 77, 0, 1));
        auto e3 = compile_error(b3, Mode::Watch, opt);
        REQUIRE(e3.has_value());
        CHECK(e3->function == "block 77");
    }
}

TEST_CASE("optimizer folds constants and drops constant branches") {
    auto code_size = [](NodeBuilder& b, bool opt) {
        return sr::vm::compile(b.data(), {Mode::Watch, opt}).code().size();
    };
    NodeBuilder b;
    b.entry(b("Add", 1, b("Multiply", 2, 3)));
    CHECK(code_size(b, false) == 6);  // 4 pushes, 2 calls
    CHECK(code_size(b, true) == 2);   // push 7, return
    NodeBuilder b2;
    b2.entry(b2("If", 0, b2("Set", 2000, 0, 1), b2("Get", 2000, 1)));
    CHECK(code_size(b2, true) == 4);  // Get(2000, 1) only
    NodeBuilder b3;
    b3.entry(b3("Get", 2000, 0));
    CHECK(code_size(b3, true) == 4);  // not foldable
    NodeBuilder b4;  // block reads are never folded (the wiki's "immutable read" optimization isn't done)
    b4.entry(b4("Add", b4("Get", 3000, 0), b4("Add", 1, 1)));
    CHECK(code_size(b4, true) == 6);
}

TEST_CASE("constant break beyond the enclosing blocks is a compile error") {
    for (bool opt : kBoth) {
        CAPTURE(opt);
        NodeBuilder b;
        b.entry(b("Block", b("Break", 2, 7)));
        auto e = compile_error(b, Mode::Watch, opt);
        REQUIRE(e.has_value());
        CHECK(e->function == "Break");
        NodeBuilder b2;
        b2.entry(b2("Add", 1, b2("Break", 0, 7)));  // no Block at all
        CHECK(compile_error(b2, Mode::Watch, opt).has_value());
        NodeBuilder b3;  // a folded constant count counts as constant
        b3.entry(b3("Block", b3("Break", b3("Add", 1, 1), 7)));
        CHECK(compile_error(b3, Mode::Watch, opt).has_value());
        NodeBuilder b4;
        b4.entry(b4("Block", b4("Block", b4("Break", 2, 7))));
        CHECK_FALSE(compile_error(b4, Mode::Watch, opt).has_value());
        NodeBuilder b5;  // a run-time count compiles (and is checked when it runs)
        b5.entry(b5("Block", b5("Break", b5("Get", 2000, 0), 7)));
        CHECK_FALSE(compile_error(b5, Mode::Watch, opt).has_value());
    }
}

TEST_CASE("node cycles are load errors") {
    NodeBuilder b;
    auto add = b("Add", 1, 2);
    b.data().nodes[size_t(add.index)].args[1] = add.index;
    b.entry(add);
    CHECK_THROWS_AS(sr::vm::compile(b.data(), {Mode::Watch, true}), sr::data::LoadError);
}

TEST_CASE("program entries") {
    NodeBuilder b;
    const int32_t a = b.entry(b("Add", 1, 2), CallbackKind::Initialize, "A");
    const int32_t c = b.entry(b("Add", 3, 4), CallbackKind::Preprocess, "B");
    const int32_t s = b.update_spawn(b("Get", 1001, 0));
    const auto program = sr::vm::compile(b.data(), {Mode::Watch, true});
    CHECK(program.has_entry(a));
    CHECK(program.has_entry(c));
    CHECK(program.has_entry(s));
    CHECK_FALSE(program.has_entry(0));
    CHECK_FALSE(program.has_entry(-1));
    CHECK_FALSE(program.has_entry(1000));
    CHECK(program.entry_count() == 3);
    CHECK(program.mode() == Mode::Watch);
}

}  // TEST_SUITE
