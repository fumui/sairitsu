# stb

From https://github.com/nothings/stb (public domain or MIT, `LICENSE`), unmodified:

- `stb_image.h`. `stb_image_impl.c` (ours) compiles it for PNG only, without the float (HDR / linear) API, which
  would call the platform `pow()`.
- `stb_vorbis.c` v1.22 at commit `4def4559dca2f93489191598418f72bd1454d991`. `stb_vorbis_impl.c` (ours) compiles
  it without stdio and the push-data API, with `sr_vorbis_compat.h` force-included: the `sin`, `cos`, `exp`, `log`,
  `pow`, `floor` and `ldexp` its codebook, MDCT and window tables use are routed to the deterministic versions in
  `sr_vorbis_compat.c` (ours), so no platform libm is referenced and host and device decode the same
  bits.
