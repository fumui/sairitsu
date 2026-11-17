#pragma once

#include <GLES3/gl3.h>

#include <string>

namespace sr::render::shaders {

// GLSL ES 3.00 programs of the GLES3 renderer (gl_renderer.h). Every quad is one instance of six vertices (two
// triangles, corners 0-1-2 and 0-2-3; corners bottom-left, top-left, top-right, bottom-right) with these per-instance
// attributes, in this order and location:
//   0 a_x   vec4   corner x in screen units
//   1 a_y   vec4   corner y
//   2 a_uv  vec4   u0, v0, u1, v1, normalised texture coordinates: u0 along the left edge (corners 0, 1), u1 the
//                  right; v0 along the top edge (corners 1, 2), v1 the bottom
//   3 a_q   vec4   per-corner projective weights (render/background_layout.h projective_weights)
//   4 a_color vec4 rgb multiplier and alpha (alpha unclamped; rgb 1 for skin sprites, the colour for particles)
// Uniforms: u_scale (screen units to clip space, (1 / aspect, 1)), u_viewport (framebuffer size in pixels), u_tex.
inline constexpr GLuint kAttribX = 0, kAttribY = 1, kAttribUv = 2, kAttribQ = 3, kAttribA = 4;

// The quad vertex shader shared by both sprite programs.
extern const char* const kQuadVertex;
// Standard render mode: bilinear quad mapping. Each fragment inverts the bilinear map of its quad's corners at
// the pixel centre (closed-form quadratic, the root inside [0, 1]) and samples there.
extern const char* const kBilinearFragment;
// Projective mapping: (u q, v q, q) interpolated in screen space, sampled at (s / q, t / q). The background and
// lightweight-mode sprites.
extern const char* const kProjectiveFragment;
// A full-screen triangle of one colour (u_color, straight alpha): the background mask.
extern const char* const kSolidVertex;
extern const char* const kSolidFragment;

// Compiles and links a program, binding the attribute locations above. Returns 0 with `error` set on failure.
GLuint build_program(const char* vertex, const char* fragment, std::string& error);

}  // namespace sr::render::shaders
