#include "render/gl_renderer.h"

#include <algorithm>
#include <chrono>
#include <cstddef>
#include <cstdio>
#include <cstring>

#include "render/shaders.h"
#include "vm/fmath.h"

namespace sr::render {

namespace {

constexpr GLsizei kVerticesPerQuad = 6;  // two triangles, corners 0-1-2 and 0-2-3 (shaders.h)

// The touch marker: a white ring `size` texels square, straight alpha, its outer edge at the square's edge and 0.16 of
// the radius thick, both edges anti-aliased over about a texel. The marker alpha is applied per quad.
data::Image ring_image(int32_t size) {
    data::Image image;
    image.width = image.height = size;
    image.rgba.resize(size_t(size) * size_t(size) * 4);
    const float r = float(size) / 2, outer = r - 1.0f, inner = outer * 0.84f;
    for (int32_t y = 0; y < size; ++y)
        for (int32_t x = 0; x < size; ++x) {
            const float dx = float(x) + 0.5f - r, dy = float(y) + 0.5f - r;
            const float d = fm::sqrt(dx * dx + dy * dy);
            const float in = std::clamp(d - inner + 0.5f, 0.0f, 1.0f), out = std::clamp(outer - d + 0.5f, 0.0f, 1.0f);
            uint8_t* p = image.rgba.data() + (size_t(y) * size_t(size) + size_t(x)) * 4;
            p[0] = p[1] = p[2] = 255;
            p[3] = uint8_t(std::min(in, out) * 255.0f + 0.5f);
        }
    return image;
}

}  // namespace

GlRenderer::~GlRenderer() {
    for (Texture* t : {&skin_, &particle_, &background_, &marker_}) {
        if (t->id != 0) glDeleteTextures(1, &t->id);
        if (t->sampler != 0) glDeleteSamplers(1, &t->sampler);
    }
    if (buffer_ != 0) glDeleteBuffers(1, &buffer_);
    if (frame_fbo_ != 0) glDeleteFramebuffers(1, &frame_fbo_);
    if (frame_rbo_ != 0) glDeleteRenderbuffers(1, &frame_rbo_);
    if (vao_ != 0) glDeleteVertexArrays(1, &vao_);
    if (solid_vao_ != 0) glDeleteVertexArrays(1, &solid_vao_);
    for (const GLuint p : {bilinear_, projective_, solid_})
        if (p != 0) glDeleteProgram(p);
}

bool GlRenderer::check(const char* where) {
    const GLenum e = glGetError();
    if (e == GL_NO_ERROR) return true;
    char text[64];
    std::snprintf(text, sizeof text, "GL error 0x%04x in %s", unsigned(e), where);
    if (error_.empty()) error_ = text;
    while (glGetError() != GL_NO_ERROR) {
    }
    return false;
}

bool GlRenderer::init(int32_t width, int32_t height) {
    width_ = width;
    height_ = height;
    if (width <= 0 || height <= 0) {
        error_ = "framebuffer size " + std::to_string(width) + " x " + std::to_string(height);
        return false;
    }
    for (GLuint* program : {&bilinear_, &projective_, &solid_}) {
        const char* vertex = program == &solid_ ? shaders::kSolidVertex : shaders::kQuadVertex;
        const char* fragment = program == &bilinear_     ? shaders::kBilinearFragment
                               : program == &projective_ ? shaders::kProjectiveFragment
                                                         : shaders::kSolidFragment;
        std::string message;
        *program = shaders::build_program(vertex, fragment, message);
        if (*program == 0) {
            error_ = message;
            return false;
        }
    }
    // Screen units to clip space: x in [-aspect, aspect], y in [-1, 1].
    const float aspect = float(width) / float(height);
    for (const GLuint program : {bilinear_, projective_}) {
        glUseProgram(program);
        glUniform2f(glGetUniformLocation(program, "u_scale"), 1.0f / aspect, 1.0f);
        glUniform2f(glGetUniformLocation(program, "u_viewport"), float(width), float(height));
        glUniform1i(glGetUniformLocation(program, "u_tex"), 0);
    }
    solid_color_ = glGetUniformLocation(solid_, "u_color");

    glGenVertexArrays(1, &solid_vao_);  // no attributes: the solid triangle comes from gl_VertexID
    glGenVertexArrays(1, &vao_);
    glGenBuffers(1, &buffer_);
    glBindVertexArray(vao_);
    glBindBuffer(GL_ARRAY_BUFFER, buffer_);
    for (const GLuint location :
         {shaders::kAttribX, shaders::kAttribY, shaders::kAttribUv, shaders::kAttribQ, shaders::kAttribA}) {
        glEnableVertexAttribArray(location);
        glVertexAttribDivisor(location, 1);
    }

    // The offscreen frame: RGBA8 UNORM, no MSAA. See the class comment for why it exists.
    glGenRenderbuffers(1, &frame_rbo_);
    glBindRenderbuffer(GL_RENDERBUFFER, frame_rbo_);
    glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, width, height);
    glGenFramebuffers(1, &frame_fbo_);
    glBindFramebuffer(GL_FRAMEBUFFER, frame_fbo_);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, frame_rbo_);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
        error_ = "offscreen framebuffer incomplete";
        return false;
    }

    glViewport(0, 0, width, height);
    glDisable(GL_DITHER);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_STENCIL_TEST);
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_CULL_FACE);
    glEnable(GL_BLEND);
    // Straight alpha in gamma space. Destination alpha stays as cleared (1), so the surface stays opaque.
    glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);
    glBlendEquation(GL_FUNC_ADD);
    check("init");
    return error_.empty();
}

void GlRenderer::upload_texture(Texture& texture, const data::Image& image, bool linear, const char* what) {
    if (texture.id != 0) glDeleteTextures(1, &texture.id);
    if (texture.sampler != 0) glDeleteSamplers(1, &texture.sampler);
    texture = Texture{};
    if (image.width <= 0 || image.height <= 0) return;
    GLint max_size = 0;
    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &max_size);
    if (image.width > max_size || image.height > max_size) {
        if (error_.empty())
            error_ = std::string(what) + " is " + std::to_string(image.width) + " x " + std::to_string(image.height) +
                     ", above the GPU's " + std::to_string(max_size);
        return;
    }
    glGenTextures(1, &texture.id);
    glBindTexture(GL_TEXTURE_2D, texture.id);
    // Straight-alpha RGBA8 (UNORM, no sRGB decode), one level: no mipmaps.
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, image.width, image.height);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, image.width, image.height, GL_RGBA, GL_UNSIGNED_BYTE, image.rgba.data());
    texture.width = image.width;
    texture.height = image.height;

    glGenSamplers(1, &texture.sampler);
    const GLint filter = linear ? GL_LINEAR : GL_NEAREST;
    glSamplerParameteri(texture.sampler, GL_TEXTURE_MIN_FILTER, filter);
    glSamplerParameteri(texture.sampler, GL_TEXTURE_MAG_FILTER, filter);
    glSamplerParameteri(texture.sampler, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glSamplerParameteri(texture.sampler, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    check(what);
}

void GlRenderer::upload(const data::LoadedLevel& level, float aspect) {
    // Render mode from the engine (sonolus.py RenderMode: standard = bilinear interpolation of textures,
    // lightweight = projective). "default" is taken as lightweight.
    lightweight_ = level.watch.render_mode != "standard";

    upload_texture(skin_, level.skin_texture, level.skin.interpolation, "skin.texture");
    upload_texture(particle_, level.particle_texture, level.particle.interpolation, "particle.texture");

    const auto start = std::chrono::steady_clock::now();
    const data::Image blurred = background_image(level.background_image, level.background_configuration, aspect);
    blur_ms_ = level.background_configuration.blur > 0
                   ? std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count()
                   : 0;
    upload_texture(background_, blurred, true, "background.image");  // Always GL_LINEAR
    // About twice the marker's on-screen size in texels on a 1600 px tall screen (0.09 of the half height of 1600 px is 72 px).
    upload_texture(marker_, ring_image(128), true, "marker");
}

void GlRenderer::append(const SpriteQuad& quad, const Texture& texture, QuadMapping mapping) {
    Instance in;
    std::memcpy(in.x, quad.screen.x, sizeof in.x);
    std::memcpy(in.y, quad.screen.y, sizeof in.y);
    const float tw = float(texture.width), th = float(texture.height);
    in.uv[0] = quad.u0 / tw;
    in.uv[1] = quad.v0 / th;
    in.uv[2] = quad.u1 / tw;
    in.uv[3] = quad.v1 / th;
    if (!corner_weights(quad.screen, mapping, in.q)) return;  // nothing is drawn for these
    for (int k = 0; k < 3; ++k) in.color[k] = quad.color[k];
    in.color[3] = quad.a;
    instances_.push_back(in);
}

void GlRenderer::draw_instances(GLuint program, const Texture& texture, size_t first, size_t count) {
    if (count == 0 || texture.id == 0) return;
    glUseProgram(program);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, texture.id);
    glBindSampler(0, texture.sampler);
    glBindVertexArray(vao_);
    glBindBuffer(GL_ARRAY_BUFFER, buffer_);
    const auto at = [&](size_t offset) {
        return reinterpret_cast<const void*>(first * sizeof(Instance) + offset);
    };
    constexpr GLsizei stride = sizeof(Instance);
    glVertexAttribPointer(shaders::kAttribX, 4, GL_FLOAT, GL_FALSE, stride, at(offsetof(Instance, x)));
    glVertexAttribPointer(shaders::kAttribY, 4, GL_FLOAT, GL_FALSE, stride, at(offsetof(Instance, y)));
    glVertexAttribPointer(shaders::kAttribUv, 4, GL_FLOAT, GL_FALSE, stride, at(offsetof(Instance, uv)));
    glVertexAttribPointer(shaders::kAttribQ, 4, GL_FLOAT, GL_FALSE, stride, at(offsetof(Instance, q)));
    glVertexAttribPointer(shaders::kAttribA, 4, GL_FLOAT, GL_FALSE, stride, at(offsetof(Instance, color)));
    glDrawArraysInstanced(GL_TRIANGLES, 0, kVerticesPerQuad, GLsizei(count));
}

void GlRenderer::draw(const std::vector<SpriteQuad>& sprites, const BackgroundQuad& background,
                      std::span<const SpriteQuad> particles, std::span<const SpriteQuad> ui,
                      std::span<const modes::ReplayMarker> markers) {
    glBindFramebuffer(GL_FRAMEBUFFER, frame_fbo_);
    // The screen is cleared to the background colour (opaque: the surface is opaque).
    glClearColor(background.color[0] / 255.0f, background.color[1] / 255.0f, background.color[2] / 255.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    instances_.clear();
    Instance bg;
    std::memcpy(bg.x, background.screen.x, sizeof bg.x);
    std::memcpy(bg.y, background.screen.y, sizeof bg.y);
    // Corner i maps to (u[i], v[i]): u0 on the left edge, v0 on the top edge (BackgroundQuad, shaders.h).
    bg.uv[0] = background.u[0];
    bg.uv[1] = background.v[1];
    bg.uv[2] = background.u[2];
    bg.uv[3] = background.v[0];
    std::memcpy(bg.q, background.q, sizeof bg.q);
    for (float& c : bg.color) c = 1;
    instances_.push_back(bg);
    const size_t sprites_from = instances_.size();
    const QuadMapping sprite_map = sprite_mapping(lightweight_);
    for (const SpriteQuad& s : sprites) append(s, skin_, sprite_map);
    const size_t particles_from = instances_.size();
    for (const SpriteQuad& s : particles) append(s, particle_, particle_mapping());
    const size_t markers_from = instances_.size();
    for (const modes::ReplayMarker& m : markers) {
        constexpr float h = kMarkerSize / 2;
        SpriteQuad q{};
        q.screen = Quad{{m.x - h, m.x - h, m.x + h, m.x + h}, {m.y - h, m.y + h, m.y + h, m.y - h}};
        q.u0 = 0;
        q.v0 = 0;
        q.u1 = float(marker_.width);
        q.v1 = float(marker_.height);
        q.a = kMarkerAlpha;
        append(q, marker_, particle_mapping());
    }
    const size_t ui_from = instances_.size();
    for (const SpriteQuad& s : ui) append(s, skin_, sprite_map);

    glBindBuffer(GL_ARRAY_BUFFER, buffer_);
    const size_t bytes = instances_.size() * sizeof(Instance);
    if (instances_.size() > buffer_capacity_) buffer_capacity_ = instances_.size() * 2;
    // New storage each frame (orphaning), so the GPU can still read the last frame's.
    glBufferData(GL_ARRAY_BUFFER, GLsizeiptr(buffer_capacity_ * sizeof(Instance)), nullptr, GL_STREAM_DRAW);
    glBufferSubData(GL_ARRAY_BUFFER, 0, GLsizeiptr(bytes), instances_.data());

    draw_instances(projective_, background_, 0, 1);
    if (background.mask[3] != 0) {
        glUseProgram(solid_);
        glUniform4f(solid_color_, background.mask[0] / 255.0f, background.mask[1] / 255.0f,
                    background.mask[2] / 255.0f, background.mask[3] / 255.0f);
        // Its own empty VAO: the quad VAO's instanced arrays may be unset (no background image drawn yet).
        glBindVertexArray(solid_vao_);
        glDrawArrays(GL_TRIANGLES, 0, 3);
    }
    const GLuint sprite_program = lightweight_ ? projective_ : bilinear_;
    draw_instances(sprite_program, skin_, sprites_from, particles_from - sprites_from);
    // Particles use the same straight-alpha blend as the sprites. Additive blending was tried and made hit
    // effects far too bright.
    // Their weights are 1, so the projective program maps them affinely per triangle in either render mode.
    draw_instances(projective_, particle_, particles_from, markers_from - particles_from);
    draw_instances(projective_, marker_, markers_from, ui_from - markers_from);  // weights 1: affine, as particles
    draw_instances(sprite_program, skin_, ui_from, instances_.size() - ui_from);

    // Onto the EGL surface: a 1:1 copy (no filtering at this scale).
    glBindFramebuffer(GL_READ_FRAMEBUFFER, frame_fbo_);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
    glBlitFramebuffer(0, 0, width_, height_, 0, 0, width_, height_, GL_COLOR_BUFFER_BIT, GL_NEAREST);
    check("draw");
}

bool GlRenderer::read_rgba(std::vector<uint8_t>& out) {
    const size_t row = size_t(width_) * 4;
    std::vector<uint8_t> bottom_up(row * size_t(height_));
    glBindFramebuffer(GL_READ_FRAMEBUFFER, frame_fbo_);
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glReadPixels(0, 0, width_, height_, GL_RGBA, GL_UNSIGNED_BYTE, bottom_up.data());
    if (!check("read_rgba")) return false;
    out.resize(bottom_up.size());
    for (int32_t y = 0; y < height_; ++y)
        std::memcpy(out.data() + size_t(y) * row, bottom_up.data() + size_t(height_ - 1 - y) * row, row);
    return true;
}

}  // namespace sr::render
