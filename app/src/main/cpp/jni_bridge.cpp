// Puente JNI + salida de audio con Oboe.
#include <jni.h>
#include <android/log.h>
#include <dirent.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <atomic>
#include <cstdio>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include <oboe/Oboe.h>

#include "engine.h"
#include "master.h"
#include "sf_engine.h"

#define TAG "PianoEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

piano::Engine g_engine;          // modo 0: piano de concierto (Salamander)
piano::SfEngine g_sf;            // modo 1: SoundFonts (.sf2)
piano::Master g_master;          // reverb, volumen, limitador
piano::SampleBank g_bank;
std::atomic<int> g_mode{0};

struct Mapping {
    void* addr;
    size_t len;
};
std::vector<Mapping> g_maps;

void unloadBank() {
    for (const Mapping& m : g_maps) munmap(m.addr, m.len);
    g_maps.clear();
    g_bank = piano::SampleBank();
}

// Archivos: <dir>/<teclaRaiz>_<numeroCapaOriginal>.pcm  (int16 estereo, 48 kHz)
int loadBank(const std::string& dir) {
    unloadBank();
    int loaded = 0;
    for (int n = 0; n < piano::kNotes; ++n) {
        for (int l = 0; l < piano::kLayers; ++l) {
            char path[512];
            std::snprintf(path, sizeof(path), "%s/%d_%d.pcm", dir.c_str(),
                          piano::rootKey(n), piano::kLayerFileIndex[l]);
            int fd = open(path, O_RDONLY);
            if (fd < 0) continue;
            struct stat st;
            if (fstat(fd, &st) != 0 || st.st_size < 64) {
                close(fd);
                continue;
            }
            size_t len = (size_t)st.st_size;
            // POPULATE: lee el archivo a la cache de paginas al cargar, para que el
            // primer toque no espere al disco. Las paginas siguen siendo liberables.
            void* addr = mmap(nullptr, len, PROT_READ, MAP_PRIVATE | MAP_POPULATE, fd, 0);
            close(fd);
            if (addr == MAP_FAILED) {
                LOGE("mmap fallo: %s", path);
                continue;
            }
            g_maps.push_back({addr, len});
            g_bank.s[n][l].data = static_cast<const int16_t*>(addr);
            g_bank.s[n][l].frames = (uint32_t)(len / 4);
            ++loaded;
        }
    }
    g_engine.setBank(loaded > 0 ? &g_bank : nullptr);
    LOGI("muestras cargadas: %d de %d", loaded, piano::kNotes * piano::kLayers);
    return loaded;
}

class AudioOut : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    bool start() {
        std::lock_guard<std::mutex> lock(mutex_);
        return openLocked();
    }

    void stop() {
        std::lock_guard<std::mutex> lock(mutex_);
        wanted_ = false;
        if (stream_) {
            stream_->stop();
            stream_->close();
            stream_.reset();
        }
    }

    // Buffer = 'n' rafagas (2 = menor latencia, 4 = mas margen contra cortes).
    void setBursts(int n) {
        std::lock_guard<std::mutex> lock(mutex_);
        bursts_ = n < 2 ? 2 : (n > 6 ? 6 : n);
        applyBufferLocked();
    }

    int xruns() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!stream_) return -1;
        auto r = stream_->getXRunCount();
        return r ? r.value() : -1;
    }

    std::string info() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!stream_) return "audio detenido";
        char buf[320];
        int sr = stream_->getSampleRate();
        int bs = stream_->getBufferSizeInFrames();
        std::snprintf(buf, sizeof(buf),
                      "audio %d Hz, rafaga %d, buffer %d cuadros (~%.0f ms), modo %s, %s",
                      sr, stream_->getFramesPerBurst(), bs,
                      sr > 0 ? 1000.0 * bs / sr : 0.0,
                      stream_->getPerformanceMode() == oboe::PerformanceMode::LowLatency
                              ? "baja latencia" : "normal",
                      stream_->getSharingMode() == oboe::SharingMode::Exclusive
                              ? "exclusivo" : "compartido");
        return buf;
    }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void* data,
                                          int32_t numFrames) override {
        float* out = static_cast<float*>(data);
        if (g_mode.load(std::memory_order_relaxed) == 1) g_sf.render(out, numFrames);
        else g_engine.render(out, numFrames);
        g_master.process(out, numFrames);
        return oboe::DataCallbackResult::Continue;
    }

    void onErrorAfterClose(oboe::AudioStream*, oboe::Result) override {
        // Por ejemplo, al conectar o desconectar audifonos: se reabre el flujo.
        std::lock_guard<std::mutex> lock(mutex_);
        stream_.reset();
        if (wanted_) openLocked();
    }

private:
    bool openLocked() {
        wanted_ = true;
        oboe::AudioStreamBuilder b;
        b.setDirection(oboe::Direction::Output)
                ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
                ->setSharingMode(oboe::SharingMode::Exclusive)
                ->setFormat(oboe::AudioFormat::Float)
                ->setChannelCount(oboe::ChannelCount::Stereo)
                ->setSampleRate(48000)
                ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
                ->setUsage(oboe::Usage::Media)
                ->setContentType(oboe::ContentType::Music)
                ->setDataCallback(this)
                ->setErrorCallback(this);
        oboe::Result r = b.openStream(stream_);
        if (r != oboe::Result::OK) {
            LOGE("no se pudo abrir el audio: %s", oboe::convertToText(r));
            stream_.reset();
            return false;
        }
        float sr = (float)stream_->getSampleRate();
        g_engine.setSampleRate(sr);
        g_sf.setSampleRate(sr);
        g_master.init(sr);  // el flujo aun no esta corriendo
        applyBufferLocked();
        r = stream_->requestStart();
        if (r != oboe::Result::OK) {
            LOGE("no se pudo iniciar el audio: %s", oboe::convertToText(r));
            stream_->close();
            stream_.reset();
            return false;
        }
        return true;
    }

    void applyBufferLocked() {
        if (!stream_) return;
        int want = stream_->getFramesPerBurst() * bursts_;
        int cap = stream_->getBufferCapacityInFrames();
        if (cap > 0 && want > cap) want = cap;
        stream_->setBufferSizeInFrames(want);
    }

    std::mutex mutex_;
    std::shared_ptr<oboe::AudioStream> stream_;
    bool wanted_ = false;
    int bursts_ = 3;
};

AudioOut g_audio;

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_jdsoporte_pianomidi_Native_nativeLoad(JNIEnv* env, jclass, jstring jdir) {
    const char* c = env->GetStringUTFChars(jdir, nullptr);
    std::string dir(c);
    env->ReleaseStringUTFChars(jdir, c);
    return loadBank(dir);
}

JNIEXPORT jboolean JNICALL
Java_com_jdsoporte_pianomidi_Native_nativeStart(JNIEnv*, jclass) {
    return g_audio.start() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_jdsoporte_pianomidi_Native_nativeStop(JNIEnv*, jclass) {
    g_audio.stop();
}

JNIEXPORT jstring JNICALL
Java_com_jdsoporte_pianomidi_Native_nativeInfo(JNIEnv* env, jclass) {
    return env->NewStringUTF(g_audio.info().c_str());
}

JNIEXPORT void JNICALL
Java_com_jdsoporte_pianomidi_Native_nativeNoteOn(JNIEnv*, jclass, jint key, jint vel) {
    if (g_mode.load() == 1) g_sf.noteOn(key, vel);
    else g_engine.noteOn(key, vel);
}

JNIEXPORT void JNICALL
Java_com_jdsoporte_pianomidi_Native_nativeNoteOff(JNIEnv*, jclass, jint key) {
    if (g_mode.load() == 1) g_sf.noteOff(key);
    else g_engine.noteOff(key);
}

JNIEXPORT void JNICALL
Java_com_jdsoporte_pianomidi_Native_nativeControl(JNIEnv*, jclass, jint cc, jint value) {
    if (g_mode.load() == 1) g_sf.control(cc, value);
    else g_engine.controlChange(cc, value);
}

JNIEXPORT void JNICALL
Java_com_jdsoporte_pianomidi_Native_nativePitchBend(JNIEnv*, jclass, jint value14) {
    if (g_mode.load() == 1) g_sf.pitchBend(value14);
}

JNIEXPORT void JNICALL
Java_com_jdsoporte_pianomidi_Native_nativeSetVolume(JNIEnv*, jclass, jfloat v) {
    g_master.setVolume(v);
}

JNIEXPORT void JNICALL
Java_com_jdsoporte_pianomidi_Native_nativeSetReverb(JNIEnv*, jclass, jfloat m) {
    g_master.setReverb(m);
}

JNIEXPORT void JNICALL
Java_com_jdsoporte_pianomidi_Native_nativeSetLatency(JNIEnv*, jclass, jint bursts) {
    g_audio.setBursts(bursts);
}

// 0 = piano de concierto, 1 = SoundFont. Silencia el motor que deja de usarse.
JNIEXPORT void JNICALL
Java_com_jdsoporte_pianomidi_Native_nativeSetMode(JNIEnv*, jclass, jint mode) {
    int old = g_mode.load();
    if (old == mode) return;
    if (old == 1) g_sf.allSoundOff();
    else g_engine.controlChange(120, 0);
    g_mode.store(mode == 1 ? 1 : 0);
}

JNIEXPORT jboolean JNICALL
Java_com_jdsoporte_pianomidi_Native_nativeSfLoad(JNIEnv* env, jclass, jstring jpath) {
    const char* c = env->GetStringUTFChars(jpath, nullptr);
    std::string path(c);
    env->ReleaseStringUTFChars(jpath, c);
    bool ok = g_sf.load(path.c_str());
    LOGI("SoundFont %s: %s", ok ? "cargado" : "ERROR al cargar", path.c_str());
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_jdsoporte_pianomidi_Native_nativeSfLoaded(JNIEnv*, jclass) {
    return g_sf.isLoaded() ? JNI_TRUE : JNI_FALSE;
}

// Cada elemento: "indice|banco|programa|nombre".
JNIEXPORT jobjectArray JNICALL
Java_com_jdsoporte_pianomidi_Native_nativeSfPresets(JNIEnv* env, jclass) {
    std::vector<piano::PresetInfo> list = g_sf.presets();
    jclass strCls = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray((jsize)list.size(), strCls, nullptr);
    for (size_t i = 0; i < list.size(); ++i) {
        std::string name = list[i].name;
        while (!name.empty() && (name.back() == ' ' || name.back() == 0)) name.pop_back();
        char buf[128];
        std::snprintf(buf, sizeof(buf), "%d|%d|%d|%s", list[i].index, list[i].bank,
                      list[i].program, name.c_str());
        env->SetObjectArrayElement(arr, (jsize)i, env->NewStringUTF(buf));
    }
    return arr;
}

JNIEXPORT jint JNICALL
Java_com_jdsoporte_pianomidi_Native_nativeSfFindPreset(JNIEnv*, jclass, jint bank, jint program) {
    return g_sf.findPreset(bank, program);
}

JNIEXPORT void JNICALL
Java_com_jdsoporte_pianomidi_Native_nativeSfSelect(JNIEnv*, jclass, jint index) {
    g_sf.selectPreset(index);
}

JNIEXPORT jfloat JNICALL
Java_com_jdsoporte_pianomidi_Native_nativePeak(JNIEnv*, jclass) {
    return g_master.takePeak();
}

JNIEXPORT jint JNICALL
Java_com_jdsoporte_pianomidi_Native_nativeXRuns(JNIEnv*, jclass) {
    return g_audio.xruns();
}

}  // extern "C"
