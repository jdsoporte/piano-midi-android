// Motor de sampler de piano. Portable (sin dependencias de Android) para poder
// probarlo en Linux. Datos de mapeo verificados contra Salamander Grand Piano V3.
#pragma once

#include <atomic>
#include <cstdint>

namespace piano {

constexpr int kNotes = 30;       // grabaciones: una cada 3 semitonos, La0..Do8
constexpr int kLayers = 8;       // capas de intensidad usadas (de las 16 originales)
constexpr int kMaxVoices = 64;
constexpr int kFirstKey = 21;    // La0
constexpr int kLastKey = 108;    // Do8

// Tecla raiz de cada grabacion: 21, 24, 27, ... 108.
inline int rootKey(int noteIndex) { return kFirstKey + 3 * noteIndex; }

// Indice de grabacion que cubre una tecla (21-22 -> 0, 23-25 -> 1, ... 107-108 -> 29).
int noteIndexForKey(int key);

// Numero de capa original (v2, v4, ... v16) de cada capa usada y su velocidad maxima.
extern const int kLayerFileIndex[kLayers];
extern const int kLayerMaxVel[kLayers];

// Capa usada (0..7) para una velocidad MIDI 1..127.
int layerForVelocity(int vel);

struct Sample {
    const int16_t* data = nullptr;  // estereo intercalado, 48 kHz
    uint32_t frames = 0;
};

struct SampleBank {
    Sample s[kNotes][kLayers];
};

class Engine {
public:
    Engine();

    // Solo con el audio detenido.
    void setBank(const SampleBank* bank) { bank_ = bank; }
    void setSampleRate(float sr);

    // Seguros para llamar desde otro hilo (hilo MIDI). Un productor a la vez (se serializan).
    void noteOn(int key, int vel);
    void noteOff(int key);
    void controlChange(int cc, int value);
    void setVolume(float v) { volume_.store(v, std::memory_order_relaxed); }

    // Hilo de audio: escribe 'frames' cuadros estereo intercalados en float.
    void render(float* out, int frames);

    int activeVoices() const;

private:
    struct Voice {
        const int16_t* data = nullptr;
        uint32_t frames = 0;
        double pos = 0.0;
        double step = 1.0;
        float gain = 0.f;
        float env = 1.f;
        float relMul = 1.f;
        bool active = false;
        bool releasing = false;
        bool held = false;  // tecla suelta pero pedal pisado
        int key = 0;
        uint64_t order = 0;
    };

    struct Event {
        uint8_t type = 0;  // 1 nota on, 2 nota off, 3 control
        uint8_t a = 0;
        uint8_t b = 0;
    };

    static constexpr uint32_t kQueueSize = 1024;  // potencia de 2

    void push(uint8_t type, uint8_t a, uint8_t b);
    void handle(const Event& e);
    void doNoteOn(int key, int vel);
    void doNoteOff(int key);
    void doControl(int cc, int value);
    void startRelease(Voice& v, float seconds);
    Voice* allocateVoice();
    void renderVoice(Voice& v, float* mix, int frames);

    const SampleBank* bank_ = nullptr;
    float sampleRate_ = 48000.f;
    std::atomic<float> volume_{1.5f};

    Voice voices_[kMaxVoices];
    bool pedalDown_ = false;
    uint64_t counter_ = 0;

    Event queue_[kQueueSize];
    std::atomic<uint32_t> head_{0};  // escribe el productor
    std::atomic<uint32_t> tail_{0};  // lee el audio
    std::atomic_flag producerLock_ = ATOMIC_FLAG_INIT;

    float mix_[8192 * 2];  // buffer de mezcla (maximo 8192 cuadros por bloque)
};

}  // namespace piano
