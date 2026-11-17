// Built-in UI (render/ui): widget layout from the Runtime UI record and Watch's overlay.
#include "render/ui.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <span>

#include "doctest.h"

using namespace sr;

namespace {

constexpr float kAspect = 1600.0f / 1068.0f;

void set_widget(modes::FrameOutput& f, render::UiWidget w, const float r[10]) {
    for (int i = 0; i < 10; ++i) f.ui[10 * int(w) + i] = r[i];
}

}  // namespace

TEST_SUITE("render") {

TEST_CASE("menu layout from runtime ui record") {
    modes::FrameOutput f;
    // Next RUSH's menu: anchor (1.7278, 0.95), pivot (1, 1), 0.225 square, alpha made visible here.
    const float menu[10] = {1.7278f, 0.95f, 1, 1, 0.225f, 0.225f, 0, 1, 0, 1};
    set_widget(f, render::UiWidget::Menu, menu);
    data::Ui ui;
    const auto q = render::build_ui(f, ui, 0, 0);
    REQUIRE(q.size() == 1);
    CHECK(q[0].widget == render::UiWidget::Menu);
    CHECK(q[0].alpha == 1);
    // Corners bl, tl, tr, br: the top-right corner sits on the anchor.
    CHECK(q[0].screen.x[0] == doctest::Approx(1.5028f));
    CHECK(q[0].screen.y[0] == doctest::Approx(0.725f));
    CHECK(q[0].screen.x[2] == doctest::Approx(1.7278f));
    CHECK(q[0].screen.y[2] == doctest::Approx(0.95f));
}

TEST_CASE("combo animation scales over its duration") {
    modes::FrameOutput f;
    const float combo[10] = {0, 0, 0.5f, 0.5f, 0.4f, 0.2f, 0, 1, 0, 0};
    set_widget(f, render::UiWidget::ComboValue, combo);
    data::Ui ui;
    ui.combo_animation.scale = {0.6f, 1.0f, 0.15f, "linear"};  // Next RUSH's configuration
    ui.combo_animation.alpha = {1.0f, 1.0f, 0.15f, "linear"};
    auto width = [&](float now) {
        const auto q = render::build_ui(f, ui, now, now);
        REQUIRE(q.size() == 1);
        return q[0].screen.x[2] - q[0].screen.x[0];
    };
    CHECK(width(0) == doctest::Approx(0.4f * 0.6f));
    CHECK(width(0.075f) == doctest::Approx(0.4f * 0.8f));
    CHECK(width(0.15f) == doctest::Approx(0.4f));
    CHECK(width(5) == doctest::Approx(0.4f));
}

TEST_CASE("hidden widget emits nothing") {
    modes::FrameOutput f;
    const float hidden[10] = {0, 0, 0.5f, 0.5f, 0.4f, 0.2f, 0, 0, 0, 0};  // alpha 0, as Next RUSH's defaults
    const float shown[10] = {0, 0, 0.5f, 0.5f, 0.4f, 0.2f, 0, 1, 0, 0};
    data::Ui ui;
    for (int i = 0; i < render::kUiWidgetCount; ++i) set_widget(f, render::UiWidget(i), hidden);
    CHECK(render::build_ui(f, ui, 0, 0).empty());
    // Visibility alpha 0 hides a widget whose record alpha is 1.
    set_widget(f, render::UiWidget::Menu, shown);
    ui.menu.alpha = 0;
    CHECK(render::build_ui(f, ui, 0, 0).empty());
    ui.menu.alpha = 1;
    CHECK(render::build_ui(f, ui, 0, 0).size() == 1);
}

namespace {

render::WatchBar bar_at(float since_shown, float time, float length, bool paused = false, bool repeat = true,
                        std::span<const modes::WatchTick> ticks = {}) {
    return render::WatchBar{since_shown, time, length, paused, repeat, ticks};
}

// Left, bottom, right, top of a quad's corners.
std::array<float, 4> bounds(const render::Quad& q) {
    std::array<float, 4> b{q.x[0], q.y[0], q.x[0], q.y[0]};
    for (int k = 1; k < 4; ++k) {
        b[0] = std::min(b[0], q.x[k]);
        b[1] = std::min(b[1], q.y[k]);
        b[2] = std::max(b[2], q.x[k]);
        b[3] = std::max(b[3], q.y[k]);
    }
    return b;
}

std::array<float, 4> join(std::array<float, 4> a, const std::array<float, 4>& b) {
    a[0] = std::min(a[0], b[0]);
    a[1] = std::min(a[1], b[1]);
    a[2] = std::max(a[2], b[2]);
    a[3] = std::max(a[3], b[3]);
    return a;
}

void check_rect(const float* r, const std::array<float, 4>& b) {
    for (int i = 0; i < 4; ++i) CHECK(r[i] == doctest::Approx(b[size_t(i)]));
}

// The bar's rects in draw order (not paused): 0 bar, 1 track, 2 cursor, 3 and 4 the pause bars, 5..10 the repeat
// glyph's strokes, then the ticks.
constexpr size_t kTrack = 1, kPauseBars = 3, kRepeatFirst = 5, kRepeatStrokes = 6, kTicksFirst = 11;

}  // namespace

TEST_CASE("overlay shows for three seconds with a fade") {
    CHECK(render::format_time(-1.0f) == "-0:01");
    CHECK(render::format_time(-0.3f) == "-0:00");
    CHECK(render::format_time(111.9f) == "1:51");
    CHECK(render::build_overlay(bar_at(-0.1f, 0, 111.9f), kAspect).rects.empty());
    CHECK(render::build_overlay(bar_at(3.0f, 0, 111.9f), kAspect).rects.empty());
    const auto on = render::build_overlay(bar_at(1.0f, -1, 111.9f), kAspect);
    REQUIRE(on.texts.size() == 1);
    CHECK(on.texts[0].text == "-0:01 / 1:51");
    CHECK(on.texts[0].rgba[3] == 1);
    CHECK(on.rects[0].rgba[3] == 1);
    // The bar: 24 px from the left, y 966 to 1046 of 1068.
    CHECK(on.rects[0].screen.x[0] == doctest::Approx(-kAspect + 24.0f / 534));
    CHECK(on.rects[0].screen.y[2] == doctest::Approx((534.0f - 966) / 534));
    // Opaque for 2.8 s, then a linear 0.2 s fade.
    const auto opaque = render::build_overlay(bar_at(2.79f, 0, 1), kAspect);
    CHECK(opaque.texts[0].rgba[3] == 1);
    CHECK(opaque.rects[0].rgba[3] == 1);
    const auto fading = render::build_overlay(bar_at(2.9f, 0, 1), kAspect);
    CHECK(fading.texts[0].rgba[3] == doctest::Approx(0.5f));
    CHECK(fading.rects[0].rgba[3] == doctest::Approx(0.5f));
}

TEST_CASE("paused overlay always shows with a play glyph") {
    const size_t running = render::build_overlay(bar_at(1.0f, 10, 100), kAspect).rects.size();
    for (const float since : {-5.0f, 0.0f, 2.9f, 3.0f, 1000.0f}) {
        const auto o = render::build_overlay(bar_at(since, 10, 100, /*paused=*/true), kAspect);
        REQUIRE(o.texts.size() == 1);
        CHECK(o.texts[0].rgba[3] == 1);
        // One play triangle in place of the two pause bars.
        REQUIRE(o.rects.size() == running - 1);
        for (const auto& r : o.rects) CHECK(r.rgba[3] == 1);
    }
    const auto o = render::build_overlay(bar_at(0, 10, 100, true), kAspect);
    const auto pause_bars = render::build_overlay(bar_at(0, 10, 100), kAspect);
    // The triangle fills the pause bars' box and points right: two corners on the left edge, two at the right tip.
    const auto play = bounds(o.rects[kPauseBars].screen);
    const auto bars = join(bounds(pause_bars.rects[kPauseBars].screen), bounds(pause_bars.rects[kPauseBars + 1].screen));
    for (size_t i = 0; i < 4; ++i) CHECK(play[i] == doctest::Approx(bars[i]));
    const render::Quad& q = o.rects[kPauseBars].screen;
    CHECK(q.x[0] == doctest::Approx(bars[0]));
    CHECK(q.x[1] == doctest::Approx(bars[0]));
    CHECK(q.x[2] == doctest::Approx(bars[2]));
    CHECK(q.x[3] == doctest::Approx(bars[2]));
    CHECK(q.y[2] == doctest::Approx((bars[1] + bars[3]) / 2));
    CHECK(q.y[3] == doctest::Approx((bars[1] + bars[3]) / 2));
}

TEST_CASE("repeat off dims the repeat glyph") {
    const auto on = render::build_overlay(bar_at(1.0f, 0, 100), kAspect);
    const auto off = render::build_overlay(bar_at(1.0f, 0, 100, false, /*repeat=*/false), kAspect);
    const auto fading = render::build_overlay(bar_at(2.9f, 0, 100, false, false), kAspect);
    REQUIRE(off.rects.size() == on.rects.size());
    for (size_t i = kRepeatFirst; i < kRepeatFirst + kRepeatStrokes; ++i) {
        CHECK(on.rects[i].rgba[3] == 1);
        CHECK(off.rects[i].rgba[3] == doctest::Approx(0.4f));
        CHECK(fading.rects[i].rgba[3] == doctest::Approx(0.4f * 0.5f));
    }
    CHECK(off.rects[kPauseBars].rgba[3] == 1);  // only the repeat glyph
}

TEST_CASE("ticks mark misses and goods along the track") {
    const modes::WatchTick ticks[] = {{25, true}, {50, false}, {75, true}};
    const auto o = render::build_overlay(bar_at(1.0f, 0, 100, false, true, ticks), kAspect);
    REQUIRE(o.rects.size() == kTicksFirst + 3);
    const auto track = bounds(o.rects[kTrack].screen);
    for (size_t i = 0; i < 3; ++i) {
        const auto& r = o.rects[kTicksFirst + i];
        const auto b = bounds(r.screen);
        CHECK(b[0] >= track[0] - 1e-5f);
        CHECK(b[2] <= track[2] + 1e-5f);
        CHECK(b[1] >= track[1] - 1e-5f);
        CHECK(b[3] <= track[3] + 1e-5f);
        CHECK(b[2] > b[0]);
        CHECK(b[3] > b[1]);
        const float want = track[0] + ticks[i].time / 100 * (track[2] - track[0]);
        CHECK((b[0] + b[2]) / 2 == doctest::Approx(want));
        CHECK(r.rgba[3] == 1);
        if (ticks[i].miss) {  // the app's error colour (Material dark error, F2B8B5)
            CHECK(r.rgba[0] == doctest::Approx(242.0f / 255));
            CHECK(r.rgba[1] == doctest::Approx(184.0f / 255));
            CHECK(r.rgba[2] == doctest::Approx(181.0f / 255));
        } else {  // the result screen's Good green (6BD68A)
            CHECK(r.rgba[0] == doctest::Approx(107.0f / 255));
            CHECK(r.rgba[1] == doctest::Approx(214.0f / 255));
            CHECK(r.rgba[2] == doctest::Approx(138.0f / 255));
        }
    }
    // Ticks at the track's ends stay inside it; without a length there are none.
    const modes::WatchTick ends[] = {{0, true}, {100, false}};
    const auto e = render::build_overlay(bar_at(1.0f, 0, 100, false, true, ends), kAspect);
    REQUIRE(e.rects.size() == kTicksFirst + 2);
    CHECK(bounds(e.rects[kTicksFirst].screen)[0] >= track[0] - 1e-5f);
    CHECK(bounds(e.rects[kTicksFirst + 1].screen)[2] <= track[2] + 1e-5f);
    CHECK(render::build_overlay(bar_at(1.0f, 0, 0, false, true, ticks), kAspect).rects.size() == kTicksFirst);
}

TEST_CASE("watch bar rects are the drawn track repeat and pause") {
    for (const float aspect : {kAspect, 2.0f, 1.3333f}) {
        const auto o = render::build_overlay(bar_at(1.0f, 0, 100), aspect);
        const render::WatchBarRects r = render::watch_bar_rects(aspect);
        check_rect(r.track, bounds(o.rects[kTrack].screen));
        check_rect(r.pause, join(bounds(o.rects[kPauseBars].screen), bounds(o.rects[kPauseBars + 1].screen)));
        std::array<float, 4> repeat = bounds(o.rects[kRepeatFirst].screen);
        for (size_t i = kRepeatFirst + 1; i < kRepeatFirst + kRepeatStrokes; ++i)
            repeat = join(repeat, bounds(o.rects[i].screen));
        check_rect(r.repeat, repeat);
        // The track: x 372 from the left edge to 197 from the right, y 979 to 1032.
        CHECK(r.track[0] == doctest::Approx(-aspect + 372.0f / 534));
        CHECK(r.track[2] == doctest::Approx(aspect - 197.0f / 534));
        CHECK(r.track[1] == doctest::Approx((534.0f - 1032) / 534));
        CHECK(r.track[3] == doctest::Approx((534.0f - 979) / 534));
    }
}

TEST_CASE("overlay glyph strokes stay inside the bar") {
    const modes::WatchTick ticks[] = {{0, true}, {50, false}, {100, true}};
    const auto on = render::build_overlay(bar_at(1.0f, 0, 100, false, true, ticks), kAspect);
    const float left = -kAspect + 24.0f / 534, right = kAspect - 22.0f / 534;
    const float top = (534.0f - 966) / 534, bottom = (534.0f - 1046) / 534;
    for (const auto& r : on.rects)
        for (int k = 0; k < 4; ++k) {
            CHECK(r.screen.x[k] >= left - 1e-4f);
            CHECK(r.screen.x[k] <= right + 1e-4f);
            CHECK(r.screen.y[k] <= top + 1e-4f);
            CHECK(r.screen.y[k] >= bottom - 1e-4f);
        }
}

namespace {

// Next RUSH's Runtime UI records made visible (alpha 1).
const float kMenu[10] = {1.7278f, 0.95f, 1, 1, 0.225f, 0.225f, 0, 1, 0, 1};
const float kJudgment[10] = {0, -0.23f, 0.5f, 0.55f, 0, 0.08f, 0, 1, 0, 0};
const float kComboValue[10] = {1.2444f, 0.175f, 0.5f, 0.7f, 0, 0.252f, 0, 1, 0, 0};
const float kComboText[10] = {1.2444f, 0.175f, 0.5f, -2.1f, 0, 0.049f, 0, 1, 0, 0};
const float kPrimaryBar[10] = {-1.7278f, 0.95f, 0, 1, 0.75f, 0.15f, 0, 1, -1, 1};
const float kPrimaryValue[10] = {-1.0128f, 0.915f, 1, 1, 0, 0.08f, 0, 1, 1, 0};
const float kSecondaryBar[10] = {1.5278f, 0.95f, 1, 1, 0.55f, 0.15f, 0, 1, -1, 1};
const float kSecondaryValue[10] = {1.4928f, 0.915f, 1, 1, 0, 0.08f, 0, 1, 1, 0};

const render::UiText* find_text(const render::UiOverlay& o, const std::string& s) {
    for (const auto& t : o.texts)
        if (t.text == s) return &t;
    return nullptr;
}

const render::UiIcon* find_icon(const render::UiOverlay& o, render::UiIconKind k) {
    for (const auto& i : o.icons)
        if (i.kind == k) return &i;
    return nullptr;
}

// An engine configuration whose animations hold scale and alpha at 1.
data::Ui steady_ui() {
    data::Ui ui;
    ui.primary_metric = "arcade";
    ui.secondary_metric = "life";
    for (data::Animation* a : {&ui.judgment_animation, &ui.combo_animation}) {
        a->scale = {1, 1, 0, "linear"};
        a->alpha = {1, 1, 0, "linear"};
    }
    ui.judgment_error_style = "none";
    return ui;
}

}  // namespace

TEST_CASE("metric views show the stats as the built-in ui does") {
    modes::Stats s;
    s.arcade = 0.17982f;
    s.accuracy = 0.5f;
    s.life = 600;
    s.max_life = 1200;
    s.perfect = 3;
    s.great = 1;
    CHECK(render::metric_view("arcade", s, 0, 0).text == "179820");
    CHECK(render::metric_view("arcade", s, 0, 0).fill == doctest::Approx(0.17982f));
    CHECK(render::metric_view("arcadePercentage", s, 0, 0).text == "17.98%");
    CHECK(render::metric_view("accuracy", s, 0, 0).text == "500000");
    CHECK(render::metric_view("accuracyPercentage", s, 0, 0).text == "50.00%");
    CHECK(render::metric_view("life", s, 0, 0).text == "600");
    CHECK(render::metric_view("life", s, 0, 0).fill == doctest::Approx(0.5f));
    CHECK(render::metric_view("life", s, 0, 0).icon == render::UiIconKind::Heart);
    CHECK(render::metric_view("time", s, 65.2f, 100).text == "1:05");
    CHECK(render::metric_view("time", s, 65.2f, 100).fill == doctest::Approx(0.652f));
    CHECK(render::metric_view("perfect", s, 0, 0).text == "3");
    CHECK(render::metric_view("perfectPercentage", s, 0, 0).text == "75.00%");
    CHECK(render::metric_view("greatGoodMiss", s, 0, 0).text == "1");
    CHECK(render::metric_view("greatGoodMissPercentage", s, 0, 0).text == "25.00%");
    CHECK(render::metric_view("miss", s, 0, 0).text == "0");
    CHECK(render::metric_view("missPercentage", s, 0, 0).text == "0.00%");
    CHECK(render::metric_view("errorHeatmap", s, 0, 0).text.empty());
    CHECK(render::metric_view("nonsense", s, 0, 0).text.empty());
    CHECK(render::metric_view("perfectPercentage", modes::Stats{}, 0, 0).text == "0.00%");  // nothing judged yet
}

TEST_CASE("hud draws the metric bars and values") {
    modes::FrameOutput f;
    set_widget(f, render::UiWidget::PrimaryBar, kPrimaryBar);
    set_widget(f, render::UiWidget::PrimaryValue, kPrimaryValue);
    set_widget(f, render::UiWidget::SecondaryBar, kSecondaryBar);
    set_widget(f, render::UiWidget::SecondaryValue, kSecondaryValue);
    f.stats.arcade = 0.17982f;
    f.stats.life = 1000;
    f.stats.max_life = 1000;
    const auto o = render::build_hud(f, steady_ui(), 10, 100);

    const render::UiText* score = find_text(o, "179820");
    REQUIRE(score != nullptr);
    CHECK(score->x == doctest::Approx(-1.0128f));  // a width-0 record: the pivot places the text
    CHECK(score->pivot == 1);
    CHECK(score->y == doctest::Approx(0.915f - 0.04f));
    CHECK(score->size == doctest::Approx(0.08f * 0.85f / render::kCapHeightEm));
    REQUIRE(find_text(o, "1000") != nullptr);

    // Icons in a bar-high square at each bar's left.
    const render::UiIcon* flame = find_icon(o, render::UiIconKind::Flame);
    REQUIRE(flame != nullptr);
    CHECK(flame->box.x[0] >= -1.7278f);
    CHECK(flame->box.x[2] <= -1.7278f + 0.15f);
    REQUIRE(find_icon(o, render::UiIconKind::Heart) != nullptr);

    // Fills: grey, after the icon square, 0.15 of the height in from the other edges.
    std::vector<const render::UiRect*> fills;
    for (const auto& r : o.rects)
        if (r.rgba[0] > 0.3f && r.rgba[0] < 0.7f) fills.push_back(&r);
    REQUIRE(fills.size() == 2);
    const float track = 0.75f - 0.15f - 0.15f * 0.15f;
    CHECK(fills[0]->screen.x[0] == doctest::Approx(-1.7278f + 0.15f));
    CHECK(fills[0]->screen.x[2] - fills[0]->screen.x[0] == doctest::Approx(0.17982f * track));
    CHECK(fills[0]->screen.y[2] == doctest::Approx(0.95f - 0.15f * 0.15f));
    const float track2 = 0.55f - 0.15f - 0.15f * 0.15f;
    CHECK(fills[1]->screen.x[2] - fills[1]->screen.x[0] == doctest::Approx(track2));  // full life
}

TEST_CASE("hud shows combo and judgment once there is one") {
    modes::FrameOutput f;
    set_widget(f, render::UiWidget::Judgment, kJudgment);
    set_widget(f, render::UiWidget::ComboValue, kComboValue);
    set_widget(f, render::UiWidget::ComboText, kComboText);
    const data::Ui ui = steady_ui();
    CHECK(render::build_hud(f, ui, 1, 100).texts.empty());  // no judgment, combo 0

    f.stats.combo = 116;
    f.stats.combo_time = 9.9f;
    f.stats.judgment = 2;
    f.stats.judgment_time = 9.9f;
    const auto o = render::build_hud(f, ui, 10, 100);
    const render::UiText* count = find_text(o, "116");
    const render::UiText* label = find_text(o, "COMBO");
    const render::UiText* great = find_text(o, "GREAT");
    REQUIRE(count != nullptr);
    REQUIRE(label != nullptr);
    REQUIRE(great != nullptr);
    CHECK(count->outline);
    CHECK(count->bold);
    CHECK(count->pivot == 0.5f);
    CHECK(label->y > count->y);  // COMBO sits above the count
    CHECK(great->x == doctest::Approx(0.0f));

    // A Miss ends the combo: the count goes, the judgment stays.
    f.stats.combo = 0;
    f.stats.judgment = 4;
    const auto miss = render::build_hud(f, ui, 10, 100);
    CHECK(find_text(miss, "MISS") != nullptr);
    CHECK(find_text(miss, "COMBO") == nullptr);
}

TEST_CASE("judgment animation fades from the last judgment") {
    modes::FrameOutput f;
    set_widget(f, render::UiWidget::Judgment, kJudgment);
    data::Ui ui = steady_ui();
    ui.judgment_animation.alpha = {1, 0, 0.2f, "linear"};
    f.stats.judgment = 1;
    f.stats.judgment_time = 5;
    const auto fresh = render::build_hud(f, ui, 5.1f, 100);
    REQUIRE(find_text(fresh, "PERFECT") != nullptr);
    CHECK(find_text(fresh, "PERFECT")->rgba[3] == doctest::Approx(0.5f));
    CHECK(render::build_hud(f, ui, 5.3f, 100).texts.empty());
}

TEST_CASE("judgment error indicator follows style placement and minimum") {
    modes::FrameOutput f;
    set_widget(f, render::UiWidget::Judgment, kJudgment);
    data::Ui ui = steady_ui();
    ui.judgment_error_style = "late";
    ui.judgment_error_placement = "top";
    ui.judgment_error_min = 20;
    f.stats.judgment = 2;
    f.stats.judgment_time = 1;

    f.stats.judgment_accuracy = -0.03f;
    auto o = render::build_hud(f, ui, 1, 100);
    const render::UiText* great = find_text(o, "GREAT");
    const render::UiText* early = find_text(o, "EARLY");
    REQUIRE(great != nullptr);
    REQUIRE(early != nullptr);
    CHECK(early->y > great->y);
    CHECK(early->size < great->size);
    f.stats.judgment_accuracy = 0.03f;
    CHECK(find_text(render::build_hud(f, ui, 1, 100), "LATE") != nullptr);
    f.stats.judgment_accuracy = 0.01f;  // under the minimum
    CHECK(render::build_hud(f, ui, 1, 100).texts.size() == 1);
    f.stats.judgment_accuracy = 0.05f;
    f.stats.judgment = 4;  // never for a Miss
    CHECK(render::build_hud(f, ui, 1, 100).texts.size() == 1);

    // Glyph styles: the named glyph for a late hit, its opposite for an early one.
    f.stats.judgment = 2;
    ui.judgment_error_style = "plus";
    ui.judgment_error_placement = "right";
    o = render::build_hud(f, ui, 1, 100);
    const render::UiIcon* plus = find_icon(o, render::UiIconKind::Plus);
    REQUIRE(plus != nullptr);
    CHECK(plus->box.x[0] > 0);  // right of the centred judgment
    f.stats.judgment_accuracy = -0.05f;
    CHECK(find_icon(render::build_hud(f, ui, 1, 100), render::UiIconKind::Minus) != nullptr);
    ui.judgment_error_placement = "leftRight";
    CHECK(render::build_hud(f, ui, 1, 100).icons.size() == 2);
    ui.judgment_error_style = "none";
    CHECK(render::build_hud(f, ui, 1, 100).icons.empty());
}

TEST_CASE("menu draws a box with three bars inside it") {
    modes::FrameOutput f;
    set_widget(f, render::UiWidget::Menu, kMenu);
    const auto o = render::build_hud(f, steady_ui(), 0, 100);
    REQUIRE(o.rects.size() == 4);
    for (const auto& r : o.rects)
        for (int k = 0; k < 4; ++k) {
            CHECK(r.screen.x[k] >= 1.5028f - 1e-4f);
            CHECK(r.screen.x[k] <= 1.7278f + 1e-4f);
            CHECK(r.screen.y[k] >= 0.725f - 1e-4f);
            CHECK(r.screen.y[k] <= 0.95f + 1e-4f);
        }
}

}  // TEST_SUITE
