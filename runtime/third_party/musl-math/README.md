# musl-math

Float math from musl libc 1.2.5, vendored so host and device produce identical bits and the platform libm is never
linked in.

Source: https://musl.libc.org/releases/musl-1.2.5.tar.gz, SHA-256
`a9a118bbe84d8764da0ea0d28b3ab3fae8477fc7e4085d90102b8596fc7c75e4`; the detached signature
(`musl-1.2.5.tar.gz.asc`) verified as a good signature from "musl libc <musl@libc.org>", key fingerprint
`8364 8929 0BB6 B70F 99FF DA05 56BC DB59 3020 450F`. `COPYRIGHT` is musl's, unchanged. Individual files carry their
own notices (Sun Microsystems / FreeBSD msun, Arm Limited MIT).

## Files

Copied byte for byte from `src/math/`, no edits:

`expf exp2f log2f floorf ceilf roundf truncf fmodf`, `__math_divzerof __math_invalidf __math_oflowf __math_uflowf
__math_xflowf`, the data tables `exp2f_data log2f_data` (`.c` and `.h`).

`roundf.c` is vendored but unused by `sr::fm` (`fm::round` is half to even, see `src/vm/fmath.cpp`); it is compiled
only to keep the vendored set complete.

musl's sin, cos, tan, asin, acos, atan, atan2, sinh, cosh, tanh, log and pow were replaced with SLEEF ports
(`src/vm/fmath_sleef.cpp`, licence in `third_party/sleef`). Their musl files and the
helpers only they used (`__sindf __cosdf __tandf __rem_pio2f __rem_pio2 __rem_pio2_large __expo2f expm1f floor
scalbn scalbnf`, `logf_data powf_data`) were removed. `sr_musl_rename.h` still lists their names; the extra
`#define`s are harmless.

## Changes

Only two files are not byte-identical to musl, and one is new:

1. `libm.h` is musl's `src/internal/libm.h` with the long double `union ldshape` block removed (nothing here uses
   long double), and `#include <endian.h>` plus `#include "fp_arch.h"` replaced by `#include "features.h"`. The
   rest, including the `asuint`/`asfloat`/`GET_FLOAT_WORD` macros and the `__math_*` declarations, is unchanged.
2. `features.h` is new. It replaces musl's `<features.h>` and defines only `hidden` as
   `__attribute__((__visibility__("hidden")))`.
3. `sr_musl_rename.h` is new and is force-included (`-include`) into every file in this directory by
   `runtime/CMakeLists.txt`. It includes `<math.h>`, `<stdint.h>` and `<float.h>` first (so the system declarations
   keep their names), then `#define`s every public and internal symbol defined or called here to `sr_musl_<name>`
   (for example `sinf` -> `sr_musl_sinf`, `__rem_pio2f` -> `sr_musl___rem_pio2f`, `__exp2f_data` ->
   `sr_musl___exp2f_data`), redeclares the renamed public functions (the system `<math.h>` declarations were seen
   before the renames), defines `M_PI_2` if the system header lacks it (MSVC ABI), and maps `sqrtf`, `fabsf`, `sqrt`
   and `fabs` to the compiler builtins (exact IEEE operations).

Compile flags for these files (set in `runtime/CMakeLists.txt`): `-w -fno-builtin -fno-math-errno
-ffp-contract=off -fno-fast-math`. `-fno-builtin` stops clang from recognising an idiom and calling back into a
libm `floorf`; `-fno-math-errno` makes `sqrtf` the hardware instruction.

`llvm-nm -u` over the built objects shows no undefined symbol other than `sr_musl_*` and the MSVC `_fltused` marker.

The C++ wrappers (`sr::fm::*`) declare the `sr_musl_*` functions `extern "C"` in `runtime/src/vm/fmath.cpp`.

## Known accuracy

Against the platform's double function rounded to float, 100,000 fixed-seed inputs per function
(`runtime/tests/vm/fmath_test.cpp`): `expf`, `exp2f` and `log2f` within 1 ulp; the rounding functions exact.
