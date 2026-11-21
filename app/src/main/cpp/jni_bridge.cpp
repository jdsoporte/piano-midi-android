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

#define TAG "PianoEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

piano::Engine g_engine;
piano::SampleBank g_bank;

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

    std::string info() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!stream_) return "audio detenido";
        char buf[256];
        std::snprintf(buf, sizeof(buf),
                      "audio %d Hz, rafaga %d, buffer %d cuadros, modo %s, %s",
                      stream_->getSampleRate(), stream_->getFramesPerBurst(),
                      stream_->getBufferSizeInFrames(),
                      stream_->getPerformanceMode() == oboe::PerformanceMode::LowLatency
                              ? "baja latencia" : "normal",
                      stream_->getSharingMode() == oboe::SharingMode::Exclusive
                              ? "exclusivo" : "compartido");
        return buf;
    }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void* data,
                                          int32_t numFrames) override {
        g_engine.render(static_cast<float*>(data), numFrames);
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
        g_engine.setSampleRate((float)stream_->getSampleRate());
        // Buffer pequeno (2 rafagas) para baja latencia.
        stream_->setBufferSizeInFrames(stream_->getFramesPerBurst() * 2);
        r = stream_->requestStart();
        if (r != oboe::Result::OK) {
            LOGE("no se pudo iniciar el audio: %s", oboe::convertToText(r));
            stream_->close();
            stream_.reset();
            return false;
        }
        return true;
    }

    std::mutex mutex_;
    std::shared_ptr<oboe::AudioStream> stream_;
    bool wanted_ = false;
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
    g_engine.noteOn(key, vel);
}

JNIEXPORT void JNICALL
Java_com_jdsoporte_pianomidi_Native_nativeNoteOff(JNIEnv*, jclass, jint key) {
    g_engine.noteOff(key);
}

JNIEXPORT void JNICALL
Java_com_jdsoporte_pianomidi_Native_nativeControl(JNIEnv*, jclass, jint cc, jint value) {
    g_engine.controlChange(cc, value);
}

JNIEXPORT void JNICALL
Java_com_jdsoporte_pianomidi_Native_nativeSetVolume(JNIEnv*, jclass, jfloat v) {
    g_engine.setVolume(v);
}

}  // extern "C"
