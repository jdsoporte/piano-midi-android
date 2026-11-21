# piano-midi-android

App Android gratuita para músicos: recibe MIDI por USB desde un teclado
(probado con Yamaha PSR-E373) y reproduce un sonido de piano de concierto
en el celular.

Estado: etapa 2 (piano con sonido, versión liviana "B": 8 capas de intensidad).

## Cómo se compila

El APK se genera en GitHub Actions (workflow "Compilar APK"). Ese workflow
descarga Salamander Grand Piano V3, convierte las muestras con
`tools/convert_samples.py` y compila la app con Oboe. Las pruebas del motor
(`tests/engine_test.cpp`) se ejecutan antes de compilar.

## Créditos de sonido

Piano: **Salamander Grand Piano V3** — grabaciones de Alexander Holm,
remapeo SFZ de kinwie. Licencia Creative Commons Attribution 3.0
(<https://creativecommons.org/licenses/by/3.0/>).
