#!/bin/sh
# Host-side tests for the Android port (no phone needed):
#   1. the web app's C++ modem round trip (encode -> decode) with and without noise
#   2. native crypto vs the web app's RibbitCrypto (extracted from index.html, run on Node's WebCrypto)
#   3. the app's Java code (Payload/RibbitCrypto/Modem over a host JNI build) against the web app's
#      compiled ribbit.wasm, over audio, in both directions, encrypted and plain.
# Needs: g++, gcc, a JDK, node >= 20.
set -e
cd "$(dirname "$0")"
export LANG=C.UTF-8 LC_ALL=C.UTF-8
DSP=../../webassembly/dsp
OUT=${OUT:-/tmp/ribbit-test}
mkdir -p "$OUT/lib" "$OUT/classes"

# the web app's RibbitCrypto, verbatim
python3 - <<'EOF'
import re
src = open('../../index.html', encoding='utf-8').read()
m = re.search(r'(window\.RibbitCrypto = \(\(\) => \{.*?\}\)\(\);)', src, re.S)
open('ribbitcrypto_web.js', 'w', encoding='utf-8').write(m.group(1))
EOF

echo "== 1. modem loopback"
g++ -std=c++11 -O2 -I../jni/shim -I$DSP loopback.cc -o "$OUT/loopback"
"$OUT/loopback" && "$OUT/loopback" 0.3

echo "== 2. crypto vs web RibbitCrypto"
gcc -O2 -std=c99 -D_DEFAULT_SOURCE -I../jni cryptotool.c ../jni/ribbit_crypto.c -o "$OUT/cryptotool"
H='Fermín|LU1ABC|GF05rk|+54 9 11'; T='Hola ñandú 🐸'
a=$("$OUT/cryptotool" enc pw "$H" "$T"); [ "$(node webcrypto.mjs dec pw "$H" "$a")" = "$T" ] && echo "native -> web ok"
b=$(node webcrypto.mjs enc pw "$H" "$T"); [ "$("$OUT/cryptotool" dec pw "$H" "$b")" = "$T" ] && echo "web -> native ok"
[ "$("$OUT/cryptotool" dec bad "$H" "$b")" = "DECRYPT_FAIL" ] && echo "wrong password rejected"
[ "$("$OUT/cryptotool" dec pw "X|$H" "$b")" = "DECRYPT_FAIL" ] && echo "tampered header rejected"

echo "== 3. app code vs web ribbit.wasm over audio"
JH=$(dirname "$(dirname "$(readlink -f "$(which javac)")")")
g++ -std=c++11 -O2 -shared -fPIC -I"$JH/include" -I"$JH/include/linux" -I../jni/shim -I../jni -I$DSP \
	../jni/ribbit_jni.cpp -x c ../jni/ribbit_crypto.c -o "$OUT/lib/libribbit.so"
javac -nowarn -d "$OUT/classes" stubs/android/util/Base64.java \
	../src/com/fcribbit/ribbit/Modem.java ../src/com/fcribbit/ribbit/Payload.java \
	../src/com/fcribbit/ribbit/RibbitCrypto.java HostTest.java
J="java -Dsun.jnu.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Djava.library.path=$OUT/lib -cp $OUT/classes HostTest"
$J tx "$OUT/a.wav" "clave" "Hola desde Android 🐸"
node wasm_modem.mjs rx "$OUT/a.wav" "clave" | grep -q "decrypt: Hola desde Android 🐸" && echo "android -> web (encrypted) ok"
node wasm_modem.mjs tx "$OUT/w.wav" "clave" "Hola desde la web 👍" >/dev/null
$J rx "$OUT/w.wav" "clave" | grep -q "decrypt: Hola desde la web 👍" && echo "web -> android (encrypted) ok"
node wasm_modem.mjs tx "$OUT/p.wav" "" "sin cifrar" >/dev/null
$J rx "$OUT/p.wav" "" | grep -q "message: sin cifrar" && echo "web -> android (plain) ok"
echo "all tests passed"
