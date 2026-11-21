// Etapa final de audio: reverb, volumen, limitador suave y medidor de pico.
#pragma once

#include <atomic>
#include <vector>

namespace piano {

// Reverb estereo tipo Freeverb (8 filtros peine + 4 pasatodo por canal).
class Reverb {
public:
    void init(float sampleRate);
    void setMix(float m) { mix_ = m; }
    // 'buf' = estereo intercalado. Suma la cola de reverb al sonido seco.
    void process(float* buf, int frames);

private:
    struct Comb {
        std::vector<float> buf;
        int idx = 0;
        float store = 0.f;
    };
    struct Allpass {
        std::vector<float> buf;
        int idx = 0;
    };
    void clear();

    Comb combL_[8], combR_[8];
    Allpass apL_[4], apR_[4];
    float feedback_ = 0.86f;
    float damp_ = 0.25f;
    float mix_ = 0.f;
    bool clean_ = true;
};

class Master {
public:
    void init(float sampleRate);
    void setVolume(float v) { volume_.store(v, std::memory_order_relaxed); }
    void setReverb(float m) { reverbMix_.store(m, std::memory_order_relaxed); }

    // Hilo de audio. Aplica reverb, volumen y limitador al bloque.
    void process(float* buf, int frames);

    // Pico de salida desde la ultima llamada (0..1), para el medidor.
    float takePeak() { return peak_.exchange(0.f, std::memory_order_relaxed); }

private:
    Reverb reverb_;
    std::atomic<float> volume_{1.5f};
    std::atomic<float> reverbMix_{0.f};
    std::atomic<float> peak_{0.f};
    float curVolume_ = 1.5f;
};

}  // namespace piano
