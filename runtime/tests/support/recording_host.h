#pragma once

#include <cstdint>
#include <span>
#include <string>
#include <vector>

#include "vm/functions.h"
#include "vm/host.h"

namespace sr::test {

// A Host that records every call (function, arguments) and returns canned values.
class RecordingHost : public vm::Host {
public:
    struct Call {
        std::string family;
        vm::Fn fn;
        std::vector<float> args;
    };

    std::vector<Call> calls;
    int64_t debug_calls = 0;  // DebugLog/DebugPause are counted, not recorded (runaway loop test)
    float result = 0;         // returned by audio/particle/stream/time/has_skin_sprite
    float random_value = 0.25f;

    void draw(const vm::DrawCall& c) override { record("draw", c.fn, c.args); }
    float audio(const vm::AudioCall& c) override { return record("audio", c.fn, c.args); }
    float particle(const vm::ParticleCall& c) override { return record("particle", c.fn, c.args); }
    void spawn(int32_t archetype, std::span<const float> data) override {
        std::vector<float> args{float(archetype)};
        args.insert(args.end(), data.begin(), data.end());
        record("spawn", vm::Fn::Spawn, args);
    }
    float stream(const vm::StreamCall& c) override { return record("stream", c.fn, c.args); }
    float time(const vm::TimeCall& c) override {
        const float v[] = {c.value};
        return record("time", c.fn, v);
    }
    float has_skin_sprite(int32_t id) override {
        const float v[] = {float(id)};
        return record("skin", vm::Fn::HasSkinSprite, v);
    }
    void add_life_scheduled(float value, float time) override {
        const float v[] = {value, time};
        record("life", vm::Fn::AddLifeScheduled, v);
    }
    float random() override { return random_value; }
    void debug(const vm::DebugCall&) override { ++debug_calls; }
    void export_value(int32_t index, float value) override {
        const float v[] = {float(index), value};
        record("export", vm::Fn::ExportValue, v);
    }

private:
    float record(const char* family, vm::Fn fn, std::span<const float> args) {
        calls.push_back(Call{family, fn, std::vector<float>(args.begin(), args.end())});
        return result;
    }
};

}  // namespace sr::test
