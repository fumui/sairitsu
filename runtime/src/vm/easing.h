#pragma once

#include <string_view>

namespace sr::vm {

enum class EaseKind {
    Linear,
    InSine, OutSine, InOutSine, OutInSine,
    InQuad, OutQuad, InOutQuad, OutInQuad,
    InCubic, OutCubic, InOutCubic, OutInCubic,
    InQuart, OutQuart, InOutQuart, OutInQuart,
    InQuint, OutQuint, InOutQuint, OutInQuint,
    InExpo, OutExpo, InOutExpo, OutInExpo,
    InCirc, OutCirc, InOutCirc, OutInCirc,
    InBack, OutBack, InOutBack, OutInBack,
    InElastic, OutElastic, InOutElastic, OutInElastic,
};

inline constexpr int kEaseKindCount = 37;

// Engine ease names (EaseType, e.g. "inOutQuad"); an empty or unknown name is linear.
EaseKind ease_kind(std::string_view name);

// Easing curves in float32 arithmetic (every intermediate is a float,
// powers and trig go through sr::fm). Input is clamped to [0, 1].
float ease(EaseKind kind, float x);

}  // namespace sr::vm
