#include "dump.h"

#include <bit>
#include <charconv>
#include <cstdint>
#include <utility>

namespace sr::tools {

namespace {

void put_hex(std::string& out, float v) {
    const uint32_t fbits = std::bit_cast<uint32_t>(v);
    if ((fbits & 0x7F800000u) == 0x7F800000u) {  // NaN or infinity
        if ((fbits & 0x007FFFFFu) != 0) out += "nan";
        else out += (fbits >> 31) != 0 ? "-inf" : "inf";
        return;
    }
    const uint64_t bits = std::bit_cast<uint64_t>(double(v));  // every float is a normal double (or zero)
    if ((bits >> 63) != 0) out += '-';
    const int biased = int((bits >> 52) & 0x7FF);
    uint64_t mantissa = bits & ((uint64_t(1) << 52) - 1);
    if (biased == 0) {  // zero: double(v) of a float is never subnormal
        out += "0x0p+0";
        return;
    }
    out += "0x1";
    if (mantissa != 0) {
        out += '.';
        static const char* digits = "0123456789abcdef";
        for (int shift = 48; mantissa != 0; shift -= 4) {
            out += digits[(mantissa >> shift) & 0xF];
            mantissa &= (uint64_t(1) << shift) - 1;
        }
    }
    const int exponent = biased - 1023;
    out += exponent < 0 ? "p-" : "p+";
    char buf[8];
    const auto r = std::to_chars(buf, buf + sizeof buf, exponent < 0 ? -exponent : exponent);
    out.append(buf, r.ptr);
}

void put_float(std::string& out, float v) {
    out += '"';
    put_hex(out, v);
    out += '"';
}

void put_int(std::string& out, int64_t v) {
    char buf[24];
    const auto r = std::to_chars(buf, buf + sizeof buf, v);
    out.append(buf, r.ptr);
}

template <size_t N>
void put_floats(std::string& out, const char* key, const float (&values)[N]) {
    out += ",\"";
    out += key;
    out += "\":[";
    for (size_t i = 0; i < N; ++i) {
        if (i > 0) out += ',';
        put_float(out, values[i]);
    }
    out += ']';
}

void put_corners(std::string& out, const float (&x)[4], const float (&y)[4]) {
    for (int i = 0; i < 4; ++i) {
        out += ',';
        put_float(out, x[i]);
        out += ',';
        put_float(out, y[i]);
    }
}

}  // namespace

std::string hex_float(float v) {
    std::string out;
    put_hex(out, v);
    return out;
}

void append_frame(std::string& out, float time, const modes::FrameOutput& f) {
    out += "{\"t\":";
    put_float(out, time);

    out += ",\"draws\":[";
    for (size_t i = 0; i < f.draws.size(); ++i) {
        const modes::DrawCmd& d = f.draws[i];
        if (i > 0) out += ',';
        out += '[';
        put_int(out, d.sprite);
        out += ',';
        put_float(out, d.z[0]);
        out += ',';
        put_float(out, d.a);
        put_corners(out, d.x, d.y);
        for (int k = 1; k < 4; ++k) {
            out += ',';
            put_float(out, d.z[k]);
        }
        out += ',';
        put_int(out, d.entity);
        if (d.curve != modes::DrawCmd::None) {
            out += ',';
            put_int(out, d.curve);
            out += ',';
            put_float(out, d.segments);
            for (const float arg : d.curve_args) {
                out += ',';
                put_float(out, arg);
            }
        }
        out += ']';
    }

    out += "],\"audio\":[";
    for (size_t i = 0; i < f.audio.size(); ++i) {
        const modes::AudioCmd& a = f.audio[i];
        if (i > 0) out += ',';
        out += '[';
        put_int(out, a.op);
        out += ',';
        put_int(out, a.clip);
        for (const float v : {a.time, a.distance, a.handle}) {
            out += ',';
            put_float(out, v);
        }
        out += ']';
    }

    out += "],\"particles\":[";
    for (size_t i = 0; i < f.particles.size(); ++i) {
        const modes::ParticleCmd& p = f.particles[i];
        if (i > 0) out += ',';
        out += '[';
        put_int(out, p.op);
        out += ',';
        put_float(out, p.handle);
        out += ',';
        put_int(out, p.effect);
        put_corners(out, p.x, p.y);
        out += ',';
        put_float(out, p.duration);
        out += p.looped ? ",true]" : ",false]";
    }
    out += ']';

    put_floats(out, "skin", f.skin_transform);
    put_floats(out, "particle", f.particle_transform);
    put_floats(out, "background", f.background);
    put_floats(out, "ui", f.ui);

    const modes::Stats& s = f.stats;
    out += ",\"stats\":{";
    const std::pair<const char*, int32_t> counts[] = {{"combo", s.combo}, {"max_combo", s.max_combo},
                                                      {"perfect", s.perfect}, {"great", s.great},
                                                      {"good", s.good}, {"miss", s.miss}};
    for (const auto& [key, value] : counts) {
        out += '"';
        out += key;
        out += "\":";
        put_int(out, value);
        out += ',';
    }
    out += "\"arcade\":";
    put_float(out, s.arcade);
    out += ",\"accuracy\":";
    put_float(out, s.accuracy);
    out += ",\"life\":";
    put_float(out, s.life);
    out += f.ended ? "},\"ended\":true}\n" : "},\"ended\":false}\n";
}

}  // namespace sr::tools
