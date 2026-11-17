/* Force-included into stb_vorbis_impl.c (see README.md): stb_vorbis builds its codebook, MDCT and window tables
   with the platform libm's sin, cos, exp, log, pow, floor and ldexp; route them to the deterministic versions in
   sr_vorbis_compat.c so host and device decode the same bits (ruling P22, tests/no_platform_libm.cmake). */
#ifndef SR_VORBIS_COMPAT_H
#define SR_VORBIS_COMPAT_H

#include <math.h>
#include <stdlib.h>

#ifdef __cplusplus
extern "C" {
#endif
double sr_vorbis_sin(double x);
double sr_vorbis_cos(double x);
double sr_vorbis_floor(double x);
double sr_vorbis_ldexp(double x, int e);
double sr_vorbis_exp(double x);
double sr_vorbis_log(double x);
double sr_vorbis_pow(double x, double y);
#ifdef __cplusplus
}
#endif

#define sin sr_vorbis_sin
#define cos sr_vorbis_cos
#define floor sr_vorbis_floor
#define ldexp sr_vorbis_ldexp
#define exp sr_vorbis_exp
#define log sr_vorbis_log
#define pow sr_vorbis_pow

#endif
