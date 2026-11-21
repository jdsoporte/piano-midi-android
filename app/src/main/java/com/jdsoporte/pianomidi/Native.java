package com.jdsoporte.pianomidi;

/** Puente al motor de sonido nativo (C++ / Oboe). */
final class Native {
    static {
        System.loadLibrary("pianoengine");
    }

    private Native() { }

    /** Carga las muestras (.pcm) de un directorio. Devuelve cuantas cargo. */
    static native int nativeLoad(String dir);

    static native boolean nativeStart();

    static native void nativeStop();

    static native String nativeInfo();

    /** Cortes de audio (underruns) desde que abrio el flujo; -1 si no se sabe. */
    static native int nativeXRuns();

    /** Pico de salida (0..1) desde la ultima consulta. */
    static native float nativePeak();

    static native void nativeNoteOn(int key, int vel);

    static native void nativeNoteOff(int key);

    static native void nativeControl(int cc, int value);

    static native void nativePitchBend(int value14);

    static native void nativeSetVolume(float v);

    /** Cantidad de reverb, 0..1. */
    static native void nativeSetReverb(float mix);

    /** Buffer de audio en rafagas: 2 = menor latencia, 4 = mas seguro. */
    static native void nativeSetLatency(int bursts);

    /** 0 = piano de concierto (Salamander), 1 = SoundFont. */
    static native void nativeSetMode(int mode);

    /** Carga un .sf2 (lento, no usar el hilo de la pantalla). */
    static native boolean nativeSfLoad(String path);

    static native boolean nativeSfLoaded();

    /** Cada elemento: "indice|banco|programa|nombre". */
    static native String[] nativeSfPresets();

    /** Indice del preset (banco, programa) o -1. */
    static native int nativeSfFindPreset(int bank, int program);

    static native void nativeSfSelect(int presetIndex);
}
