#include "render/geometry.h"

#include <bit>
#include <cmath>
#include <utility>

namespace sr::render {

namespace {

// float.CompareTo order: NaN below every number, two NaNs equal, -0 equal to +0.
int compare_float(float a, float b) {
    if (a < b) return -1;
    if (a > b) return 1;
    if (a == b) return 0;
    if (std::isnan(a)) return std::isnan(b) ? 0 : -1;
    return 1;
}

// The sprite sort (an introsort). Indices are inclusive.
class IntroSort {
public:
    explicit IntroSort(std::vector<SpriteQuad>& a) : a_(a) {}

    void run(int32_t lo, int32_t hi, int32_t depth) {
        while (hi > lo) {
            const int32_t size = hi - lo + 1;
            if (size <= 16) {
                if (size == 2) {
                    swap_if_greater(lo, hi);
                } else if (size == 3) {
                    swap_if_greater(lo, hi - 1);
                    swap_if_greater(lo, hi);
                    swap_if_greater(hi - 1, hi);
                } else {
                    insertion_sort(lo, hi);
                }
                return;
            }
            if (depth == 0) {
                heap_sort(lo, hi);
                return;
            }
            --depth;
            const int32_t p = partition(lo, hi);
            run(p + 1, hi, depth);
            hi = p - 1;
        }
    }

private:
    SpriteQuad& at(int32_t i) { return a_[size_t(i)]; }
    int cmp(const SpriteQuad& x, const SpriteQuad& y) const { return compare_sprites(x, y); }
    void swap(int32_t i, int32_t j) { std::swap(at(i), at(j)); }

    void swap_if_greater(int32_t i, int32_t j) {
        if (i != j && cmp(at(i), at(j)) > 0) swap(i, j);
    }

    void insertion_sort(int32_t lo, int32_t hi) {
        for (int32_t i = lo; i < hi; ++i) {
            int32_t j = i;
            const SpriteQuad t = at(i + 1);
            while (j >= lo && cmp(t, at(j)) < 0) {
                at(j + 1) = at(j);
                --j;
            }
            at(j + 1) = t;
        }
    }

    int32_t partition(int32_t lo, int32_t hi) {
        const int32_t mid = lo + (hi - lo) / 2;
        swap_if_greater(lo, mid);
        swap_if_greater(lo, hi);
        swap_if_greater(mid, hi);
        const SpriteQuad pivot = at(mid);
        swap(mid, hi - 1);
        int32_t left = lo, right = hi - 1;
        while (left < right) {
            while (left < hi && cmp(pivot, at(++left)) > 0) {
            }
            while (right > left && cmp(pivot, at(--right)) < 0) {
            }
            if (left >= right) break;
            swap(left, right);
        }
        swap(left, hi - 1);
        return left;
    }

    // 1-based i within [lo, lo + n).
    void heapify(int32_t i, int32_t n, int32_t lo) {
        const SpriteQuad d = at(lo + i - 1);
        while (i <= n / 2) {
            int32_t child = 2 * i;
            if (child < n && cmp(at(lo + child - 1), at(lo + child)) < 0) ++child;
            if (!(cmp(d, at(lo + child - 1)) < 0)) break;
            at(lo + i - 1) = at(lo + child - 1);
            i = child;
        }
        at(lo + i - 1) = d;
    }

    // These bodies are the least tested part of the sort; they only run after 2 * floor(log2 n) partitions.
    void heap_sort(int32_t lo, int32_t hi) {
        const int32_t n = hi - lo + 1;
        for (int32_t i = n / 2; i >= 1; --i) heapify(i, n, lo);
        for (int32_t i = n; i > 1; --i) {
            swap(lo, lo + i - 1);
            heapify(1, i - 1, lo);
        }
    }

    std::vector<SpriteQuad>& a_;
};

int32_t log2_floor(uint32_t n) { return 31 - std::countl_zero(n); }

}  // namespace

Quad apply_sprite_transform(const data::SkinTransform& t, const Quad& q) {
    const float in[8] = {q.x[0], q.x[1], q.x[2], q.x[3], q.y[0], q.y[1], q.y[2], q.y[3]};
    float out[8];
    for (int r = 0; r < 8; ++r) {
        float s = t.coef[r][0] * in[0];
        for (int c = 1; c < 8; ++c) s = s + t.coef[r][c] * in[c];
        out[r] = s;
    }
    Quad o;
    for (int i = 0; i < 4; ++i) {
        o.x[i] = out[2 * i];
        o.y[i] = out[2 * i + 1];
    }
    return o;
}

QuadMapping sprite_mapping(bool lightweight) {
    return lightweight ? QuadMapping::Projective : QuadMapping::Bilinear;
}

QuadMapping particle_mapping() { return QuadMapping::AffineTriangles; }

bool corner_weights(const Quad& quad, QuadMapping mapping, float q[4]) {
    if (mapping == QuadMapping::Projective) return lightweight_weights(quad, q);
    for (int i = 0; i < 4; ++i) q[i] = 1;
    return true;
}

bool lightweight_weights(const Quad& quad, float q[4]) {
    // Float32 in this order: the distance form of the projective weights, with the
    // diagonals' crossing from the determinant form of the line-line intersection (lines p0-p2 and p1-p3).
    const float x1 = quad.x[0], y1 = quad.y[0], x2 = quad.x[2], y2 = quad.y[2];
    const float x3 = quad.x[1], y3 = quad.y[1], x4 = quad.x[3], y4 = quad.y[3];
    const float den = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4);
    const float a = x1 * y2 - y1 * x2, b = x3 * y4 - y3 * x4;
    const float cx = (a * (x3 - x4) - (x1 - x2) * b) / den;
    const float cy = (a * (y3 - y4) - (y1 - y2) * b) / den;
    float d[4];
    for (int i = 0; i < 4; ++i) {
        const float dx = quad.x[i] - cx, dy = quad.y[i] - cy;
        d[i] = std::sqrt(dx * dx + dy * dy);
    }
    bool finite = true;
    for (int i = 0; i < 4; ++i) {
        q[i] = (d[i] + d[(i + 2) % 4]) / d[(i + 2) % 4];
        finite = finite && std::isfinite(q[i]);
    }
    return finite;
}

Quad apply_matrix(const float m[16], const Quad& q) {
    Quad o;
    for (int i = 0; i < 4; ++i) {
        // (x, y, 0, 1) times the row-major matrix; m12..m15 (perspective) not applied.
        o.x[i] = m[0] * q.x[i] + m[1] * q.y[i] + m[3];
        o.y[i] = m[4] * q.x[i] + m[5] * q.y[i] + m[7];
    }
    return o;
}

int compare_sprites(const SpriteQuad& a, const SpriteQuad& b) {
    for (int k = 0; k < 4; ++k) {
        const int c = compare_float(a.z[k], b.z[k]);
        if (c != 0) return c;
    }
    return a.entity < b.entity ? -1 : (a.entity > b.entity ? 1 : 0);
}

namespace detail {
void intro_sort(std::vector<SpriteQuad>& sprites, int32_t depth) {
    if (sprites.size() <= 1) return;
    IntroSort(sprites).run(0, int32_t(sprites.size()) - 1, depth);
}
}  // namespace detail

void sort_sprites(std::vector<SpriteQuad>& sprites) {
    if (sprites.size() <= 1) return;
    detail::intro_sort(sprites, 2 * log2_floor(uint32_t(sprites.size())));
}

std::vector<SpriteQuad> build_sprite_list(const modes::FrameOutput& frame, const data::SkinData& skin) {
    std::vector<SpriteQuad> out;
    out.reserve(frame.draws.size());
    for (const modes::DrawCmd& d : frame.draws) {
        if (d.sprite < 0 || size_t(d.sprite) >= skin.sprites.size()) continue;
        const data::SkinSprite& sprite = skin.sprites[size_t(d.sprite)];
        // DrawCurved* are drawn as their straight quad: their edges are not tessellated yet (Next RUSH makes only
        // Draw calls; curve segments are not implemented).
        Quad q;
        for (int i = 0; i < 4; ++i) {
            q.x[i] = d.x[i];
            q.y[i] = d.y[i];
        }
        SpriteQuad s;
        s.screen = apply_matrix(frame.skin_transform, apply_sprite_transform(sprite.transform, q));
        s.u0 = sprite.x;
        s.v0 = sprite.y;
        s.u1 = sprite.x + sprite.w;
        s.v1 = sprite.y + sprite.h;
        for (int k = 0; k < 4; ++k) s.z[k] = d.z[k];
        s.entity = d.entity;
        s.a = d.a;
        s.sprite = d.sprite;
        s.interpolation = skin.interpolation;
        out.push_back(s);
    }
    sort_sprites(out);
    return out;
}

}  // namespace sr::render
