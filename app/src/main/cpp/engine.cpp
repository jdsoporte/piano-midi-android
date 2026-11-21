#include "engine.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace piano {

const int kLayerFileIndex[kLayers] = {2, 4, 6, 8, 10, 12, 14, 16};
// Rangos de velocidad originales: v2 27-34, v4 37-43, v6 47-50, v8 57-64,
// v10 73-80, v12 89-96, v14 105-112, v16 121-127. Cada capa par absorbe a la impar anterior.
const int kLayerMaxVel[kLayers] = {34, 43, 50, 64, 80, 96, 112, 127};

int noteIndexForKey(int key) {
    if (key < kFirstKey || key > kLastKey) return -1;
    if (key <= 22) return 0;
    return (key - 23) / 3 + 1;
}

int layerForVelocity(int vel) {
    for (int i = 0; i < kLayers; ++i) {
        if (vel <= kLayerMaxVel[i]) return i;
    }
    return kLayers - 1;
}

namespace {

// Liberacion del apagador (SFZ: 1 s) mas el extra de las notas sin apagadores
// (teclas 89-94: +3 s, desde la 95: +4 s).
float releaseSeconds(int key) {
    float extra = 0.f;
    if (key >= 95) extra = 4.f;
    else if (key >= 89) extra = 3.f;
    return 1.0f + extra;
}

constexpr float kSilence = 1.0e-4f;  // -80 dB
// Las grabaciones de Salamander son suaves (capa baja de Do4: pico ~ -15 dBFS), por eso
// no se atenuan aqui; el limitador suave de salida evita pasar de 1.0.
constexpr float kVoiceGain = 1.0f / 32768.f;

// Limitador suave: lineal hasta 'kKnee' y despues curva que nunca pasa de 'kCeiling'
// (-1.4 dBFS). Deja margen para que el amplificador del celular y la entrada del piano
// no se saturen con notas fuertes.
constexpr float kKnee = 0.55f;
constexpr float kCeiling = 0.85f;

inline float softClip(float x) {
    float a = std::fabs(x);
    if (a <= kKnee) return x;
    float y = kKnee + (kCeiling - kKnee) * std::tanh((a - kKnee) / (kCeiling - kKnee));
    return x < 0 ? -y : y;
}

}  // namespace

Engine::Engine() {
    std::memset(mix_, 0, sizeof(mix_));
}

void Engine::setSampleRate(float sr) {
    if (sr > 8000.f) sampleRate_ = sr;
}

int Engine::activeVoices() const {
    int n = 0;
    for (const Voice& v : voices_) n += v.active ? 1 : 0;
    return n;
}

void Engine::push(uint8_t type, uint8_t a, uint8_t b) {
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
    }  // si esta llena se descarta el evento
    producerLock_.clear(std::memory_order_release);
}

void Engine::noteOn(int key, int vel) { push(1, (uint8_t)key, (uint8_t)vel); }
void Engine::noteOff(int key) { push(2, (uint8_t)key, 0); }
void Engine::controlChange(int cc, int value) { push(3, (uint8_t)cc, (uint8_t)value); }

void Engine::handle(const Event& e) {
    switch (e.type) {
        case 1: doNoteOn(e.a, e.b); break;
        case 2: doNoteOff(e.a); break;
        case 3: doControl(e.a, e.b); break;
        default: break;
    }
}

void Engine::startRelease(Voice& v, float seconds) {
    v.releasing = true;
    v.held = false;
    // Factor por cuadro que lleva la envolvente a -60 dB en 'seconds'.
    v.relMul = std::pow(10.f, -3.f / (seconds * sampleRate_));
}

Engine::Voice* Engine::allocateVoice() {
    for (Voice& v : voices_) {
        if (!v.active) return &v;
    }
    Voice* best = nullptr;
    for (Voice& v : voices_) {  // primero, la liberacion mas baja
        if (v.releasing && (!best || v.env < best->env)) best = &v;
    }
    if (best) return best;
    for (Voice& v : voices_) {  // si no, la mas antigua
        if (!best || v.order < best->order) best = &v;
    }
    return best;
}

void Engine::doNoteOn(int key, int vel) {
    if (vel <= 0) {
        doNoteOff(key);
        return;
    }
    if (!bank_) return;
    int ni = noteIndexForKey(key);
    if (ni < 0) return;
    vel = std::min(vel, 127);

    // Capa pedida; si falta, la mas cercana que exista.
    int want = layerForVelocity(vel);
    int layer = -1;
    for (int d = 0; d < kLayers && layer < 0; ++d) {
        int lo = want - d, hi = want + d;
        if (lo >= 0 && bank_->s[ni][lo].data) layer = lo;
        else if (hi < kLayers && bank_->s[ni][hi].data) layer = hi;
    }
    if (layer < 0) return;
    const Sample& smp = bank_->s[ni][layer];
    if (smp.frames < 8) return;

    // Misma tecla otra vez: la anterior se apaga en 0.5 s (SFZ off_time).
    for (Voice& v : voices_) {
        if (v.active && v.key == key && !v.releasing) startRelease(v, 0.5f);
        else if (v.active && v.key == key && v.held) startRelease(v, 0.5f);
    }

    Voice* v = allocateVoice();
    if (!v) return;
    *v = Voice();
    v->active = true;
    v->key = key;
    v->data = smp.data;
    v->frames = smp.frames;
    v->pos = 0.0;
    v->step = std::pow(2.0, (key - rootKey(ni)) / 12.0) * (48000.0 / sampleRate_);
    float rel = (float)vel / (float)kLayerMaxVel[want];
    v->gain = std::sqrt(std::min(rel, 1.f)) * kVoiceGain;
    v->env = 1.f;
    v->order = ++counter_;
}

void Engine::doNoteOff(int key) {
    for (Voice& v : voices_) {
        if (v.active && v.key == key && !v.releasing && !v.held) {
            if (pedalDown_) v.held = true;
            else startRelease(v, releaseSeconds(key));
        }
    }
}

void Engine::doControl(int cc, int value) {
    if (cc == 64) {
        bool down = value >= 64;
        if (pedalDown_ && !down) {
            for (Voice& v : voices_) {
                if (v.active && v.held) startRelease(v, releaseSeconds(v.key));
            }
        }
        pedalDown_ = down;
    } else if (cc == 123) {  // todas las notas off
        for (Voice& v : voices_) {
            if (v.active && !v.releasing) startRelease(v, releaseSeconds(v.key));
        }
    } else if (cc == 120) {  // todo el sonido off
        for (Voice& v : voices_) v.active = false;
    }
}

void Engine::renderVoice(Voice& v, float* mix, int frames) {
    const int16_t* d = v.data;
    const uint32_t n = v.frames;
    double pos = v.pos;
    const double step = v.step;
    float env = v.env;
    const float gain = v.gain;
    const bool rel = v.releasing;
    const float relMul = v.relMul;

    for (int i = 0; i < frames; ++i) {
        uint32_t idx = (uint32_t)pos;
        if (idx + 3 >= n) {
            v.active = false;
            break;
        }
        float t = (float)(pos - (double)idx);
        uint32_t im = idx > 0 ? idx - 1 : 0;
        const int16_t* pm = d + 2 * im;
        const int16_t* p0 = d + 2 * idx;
        const int16_t* p1 = p0 + 2;
        const int16_t* p2 = p0 + 4;

        float l, r;
        {
            float ym = pm[0], y0 = p0[0], y1 = p1[0], y2 = p2[0];
            float c1 = 0.5f * (y1 - ym);
            float c2 = ym - 2.5f * y0 + 2.f * y1 - 0.5f * y2;
            float c3 = 0.5f * (y2 - ym) + 1.5f * (y0 - y1);
            l = ((c3 * t + c2) * t + c1) * t + y0;
        }
        {
            float ym = pm[1], y0 = p0[1], y1 = p1[1], y2 = p2[1];
            float c1 = 0.5f * (y1 - ym);
            float c2 = ym - 2.5f * y0 + 2.f * y1 - 0.5f * y2;
            float c3 = 0.5f * (y2 - ym) + 1.5f * (y0 - y1);
            r = ((c3 * t + c2) * t + c1) * t + y0;
        }
        float g = env * gain;
        mix[2 * i] += l * g;
        mix[2 * i + 1] += r * g;
        pos += step;
        if (rel) {
            env *= relMul;
            if (env < kSilence) {
                v.active = false;
                break;
            }
        }
    }
    v.pos = pos;
    v.env = env;
}

void Engine::render(float* out, int frames) {
    // 1) eventos pendientes
    uint32_t t = tail_.load(std::memory_order_relaxed);
    uint32_t h = head_.load(std::memory_order_acquire);
    while (t != h) {
        handle(queue_[t & (kQueueSize - 1)]);
        ++t;
    }
    tail_.store(t, std::memory_order_release);

    // 2) mezcla por bloques
    const float vol = volume_.load(std::memory_order_relaxed);
    int done = 0;
    while (done < frames) {
        int n = std::min(frames - done, 8192);
        std::memset(mix_, 0, sizeof(float) * 2 * n);
        for (Voice& v : voices_) {
            if (v.active) renderVoice(v, mix_, n);
        }
        float* o = out + 2 * done;
        float blockPeak = 0.f;
        for (int i = 0; i < 2 * n; ++i) {
            float y = softClip(mix_[i] * vol);
            o[i] = y;
            blockPeak = std::max(blockPeak, std::fabs(y));
        }
        if (blockPeak > peak_.load(std::memory_order_relaxed)) {
            peak_.store(blockPeak, std::memory_order_relaxed);
        }
        done += n;
    }
}

}  // namespace piano
