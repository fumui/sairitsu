/* Stand-in for musl's <features.h>: only the `hidden` visibility macro is used by the vendored math sources. */
#ifndef SR_MUSL_FEATURES_H
#define SR_MUSL_FEATURES_H
#define hidden __attribute__((__visibility__("hidden")))
#endif
