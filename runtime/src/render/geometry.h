#pragma once

#include <cstdint>
#include <vector>

#include "data/skin.h"
#include "modes/presentation.h"

namespace sr::render {

// Screen space: (0, 0) at the screen centre, y from -1 to 1 over the height, x from
// -aspect to aspect. Corners bottom-left, top-left, top-right, bottom-right.
struct Quad {
    float x[4], y[4];
};

// The skin data's per-sprite transform: output coordinate r (x1, y1, x2, y2, ...) is the sum over the inputs
// x1..x4, y1..y4 of coef[r][column] * input, summed in that column order in float32.
Quad apply_sprite_transform(const data::SkinTransform& t, const Quad& q);

// Runtime Skin Transform, row-major 4 x 4 applied to (x, y, 0, 1) : x' = m0 x + m1 y + m3,
// y' = m4 x + m5 y + m7. The perspective row (m12..m15) is not applied.
Quad apply_matrix(const float m[16], const Quad& q);

// Lightweight render mode: the per-corner weights q of a sprite quad. The quad is
// drawn as the triangles 0-1-2 and 0-2-3 with the vertex attribute (u q, v q, q) interpolated linearly in screen space
// (sampled at its ratio). c = the crossing of the lines p0-p2 and p1-p3 in the determinant form (den = (x0 - x2)
// (y1 - y3) - (y0 - y2)(x1 - x3), c = ((x0 y2 - y0 x2)(p1 - p3) - (x1 y3 - y1 x3)(p0 - p2)) / den); d_i = |p_i - c|;
// q_i = (d_i + d_{i+2}) / d_{i+2}; all in float32 in this order. On a convex quad that is the homography of the unit
// square; on a non-convex one the unsigned distances keep every q positive. False when a weight is not finite
// (parallel diagonals, as in a bow-tie, or a crossing exactly on a corner): nothing is drawn for those
// quads. The float32 form matters only for degenerate quads: with two equal top corners it puts the
// crossing a rounding error away from them (huge finite weights: drawn), with two equal bottom corners
// exactly on them (not drawn); the simpler c = p0 + s (p2 - p0) gives both exactly.
bool lightweight_weights(const Quad& quad, float q[4]);

// How a quad's texture is mapped onto it. Bilinear: standard-mode sprites. Projective: lightweight-mode
// sprites (lightweight_weights). AffineTriangles: particles in every render mode (the particle mesh has
// one UV per vertex, triangles 0-1-2 and 2-3-0, and nothing else), i.e. weights 1, never dropped.
enum class QuadMapping { Bilinear, Projective, AffineTriangles };
QuadMapping sprite_mapping(bool lightweight);
QuadMapping particle_mapping();
// The per-corner weights of `quad` under `mapping` (1 except for Projective); false when it is not drawn.
bool corner_weights(const Quad& quad, QuadMapping mapping, float q[4]);

// One skin sprite to draw, in screen space.
struct SpriteQuad {
    Quad screen;
    // Texture rect in atlas texels: u from u0 (the sprite rect's x) at the left edge (corners 0, 1) to u1
    // (x + w) at the right edge (corners 2, 3); v from v0 (y, the rect's top) along the top edge (corners 1, 2) to
    // v1 (y + h) along the bottom edge (corners 0, 3). No inset or padding: bilinear filtering bleeds into atlas
    // neighbours. Standard mode maps the quad bilinearly, lightweight (and default) mode
    // projectively with lightweight_weights.
    float u0, v0, u1, v1;
    // Sort key: z1..z4, then the entity index (kGlobalEntity for a global callback). It replaces the
    // uint64_t sort_key, which cannot hold four floats and an index.
    float z[4];
    int32_t entity;
    float a;             // alpha, unclamped (the GPU clamps the fragment output)
    float color[3] = {1, 1, 1};  // multiplies the texel's rgb: a particle's colour, 1 for skin sprites
    int32_t sprite;      // index into SkinData::sprites
    bool interpolation;  // SkinData::interpolation: true -> GL_LINEAR, false -> GL_NEAREST; no mipmaps
};

// Comparer: z1, z2, z3, z4 in turn by float.CompareTo order (NaN below every number, NaN == NaN, -0 == +0), then
// the entity index. Negative, zero or positive like CompareTo.
int compare_sprites(const SpriteQuad& a, const SpriteQuad& b);

// Sort: an introsort (partition, insertion sort, heap sort) with compare_sprites, so draws with equal keys come
// out in a fixed order for the same initial array. Not stable.
void sort_sprites(std::vector<SpriteQuad>& sprites);

namespace detail {
// sort_sprites with the IntroSort depth given instead of 2 * floor(log2 n) (tests: depth 0 reaches HeapSort).
void intro_sort(std::vector<SpriteQuad>& sprites, int32_t depth);
}  // namespace detail

// The frame's skin sprites in draw order (later on top). Each Draw becomes one quad: the skin data's transform of
// its sprite, then the frame's Runtime Skin Transform (A2: the matrix as it stands at presentation applies to every
// Draw of the frame). The initial array is the frame's draw order (updateSequential draws in execution order, then
// updateParallel draws in entity-index order), sorted with sort_sprites. A DrawCmd::sprite that is not an index
// of skin.sprites is skipped.
std::vector<SpriteQuad> build_sprite_list(const modes::FrameOutput& frame, const data::SkinData& skin);

}  // namespace sr::render
