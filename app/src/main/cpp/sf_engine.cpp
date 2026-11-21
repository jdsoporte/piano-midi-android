#include "sf_engine.h"

#include <algorithm>
#include <cstring>

#define TSF_IMPLEMENTATION
#include "third_party/tsf/tsf.h"

namespace piano {

namespace {
constexpr int kMaxVoices = 96;
}

SfEngine::SfEngine() = default;

SfEngine::~SfEngine() {
    unload();
}

bool SfEngine::load(const char* path) {
    tsf* n = tsf_load_filename(path);
    if (!n) return false;
    // Todo lo que reserva memoria se hace aqui, no en el hilo de audio.
    tsf_set_max_voices(n, kMaxVoices);
    int sr;
    {
        std::lock_guard<std::mutex> lock(mu_);
        sr = sampleRate_;
    }
    tsf_set_output(n, TSF_STEREO_INTERLEAVED, sr, 0.f);
    tsf_channel_set_presetindex(n, 0, 0);  // crea el canal 0
    int first = tsf_get_presetindex(n, 0, 0);  // piano 1 GM, si existe
    if (first >= 0) tsf_channel_set_presetindex(n, 0, first);

    tsf* old;
    {
        std::lock_guard<std::mutex> lock(mu_);
        old = tsf_;
        tsf_ = n;
        pedalDown_ = false;
        std::memset(held_, 0, sizeof(held_));
    }
    if (old) tsf_close(old);
    return true;
}

void SfEngine::unload() {
    tsf* old;
    {
        std::lock_guard<std::mutex> lock(mu_);
        old = tsf_;
        tsf_ = nullptr;
    }
    if (old) tsf_close(old);
}

bool SfEngine::isLoaded() {
    std::lock_guard<std::mutex> lock(mu_);
    return tsf_ != nullptr;
}

std::vector<PresetInfo> SfEngine::presets() {
    std::vector<PresetInfo> out;
    std::lock_guard<std::mutex> lock(mu_);
    if (!tsf_) return out;
    for (int i = 0; i < tsf_->presetNum; ++i) {
        PresetInfo p;
        p.index = i;
        p.bank = tsf_->presets[i].bank;
        p.program = tsf_->presets[i].preset;
        char name[21];
        std::memcpy(name, tsf_->presets[i].presetName, 20);
        name[20] = 0;
        p.name = name;
        out.push_back(p);
    }
    return out;
}

int SfEngine::findPreset(int bank, int program) {
    std::lock_guard<std::mutex> lock(mu_);
    if (!tsf_) return -1;
    return tsf_get_presetindex(tsf_, bank, program);
}

void SfEngine::setSampleRate(float sr) {
    std::lock_guard<std::mutex> lock(mu_);
    sampleRate_ = (int)sr;
    if (tsf_) tsf_set_output(tsf_, TSF_STEREO_INTERLEAVED, sampleRate_, 0.f);
}

void SfEngine::push(uint8_t type, int a, int b) {
    while (producerLock_.test_and_set(std::memory_order_acquire)) {
    }
    uint32_t h = head_.load(std::memory_order_relaxed);
    uint32_t t = tail_.load(std::memory_order_acquire);
    if (h - t < kQueueSize) {
        Event& e = queue_[h & (kQueueSize - 1)];
        e.type = type;
        e.a = a;
        e.b = b;
        head_.store(h + 1, std::memory_order_release);
    }
    producerLock_.clear(std::memory_order_release);
}

void SfEngine::noteOn(int key, int vel) { push(1, key, vel); }
void SfEngine::noteOff(int key) { push(2, key, 0); }
void SfEngine::control(int cc, int value) { push(3, cc, value); }
void SfEngine::pitchBend(int value14) { push(4, value14, 0); }
void SfEngine::selectPreset(int presetIndex) { push(5, presetIndex, 0); }
void SfEngine::allSoundOff() { push(6, 0, 0); }

void SfEngine::handle(const Event& e) {
    switch (e.type) {
        case 1: {  // nota on
            if (e.a < 0 || e.a > 127) break;
            if (e.b <= 0) { handle(Event{2, e.a, 0}); break; }
            held_[e.a] = false;  // tecla pulsada de nuevo: ya no esta "sostenida"
            tsf_channel_note_on(tsf_, 0, e.a, std::min(e.b, 127) / 127.0f);
            break;
        }
        case 2: {  // nota off
            if (e.a < 0 || e.a > 127) break;
            if (pedalDown_) held_[e.a] = true;
            else tsf_channel_note_off(tsf_, 0, e.a);
            break;
        }
        case 3: {  // control
            if (e.a == 64) {
                bool down = e.b >= 64;
                if (pedalDown_ && !down) {
                    for (int k = 0; k < 128; ++k) {
                        if (held_[k]) {
                            held_[k] = false;
                            tsf_channel_note_off(tsf_, 0, k);
                        }
                    }
                }
                pedalDown_ = down;
            } else if (e.a == 123) {
                tsf_channel_note_off_all(tsf_, 0);
                std::memset(held_, 0, sizeof(held_));
            } else if (e.a == 120) {
                tsf_channel_sounds_off_all(tsf_, 0);
                std::memset(held_, 0, sizeof(held_));
                pedalDown_ = false;
            }
            break;
        }
        case 4:
            tsf_channel_set_pitchwheel(tsf_, 0, std::max(0, std::min(e.a, 16383)));
            break;
        case 5:
            if (e.a >= 0 && e.a < tsf_->presetNum) {
                tsf_channel_sounds_off_all(tsf_, 0);
                std::memset(held_, 0, sizeof(held_));
                tsf_channel_set_presetindex(tsf_, 0, e.a);
            }
            break;
        case 6:
            tsf_channel_sounds_off_all(tsf_, 0);
            std::memset(held_, 0, sizeof(held_));
            pedalDown_ = false;
            break;
        default: break;
    }
}

void SfEngine::render(float* out, int frames) {
    std::unique_lock<std::mutex> lock(mu_, std::try_to_lock);
    if (!lock.owns_lock()) {  // se esta cargando/consultando: este bloque va en silencio
        std::memset(out, 0, sizeof(float) * 2 * frames);
        return;
    }
    uint32_t t = tail_.load(std::memory_order_relaxed);
    uint32_t h = head_.load(std::memory_order_acquire);
    while (t != h) {
        if (tsf_) handle(queue_[t & (kQueueSize - 1)]);
        ++t;
    }
    tail_.store(t, std::memory_order_release);

    if (!tsf_) {
        std::memset(out, 0, sizeof(float) * 2 * frames);
        return;
    }
    tsf_render_float(tsf_, out, frames, 0);
}

}  // namespace piano
