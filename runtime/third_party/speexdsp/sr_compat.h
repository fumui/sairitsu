/* Force-included into speexdsp's resample.c (see README.md): its filter-table setup calls the platform libm's sin()
   and floor(); route both to the deterministic versions in sr_compat.c. */
#ifndef SR_SPEEX_COMPAT_H
#define SR_SPEEX_COMPAT_H

#include <math.h>
#include <stdlib.h>

#ifdef __cplusplus
extern "C" {
#endif
double sr_speex_sin(double x);
double sr_speex_floor(double x);
#ifdef __cplusplus
}
#endif

#define sin sr_speex_sin
#define floor sr_speex_floor

#endif
