/* Single translation unit holding the minimp3 implementation: MP3 (Layer III) only, float output, no SIMD paths,
   so host and device run the same scalar code. */
#define MINIMP3_IMPLEMENTATION
#define MINIMP3_ONLY_MP3
#define MINIMP3_NO_SIMD
#define MINIMP3_FLOAT_OUTPUT
#include "minimp3.h"
