#pragma once

#include <GLES3/gl3.h>

#include <cstdint>
#include <span>
#include <string>
#include <vector>

#include "data/resources.h"
#include "modes/replay_view.h"
#include "render/background_layout.h"
#include "render/geometry.h"

namespace sr::render {

// Draws a Watch frame with OpenGL ES 3 into the current framebuffer (the EGL window surface, or an offscreen
// framebuffer for render-at-time). Every method needs the GL context current on the calling thread, from init()
// until the destructor.
//
// One RGBA8 UNORM framebuffer, no MSAA, no sRGB encode or decode; straight-alpha textures; fragment = texel *
// (1, 1, 1, a) with a unclamped; blend SRC_ALPHA, ONE_MINUS_SRC_ALPHA for colour (destination alpha is kept at 1).
// GL_LINEAR when the skin's (particle's) `interpolation` is on, GL_NEAREST when off; no mipmaps;
// GL_CLAMP_TO_EDGE; sprites sample their atlas rect with no inset, so bilinear filtering bleeds into neighbours.
// Clear to the background colour, the (blurred) image projectively into its quad (GL_LINEAR), the mask over the
// whole screen; then the sprites.
// Coverage: every frame is drawn into an offscreen RGBA8 framebuffer and copied 1:1 to the EGL surface. On Adreno,
// drawing straight into the window surface resolves a pixel centre that lies exactly on a horizontal sprite edge
// the other way (top edge in, bottom edge out); the offscreen frame resolves it top out, bottom in.
class GlRenderer {
public:
    GlRenderer() = default;
    ~GlRenderer();
    GlRenderer(const GlRenderer&) = delete;
    GlRenderer& operator=(const GlRenderer&) = delete;

    // Builds the programs and buffers for a width x height framebuffer. False with error() set on failure.
    bool init(int32_t width, int32_t height);

    // Uploads the skin, particle and background textures and takes the render mode from the engine's watch data.
    // The background image is blurred first (render::background_image) with `aspect`, Runtime Environment's
    // aspect ratio. Sets error() when a texture
    // is larger than the GPU allows (that texture is then not drawn).
    void upload(const data::LoadedLevel& level, float aspect);

    // One frame, back to front: background (clear, image, mask), skin sprites in the given order (later on top),
    // particles (particle texture, its own interpolation flag), Watch's replay touch markers (a ring
    // kMarkerSize across, white at kMarkerAlpha, centred on each), built-in UI (skin texture until the built-in UI
    // has its own), into the offscreen frame, then copied to framebuffer 0 (the EGL surface). A GL error
    // sets error().
    void draw(const std::vector<SpriteQuad>& sprites, const BackgroundQuad& background,
              std::span<const SpriteQuad> particles, std::span<const SpriteQuad> ui,
              std::span<const modes::ReplayMarker> markers = {});

    // The touch marker's look (saved replays, design section 5; the builder's call): a ring a bit larger than a
    // fingertip, semi-transparent white.
    static constexpr float kMarkerSize = 0.09f;  // screen units across
    static constexpr float kMarkerAlpha = 0.55f;

    // The last drawn frame's pixels (offscreen) as RGBA8, the screen's top row first. False on a GL error.
    bool read_rgba(std::vector<uint8_t>& out);

    // The last failure (empty when none); clear_error() resets it.
    const std::string& error() const { return error_; }
    void clear_error() { error_.clear(); }

    // The render mode in use: true for lightweight (projective sprite mapping), false for standard (bilinear).
    bool lightweight() const { return lightweight_; }
    // Milliseconds upload() spent blurring the background image (0 when blur is off).
    double blur_ms() const { return blur_ms_; }

private:
    struct Texture {
        GLuint id = 0;
        int32_t width = 0, height = 0;
        GLuint sampler = 0;
    };
    // One quad's instance attributes (shaders.h): x[4], y[4], uv[4], q[4], color (rgb, a).
    struct Instance {
        float x[4], y[4], uv[4], q[4], color[4];
    };

    void upload_texture(Texture& texture, const data::Image& image, bool linear, const char* what);
    void append(const SpriteQuad& quad, const Texture& texture, QuadMapping mapping);
    void draw_instances(GLuint program, const Texture& texture, size_t first, size_t count);
    // False, recording the first error in error_, when GL reported one since the last check.
    bool check(const char* where);

    int32_t width_ = 0, height_ = 0;
    GLuint bilinear_ = 0, projective_ = 0, solid_ = 0;
    GLint solid_color_ = -1;
    GLuint vao_ = 0, buffer_ = 0;
    GLuint solid_vao_ = 0;  // empty, for the mask's full-screen triangle
    GLuint frame_fbo_ = 0, frame_rbo_ = 0;  // the offscreen frame
    size_t buffer_capacity_ = 0;  // instances
    Texture skin_, particle_, background_, marker_;
    bool lightweight_ = false;
    double blur_ms_ = 0;
    std::vector<Instance> instances_;
    std::string error_;
};

}  // namespace sr::render
