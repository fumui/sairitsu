#include "render/shaders.h"

#include <vector>

namespace sr::render::shaders {

const char* const kQuadVertex = R"(#version 300 es
precision highp float;
precision highp int;
layout(location = 0) in vec4 a_x;
layout(location = 1) in vec4 a_y;
layout(location = 2) in vec4 a_uv;
layout(location = 3) in vec4 a_q;
layout(location = 4) in vec4 a_color;
uniform vec2 u_scale;
uniform vec2 u_viewport;
flat out vec4 v_p01;
flat out vec4 v_p23;
flat out vec4 v_uv;
flat out vec4 v_color;
out vec3 v_proj;
const int kCorner[6] = int[6](0, 1, 2, 0, 2, 3);
void main() {
    int c = kCorner[gl_VertexID];
    gl_Position = vec4(vec2(a_x[c], a_y[c]) * u_scale, 0.0, 1.0);
    // The corners in window pixels (pixel centres at +0.5), where the rasteriser places gl_FragCoord.
    vec4 px = (a_x * u_scale.x + 1.0) * (0.5 * u_viewport.x);
    vec4 py = (a_y * u_scale.y + 1.0) * (0.5 * u_viewport.y);
    v_p01 = vec4(px[0], py[0], px[1], py[1]);
    v_p23 = vec4(px[2], py[2], px[3], py[3]);
    v_uv = a_uv;
    v_color = a_color;
    float s = (c == 2 || c == 3) ? 1.0 : 0.0;  // 0 on the left edge, 1 on the right
    float t = (c == 1 || c == 2) ? 1.0 : 0.0;  // 0 on the bottom edge, 1 on the top
    vec2 uv = vec2(mix(a_uv.x, a_uv.z, s), mix(a_uv.w, a_uv.y, t));
    v_proj = vec3(uv * a_q[c], a_q[c]);
}
)";

// P(s, t) = p0 + s e + t f + s t g with e = p3 - p0, f = p1 - p0, g = p0 - p1 + p2 - p3 (s left to right, t bottom to
// top). For a pixel h = p - p0: h - t f = s (e + t g); crossing with (e + t g) gives k2 t^2 + k1 t + k0 = 0 with
// k2 = g x f, k1 = e x f + h x g, k0 = h x e. Both roots come from the stable form (q = -(k1 + sign(k1) sqrt(D)) / 2,
// t = k0 / q and q / k2), which also covers k2 = 0 (parallelograms); the root with (s, t) inside the unit square wins.
const char* const kBilinearFragment = R"(#version 300 es
precision highp float;
flat in vec4 v_p01;
flat in vec4 v_p23;
flat in vec4 v_uv;
flat in vec4 v_color;
in vec3 v_proj;
uniform sampler2D u_tex;
out vec4 o_color;
float cross2(vec2 a, vec2 b) { return a.x * b.y - a.y * b.x; }
float solve_s(vec2 h, vec2 e, vec2 f, vec2 g, float t) {
    vec2 d = e + g * t;
    vec2 n = h - f * t;
    if (abs(d.x) >= abs(d.y)) return d.x != 0.0 ? n.x / d.x : 0.0;
    return n.y / d.y;
}
float outside(vec2 st) {
    vec2 o = max(-st, st - 1.0);
    return max(max(o.x, o.y), 0.0);
}
void main() {
    vec2 p0 = v_p01.xy, p1 = v_p01.zw, p2 = v_p23.xy, p3 = v_p23.zw;
    vec2 e = p3 - p0, f = p1 - p0, g = p0 - p1 + p2 - p3, h = gl_FragCoord.xy - p0;
    float k2 = cross2(g, f);
    float k1 = cross2(e, f) + cross2(h, g);
    float k0 = cross2(h, e);
    float root = sqrt(max(k1 * k1 - 4.0 * k0 * k2, 0.0));
    float q = -0.5 * (k1 + (k1 >= 0.0 ? root : -root));
    float ta = q != 0.0 ? k0 / q : 0.0;
    vec2 st = vec2(solve_s(h, e, f, g, ta), ta);
    if (k2 != 0.0) {
        float tb = q / k2;
        vec2 sb = vec2(solve_s(h, e, f, g, tb), tb);
        if (outside(sb) < outside(st)) st = sb;
    }
    // A fuller standard mode would extend the map past the unit square on non-convex, degenerate and bow-tie
    // quads; ours clamps. Owner: the render follow-ups task.
    st = clamp(st, 0.0, 1.0);
    vec2 uv = vec2(mix(v_uv.x, v_uv.z, st.x), mix(v_uv.w, v_uv.y, st.y));
    o_color = texture(u_tex, uv) * v_color;
}
)";

const char* const kProjectiveFragment = R"(#version 300 es
precision highp float;
flat in vec4 v_p01;
flat in vec4 v_p23;
flat in vec4 v_uv;
flat in vec4 v_color;
in vec3 v_proj;
uniform sampler2D u_tex;
out vec4 o_color;
void main() {
    o_color = texture(u_tex, v_proj.xy / v_proj.z) * v_color;
}
)";

const char* const kSolidVertex = R"(#version 300 es
precision highp float;
void main() {
    // One triangle over the whole clip square.
    vec2 p = vec2(gl_VertexID == 1 ? 3.0 : -1.0, gl_VertexID == 2 ? 3.0 : -1.0);
    gl_Position = vec4(p, 0.0, 1.0);
}
)";

const char* const kSolidFragment = R"(#version 300 es
precision highp float;
uniform vec4 u_color;
out vec4 o_color;
void main() { o_color = u_color; }
)";

namespace {

std::string info_log(GLuint object, bool program) {
    GLint length = 0;
    if (program) glGetProgramiv(object, GL_INFO_LOG_LENGTH, &length);
    else glGetShaderiv(object, GL_INFO_LOG_LENGTH, &length);
    std::vector<char> log(size_t(length > 0 ? length : 1), '\0');
    if (program) glGetProgramInfoLog(object, GLsizei(log.size()), nullptr, log.data());
    else glGetShaderInfoLog(object, GLsizei(log.size()), nullptr, log.data());
    return log.data();
}

GLuint compile(GLenum type, const char* source, std::string& error) {
    const GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint ok = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (ok != GL_TRUE) {
        error = std::string(type == GL_VERTEX_SHADER ? "vertex" : "fragment") + " shader: " + info_log(shader, false);
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

}  // namespace

GLuint build_program(const char* vertex, const char* fragment, std::string& error) {
    const GLuint vs = compile(GL_VERTEX_SHADER, vertex, error);
    if (vs == 0) return 0;
    const GLuint fs = compile(GL_FRAGMENT_SHADER, fragment, error);
    if (fs == 0) {
        glDeleteShader(vs);
        return 0;
    }
    const GLuint program = glCreateProgram();
    glAttachShader(program, vs);
    glAttachShader(program, fs);
    glLinkProgram(program);
    glDeleteShader(vs);
    glDeleteShader(fs);
    GLint ok = GL_FALSE;
    glGetProgramiv(program, GL_LINK_STATUS, &ok);
    if (ok != GL_TRUE) {
        error = "link: " + info_log(program, true);
        glDeleteProgram(program);
        return 0;
    }
    return program;
}

}  // namespace sr::render::shaders
