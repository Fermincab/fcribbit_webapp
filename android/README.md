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

## Escucha en segundo plano y sesiones

Ribbit sigue escuchando con la app cerrada y la pantalla apagada (*Ajustes → Escucha en segundo plano*):

- **Intermitente** (predeterminado): el teléfono duerme y cada **7 s** (configurable entre 5 y
  60 s) se despierta ~0,7 s para buscar la **señal de inicio de sesión** de su grupo.
- **Continua**: siempre escuchando, para teléfonos conectados al cargador.
- **Solo con la app abierta**: el comportamiento original.

### Señal de inicio y código de grupo

La señal de inicio **suena como ruido**: un siseo, como una portadora con estática. Técnicamente
es una secuencia pseudoaleatoria de 128 ms, filtrada a 400–2600 Hz y repetida, que se genera a
partir del **código de grupo** (*Ajustes → Código de grupo*). Para detectarla, el teléfono pliega
512 ms de audio sobre un período y lo compara con la secuencia conocida (correlación PHAT): la
señal repetida se refuerza y el ruido no.

- **Los teléfonos con otro código no se despiertan** ni la reconocen.
- Se detecta **por debajo del nivel de ruido** (−8 dB), a través de un enlace FM simulado
  (filtros, preénfasis/deénfasis, limitador) y con relojes desfasados ±500 ppm.
- No se dispara con ruido, voz, tonos fijos, tramas Ribbit, la señal de otro grupo ni otras
  señales que se repiten cada 128 ms.
- Dura **el intervalo + 2,5 s** (9,5 s con el intervalo de 7 s). Por eso **todos los teléfonos del
  grupo tienen que usar el mismo código y el mismo intervalo**.

Con baterías de teléfono al 50 % y modo avión, la estimación es de unas 40 h con el intervalo de
7 s, en línea con lo que dura un UV-5R con VOX. *Ajustes → Consumo de batería* mide el valor real.

### Sesiones

1. Si no hay sesión abierta, el primer mensaje sale precedido por la señal de inicio y se repite,
   señal incluida, hasta que el otro teléfono confirme, con un máximo de 3 intentos y una espera
   aleatoria entre intentos. Lo que escribas mientras tanto queda en cola.
2. El teléfono que escuchó la señal se queda despierto, recibe el mensaje y confirma con
   `CTL1:SESSION_OK`.
3. Durante la sesión, los mensajes salen sin la señal. Cada mensaje enviado o recibido extiende la
   sesión 5 minutos, y 30 s antes del cierre aparece un aviso.
4. Al vencer la sesión, el teléfono que la abrió manda `CTL1:SESSION_END` y los dos vuelven a dormir.
   El próximo mensaje vuelve a mandar la señal de inicio.

Los mensajes recibidos en segundo plano generan una notificación **solo con vibración y LED**,
sin sonido: un sonido saldría por el jack y activaría el VOX de la radio.

*Menú → Diagnóstico* muestra lo que pasa por dentro (tramas, señales de inicio, sesiones,
micrófono) y permite copiarlo.

## Alarma

El botón **Alarma** manda `CTL1:ALARM`. El teléfono que la recibe abre una pantalla completa y hace
sonar el tono de llamada **de inmediato**, hasta que toques la pantalla o cualquier tecla (el botón
de encendido incluido). Si nadie la detiene, se corta sola al minuto.

Suena **solo por el parlante del teléfono**, aunque el cable de la radio esté conectado. Para eso
activa el altavoz y reproduce el timbre por el canal de llamada, como una llamada en manos libres;
Android manda ese canal al parlante aunque haya algo en el jack. El audio de Ribbit usa el canal
multimedia, que sigue saliendo por el jack, así que el teléfono puede transmitir (por ejemplo, la
confirmación de sesión) mientras suena la alarma. Se probó en el Galaxy Y Pro. Forzar el canal
multimedia con la API oculta `AudioSystem.setForceUse` no funcionó.

*Ajustes → Probar la alarma en este teléfono* la hace sonar localmente, sin usar la radio.

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
4. El detector de la señal de inicio: por debajo del ruido, a través de un enlace FM simulado, con
   relojes desfasados y solapamiento parcial; sin falsas detecciones con ruido, voz, tonos fijos,
   tramas Ribbit ni la señal de otro grupo.
5. El protocolo de sesiones con dos teléfonos simulados en un canal semidúplex con VOX, ciclos de
   sueño de fase aleatoria, pérdidas de tramas, colisiones y un teléfono apagado.
