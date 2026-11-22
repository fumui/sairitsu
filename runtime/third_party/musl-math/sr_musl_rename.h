/*
 * Force-included (-include) into every vendored musl source. It pulls in the
 * system headers first, so their declarations keep the original names, and only
 * then renames every symbol this directory defines or references to sr_musl_*.
 * The platform libm can therefore never satisfy, or be satisfied by, these files.
 * sqrtf and fabsf are mapped to compiler builtins (exact IEEE operations, no libm call).
 */
#ifndef SR_MUSL_RENAME_H
#define SR_MUSL_RENAME_H

#include <math.h>
#include <stdint.h>
#include <float.h>

#define sinf sr_musl_sinf
#define cosf sr_musl_cosf
#define tanf sr_musl_tanf
#define asinf sr_musl_asinf
#define acosf sr_musl_acosf
#define atanf sr_musl_atanf
#define atan2f sr_musl_atan2f
#define sinhf sr_musl_sinhf
#define coshf sr_musl_coshf
#define tanhf sr_musl_tanhf
#define expf sr_musl_expf
#define exp2f sr_musl_exp2f
#define expm1f sr_musl_expm1f
#define logf sr_musl_logf
#define log2f sr_musl_log2f
#define powf sr_musl_powf
#define floorf sr_musl_floorf
#define ceilf sr_musl_ceilf
#define roundf sr_musl_roundf
#define truncf sr_musl_truncf
#define fmodf sr_musl_fmodf
#define scalbn sr_musl_scalbn
#define scalbnf sr_musl_scalbnf
#define floor sr_musl_floor

#define __sindf sr_musl___sindf
#define __cosdf sr_musl___cosdf
#define __tandf sr_musl___tandf
#define __rem_pio2f sr_musl___rem_pio2f
#define __rem_pio2 sr_musl___rem_pio2
#define __rem_pio2_large sr_musl___rem_pio2_large
#define __expo2f sr_musl___expo2f
#define __math_divzerof sr_musl___math_divzerof
#define __math_invalidf sr_musl___math_invalidf
#define __math_oflowf sr_musl___math_oflowf
#define __math_uflowf sr_musl___math_uflowf
#define __math_xflowf sr_musl___math_xflowf
#define __exp2f_data sr_musl___exp2f_data
#define __powf_log2_data sr_musl___powf_log2_data
#define __logf_data sr_musl___logf_data
#define __log2f_data sr_musl___log2f_data
/* Declared in libm.h but unused here; glibc's <math.h> declares its own functions under these names. */
#define __sin sr_musl___sin
#define __cos sr_musl___cos
#define __tan sr_musl___tan
#define __sinl sr_musl___sinl
#define __cosl sr_musl___cosl
#define __tanl sr_musl___tanl

#define sqrtf __builtin_sqrtf
#define fabsf __builtin_fabsf
#define fabs __builtin_fabs
#define sqrt __builtin_sqrt

/* The public declarations in <math.h> were seen before the renames; declare the renamed ones. */
#ifdef __cplusplus
extern "C" {
#endif
float sinf(float); float cosf(float); float tanf(float);
float asinf(float); float acosf(float); float atanf(float); float atan2f(float, float);
float sinhf(float); float coshf(float); float tanhf(float);
float expf(float); float exp2f(float); float expm1f(float);
float logf(float); float log2f(float); float powf(float, float);
float floorf(float); float ceilf(float); float roundf(float); float truncf(float);
float fmodf(float, float);
double scalbn(double, int); double floor(double); float scalbnf(float, int);
#ifdef __cplusplus
}
#endif

#ifndef M_PI_2
#define M_PI_2 1.57079632679489661923
#endif

#endif
