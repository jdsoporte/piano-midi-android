# piano-midi-android

App Android gratuita para músicos: recibe MIDI por USB desde un teclado
(probado con Yamaha PSR-E373) y reproduce un sonido de piano de concierto
en el celular.

Estado: etapa 3 — piano de concierto (Salamander, versión liviana "B": 8 capas),
instrumentos General MIDI (FluidR3, 128 sonidos), carga de archivos `.sf2` propios,
reverb, latencia ajustable y teclado en pantalla.

## Cómo se compila

El APK se genera en GitHub Actions (workflow "Compilar APK"). Ese workflow
descarga Salamander Grand Piano V3, convierte las muestras con
`tools/convert_samples.py` y compila la app con Oboe. Las pruebas del motor
(`tests/engine_test.cpp`) se ejecutan antes de compilar.

## Estructura

- `app/src/main/cpp/` — motor de audio en C++: `engine` (sampler del piano), `sf_engine`
  (SoundFonts con TinySoundFont, pedal de sustain propio), `master` (reverb, volumen,
  limitador) y `jni_bridge` (Oboe + puente JNI).
- `app/src/main/java/` — interfaz y entrada MIDI USB.
- `tests/` — pruebas del motor (se ejecutan en GitHub Actions antes de compilar).
- `tools/convert_samples.py` — convierte Salamander a PCM de 16 bits.

## Créditos de sonido

Piano: **Salamander Grand Piano V3** — grabaciones de Alexander Holm,
remapeo SFZ de kinwie. Licencia Creative Commons Attribution 3.0
(<https://creativecommons.org/licenses/by/3.0/>).

FluidR3 GM — Frank Wen, licencia MIT. TinySoundFont — Bernhard Schelling, licencia MIT.
Oboe — Google, Apache 2.0. Los textos completos están en
`app/src/main/assets/licenses/NOTICE.txt` y dentro de la app (Créditos y licencias).
