/* Shim so the web app's modem headers (webassembly/dsp) build outside Emscripten.
   encoder.hh includes this first, so also pull in what the headers expect. */
#pragma once
#include <stdint.h>
#include <stddef.h>
#include <assert.h>
#define EMSCRIPTEN_KEEPALIVE
#define EM_ASM(...) do {} while (0)

#if defined(__cplusplus) && defined(__ANDROID__)
/* The NDK r10e gnustl does not export the C99 rounding functions into std. */
#include <math.h>
namespace std {
	inline float nearbyint(float x) { return ::nearbyintf(x); }
	inline double nearbyint(double x) { return ::nearbyint(x); }
}
#endif
