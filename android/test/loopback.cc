// Host test: encode a payload with the web app's Encoder and decode it with its Decoder.
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <vector>
#include <cassert>
#include "encoder.hh"
#include "decoder.hh"
int main(int argc, char **argv) {
	float noise = argc > 1 ? atof(argv[1]) : 0;
	static Encoder enc; static Decoder dec;
	uint8_t msg[256] = {0};
	const char *s = "Fermin|LU1ABC|GF05|123&=hola desde Android 2.3";
	memcpy(msg, s, strlen(s));
	enc.init(msg);
	std::vector<float> sig(4000, 0.f); // leading silence
	float buf[160]; bool done;
	do { done = enc.read(buf, 160); sig.insert(sig.end(), buf, buf + 160); } while (!done);
	sig.resize(sig.size() + 8000, 0.f);
	srand(1);
	for (auto &x : sig) x = x * 0.5f + noise * ((rand() / (float)RAND_MAX) - 0.5f);
	printf("samples %zu (%.2f s)\n", sig.size(), sig.size() / 8000.0);
	int got = 0;
	for (size_t i = 0; i + 160 <= sig.size(); i += 160)
		if (dec.feed(sig.data() + i, 160)) {
			uint8_t out[256]; int r = dec.fetch(out);
			printf("fetch=%d payload='%s'\n", r, (char *)out); got++;
		}
	return got ? 0 : 1;
}
