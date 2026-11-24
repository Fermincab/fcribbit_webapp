# Ribbit para Android 2.3 (Gingerbread)

Port nativo de la web app de Ribbit para teléfonos con Android 2.3, pensado para el
**Samsung Galaxy Y Pro GT-B5510L** (Android 2.3.6, ARM1136 a 832 MHz con VFP, pantalla
320×240 apaisada y teclado QWERTY).

- **Mismo módem:** el C++ de `../webassembly/dsp` (`encoder.hh`/`decoder.hh`) se compila tal cual
  con el NDK y se usa por JNI. Solo `jni/shim/emscripten.h` neutraliza los `EM_ASM`.
- **Audio:** `AudioRecord` y `AudioTrack` a 8 kHz, 16 bits, mono (la misma frecuencia que el
  `AudioContext` de la web app). Mientras transmite, el decodificador recibe silencio, como en la web.
- **Mismo formato de mensaje:** UTF-8 de `nombre|indicativo|grilla|teléfono&=cuerpo`, rellenado
  con ceros hasta 256 bytes.
- **Cifrado compatible:** `ENC1:` + base64(sal 8 | IV 12 | texto cifrado + tag 16), clave
  PBKDF2-HMAC-SHA256 (100 000 iteraciones) → AES-256-GCM, con la cabecera como datos adicionales.
  Android 2.3 no trae PBKDF2-SHA256 ni AES-GCM, así que están implementados en C
  (`jni/ribbit_crypto.c`), con la sal y el IV leídos de `/dev/urandom`.
- **Chat:** burbujas, historial en SQLite, "Borrar mensajes", contador de bytes disponibles y
  Enter para enviar.
- **Ajustes:** nombre, indicativo, grilla (con botón de GPS), teléfono, guardar mensajes, ocultar
  remitentes, nivel de transmisión, entrada de micrófono, y cifrado con su contraseña.
- **Interfaz** en inglés y en español (según el idioma del teléfono).

> El cifrado sigue siendo experimental, igual que en la web app. Cifrar en bandas de
> radioaficionados suele estar prohibido (UIT RR art. 25, FCC 97.113).

## Instalar

1. En el teléfono: *Ajustes → Aplicaciones → Orígenes desconocidos*.
2. Copiá `ribbit-gingerbread.apk` a la tarjeta SD y abrilo, o usá `adb install ribbit-gingerbread.apk`.

El APK trae dos versiones de la librería nativa. `libribbit_vfp.so` (ARMv6 + VFP) se carga si
`/proc/cpuinfo` informa VFP. Si no, se usa `libribbit.so` (soft-float), y la barra de título
muestra "(soft-float)".

## Uso con radio

Igual que la web app: la salida de audio va a la entrada de micrófono del equipo (con VOX) y el
audio del receptor entra por el micrófono del teléfono. El volumen de transmisión se ajusta con
las teclas de volumen (stream de música) y con *Nivel de transmisión*. La barra verde de arriba
muestra el nivel de entrada. La recepción funciona mientras la app está en primer plano; la
pantalla se mantiene encendida.

## Compilar

Necesita el NDK r10e (el último que trae `armeabi`, `android-9` y gnustl), la plataforma API 10
(`android-2.3.3_r02`), build-tools 35.0.1 y un JDK. No usa Gradle:

```sh
SDK=/ruta/al/sdk ./build.sh        # → bin/ribbit-gingerbread.apk
```

`build.sh` espera `$SDK/android-ndk-r10e`, `$SDK/platform-10/android.jar` y `$SDK/build-tools`;
cada ruta se puede cambiar con `NDK`, `PLATFORM_JAR` y `BUILD_TOOLS`. El código del módem
necesita C++17 en algunos puntos (`static_assert` sin mensaje), así que se compila con el Clang
3.6 del NDK en modo `gnu++1z`. En distribuciones nuevas, ese Clang necesita `libncurses.so.5`:
alcanza con un enlace a `libncursesw.so.6` en un directorio de `LD_LIBRARY_PATH`. La firma es
JAR v1 con SHA-1, que es lo que verifica Android 2.3. Si no existe el keystore de depuración,
el script lo crea.

## Pruebas (sin teléfono)

```sh
test/run_tests.sh
```

1. Vuelta completa del módem C++ (codificar → decodificar), con y sin ruido.
2. Cifrado nativo contra el `RibbitCrypto` original de `index.html`, ejecutado con WebCrypto en
   Node, en las dos direcciones. También comprueba que se rechazan una contraseña incorrecta y una
   cabecera alterada.
3. El código Java de la app (`Payload`, `RibbitCrypto`, `Modem` sobre una compilación JNI para el
   host) contra el `ribbit.wasm` compilado de la web app, por audio, en las dos direcciones y con
   mensajes cifrados y sin cifrar.
