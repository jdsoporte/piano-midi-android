// Pruebas del motor con muestras sinteticas (seno a la frecuencia de cada tecla raiz).
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <vector>

#include "../app/src/main/cpp/engine.h"
#include "../app/src/main/cpp/master.h"
#include "../app/src/main/cpp/sf_engine.h"

using namespace piano;

static int fallos = 0;
#define CHECK(cond, msg)                                         \
    do {                                                         \
        if (!(cond)) { std::printf("FALLA: %s\n", msg); ++fallos; } \
        else std::printf("ok:    %s\n", msg);                      \
    } while (0)

static std::vector<std::vector<int16_t>> g_store;

static SampleBank makeBank(float seconds = 3.f) {
    SampleBank b;
    g_store.clear();
    g_store.reserve(kNotes * kLayers);
    uint32_t frames = (uint32_t)(48000 * seconds);
    for (int n = 0; n < kNotes; ++n) {
        double f = 440.0 * std::pow(2.0, (rootKey(n) - 69) / 12.0);
        for (int l = 0; l < kLayers; ++l) {
            std::vector<int16_t> s(frames * 2);
            for (uint32_t i = 0; i < frames; ++i) {
                double v = std::sin(2 * M_PI * f * i / 48000.0) * 20000.0 * (l + 1) / kLayers;  // capas mas fuertes = mas volumen, como las grabaciones reales
                s[2 * i] = s[2 * i + 1] = (int16_t)v;
            }
            g_store.push_back(std::move(s));
            b.s[n][l].data = g_store.back().data();
            b.s[n][l].frames = frames;
        }
    }
    return b;
}

static double rms(const std::vector<float>& x, size_t from, size_t to) {
    double s = 0;
    for (size_t i = from; i < to; ++i) s += (double)x[i] * x[i];
    return std::sqrt(s / (to - from));
}

// Frecuencia por cruces por cero del canal izquierdo.
static double freqOf(const std::vector<float>& x, size_t frames) {
    int cross = 0;
    for (size_t i = 1; i < frames; ++i)
        if (x[2 * (i - 1)] <= 0 && x[2 * i] > 0) ++cross;
    return cross * 48000.0 / frames;
}

static Master g_master;

static std::vector<float> run(Engine& e, double seconds) {
    size_t total = (size_t)(48000 * seconds);
    std::vector<float> out(total * 2);
    size_t done = 0;
    while (done < total) {
        size_t n = std::min<size_t>(192, total - done);
        e.render(out.data() + 2 * done, (int)n);
        g_master.process(out.data() + 2 * done, (int)n);
        done += n;
    }
    return out;
}

int main() {
    // mapeo
    CHECK(noteIndexForKey(21) == 0 && noteIndexForKey(22) == 0, "teclas 21-22 -> grabacion 0 (La0)");
    CHECK(noteIndexForKey(60) == 13 && rootKey(13) == 60, "Do4 (60) tiene su propia grabacion");
    CHECK(noteIndexForKey(59) == 13 && noteIndexForKey(61) == 13, "59 y 61 usan la grabacion de Do4");
    CHECK(noteIndexForKey(107) == 29 && noteIndexForKey(108) == 29 && rootKey(29) == 108, "107-108 -> Do8");
    CHECK(noteIndexForKey(20) == -1 && noteIndexForKey(109) == -1, "fuera de rango se ignora");
    CHECK(layerForVelocity(1) == 0 && layerForVelocity(34) == 0 && layerForVelocity(35) == 1, "capas bajas");
    CHECK(layerForVelocity(64) == 3 && layerForVelocity(65) == 4 && layerForVelocity(127) == 7, "capas altas");

    g_master.init(48000.f);
    SampleBank bank = makeBank();
    Engine e;
    e.setSampleRate(48000.f);
    e.setBank(&bank);

    // tono: La4 (69) debe sonar a 440 Hz; tecla 70 (un semitono arriba de la raiz 69)
    e.noteOn(69, 100);
    auto o = run(e, 1.0);
    double f = freqOf(o, 48000);
    CHECK(std::fabs(f - 440.0) < 3.0, "La4 suena a ~440 Hz");
    e.controlChange(120, 0);

    e.noteOn(70, 100);
    o = run(e, 1.0);
    f = freqOf(o, 48000);
    double esperado = 440.0 * std::pow(2.0, 1.0 / 12.0);
    CHECK(std::fabs(f - esperado) < 4.0, "tecla 70 (+1 semitono de la grabacion) ~466 Hz");
    e.controlChange(120, 0);

    // intensidad: mas fuerte -> mas volumen
    e.noteOn(60, 20);
    auto suave = run(e, 0.5);
    e.controlChange(120, 0);
    e.noteOn(60, 120);
    auto fuerte = run(e, 0.5);
    e.controlChange(120, 0);
    CHECK(rms(fuerte, 0, fuerte.size()) > 2.0 * rms(suave, 0, suave.size()), "fuerza 120 suena mucho mas que fuerza 20");

    // liberacion sin pedal: tras note off, silencio en ~1.5 s
    e.noteOn(60, 100);
    run(e, 0.3);
    e.noteOff(60);
    o = run(e, 1.5);
    CHECK(rms(o, 0, 4800) > 0.05, "tras soltar, sigue sonando un momento (liberacion)");
    CHECK(rms(o, o.size() - 4800, o.size()) < 0.002, "a los 1.5 s la nota esta apagada");
    CHECK(e.activeVoices() == 0, "voz liberada");

    // pedal: nota sostenida mientras el pedal esta pisado
    e.controlChange(64, 127);
    e.noteOn(64, 100);
    run(e, 0.2);
    e.noteOff(64);
    o = run(e, 1.5);
    CHECK(rms(o, o.size() - 4800, o.size()) > 0.05, "con pedal pisado la nota sigue sonando");
    e.controlChange(64, 0);
    o = run(e, 1.6);
    CHECK(rms(o, o.size() - 4800, o.size()) < 0.002, "al soltar el pedal se apaga");

    // misma tecla repetida: la anterior se apaga
    e.noteOn(72, 90);
    run(e, 0.1);
    e.noteOn(72, 90);
    run(e, 0.1);
    CHECK(e.activeVoices() == 2, "dos voces durante el cruce");
    run(e, 1.0);
    CHECK(e.activeVoices() == 1, "la voz antigua se apaga (0.5 s)");
    e.controlChange(120, 0);
    run(e, 0.05);

    // polifonia: 200 notas, no rompe y respeta el limite
    for (int i = 0; i < 200; ++i) e.noteOn(21 + (i % 88), 60 + (i % 60));
    o = run(e, 0.5);
    bool ok = true;
    float pico = 0;
    for (float x : o) {
        if (!std::isfinite(x)) ok = false;
        pico = std::max(pico, std::fabs(x));
    }
    CHECK(ok, "salida sin NaN ni infinitos con 200 notas");
    CHECK(pico <= 0.851f, "el limitador evita pasar de 0.85 (-1.4 dBFS)");
    CHECK(g_master.takePeak() > 0.5f && g_master.takePeak() == 0.f, "el medidor de pico reporta y se reinicia");
    CHECK(e.activeVoices() <= kMaxVoices, "maximo 64 voces");

    // rendimiento: 64 voces durante 10 s de audio
    e.controlChange(120, 0);
    run(e, 0.05);
    e.controlChange(64, 127);
    for (int i = 0; i < 64; ++i) e.noteOn(30 + i, 80);
    auto t0 = std::chrono::steady_clock::now();
    run(e, 2.5);
    auto t1 = std::chrono::steady_clock::now();
    double ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
    std::printf("info:  64 voces, 2.5 s de audio calculados en %.1f ms (%.1f%% de un nucleo en este PC)\n",
                ms, ms / 25.0);
    CHECK(ms < 2500, "mas rapido que tiempo real con 64 voces");


    // ---- Reverb ----
    {
        Master m;
        m.init(48000.f);
        m.setVolume(1.0f);
        m.setReverb(0.f);
        std::vector<float> settle(480 * 2, 0.f);
        m.process(settle.data(), 480);  // deja que la rampa de volumen llegue a 1.0
        std::vector<float> in(48000 * 2, 0.f), ref;
        for (int i = 0; i < 2000; ++i) in[2 * i] = in[2 * i + 1] = 0.3f * std::sin(2 * M_PI * 440.0 * i / 48000.0);
        ref = in;
        m.process(in.data(), 48000);
        bool igual = true;
        for (size_t i = 0; i < in.size(); ++i) if (std::fabs(in[i] - ref[i]) > 1e-6f) igual = false;
        CHECK(igual, "reverb en 0: el sonido pasa sin cambios");

        // impulso con reverb: debe dejar cola que se apaga y no producir NaN
        Master r;
        r.init(48000.f);
        r.setVolume(1.0f);
        r.setReverb(0.5f);
        std::vector<float> imp(48000 * 6 * 2, 0.f);
        imp[0] = imp[1] = 0.5f;
        for (size_t o = 0; o < 48000 * 6; o += 480) r.process(imp.data() + 2 * o, 480);
        bool finito = true;
        for (float x : imp) if (!std::isfinite(x)) finito = false;
        CHECK(finito, "reverb sin NaN ni infinitos");
        double early = rms(imp, 2 * 4800, 2 * 24000);       // 0.1 - 0.5 s
        double late = rms(imp, 2 * 48000 * 5, 2 * 48000 * 6);  // 5 - 6 s
        CHECK(early > 1e-4, "el reverb genera cola despues del impulso");
        CHECK(late < early * 0.1, "la cola del reverb decae (a los 5 s es 20 dB menor)");
    }

    // ---- SoundFont (TinySoundFont) con un .sf2 de prueba ----
    {
        SfEngine sf;
        sf.setSampleRate(48000.f);
        CHECK(!sf.isLoaded(), "sin archivo, el motor SF esta vacio");
        std::vector<float> z(192 * 2, 1.f);
        sf.render(z.data(), 192);
        CHECK(rms(z, 0, z.size()) == 0.0, "motor SF vacio renderiza silencio");

        bool ok = sf.load("tests/resources/sinewave.sf2");
        CHECK(ok && sf.isLoaded(), "carga un .sf2 real");
        CHECK(!sf.load("tests/resources/no_existe.sf2"), "un archivo inexistente falla sin romper");
        CHECK(sf.isLoaded(), "tras un fallo de carga, el sonido anterior sigue cargado");
        auto ps = sf.presets();
        CHECK(!ps.empty(), "lista los presets del .sf2");
        if (!ps.empty()) std::printf("info:  preset 0: banco %d programa %d '%s' (%zu presets)\n",
                                      ps[0].bank, ps[0].program, ps[0].name.c_str(), ps.size());

        auto runSf = [&](double sec) {
            size_t total = (size_t)(48000 * sec);
            std::vector<float> out(total * 2);
            for (size_t d = 0; d < total; d += 192) {
                size_t n = std::min<size_t>(192, total - d);
                sf.render(out.data() + 2 * d, (int)n);
            }
            return out;
        };
        sf.selectPreset(0);
        sf.noteOn(69, 100);
        auto a = runSf(0.5);
        CHECK(rms(a, 4800, a.size()) > 0.01, "una nota produce sonido");
        sf.noteOff(69);
        auto rel = runSf(4.0);
        double tailRms = rms(rel, rel.size() - 9600, rel.size());
        sf.control(120, 0);
        runSf(0.05);

        // pedal: con el pedal pisado, la nota soltada sigue; al soltar el pedal, se apaga
        sf.control(64, 127);
        sf.noteOn(69, 100);
        runSf(0.3);
        sf.noteOff(69);
        auto held = runSf(0.5);
        double heldRms = rms(held, held.size() - 4800, held.size());
        sf.control(64, 0);
        auto after = runSf(4.0);
        double afterRms = rms(after, after.size() - 9600, after.size());
        std::printf("info:  SF: cola sin pedal=%.5f, con pedal pisado=%.5f, tras soltar pedal=%.5f\n",
                    tailRms, heldRms, afterRms);
        CHECK(heldRms > 0.01, "SF: con pedal pisado la nota sigue sonando");
        CHECK(afterRms < heldRms * 0.2, "SF: al soltar el pedal la nota se apaga");

        // 300 notas seguidas: sin NaN, sin caidas
        for (int i = 0; i < 300; ++i) sf.noteOn(30 + (i % 70), 40 + (i % 80));
        auto many = runSf(0.3);
        bool fin = true;
        for (float x : many) if (!std::isfinite(x)) fin = false;
        CHECK(fin, "SF: 300 notas seguidas sin NaN");
        sf.unload();
        CHECK(!sf.isLoaded(), "descarga el sonido");
    }

    // sin banco: no falla
    Engine vacio;
    vacio.noteOn(60, 100);
    auto z = run(vacio, 0.1);
    CHECK(rms(z, 0, z.size()) == 0.0, "sin muestras cargadas no suena ni falla");

    std::printf(fallos ? "\n%d PRUEBA(S) FALLARON\n" : "\nTODAS LAS PRUEBAS PASARON\n", fallos);
    return fallos ? 1 : 0;
}
