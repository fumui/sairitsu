#include "vm/functions.h"

#include <algorithm>
#include <array>

namespace sr::vm {

namespace {

constexpr uint8_t kP = mode_bit(data::Mode::Play);
constexpr uint8_t kPW = mode_bit(data::Mode::Play) | mode_bit(data::Mode::Watch);
constexpr uint8_t kNone = 0;

constexpr FnInfo kInfo[] = {
#define FN(id, name, min_args, max_args, modes) {name, min_args, max_args, modes},
#include "vm/functions.inc"
#undef FN
};
static_assert(std::size(kInfo) == size_t(kFnCount));

struct NameEntry {
    std::string_view name;
    Fn fn;
};

// Sorted by name for binary search.
const std::array<NameEntry, kFnCount>& by_name() {
    static const std::array<NameEntry, kFnCount> table = [] {
        std::array<NameEntry, kFnCount> t{};
        for (int i = 0; i < kFnCount; ++i) t[size_t(i)] = NameEntry{kInfo[i].name, Fn(i)};
        std::sort(t.begin(), t.end(), [](const NameEntry& a, const NameEntry& b) { return a.name < b.name; });
        return t;
    }();
    return table;
}

}  // namespace

const FnInfo& fn_info(Fn fn) { return kInfo[size_t(fn)]; }

std::optional<Fn> fn_from_name(std::string_view name) {
    const auto& t = by_name();
    auto it = std::lower_bound(t.begin(), t.end(), name,
                               [](const NameEntry& e, std::string_view v) { return e.name < v; });
    if (it == t.end() || it->name != name) return std::nullopt;
    return it->fn;
}

bool fn_foldable(Fn fn) {
    if (fn >= Fn::EaseInSine && fn <= Fn::EaseOutInElastic) return true;
    switch (fn) {
        case Fn::Abs: case Fn::Add: case Fn::Arccos: case Fn::Arcsin: case Fn::Arctan: case Fn::Arctan2:
        case Fn::Ceil: case Fn::Clamp: case Fn::Cos: case Fn::Cosh: case Fn::Degree: case Fn::Divide:
        case Fn::Equal: case Fn::Floor: case Fn::Frac: case Fn::Greater: case Fn::GreaterOr: case Fn::Judge:
        case Fn::JudgeSimple: case Fn::Lerp: case Fn::LerpClamped: case Fn::Less: case Fn::LessOr: case Fn::Log:
        case Fn::Max: case Fn::Min: case Fn::Mod: case Fn::Multiply: case Fn::Negate: case Fn::Not:
        case Fn::NotEqual: case Fn::Power: case Fn::Radian: case Fn::Rem: case Fn::Remap: case Fn::RemapClamped:
        case Fn::Round: case Fn::Sign: case Fn::Sin: case Fn::Sinh: case Fn::Subtract: case Fn::Tan:
        case Fn::Tanh: case Fn::Trunc: case Fn::Unlerp: case Fn::UnlerpClamped:
            return true;
        default:
            return false;
    }
}

}  // namespace sr::vm
