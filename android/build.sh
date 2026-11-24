#!/bin/sh
# Builds bin/ribbit-gingerbread.apk without Gradle.
#
# Needs (paths can be overridden from the environment):
#   NDK          Android NDK r10e  (last NDK with armeabi + android-9 + gnustl)
#   PLATFORM_JAR android.jar of API 10 (SDK package "android-2.3.3_r02")
#   BUILD_TOOLS  build-tools 35.0.1 (aapt, d8, zipalign, apksigner; older d8 crash on JDK 21)
#   KEYSTORE     signing keystore (a debug one is created if missing)
set -e
cd "$(dirname "$0")"

SDK=${SDK:-$HOME/sdk}
NDK=${NDK:-$SDK/android-ndk-r10e}
PLATFORM_JAR=${PLATFORM_JAR:-$SDK/platform-10/android.jar}
BUILD_TOOLS=${BUILD_TOOLS:-$SDK/build-tools}
KEYSTORE=${KEYSTORE:-$HOME/.android/ribbit-debug.keystore}
KS_PASS=${KS_PASS:-android}
KEY_ALIAS=${KEY_ALIAS:-ribbit}

for f in "$NDK/ndk-build" "$PLATFORM_JAR" "$BUILD_TOOLS/aapt" "$BUILD_TOOLS/d8"; do
	[ -e "$f" ] || { echo "missing: $f" >&2; exit 1; }
done

rm -rf gen obj/classes obj/dex bin
mkdir -p gen obj/classes obj/dex bin

echo "== native (NDK r10e, armeabi)"
"$NDK/ndk-build" -j4

echo "== resources"
"$BUILD_TOOLS/aapt" package -f -m -J gen -M AndroidManifest.xml -S res -I "$PLATFORM_JAR"

echo "== java"
find src gen -name '*.java' > obj/sources.txt
javac -nowarn -Xlint:-options -encoding UTF-8 -source 8 -target 8 \
	-bootclasspath "$PLATFORM_JAR" -d obj/classes @obj/sources.txt

echo "== dex"
"$BUILD_TOOLS/d8" --release --min-api 10 --lib "$PLATFORM_JAR" --output obj/dex \
	$(find obj/classes -name '*.class')

echo "== package"
"$BUILD_TOOLS/aapt" package -f -M AndroidManifest.xml -S res -I "$PLATFORM_JAR" -F bin/unsigned.apk
cp obj/dex/classes.dex bin/classes.dex
mkdir -p bin/lib/armeabi
cp libs/armeabi/*.so bin/lib/armeabi/
(cd bin && "$BUILD_TOOLS/aapt" add unsigned.apk classes.dex lib/armeabi/libribbit.so lib/armeabi/libribbit_vfp.so >/dev/null)
"$BUILD_TOOLS/zipalign" -f -p 4 bin/unsigned.apk bin/aligned.apk

echo "== sign"
if [ ! -f "$KEYSTORE" ]; then
	mkdir -p "$(dirname "$KEYSTORE")"
	keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KS_PASS" -keypass "$KS_PASS" \
		-alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 -sigalg SHA1withRSA -validity 10000 \
		-dname "CN=Ribbit Debug, O=fcribbit, C=AR"
fi
# min-sdk 9 makes apksigner use a v1 (JAR) signature with SHA-1 digests, which Android 2.3 understands
"$BUILD_TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --ks-key-alias "$KEY_ALIAS" \
	--min-sdk-version 9 --v1-signing-enabled true --v2-signing-enabled true \
	--out bin/ribbit-gingerbread.apk bin/aligned.apk
"$BUILD_TOOLS/apksigner" verify --min-sdk-version 9 bin/ribbit-gingerbread.apk
rm -f bin/unsigned.apk bin/aligned.apk bin/classes.dex
rm -rf bin/lib
ls -l bin/ribbit-gingerbread.apk
