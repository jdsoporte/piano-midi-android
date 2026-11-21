// Motor para SoundFonts (.sf2) basado en TinySoundFont (MIT).
// TinySoundFont no maneja el pedal de sustain (CC64); aqui se implementa.
#pragma once

#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

struct tsf;

namespace piano {

struct PresetInfo {
    int index = 0;
    int bank = 0;
    int program = 0;
    std::string name;
};

class SfEngine {
public:
    SfEngine();
    ~SfEngine();

    // Carga un .sf2 (lento: hilo que no sea el de audio). Devuelve false si falla.
    // Si ya habia uno cargado, lo reemplaza.
    bool load(const char* path);
    bool isLoaded();
    void unload();

    // Consultas (hilo de interfaz).
    std::vector<PresetInfo> presets();
    int findPreset(int bank, int program);

    void setSampleRate(float sr);

    // Seguros desde otro hilo (hilo MIDI). Un productor a la vez (se serializan).
    void noteOn(int key, int vel);
    void noteOff(int key);
    void control(int cc, int value);
    void pitchBend(int value14);
    void selectPreset(int presetIndex);
    void allSoundOff();

    // Hilo de audio: sobrescribe 'out' con 'frames' cuadros estereo intercalados.
    void render(float* out, int frames);

private:
    struct Event {
        uint8_t type = 0;  // 1 on, 2 off, 3 cc, 4 bend, 5 preset, 6 sound off
        int a = 0;
        int b = 0;
    };
    static constexpr uint32_t kQueueSize = 1024;

    void push(uint8_t type, int a, int b);
    void handle(const Event& e);  // con el mutex tomado

    std::mutex mu_;
    tsf* tsf_ = nullptr;
    int sampleRate_ = 48000;
    bool pedalDown_ = false;
    bool held_[128] = {};

    Event queue_[kQueueSize];
    std::atomic<uint32_t> head_{0};
    std::atomic<uint32_t> tail_{0};
    std::atomic_flag producerLock_ = ATOMIC_FLAG_INIT;
};

}  // namespace piano
