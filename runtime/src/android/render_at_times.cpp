#include "android/render_at_times.h"

#include <EGL/egl.h>
#include <android/log.h>

#include <algorithm>
#include <cstdio>
#include <map>
#include <memory>

#include "miniz.h"
#include "miniz_tdef.h"
#include "particle/particle_system.h"
#include "render/background_layout.h"
#include "render/geometry.h"
#include "render/gl_renderer.h"
#include "time/frame_clock.h"
#include "time/frame_time.h"

namespace sr::android {

namespace {

constexpr double kRenderStep = 1.0 / 120;  // the stepping grid

// An EGL ES 3 context on a 1 x 1 pbuffer, current on the calling thread while it lives. The renderer draws into its
// own offscreen frame, so the pbuffer only gives the context a surface.
struct OffscreenContext {
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface surface = EGL_NO_SURFACE;

    std::string create() {
        display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (display == EGL_NO_DISPLAY || eglInitialize(display, nullptr, nullptr) != EGL_TRUE)
            return "eglInitialize failed";
        const EGLint attribs[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
                                  EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE};
        EGLConfig config = nullptr;
        EGLint count = 0;
        if (eglChooseConfig(display, attribs, &config, 1, &count) != EGL_TRUE || count < 1)
            return "eglChooseConfig failed";
        const EGLint context_attribs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
        context = eglCreateContext(display, config, EGL_NO_CONTEXT, context_attribs);
        if (context == EGL_NO_CONTEXT) return "eglCreateContext failed";
        const EGLint surface_attribs[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
        surface = eglCreatePbufferSurface(display, config, surface_attribs);
        if (surface == EGL_NO_SURFACE) return "eglCreatePbufferSurface failed";
        if (eglMakeCurrent(display, surface, surface, context) != EGL_TRUE) return "eglMakeCurrent failed";
        return {};
    }

    ~OffscreenContext() {
        if (display == EGL_NO_DISPLAY) return;
        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (surface != EGL_NO_SURFACE) eglDestroySurface(display, surface);
        if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context);
        eglReleaseThread();
    }
};

bool write_file(const std::filesystem::path& path, const void* data, size_t size) {
    std::FILE* f = std::fopen(path.string().c_str(), "wb");
    bool ok = f != nullptr && std::fwrite(data, 1, size, f) == size;
    if (f != nullptr) ok = std::fclose(f) == 0 && ok;
    return ok;
}

// One PNG (RGB, fast deflate) of an RGBA frame, top row first.
std::string write_png(const std::filesystem::path& path, const std::vector<uint8_t>& rgba, int32_t w, int32_t h) {
    std::vector<uint8_t> rgb(size_t(w) * size_t(h) * 3);
    for (size_t i = 0, n = size_t(w) * size_t(h); i < n; ++i) {
        rgb[3 * i] = rgba[4 * i];
        rgb[3 * i + 1] = rgba[4 * i + 1];
        rgb[3 * i + 2] = rgba[4 * i + 2];
    }
    size_t size = 0;
    void* png = tdefl_write_image_to_png_file_in_memory_ex(rgb.data(), w, h, 3, &size, MZ_BEST_SPEED, MZ_FALSE);
    if (png == nullptr) return "PNG encoding failed";
    const bool ok = write_file(path, png, size);
    mz_free(png);
    return ok ? std::string() : "cannot write " + path.string();
}

// The time in a file name: up to 6 decimals, so the PC can read it back.
std::string time_label(float t) {
    char text[32];
    std::snprintf(text, sizeof text, "%.6f", double(t));
    return text;
}

// The particle effect instances the session has spawned and not yet ended: a non-looped instance lives for its
// duration from its spawn time, a looped one until DestroyParticleEffect. (Bookkeeping for the PC only; the particle
// zones' sidecar; the drawn particles come from particle::ParticleSystem.)
struct ParticleInstance {
    int32_t effect = -1;
    float x[4] = {}, y[4] = {};
    double spawned = 0;
    float duration = 0;
    bool looped = false;
};

void track_particles(std::map<float, ParticleInstance>& alive, const modes::FrameOutput& out, double t) {
    for (const modes::ParticleCmd& c : out.particles) {
        if (c.op == modes::ParticleCmd::Spawn) {
            ParticleInstance p;
            p.effect = c.effect;
            std::copy(c.x, c.x + 4, p.x);
            std::copy(c.y, c.y + 4, p.y);
            p.spawned = t;
            p.duration = c.duration;
            p.looped = c.looped;
            alive[c.handle] = p;
        } else if (c.op == modes::ParticleCmd::Move) {
            if (auto it = alive.find(c.handle); it != alive.end()) {
                std::copy(c.x, c.x + 4, it->second.x);
                std::copy(c.y, c.y + 4, it->second.y);
            }
        } else {
            alive.erase(c.handle);
        }
    }
    for (auto it = alive.begin(); it != alive.end();) {
        const bool ended = !it->second.looped && t > it->second.spawned + double(it->second.duration);
        it = ended ? alive.erase(it) : std::next(it);
    }
}

std::string particles_json(const std::map<float, ParticleInstance>& alive, const float transform[16], double t) {
    std::string s = "{\"transform\":[";
    char num[48];
    for (int i = 0; i < 16; ++i) {
        std::snprintf(num, sizeof num, "%s%.9g", i ? "," : "", double(transform[i]));
        s += num;
    }
    s += "],\"instances\":[";
    bool first = true;
    for (const auto& [handle, p] : alive) {
        s += first ? "{" : ",{";
        first = false;
        std::snprintf(num, sizeof num, "\"effect\":%d,\"age\":%.6f,", p.effect, t - p.spawned);
        s += num;
        std::snprintf(num, sizeof num, "\"duration\":%.6g,\"looped\":%s,", double(p.duration),
                      p.looped ? "true" : "false");
        s += num;
        for (const char* axis : {"x", "y"}) {
            s += std::string("\"") + axis + "\":[";
            const float* v = axis[0] == 'x' ? p.x : p.y;
            for (int i = 0; i < 4; ++i) {
                std::snprintf(num, sizeof num, "%s%.9g", i ? "," : "", double(v[i]));
                s += num;
            }
            s += axis[0] == 'x' ? "]," : "]";
        }
        s += "}";
    }
    return s + "]}\n";
}

}  // namespace

std::string render_at_times(const data::LoadedLevel& level, const modes::WatchConfig& config,
                            const std::vector<float>& times, const std::filesystem::path& out_dir) {
    std::error_code ec;
    std::filesystem::create_directories(out_dir, ec);
    if (ec) return "cannot create " + out_dir.string() + ": " + ec.message();
    OffscreenContext gl;
    if (std::string e = gl.create(); !e.empty()) return e;
    render::GlRenderer renderer;
    if (!renderer.init(kRenderWidth, kRenderHeight)) return "renderer: " + renderer.error();
    renderer.upload(level, config.env.aspect);
    if (!renderer.error().empty()) return "renderer: " + renderer.error();
    std::unique_ptr<modes::Session> session = modes::WatchSession::create(level, config);
    session->prepare();
    if (session->faulted()) return "The level stopped: " + session->fault().detail;

    std::vector<size_t> order(times.size());
    for (size_t i = 0; i < order.size(); ++i) order[i] = i;
    std::stable_sort(order.begin(), order.end(), [&](size_t a, size_t b) { return times[a] < times[b]; });

    bool stepped = false;
    double last = 0;
    const modes::FrameOutput* out = nullptr;
    std::map<float, ParticleInstance> particles;
    particle::ParticleSystem particle_system(level.particle, particle::kParticleSeed);
    const auto step_to = [&](double t) {
        time::FrameTime ft;
        ft.time = float(t);
        ft.delta = stepped ? float(t - last) : 0.0f;
        ft.scaled = session->times().time_to_scaled_time(ft.time);
        ft.skip = false;
        out = &session->step(ft);
        track_particles(particles, *out, t);
        for (const modes::ParticleCmd& cmd : out->particles) particle_system.apply(cmd, ft.time);
        last = t;
        stepped = true;
    };
    // The grid starts where live playback starts (-kPreRoll), so a level renders its negative times too.
    int64_t k = 0;  // the next grid point is -kPreRoll + k * kRenderStep
    const auto grid = [&](int64_t i) { return -time::kPreRoll + double(i) * kRenderStep; };
    std::vector<uint8_t> rgba;
    for (const size_t index : order) {
        const double target = times[index];
        for (; grid(k) < target; ++k) {
            if (!stepped || grid(k) > last) step_to(grid(k));
            if (session->faulted()) return "The level stopped: " + session->fault().detail;
        }
        if (!stepped || target > last) step_to(target);
        if (session->faulted()) return "The level stopped: " + session->fault().detail;
        const std::vector<render::SpriteQuad> sprites = render::build_sprite_list(*out, level.skin);
        const render::BackgroundQuad background =
            render::layout_background(level.background, level.background_configuration, out->background);
        std::vector<render::SpriteQuad> particle_quads;
        particle_system.emit(float(last), out->particle_transform, particle_quads);
        renderer.draw(sprites, background, particle_quads, {});  // no built-in UI
        if (!renderer.read_rgba(rgba)) return "renderer: " + renderer.error();
        const std::string stem = std::to_string(index) + "_" + time_label(times[index]);
        if (std::string e = write_png(out_dir / (stem + ".png"), rgba, kRenderWidth, kRenderHeight); !e.empty())
            return e;
        const std::string json = particles_json(particles, out->particle_transform, last);
        if (!write_file(out_dir / (stem + ".particles.json"), json.data(), json.size()))
            return "cannot write " + (out_dir / (stem + ".particles.json")).string();
    }
    __android_log_print(ANDROID_LOG_INFO, "sairitsu_runtime", "Rendered %zu frames into %s", times.size(),
                        out_dir.string().c_str());
    return {};
}

}  // namespace sr::android
