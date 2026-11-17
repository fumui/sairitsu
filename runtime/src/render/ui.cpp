#include "render/ui.h"

#include <algorithm>
#include <cmath>
#include <cstdio>

#include "vm/easing.h"
#include "vm/fmath.h"

namespace sr::render {

namespace fm = sr::fm;

namespace {

// A tween's value at `now` seconds: from + (to - from) * ease(now / duration), `to` once the duration has passed
// (and for a zero duration).
float tween(const data::AnimationTween& t, float now) {
    if (!(t.duration > 0) || now >= t.duration) return t.to;
    const float p = now < 0 ? 0.0f : now / t.duration;
    return t.from + (t.to - t.from) * vm::ease(vm::ease_kind(t.ease), p);
}

Quad rect_quad(float left, float bottom, float right, float top) {
    return Quad{{left, left, right, right}, {bottom, top, top, bottom}};
}

constexpr float kHalf = 534.0f;  // the recording's half height in px: 1 screen unit

}  // namespace

std::vector<UiQuad> build_ui(const modes::FrameOutput& frame, const data::Ui& ui, float since_judgment,
                             float since_combo) {
    struct Vis {
        const data::Visibility* v;
        const data::Animation* anim;
    };
    const Vis vis[kUiWidgetCount] = {
        {&ui.menu, nullptr},
        {&ui.judgment, &ui.judgment_animation},
        {&ui.combo, &ui.combo_animation},
        {&ui.combo, &ui.combo_animation},
        {&ui.primary_metric_visibility, nullptr},
        {&ui.primary_metric_visibility, nullptr},
        {&ui.secondary_metric_visibility, nullptr},
        {&ui.secondary_metric_visibility, nullptr},
        {&ui.progress, nullptr},
        {&ui.progress, nullptr},
    };
    std::vector<UiQuad> out;
    for (int i = 0; i < int(UiWidget::Progress); ++i) {  // the progress widgets are Watch's overlay
        const float* r = frame.ui + 10 * i;
        float alpha = r[7] * vis[i].v->alpha;
        float scale = vis[i].v->scale;
        if (vis[i].anim) {
            const float now = UiWidget(i) == UiWidget::Judgment ? since_judgment : since_combo;
            scale *= tween(vis[i].anim->scale, now);
            alpha *= tween(vis[i].anim->alpha, now);
        }
        if (!(alpha > 0)) continue;
        const float w = r[4] * scale, h = r[5] * scale;
        const float ax = r[0], ay = r[1];
        const float l = ax - r[2] * w, b = ay - r[3] * h;
        const float rad = r[6] * 0.017453292519943295f;
        const float c = fm::cos(rad), s = fm::sin(rad);
        Quad q = rect_quad(l, b, l + w, b + h);
        for (int k = 0; k < 4; ++k) {  // counter-clockwise about the anchor
            const float dx = q.x[k] - ax, dy = q.y[k] - ay;
            q.x[k] = ax + dx * c - dy * s;
            q.y[k] = ay + dx * s + dy * c;
        }
        out.push_back(UiQuad{UiWidget(i), q, alpha, r[8], r[9] != 0, r[2]});
    }
    return out;
}

namespace {

// A widget box as an origin (its bl corner) and two edge vectors (bl to br, bl to tl), so content laid out in the
// box's own fractions follows its rotation.
struct Box {
    float ox, oy, ux, uy, vx, vy, w, h;
};

Box box_of(const Quad& q) {
    Box b{q.x[0], q.y[0], q.x[3] - q.x[0], q.y[3] - q.y[0], q.x[1] - q.x[0], q.y[1] - q.y[0], 0, 0};
    b.w = fm::sqrt(b.ux * b.ux + b.uy * b.uy);
    b.h = fm::sqrt(b.vx * b.vx + b.vy * b.vy);
    return b;
}

// The part of the box from fractions (u0, v0) to (u1, v1), corners bl, tl, tr, br.
Quad part(const Box& b, float u0, float v0, float u1, float v1) {
    Quad q;
    const float us[4] = {u0, u0, u1, u1}, vs[4] = {v0, v1, v1, v0};
    for (int k = 0; k < 4; ++k) {
        q.x[k] = b.ox + us[k] * b.ux + vs[k] * b.vx;
        q.y[k] = b.oy + us[k] * b.uy + vs[k] * b.vy;
    }
    return q;
}

void add_rect(UiOverlay& o, const Quad& q, float g, float a) { o.rects.push_back({q, {g, g, g, a}}); }

// An upright square of side `side` centred on (x, y).
Quad square(float x, float y, float side) {
    const float r = side / 2;
    return rect_quad(x - r, y - r, x + r, y + r);
}

constexpr float kCapOfBox = 0.85f;     // digits and capitals: this much of the widget's height
constexpr float kCapsAdvance = 0.9f;   // a bold capital's mean advance, in cap heights (Roboto Bold)
constexpr float kBarBackground = 0.0f, kBarBackgroundAlpha = 0.85f;
constexpr float kMenuBackground = 0.13f;
constexpr float kFillGrey = 0.5f;
constexpr float kBarInset = 0.15f;  // the fill's inset from the bar's edges, in bar heights
constexpr float kIconInset = 0.2f;  // the icon's inset in its square, in bar heights

// A text widget's run: its text fills the box's height; a box with a width aligns the text in it (`align`), a box
// sized by its text (width 0) puts the text's pivot on the anchor. Rotation is not applied to text.
UiText widget_text(const UiQuad& q, std::string text, bool outline) {
    float l = q.screen.x[0], r = l, b = q.screen.y[0], t = b;
    for (int k = 1; k < 4; ++k) {
        l = std::min(l, q.screen.x[k]);
        r = std::max(r, q.screen.x[k]);
        b = std::min(b, q.screen.y[k]);
        t = std::max(t, q.screen.y[k]);
    }
    UiText out;
    out.text = std::move(text);
    const float a = std::clamp(q.align, -1.0f, 1.0f);
    if (r - l > 1e-6f) {
        out.pivot = (a + 1) / 2;
        out.x = l + out.pivot * (r - l);
    } else {
        out.pivot = q.pivot_x;
        out.x = l;
    }
    out.y = (b + t) / 2;
    out.size = box_of(q.screen).h * kCapOfBox / kCapHeightEm;
    out.rgba[0] = out.rgba[1] = out.rgba[2] = 1;
    out.rgba[3] = q.alpha;
    out.bold = true;
    out.outline = outline;
    return out;
}

void add_text_widget(UiOverlay& o, const UiQuad& q, std::string text, bool outline) {
    const Box b = box_of(q.screen);
    if (q.background && b.w > 1e-6f) add_rect(o, q.screen, kBarBackground, kBarBackgroundAlpha * q.alpha);
    o.texts.push_back(widget_text(q, std::move(text), outline));
}

void add_menu(UiOverlay& o, const UiQuad& q) {
    const Box b = box_of(q.screen);
    if (q.background) add_rect(o, q.screen, kMenuBackground, q.alpha);
    // Three bars across the middle half, 0.08 of the height thick, at 0.3, 0.5 and 0.7 from the top.
    for (const float c : {0.7f, 0.5f, 0.3f}) add_rect(o, part(b, 0.25f, c - 0.04f, 0.75f, c + 0.04f), 1, q.alpha);
}

void add_bar(UiOverlay& o, const UiQuad& q, const MetricView& m) {
    const Box b = box_of(q.screen);
    if (!(b.w > 0) || !(b.h > 0)) return;
    if (q.background) add_rect(o, q.screen, kBarBackground, kBarBackgroundAlpha * q.alpha);
    const float sq = std::min(b.h / b.w, 1.0f);  // the icon's square, as a fraction of the width
    const float in = kIconInset * b.h / b.w;
    o.icons.push_back(UiIcon{m.icon, part(b, in, kIconInset, sq - in, 1 - kIconInset), {1, 1, 1, q.alpha}});
    const float track = std::max(0.0f, 1 - sq - kBarInset * b.h / b.w);
    const float fill = std::clamp(m.fill, 0.0f, 1.0f) * track;
    if (fill > 0) add_rect(o, part(b, sq, kBarInset, sq + fill, 1 - kBarInset), kFillGrey, q.alpha);
}

// The judgment error glyph for a late hit under each glyph style, and its opposite for an early one.
bool error_glyph(const std::string& style, bool late, UiIconKind& out) {
    struct Pair {
        const char* name;
        UiIconKind late, early;
    };
    static const Pair pairs[] = {
        {"plus", UiIconKind::Plus, UiIconKind::Minus},
        {"minus", UiIconKind::Minus, UiIconKind::Plus},
        {"arrowUp", UiIconKind::ArrowUp, UiIconKind::ArrowDown},
        {"arrowDown", UiIconKind::ArrowDown, UiIconKind::ArrowUp},
        {"arrowLeft", UiIconKind::ArrowLeft, UiIconKind::ArrowRight},
        {"arrowRight", UiIconKind::ArrowRight, UiIconKind::ArrowLeft},
        {"triangleUp", UiIconKind::TriangleUp, UiIconKind::TriangleDown},
        {"triangleDown", UiIconKind::TriangleDown, UiIconKind::TriangleUp},
        {"triangleLeft", UiIconKind::TriangleLeft, UiIconKind::TriangleRight},
        {"triangleRight", UiIconKind::TriangleRight, UiIconKind::TriangleLeft},
    };
    for (const Pair& p : pairs)
        if (style == p.name) {
            out = late ? p.late : p.early;
            return true;
        }
    return false;
}

void add_judgment(UiOverlay& o, const UiQuad& q, const data::Ui& ui, const modes::Stats& s) {
    static const char* const names[] = {"PERFECT", "GREAT", "GOOD", "MISS"};
    UiText t = widget_text(q, names[s.judgment - 1], true);
    const float cap = t.size * kCapHeightEm;
    const float width = cap * kCapsAdvance * float(t.text.size());  // estimated: the renderer measures the real one
    const float cx = t.x + (0.5f - t.pivot) * width, cy = t.y;
    const float alpha = t.rgba[3];
    o.texts.push_back(std::move(t));

    // The error indicator: not for a Miss, nor for an error under the minimum (ms).
    const float error = s.judgment_accuracy;
    const std::string& style = ui.judgment_error_style;
    if (s.judgment == 4 || error == 0 || style.empty() || style == "none") return;
    if (std::fabs(error) * 1000.0f < ui.judgment_error_min) return;
    const bool late = error > 0;
    const bool is_text = style == "late" || style == "early";
    UiIconKind glyph{};
    if (!is_text && !error_glyph(style, late, glyph)) return;

    const float ic = cap * 0.5f, gap = cap * 0.25f;  // the indicator's height and its gap to the judgment
    // pivot: 0 the indicator starts at x, 0.5 it is centred on x, 1 it ends at x.
    auto put = [&](float x, float y, float pivot) {
        if (is_text) {
            UiText e;
            e.text = late ? "LATE" : "EARLY";
            e.x = x;
            e.y = y;
            e.size = ic / kCapHeightEm;
            e.pivot = pivot;
            e.rgba[0] = e.rgba[1] = e.rgba[2] = 1;
            e.rgba[3] = alpha;
            e.bold = true;
            e.outline = true;
            o.texts.push_back(std::move(e));
        } else {
            const float side = ic * 1.2f;
            o.icons.push_back(UiIcon{glyph, square(x + (0.5f - pivot) * side, y, side), {1, 1, 1, alpha}});
        }
    };
    const std::string& at = ui.judgment_error_placement;
    const float above = cy + cap / 2 + gap + ic / 2, below = cy - cap / 2 - gap - ic / 2;
    const float left = cx - width / 2 - gap, right = cx + width / 2 + gap;
    if (at == "left" || at == "leftRight") put(left, cy, 1);
    if (at == "right" || at == "leftRight") put(right, cy, 0);
    if (at == "bottom" || at == "topBottom") put(cx, below, 0.5f);
    if (at == "center") put(cx, cy, 0.5f);
    if (at == "top" || at == "topBottom" || at.empty()) put(cx, above, 0.5f);
}

}  // namespace

MetricView metric_view(const std::string& metric, const modes::Stats& s, float time, float length) {
    MetricView v;
    const int32_t judged = s.perfect + s.great + s.good + s.miss;
    auto rate = [&](int32_t n) { return judged == 0 ? 0.0f : float(n) / float(judged); };
    auto percent = [](float f) {
        char buf[32];
        std::snprintf(buf, sizeof buf, "%.2f%%", double(f) * 100.0);
        return std::string(buf);
    };
    auto millionths = [](float f) { return std::to_string(int64_t(double(f) * 1e6)); };
    const int32_t ggm = s.great + s.good + s.miss;
    if (metric == "arcade" || metric == "arcadePercentage") {
        v.icon = UiIconKind::Flame;
        v.fill = s.arcade;
        v.text = metric == "arcade" ? millionths(s.arcade) : percent(s.arcade);
    } else if (metric == "accuracy" || metric == "accuracyPercentage") {
        v.icon = UiIconKind::Target;
        v.fill = s.accuracy;
        v.text = metric == "accuracy" ? millionths(s.accuracy) : percent(s.accuracy);
    } else if (metric == "life") {
        v.icon = UiIconKind::Heart;
        v.fill = s.max_life > 0 ? s.life / s.max_life : 0.0f;
        v.text = std::to_string(int32_t(s.life));
    } else if (metric == "time") {
        v.icon = UiIconKind::Clock;
        v.fill = length > 0 ? time / length : 0.0f;
        v.text = format_time(time);
    } else if (metric == "perfect" || metric == "perfectPercentage") {
        v.icon = UiIconKind::Star;
        v.fill = rate(s.perfect);
        v.text = metric == "perfect" ? std::to_string(s.perfect) : percent(rate(s.perfect));
    } else if (metric == "greatGoodMiss" || metric == "greatGoodMissPercentage") {
        v.icon = UiIconKind::Cross;
        v.fill = rate(ggm);
        v.text = metric == "greatGoodMiss" ? std::to_string(ggm) : percent(rate(ggm));
    } else if (metric == "miss" || metric == "missPercentage") {
        v.icon = UiIconKind::Cross;
        v.fill = rate(s.miss);
        v.text = metric == "miss" ? std::to_string(s.miss) : percent(rate(s.miss));
    } else if (metric == "errorHeatmap") {
        v.icon = UiIconKind::Target;  // the heatmap itself is not drawn
    }
    v.fill = std::clamp(v.fill, 0.0f, 1.0f);
    return v;
}

UiOverlay build_hud(const modes::FrameOutput& frame, const data::Ui& ui, float time, float length) {
    UiOverlay o;
    const modes::Stats& s = frame.stats;
    const MetricView primary = metric_view(ui.primary_metric, s, time, length);
    const MetricView secondary = metric_view(ui.secondary_metric, s, time, length);
    for (const UiQuad& q : build_ui(frame, ui, time - s.judgment_time, time - s.combo_time)) {
        switch (q.widget) {
            case UiWidget::Menu: add_menu(o, q); break;
            case UiWidget::Judgment:
                if (s.judgment >= 1 && s.judgment <= 4) add_judgment(o, q, ui, s);
                break;
            case UiWidget::ComboValue:
                if (s.combo > 0) add_text_widget(o, q, std::to_string(s.combo), true);
                break;
            case UiWidget::ComboText:
                if (s.combo > 0) add_text_widget(o, q, "COMBO", true);
                break;
            case UiWidget::PrimaryBar: add_bar(o, q, primary); break;
            case UiWidget::PrimaryValue:
                if (!primary.text.empty()) add_text_widget(o, q, primary.text, false);
                break;
            case UiWidget::SecondaryBar: add_bar(o, q, secondary); break;
            case UiWidget::SecondaryValue:
                if (!secondary.text.empty()) add_text_widget(o, q, secondary.text, false);
                break;
            default: break;
        }
    }
    return o;
}

std::string format_time(float seconds) {
    const bool neg = seconds < 0;
    const int total = int(fm::floor(seconds < 0 ? -seconds : seconds));
    char buf[32];
    std::snprintf(buf, sizeof buf, "%s%d:%02d", neg ? "-" : "", total / 60, total % 60);
    return buf;
}

namespace {

// The bar's geometry for one aspect: px of the 1600 x 1068 recording to screen units.
struct BarGeometry {
    float aspect;
    float X(float px) const { return -aspect + px / kHalf; }      // px from the left edge
    float XR(float dist) const { return aspect - dist / kHalf; }  // px from the right edge
    static float Y(float py) { return (kHalf - py) / kHalf; }
    float track_l() const { return X(372); }
    float track_r() const { return XR(197); }
};

constexpr float kTrackTop = 979, kTrackBottom = 1032;
constexpr float kPauseTop = 986, kPauseBottom = 1026, kPauseL = 79, kPauseR = 45;  // px from the right edge
constexpr float kTickWidth = 6, kTickTop = 1019;  // a tick: the track's lower part, under the cursor (992 to 1018)
constexpr float kRepeatOffAlpha = 0.4f;
// The app's error colour (Material 3 dark error, F2B8B5) for a Miss, the result screen's Good green (6BD68A).
constexpr float kMissRgb[3] = {242.0f / 255, 184.0f / 255, 181.0f / 255};
constexpr float kGoodRgb[3] = {107.0f / 255, 214.0f / 255, 138.0f / 255};

// The repeat glyph: two opposed arrows as six 6 px strokes (from the 1600 px recording at x 1433 to 1481, 119 to 167
// px from the right edge), white at `alpha`.
void add_repeat(UiOverlay& o, const BarGeometry& g, float alpha) {
    // A line of thickness 6 px between two points, as a quad.
    auto line = [&](float x0, float y0, float x1, float y1) {
        const float dx = x1 - x0, dy = y1 - y0, len = fm::sqrt(dx * dx + dy * dy);
        const float nx = -dy / len * 3.0f / kHalf, ny = dx / len * 3.0f / kHalf;  // half of the 6 px stroke
        UiRect r;
        const float px[4] = {x0 + nx, x0 - nx, x1 - nx, x1 + nx}, py[4] = {y0 + ny, y0 - ny, y1 - ny, y1 + ny};
        for (int k = 0; k < 4; ++k) {
            r.screen.x[k] = px[k];
            r.screen.y[k] = py[k];
        }
        r.rgba[0] = r.rgba[1] = r.rgba[2] = 1;
        r.rgba[3] = alpha;
        o.rects.push_back(r);
    };
    auto seg = [&](float x0, float y0, float x1, float y1) {
        line(g.XR(1600.0f - x0), BarGeometry::Y(y0), g.XR(1600.0f - x1), BarGeometry::Y(y1));
    };
    seg(1434, 992, 1480, 992);
    seg(1466, 979, 1480, 992);
    seg(1466, 1006, 1480, 992);
    seg(1433, 1018, 1479, 1018);
    seg(1447, 1005, 1433, 1018);
    seg(1447, 1031, 1433, 1018);
}

// Left, bottom, right, top of rects [from, to).
void rect_bounds(const UiOverlay& o, size_t from, size_t to, float out[4]) {
    out[0] = out[1] = 1e30f;
    out[2] = out[3] = -1e30f;
    for (size_t i = from; i < to; ++i)
        for (int k = 0; k < 4; ++k) {
            out[0] = std::min(out[0], o.rects[i].screen.x[k]);
            out[1] = std::min(out[1], o.rects[i].screen.y[k]);
            out[2] = std::max(out[2], o.rects[i].screen.x[k]);
            out[3] = std::max(out[3], o.rects[i].screen.y[k]);
        }
}

}  // namespace

UiOverlay build_overlay(const WatchBar& bar, float aspect) {
    UiOverlay o;
    float a = 1;
    if (!bar.paused) {
        if (!(bar.since_shown >= 0) || bar.since_shown >= 3.0f) return o;
        if (bar.since_shown > 2.8f) a = 1.0f - (bar.since_shown - 2.8f) / 0.2f;
    }
    const BarGeometry g{aspect};
    auto X = [&](float px) { return g.X(px); };
    auto XR = [&](float dist) { return g.XR(dist); };
    auto Y = [](float py) { return BarGeometry::Y(py); };
    auto rect = [&](float l, float t, float r, float b, float grey, float al) {
        o.rects.push_back({rect_quad(l, Y(b), r, Y(t)), {grey, grey, grey, al * a}});
    };

    const float bar_l = X(24), bar_r = XR(22);
    rect(bar_l, 966, bar_r, 1046, 31.0f / 255, 1);  // note: the bar spans to 22 px from the right edge
    const float track_l = g.track_l(), track_r = g.track_r();
    rect(track_l, kTrackTop, track_r, kTrackBottom, 24.0f / 255, 1);
    // Cursor: 6 px wide, at the start of the track plus the progress along it.
    const float length = bar.length;
    const float progress = length > 0 ? std::clamp(bar.time / length, 0.0f, 1.0f) : 0.0f;
    const float cx = track_l + progress * (track_r - track_l - 6.0f / kHalf);
    rect(cx, 992, cx + 6.0f / kHalf, 1018, 1, 1);
    if (bar.paused) {
        // Play: a right-pointing triangle filling the pause bars' box (corners bl, tl, then the tip twice).
        const float l = XR(kPauseL), r = XR(kPauseR), t = Y(kPauseTop), b = Y(kPauseBottom), mid = (t + b) / 2;
        o.rects.push_back({Quad{{l, l, r, r}, {b, t, mid, mid}}, {1, 1, 1, a}});
    } else {
        // Pause: two bars 14 x 40 px, 6 apart, 45 px from the right edge.
        rect(XR(kPauseL), kPauseTop, XR(65), kPauseBottom, 1, 1);
        rect(XR(59), kPauseTop, XR(kPauseR), kPauseBottom, 1, 1);
    }
    add_repeat(o, g, a * (bar.repeat ? 1.0f : kRepeatOffAlpha));

    // Ticks, last: a tick within a tick's width of the last one of its kind adds nothing visible and is left out.
    if (length > 0) {
        const float w = kTickWidth / kHalf, span = track_r - track_l;
        float last_miss = -1e30f, last_good = -1e30f;
        for (const modes::WatchTick& tick : bar.ticks) {
            const float at = track_l + std::clamp(tick.time / length, 0.0f, 1.0f) * span;
            const float c = std::clamp(at, track_l + w / 2, track_r - w / 2);
            float& last = tick.miss ? last_miss : last_good;
            if (std::fabs(c - last) < w) continue;
            last = c;
            const float* rgb = tick.miss ? kMissRgb : kGoodRgb;
            o.rects.push_back({rect_quad(c - w / 2, Y(kTrackBottom), c + w / 2, Y(kTickTop)), {rgb[0], rgb[1], rgb[2], a}});
        }
    }

    UiText t;
    t.text = format_time(bar.time) + " / " + format_time(length);
    t.x = X(77);
    t.y = Y(1006);
    t.size = 42.0f / kHalf;
    t.rgba[0] = t.rgba[1] = t.rgba[2] = 1;
    t.rgba[3] = a;
    o.texts.push_back(std::move(t));
    return o;
}

WatchBarRects watch_bar_rects(float aspect) {
    const BarGeometry g{aspect};
    WatchBarRects r;
    r.track[0] = g.track_l();
    r.track[1] = BarGeometry::Y(kTrackBottom);
    r.track[2] = g.track_r();
    r.track[3] = BarGeometry::Y(kTrackTop);
    r.pause[0] = g.XR(kPauseL);
    r.pause[1] = BarGeometry::Y(kPauseBottom);
    r.pause[2] = g.XR(kPauseR);
    r.pause[3] = BarGeometry::Y(kPauseTop);
    UiOverlay repeat;
    add_repeat(repeat, g, 1);
    rect_bounds(repeat, 0, repeat.rects.size(), r.repeat);
    return r;
}

}  // namespace sr::render
