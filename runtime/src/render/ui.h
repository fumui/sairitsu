#pragma once

#include <span>
#include <string>
#include <vector>

#include "data/configuration.h"
#include "modes/presentation.h"
#include "modes/replay_view.h"
#include "render/geometry.h"

namespace sr::render {

// Built-in UI. With Next RUSH's defaults no built-in widget shows during play (all alpha 0), but other engines
// (e.g. those that draw no HUD of their own) show the menu, judgment, combo and metric widgets. Three things exist:
// the widget layout (build_ui), the widgets' content as shapes and text runs (build_hud), and Watch's own overlay
// (the bottom control bar, 3 s after each start, restart or interaction, and while paused). The Compose layer draws the
// shapes and text.

// The ten widgets in Runtime UI block order (10 floats each: anchor x, y, pivot x, y, width, height, rotation,
// alpha, align, background).
enum class UiWidget : int {
    Menu, Judgment, ComboValue, ComboText, PrimaryBar, PrimaryValue, SecondaryBar, SecondaryValue, Progress,
    ProgressGraph,
};
inline constexpr int kUiWidgetCount = 10;

// One visible widget's box in screen space. The widget's content fills the quad; `align` is the text alignment
// (-1 left, 0 centre, 1 right), `background` whether the widget draws its background, `pivot_x` the record's pivot x
// (a widget of width 0 is sized by its text, which the pivot places about the anchor).
struct UiQuad {
    UiWidget widget;
    Quad screen;
    float alpha;
    float align;
    bool background;
    float pivot_x = 0;
};

// Lays out every widget whose alpha is above 0, after the configuration's visibility (scale, alpha) and,
// for judgment and combo, its animation. Rotation is counter-clockwise degrees about the anchor (an assumption:
// no tested engine rotates a widget). `since_judgment` and `since_combo` are the times in seconds since the last
// judgment and the last combo change; they drive the judgment's and the combo's animation: scale and alpha are each
// from + (to - from) * ease(now / duration), held at `to` after the duration. The progress widgets are skipped:
// Watch shows them only in its overlay.
std::vector<UiQuad> build_ui(const modes::FrameOutput& frame, const data::Ui& ui, float since_judgment,
                             float since_combo);

// A solid-colour rectangle (any quad), straight alpha.
struct UiRect {
    Quad screen;
    float rgba[4];
};

// A text run: x is where the renderer puts the point `pivot` of the way along the measured text (0 its left edge,
// 0.5 its centre, 1 its right edge), y its vertical centre (of the digits' and capitals' height), size the font size
// (the em, in screen units), straight alpha. `bold` picks the bold weight of the app's font; `outline` a dark stroke
// behind the glyphs.
struct UiText {
    std::string text;
    float x, y, size;
    float rgba[4];
    float pivot = 0;
    bool bold = false, outline = false;
};

// A glyph the renderer draws inside `box` (corners bl, tl, tr, br; the glyph's own up is from bl to tl).
enum class UiIconKind : int {
    Flame, Target, Heart, Clock, Star, Cross,  // metric icons
    Plus, Minus, ArrowUp, ArrowDown, ArrowLeft, ArrowRight, TriangleUp, TriangleDown, TriangleLeft, TriangleRight,
};
struct UiIcon {
    UiIconKind kind;
    Quad box;
    float rgba[4];
};

// Drawn in order: rects, then icons, then texts.
struct UiOverlay {
    std::vector<UiRect> rects;
    std::vector<UiText> texts;
    std::vector<UiIcon> icons;
};

// The fraction of the em that a digit or capital is tall in the app's font (Roboto: 0.711).
inline constexpr float kCapHeightEm = 0.711f;

// The built-in widgets' content for a frame (an original HUD):
// - menu: a dark box with three white bars;
// - primary and secondary metric: the bar (dark box, the metric's icon in a square at its left, a grey fill after it
//   for the metric's fraction) and the value text, right-aligned in its own record;
// - combo: the count and "COMBO" (only while the combo is above 0);
// - judgment: "PERFECT", "GREAT", "GOOD" or "MISS" from the last judgment, with the judgment error indicator the
//   configuration asks for (style, placement and minimum in ms; not for a Miss).
// `time` is the frame's music time, `length` the BGM length (the time metric). Text heights follow the widget box
// (the digits and capitals 0.85 of it), widget alphas the record times visibility times animation.
UiOverlay build_hud(const modes::FrameOutput& frame, const data::Ui& ui, float time, float length);

// The text a metric shows for `stats` ("179820", "98.34%", "1000", "1:05" ...) and its bar fraction in 0..1.
// Unknown metrics and errorHeatmap show nothing.
struct MetricView {
    std::string text;
    float fill = 0;
    UiIconKind icon = UiIconKind::Flame;
};
MetricView metric_view(const std::string& metric, const modes::Stats& stats, float time, float length);

// Watch's control bar as of a frame: `since_shown` the seconds since it was last shown (the loop's start or restart,
// or the user's last interaction), `time` the music time shown and `length` the BGM length (s), `paused` and
// `repeat` the controls' states, `ticks` the replay's Miss and Good inputs (empty without a replay; must outlive the
// call).
struct WatchBar {
    float since_shown, time, length;
    bool paused, repeat;
    std::span<const modes::WatchTick> ticks;
};

// Watch's overlay. Shown for 3 s after `since_shown` 0 (opaque for 2.8 s, then a linear 0.2 s fade; nothing
// after, and nothing before 0), and always while paused. Geometry on a 1600 x 1068 screen (units of
// 1/534 of the half height), left items anchored to the left edge and right items to the right edge:
// bar x 24 to aspect edge - 23, y 966 to 1046, fill grey 31/255; seek track x 372 to 1403 (from the left edge, of
// 1600), y 979 to 1032, grey 24/255; cursor 6 px wide, white; repeat glyph (two opposed arrows) and pause (two bars
// 14 x 40, 6 apart) white. No dim of the stage (the dimming seen at a restart is Next RUSH's own fade-in).
// Also: while paused a play triangle fills the pause bars' box instead; with repeat off the repeat glyph
// is drawn at 0.4 of the bar's alpha; each tick is a 6 px wide mark in the track's lower part (below the cursor),
// centred at track left + time / length of the track (kept inside it), in the app's error colour for a Miss and the
// result screen's Good green for a Good. Rects in draw order: bar, track, cursor, pause (two bars, or the triangle),
// the repeat glyph's six strokes, the ticks (last, so a capped rect count drops ticks before controls).
UiOverlay build_overlay(const WatchBar& bar, float aspect);

// The bar's controls in screen units (left, bottom, right, top), exactly the drawn shapes' bounds: the seek track, the
// repeat glyph's strokes, the pause bars (the play triangle has the same box). The Compose layer hit-tests these.
struct WatchBarRects {
    float track[4], repeat[4], pause[4];
};
WatchBarRects watch_bar_rects(float aspect);

// "m:ss" with a leading "-" while time is negative: floor of the magnitude, e.g. -1 -> "-0:01", 111.9 -> "1:51".
std::string format_time(float seconds);

}  // namespace sr::render
