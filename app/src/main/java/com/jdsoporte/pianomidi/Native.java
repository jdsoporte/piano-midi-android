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

    static native void nativeSetVolume(float v);
}
