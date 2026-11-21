#!/usr/bin/env python3
"""Convierte Salamander Grand Piano V3 (FLAC 24 bits) a PCM crudo de 16 bits para la app.

Uso: convert_samples.py <carpeta Samples de Salamander> <carpeta de salida>

Genera <salida>/<teclaRaiz>_<capa>.pcm  (int16 little-endian, estereo, 48 kHz),
solo con las capas pares v2,v4,...,v16 (version "B" liviana). Las notas se recortan
a MAX_SEG segundos con un desvanecimiento final para limitar el tamano.
"""
import os
import sys

import numpy as np
import soundfile as sf

MAX_SEG = 12.0   # segundos maximos por grabacion
FADE_SEG = 1.5   # desvanecimiento si se recorta
LAYERS = [2, 4, 6, 8, 10, 12, 14, 16]
NAMES = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]


def note_name(midi):
    return f"{NAMES[midi % 12]}{midi // 12 - 1}"


def main():
    src, dst = sys.argv[1], sys.argv[2]
    os.makedirs(dst, exist_ok=True)
    rng = np.random.default_rng(1234)
    total = 0
    count = 0
    peaks = {}
    for i in range(30):
        root = 21 + 3 * i
        name = note_name(root)
        for v in LAYERS:
            path = os.path.join(src, f"{name}v{v}.flac")
            data, sr = sf.read(path, dtype="float64", always_2d=True)
            if sr != 48000 or data.shape[1] != 2:
                raise SystemExit(f"formato inesperado en {path}: sr={sr} canales={data.shape[1]}")
            maxn = int(MAX_SEG * sr)
            if data.shape[0] > maxn:
                data = data[:maxn].copy()
                fade = int(FADE_SEG * sr)
                data[-fade:] *= np.linspace(1.0, 0.0, fade)[:, None]
            # 24 -> 16 bits con dither triangular de 1 LSB
            scaled = data * 32767.0
            dither = rng.random(scaled.shape) - rng.random(scaled.shape)
            pcm = np.clip(np.rint(scaled + dither), -32768, 32767).astype("<i2")
            out = os.path.join(dst, f"{root}_{v}.pcm")
            pcm.tofile(out)
            total += pcm.nbytes
            count += 1
            if root == 60:
                peaks[v] = int(np.abs(pcm).max())
    print(f"archivos: {count}, total: {total / 1e6:.1f} MB")
    print("picos de Do4 por capa (para ver que crecen con la intensidad):", peaks)


if __name__ == "__main__":
    main()
