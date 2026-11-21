#include "master.h"

#include <algorithm>
#include <cmath>

namespace piano {

namespace {

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

// Longitudes de Freeverb a 44.1 kHz (dominio publico).
const int kCombLen[8] = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
const int kAllpassLen[4] = {556, 441, 341, 225};
constexpr int kStereoSpread = 23;
constexpr float kInputGain = 0.015f;
constexpr float kWetScale = 3.0f;

}  // namespace

void Reverb::init(float sampleRate) {
    float k = sampleRate / 44100.f;
    for (int i = 0; i < 8; ++i) {
        combL_[i].buf.assign((size_t)std::max(1, (int)(kCombLen[i] * k)), 0.f);
        combR_[i].buf.assign((size_t)std::max(1, (int)((kCombLen[i] + kStereoSpread) * k)), 0.f);
    }
    for (int i = 0; i < 4; ++i) {
        apL_[i].buf.assign((size_t)std::max(1, (int)(kAllpassLen[i] * k)), 0.f);
        apR_[i].buf.assign((size_t)std::max(1, (int)((kAllpassLen[i] + kStereoSpread) * k)), 0.f);
    }
    clear();
}

void Reverb::clear() {
    for (auto& c : combL_) { std::fill(c.buf.begin(), c.buf.end(), 0.f); c.idx = 0; c.store = 0.f; }
    for (auto& c : combR_) { std::fill(c.buf.begin(), c.buf.end(), 0.f); c.idx = 0; c.store = 0.f; }
    for (auto& a : apL_) { std::fill(a.buf.begin(), a.buf.end(), 0.f); a.idx = 0; }
    for (auto& a : apR_) { std::fill(a.buf.begin(), a.buf.end(), 0.f); a.idx = 0; }
    clean_ = true;
}

void Reverb::process(float* buf, int frames) {
    if (mix_ <= 0.0005f) {
        if (!clean_) clear();
        return;
    }
    clean_ = false;
    const float wet = mix_ * kWetScale;
    const float d1 = damp_, d2 = 1.f - damp_;
    for (int n = 0; n < frames; ++n) {
        float in = (buf[2 * n] + buf[2 * n + 1]) * kInputGain;
        float oL = 0.f, oR = 0.f;
        for (int i = 0; i < 8; ++i) {
            Comb& c = combL_[i];
            float t = c.buf[c.idx];
            c.store = t * d2 + c.store * d1;
            if (std::fabs(c.store) < 1e-18f) c.store = 0.f;  // evita denormales
            c.buf[c.idx] = in + c.store * feedback_;
            if (++c.idx >= (int)c.buf.size()) c.idx = 0;
            oL += t;
            Comb& r = combR_[i];
            t = r.buf[r.idx];
            r.store = t * d2 + r.store * d1;
            if (std::fabs(r.store) < 1e-18f) r.store = 0.f;
            r.buf[r.idx] = in + r.store * feedback_;
            if (++r.idx >= (int)r.buf.size()) r.idx = 0;
            oR += t;
        }
        for (int i = 0; i < 4; ++i) {
            Allpass& a = apL_[i];
            float b = a.buf[a.idx];
            float o = -oL + b;
            a.buf[a.idx] = oL + b * 0.5f;
            if (++a.idx >= (int)a.buf.size()) a.idx = 0;
            oL = o;
            Allpass& q = apR_[i];
            b = q.buf[q.idx];
            o = -oR + b;
            q.buf[q.idx] = oR + b * 0.5f;
            if (++q.idx >= (int)q.buf.size()) q.idx = 0;
            oR = o;
        }
        buf[2 * n] += oL * wet;
        buf[2 * n + 1] += oR * wet;
    }
}

void Master::init(float sampleRate) {
    reverb_.init(sampleRate);
}

void Master::process(float* buf, int frames) {
    reverb_.setMix(reverbMix_.load(std::memory_order_relaxed));
    reverb_.process(buf, frames);

    // Volumen con rampa lineal dentro del bloque (sin chasquidos al mover la barra).
    const float target = volume_.load(std::memory_order_relaxed);
    const float step = frames > 0 ? (target - curVolume_) / (float)frames : 0.f;
    float g = curVolume_;
    float blockPeak = 0.f;
    for (int i = 0; i < frames; ++i) {
        g += step;
        float l = softClip(buf[2 * i] * g);
        float r = softClip(buf[2 * i + 1] * g);
        buf[2 * i] = l;
        buf[2 * i + 1] = r;
        blockPeak = std::max(blockPeak, std::max(std::fabs(l), std::fabs(r)));
    }
    curVolume_ = target;
    if (blockPeak > peak_.load(std::memory_order_relaxed)) {
        peak_.store(blockPeak, std::memory_order_relaxed);
    }
}

}  // namespace piano
