/* Stand-in for musl's <features.h>: only the `hidden` visibility macro is used by the vendored math sources. */
#ifndef SR_MUSL_FEATURES_H
#define SR_MUSL_FEATURES_H
/* This file shadows the C library's <features.h> (glibc's <math.h> needs it), so chain to it when it exists. */
#if defined(__has_include_next)
#if __has_include_next(<features.h>)
#include_next <features.h>
#endif
#endif
#define hidden __attribute__((__visibility__("hidden")))
#endif
