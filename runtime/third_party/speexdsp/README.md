# speexdsp

The resampler of SpeexDSP 1.2.1 (tag `SpeexDSP-1.2.1`, commit `1b28a0f61bc31162979e1f26f3981fc3637095c8`,
https://github.com/xiph/speexdsp), BSD 3-clause (`LICENSE` is its `COPYING`).

Copied byte for byte: `libspeexdsp/resample.c`, `libspeexdsp/arch.h`, `libspeexdsp/fixed_generic.h`,
`include/speex/speex_resampler.h`, `include/speex/speexdsp_types.h`.

Built as the float resampler (`-DOUTSIDE_SPEEX -DFLOATING_POINT`, no SSE/NEON paths, so host and device run the same
scalar code). `resample.c` calls the platform libm's `sin()` and `floor()` while building its filter table, so CMake
force-includes `sr_compat.h`, which renames both to the libm-free `sr_speex_sin` / `sr_speex_floor` in `sr_compat.c`
(ours, not upstream).
