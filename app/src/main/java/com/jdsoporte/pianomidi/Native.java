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

    static native void nativeNoteOn(int key, int vel);

    static native void nativeNoteOff(int key);

    static native void nativeControl(int cc, int value);

    static native void nativeSetVolume(float v);
}
