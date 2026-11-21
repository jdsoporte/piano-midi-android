package com.jdsoporte.pianomidi;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.midi.MidiDevice;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiManager;
import android.media.midi.MidiOutputPort;
import android.media.midi.MidiReceiver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Piano MIDI: recibe un teclado por USB y lo suena con el piano de concierto (Salamander)
 * o con los instrumentos de un SoundFont (FluidR3 incluido, o archivos .sf2 del usuario).
 */
public class MainActivity extends Activity {

    // ---- colores -------------------------------------------------------------
    private static final int C_BG = 0xFF0E1116;
    private static final int C_SURFACE = 0xFF171C24;
    private static final int C_SURFACE2 = 0xFF222936;
    private static final int C_TEXT = 0xFFE8E6E1;
    private static final int C_MUTED = 0xFF9AA3AF;
    private static final int C_GOLD = 0xFFD4A94B;
    private static final int C_OK = 0xFF4CAF7A;
    private static final int C_WARN = 0xFFE5A04B;
    private static final int C_BAD = 0xFFE5645B;

    private static final int REQ_SF2 = 77;
    private static final String VERSION_PIANO = "piano-v1";
    private static final String VERSION_FLUID = "fluidr3-v1";
    private static final String FLUID_NAME = "FluidR3_GM.sf2";
    private static final float VOLUMEN_MAXIMO = 3.0f;
    private static final int MAX_LINEAS = 20;

    private static final String[] NOMBRES_NOTA = {
            "Do", "Do#", "Re", "Re#", "Mi", "Fa", "Fa#", "Sol", "Sol#", "La", "La#", "Si"};

    /** Instrumentos General MIDI mas usados (programa 0..127 del banco 0). */
    private static final String[] VOZ_ETIQUETA = {
            "Piano Yamaha", "Piano brillante", "Piano eléctrico", "Piano eléctrico 2",
            "Órgano de iglesia", "Órgano Hammond", "Acordeón", "Cuerdas", "Cuerdas sintéticas",
            "Coro", "Voces", "Pad cálido", "Pad coro", "Guitarra acústica", "Guitarra limpia",
            "Arpa", "Flauta", "Vibráfono", "Trompeta", "Clavecín"};
    private static final int[] VOZ_PROGRAMA = {
            0, 1, 4, 5, 19, 16, 21, 48, 50, 52, 53, 89, 91, 25, 27, 46, 73, 11, 56, 6};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<String> lineas = new ArrayList<>();
    private final List<MidiDevice> abiertos = new ArrayList<>();
    private final List<MidiOutputPort> puertos = new ArrayList<>();
    private final List<TextView> chips = new ArrayList<>();

    private SharedPreferences prefs;
    private MidiManager midiManager;

    private TextView pildora;
    private TextView sonidoActual;
    private TextView sonidoDetalle;
    private TextView notaGrande;
    private TextView estadoCarga;
    private TextView infoAudio;
    private TextView medidor;
    private TextView registro;
    private LinearLayout panelDiagnostico;
    private PianoKeyboardView teclado;
    private final List<TextView> botonesLatencia = new ArrayList<>();

    private volatile boolean pianoListo = false;
    private volatile boolean gmCopiado = false;
    private volatile boolean ocupado = false;
    private volatile String sfCargado = null; // ruta del .sf2 que esta en el motor
    private int chipActivo = 0;
    private float picoVisto = 0f;
    private boolean destruida = false;

    // =========================================================================
    // Pantalla
    // =========================================================================

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable fondo(int color, float radioDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radioDp));
        return g;
    }

    private TextView texto(String t, float sp, int color) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(sp);
        v.setTextColor(color);
        return v;
    }

    private LinearLayout tarjeta(LinearLayout padre) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(fondo(C_SURFACE, 16));
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        padre.addView(c, lp);
        return c;
    }

    private TextView boton(String t, boolean destacado) {
        TextView b = texto(t, 14, destacado ? 0xFF1A1405 : C_TEXT);
        b.setGravity(Gravity.CENTER);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setPadding(dp(14), dp(11), dp(14), dp(11));
        b.setBackground(fondo(destacado ? C_GOLD : C_SURFACE2, 12));
        b.setClickable(true);
        return b;
    }

    private void estiloChip(TextView c, boolean activo) {
        c.setBackground(fondo(activo ? C_GOLD : C_SURFACE2, 20));
        c.setTextColor(activo ? 0xFF1A1405 : C_TEXT);
    }

    private TextView chip(String t) {
        TextView c = texto(t, 14, C_TEXT);
        c.setPadding(dp(16), dp(9), dp(16), dp(9));
        c.setGravity(Gravity.CENTER);
        c.setClickable(true);
        estiloChip(c, false);
        return c;
    }

    private SeekBar barra(int max, int progreso, SeekBar.OnSeekBarChangeListener l) {
        SeekBar s = new SeekBar(this);
        s.setMax(max);
        s.setProgress(progreso);
        s.setProgressTintList(ColorStateList.valueOf(C_GOLD));
        s.setThumbTintList(ColorStateList.valueOf(C_GOLD));
        s.setOnSeekBarChangeListener(l);
        return s;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setBackgroundColor(C_BG);
        prefs = getSharedPreferences("ajustes", MODE_PRIVATE);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(C_BG);
        scroll.setFillViewport(true);
        LinearLayout raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);
        raiz.setPadding(dp(16), dp(12), dp(16), dp(24));
        scroll.addView(raiz, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });

        // ---- encabezado
        LinearLayout cab = new LinearLayout(this);
        cab.setOrientation(LinearLayout.HORIZONTAL);
        cab.setGravity(Gravity.CENTER_VERTICAL);
        TextView titulo = texto("Piano MIDI", 26, C_TEXT);
        titulo.setTypeface(Typeface.DEFAULT_BOLD);
        cab.addView(titulo, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        pildora = texto("Sin piano", 13, C_TEXT);
        pildora.setPadding(dp(12), dp(6), dp(12), dp(6));
        pildora.setBackground(fondo(C_SURFACE2, 20));
        cab.addView(pildora);
        raiz.addView(cab);

        // ---- sonido actual
        LinearLayout t1 = tarjeta(raiz);
        t1.addView(texto("SONIDO", 11, C_MUTED));
        sonidoActual = texto("Preparando…", 22, C_GOLD);
        sonidoActual.setTypeface(Typeface.DEFAULT_BOLD);
        t1.addView(sonidoActual);
        sonidoDetalle = texto("", 13, C_MUTED);
        t1.addView(sonidoDetalle);
        estadoCarga = texto("", 13, C_WARN);
        estadoCarga.setPadding(0, dp(4), 0, 0);
        t1.addView(estadoCarga);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout fila = new LinearLayout(this);
        fila.setOrientation(LinearLayout.HORIZONTAL);
        fila.setPadding(0, dp(12), 0, dp(4));
        hs.addView(fila);
        t1.addView(hs);

        TextView cPiano = chip("Piano de concierto");
        cPiano.setOnClickListener(v -> elegirConcierto());
        agregarChip(fila, cPiano);
        for (int i = 0; i < VOZ_ETIQUETA.length; i++) {
            final int programa = VOZ_PROGRAMA[i];
            final String etiqueta = VOZ_ETIQUETA[i];
            final int pos = i + 1;
            TextView c = chip(etiqueta);
            c.setOnClickListener(v -> elegirGm(pos, programa, etiqueta));
            agregarChip(fila, c);
        }

        LinearLayout botones = new LinearLayout(this);
        botones.setOrientation(LinearLayout.HORIZONTAL);
        botones.setPadding(0, dp(10), 0, 0);
        TextView bTodos = boton("Todos los sonidos", false);
        bTodos.setOnClickListener(v -> mostrarTodos());
        TextView bCargar = boton("Cargar .sf2", false);
        bCargar.setOnClickListener(v -> pedirArchivoSf2());
        LinearLayout.LayoutParams lpb = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lpb.rightMargin = dp(8);
        botones.addView(bTodos, lpb);
        botones.addView(bCargar, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        t1.addView(botones);

        // ---- teclado
        LinearLayout t2 = tarjeta(raiz);
        LinearLayout cabT = new LinearLayout(this);
        cabT.setOrientation(LinearLayout.HORIZONTAL);
        cabT.addView(texto("TECLADO", 11, C_MUTED), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        notaGrande = texto("", 20, C_GOLD);
        notaGrande.setTypeface(Typeface.DEFAULT_BOLD);
        cabT.addView(notaGrande);
        t2.addView(cabT);
        teclado = new PianoKeyboardView(this);
        teclado.setListener(new PianoKeyboardView.Listener() {
            @Override
            public void onKeyDown(int key, int velocity) {
                Native.nativeNoteOn(key, velocity);
                mostrarNota(key);
            }

            @Override
            public void onKeyUp(int key) {
                Native.nativeNoteOff(key);
            }
        });
        LinearLayout.LayoutParams lpk = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(150));
        lpk.topMargin = dp(8);
        t2.addView(teclado, lpk);

        // ---- mezcla
        LinearLayout t3 = tarjeta(raiz);
        t3.addView(texto("MEZCLA", 11, C_MUTED));
        TextView lv = texto("Volumen", 14, C_TEXT);
        lv.setPadding(0, dp(8), 0, 0);
        t3.addView(lv);
        int volIni = prefs.getInt("volumen", 50);
        t3.addView(barra(100, volIni, new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int v, boolean user) {
                Native.nativeSetVolume(v / 100f * VOLUMEN_MAXIMO);
                if (user) {
                    prefs.edit().putInt("volumen", v).apply();
                }
            }
        }));
        Native.nativeSetVolume(volIni / 100f * VOLUMEN_MAXIMO);
        TextView lr = texto("Reverberación (eco de sala)", 14, C_TEXT);
        lr.setPadding(0, dp(8), 0, 0);
        t3.addView(lr);
        int revIni = prefs.getInt("reverb", 25);
        t3.addView(barra(100, revIni, new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int v, boolean user) {
                Native.nativeSetReverb(v / 100f);
                if (user) {
                    prefs.edit().putInt("reverb", v).apply();
                }
            }
        }));
        Native.nativeSetReverb(revIni / 100f);

        // ---- latencia
        LinearLayout t4 = tarjeta(raiz);
        t4.addView(texto("RESPUESTA (LATENCIA)", 11, C_MUTED));
        TextView ayuda = texto("Mínima responde más rápido. Si oyes cortes o chasquidos, "
                + "sube a Normal o Segura.", 12, C_MUTED);
        ayuda.setPadding(0, dp(4), 0, dp(8));
        t4.addView(ayuda);
        LinearLayout filaLat = new LinearLayout(this);
        filaLat.setOrientation(LinearLayout.HORIZONTAL);
        final String[] nombresLat = {"Mínima", "Normal", "Segura"};
        final int[] rafagas = {2, 3, 4};
        int latIni = prefs.getInt("latencia", 1);
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView b = chip(nombresLat[i]);
            b.setOnClickListener(v -> elegirLatencia(idx, rafagas[idx]));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i < 2) {
                lp.rightMargin = dp(8);
            }
            filaLat.addView(b, lp);
            botonesLatencia.add(b);
        }
        t4.addView(filaLat);
        for (int i = 0; i < 3; i++) {
            estiloChip(botonesLatencia.get(i), i == latIni);
        }
        Native.nativeSetLatency(rafagas[latIni]);

        // ---- diagnostico (plegable)
        LinearLayout t5 = tarjeta(raiz);
        TextView bDiag = boton("Diagnóstico ▾", false);
        t5.addView(bDiag);
        panelDiagnostico = new LinearLayout(this);
        panelDiagnostico.setOrientation(LinearLayout.VERTICAL);
        panelDiagnostico.setVisibility(View.GONE);
        panelDiagnostico.setPadding(0, dp(8), 0, 0);
        infoAudio = texto("", 12, C_MUTED);
        medidor = texto("", 13, C_TEXT);
        registro = texto("", 12, C_MUTED);
        registro.setTypeface(Typeface.MONOSPACE);
        panelDiagnostico.addView(infoAudio);
        panelDiagnostico.addView(medidor);
        panelDiagnostico.addView(registro);
        t5.addView(panelDiagnostico);
        bDiag.setOnClickListener(v -> {
            boolean abierto = panelDiagnostico.getVisibility() == View.VISIBLE;
            panelDiagnostico.setVisibility(abierto ? View.GONE : View.VISIBLE);
            bDiag.setText(abierto ? "Diagnóstico ▾" : "Diagnóstico ▴");
        });

        // ---- creditos
        TextView bCred = boton("Créditos y licencias", false);
        LinearLayout.LayoutParams lpc = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpc.topMargin = dp(12);
        raiz.addView(bCred, lpc);
        bCred.setOnClickListener(v -> mostrarCreditos());
        TextView pie = texto("Piano gratuito para músicos. Piano de concierto: Salamander "
                + "Grand Piano V3 de Alexander Holm (CC BY 3.0).", 11, C_MUTED);
        pie.setPadding(0, dp(10), 0, 0);
        raiz.addView(pie);

        setContentView(scroll);

        iniciarMidi();
        new Thread(this::iniciarSonido, "carga-sonido").start();
        ui.post(this::actualizarMedidor);
    }

    private void agregarChip(LinearLayout fila, TextView c) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        fila.addView(c, lp);
        chips.add(c);
    }

    private void marcarChip(int pos) {
        chipActivo = pos;
        for (int i = 0; i < chips.size(); i++) {
            estiloChip(chips.get(i), i == pos);
        }
    }

    private void desmarcarChips() {
        chipActivo = -1;
        for (TextView c : chips) {
            estiloChip(c, false);
        }
    }

    /** Listener de barra con los metodos que no usamos ya resueltos. */
    private abstract static class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar s) { }

        @Override
        public void onStopTrackingTouch(SeekBar s) { }
    }

    private void elegirLatencia(int idx, int rafagas) {
        for (int i = 0; i < botonesLatencia.size(); i++) {
            estiloChip(botonesLatencia.get(i), i == idx);
        }
        Native.nativeSetLatency(rafagas);
        prefs.edit().putInt("latencia", idx).apply();
        infoAudio.setText(Native.nativeInfo());
    }

    private void mostrarNota(final int key) {
        final String n = NOMBRES_NOTA[key % 12] + (key / 12 - 1);
        ui.post(() -> notaGrande.setText(n));
    }

    // =========================================================================
    // Sonido: preparacion y arranque
    // =========================================================================

    private void estado(final String t) {
        ui.post(() -> estadoCarga.setText(t));
    }

    private void iniciarSonido() {
        try {
            File dir = new File(getFilesDir(), "piano");
            File marca = new File(dir, "ok-" + VERSION_PIANO);
            if (!marca.exists()) {
                copiarCarpetaAsset("piano", dir, "Preparando el piano (solo la primera vez)");
                if (!marca.createNewFile()) {
                    throw new IllegalStateException("no se pudo guardar la marca del piano");
                }
            }
            estado("Cargando el piano en memoria…");
            if (Native.nativeLoad(dir.getAbsolutePath()) == 0) {
                estado("ERROR: no se encontraron los sonidos del piano.");
                return;
            }
            if (!Native.nativeStart()) {
                estado("ERROR: no se pudo abrir el audio.");
                return;
            }
            pianoListo = true;
            ui.post(() -> infoAudio.setText(Native.nativeInfo()));

            // Sonidos adicionales (GM): si ya estan copiados de una vez anterior, se usan ya.
            File sfDir = new File(getFilesDir(), "sf2");
            File marcaSf = new File(sfDir, "ok-" + VERSION_FLUID);
            gmCopiado = marcaSf.exists();
            restaurarSonido();
            estado("");

            if (!gmCopiado && existeAsset("sf2/" + FLUID_NAME)) {
                estado("Preparando más sonidos (solo la primera vez)…");
                sfDir.mkdirs();
                copiarArchivoAsset("sf2/" + FLUID_NAME, new File(sfDir, FLUID_NAME));
                if (!marcaSf.createNewFile()) {
                    throw new IllegalStateException("no se pudo guardar la marca de sonidos GM");
                }
                gmCopiado = true;
                estado("");
                if (prefs.getString("ultimo", "concierto").startsWith("gm:")) {
                    restaurarSonido();
                }
            }
        } catch (Exception e) {
            estado("ERROR: " + e);
        }
    }

    private boolean existeAsset(String ruta) {
        try (InputStream in = getAssets().open(ruta)) {
            return in != null;
        } catch (Exception e) {
            return false;
        }
    }

    private void copiarCarpetaAsset(String carpeta, File destino, String mensaje)
            throws Exception {
        destino.mkdirs();
        String[] nombres = getAssets().list(carpeta);
        if (nombres == null || nombres.length == 0) {
            throw new IllegalStateException("la app no trae sonidos en " + carpeta);
        }
        for (int i = 0; i < nombres.length; i++) {
            estado(mensaje + ": " + (i + 1) + " de " + nombres.length);
            copiarArchivoAsset(carpeta + "/" + nombres[i], new File(destino, nombres[i]));
        }
    }

    private void copiarArchivoAsset(String ruta, File destino) throws Exception {
        File tmp = new File(destino.getParentFile(), destino.getName() + ".tmp");
        byte[] buf = new byte[256 * 1024];
        try (InputStream in = getAssets().open(ruta);
             OutputStream out = new FileOutputStream(tmp)) {
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
            }
        }
        if (!tmp.renameTo(destino)) {
            throw new IllegalStateException("no se pudo guardar " + destino.getName());
        }
    }

    // =========================================================================
    // Elegir sonidos
    // =========================================================================

    private void restaurarSonido() {
        String ultimo = prefs.getString("ultimo", "concierto");
        if (ultimo.startsWith("gm:") && gmCopiado) {
            try {
                int pos = Integer.parseInt(ultimo.substring(3));
                if (pos >= 1 && pos <= VOZ_PROGRAMA.length) {
                    elegirGm(pos, VOZ_PROGRAMA[pos - 1], VOZ_ETIQUETA[pos - 1]);
                    return;
                }
            } catch (NumberFormatException ignorada) { }
        }
        if (ultimo.equals("concierto") || !gmCopiado) {
            ui.post(this::elegirConcierto);
        }
    }

    private void elegirConcierto() {
        if (!pianoListo) {
            Toast.makeText(this, "El piano aún se está preparando", Toast.LENGTH_SHORT).show();
            return;
        }
        Native.nativeSetMode(0);
        marcarChip(0);
        sonidoActual.setText("Piano de concierto");
        sonidoDetalle.setText("Piano Yamaha C5 grabado en estudio (Salamander), 8 capas de intensidad");
        prefs.edit().putString("ultimo", "concierto").apply();
    }

    private void elegirGm(final int pos, final int programa, final String etiqueta) {
        if (!pianoListo) {
            Toast.makeText(this, "Aún se está preparando", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!gmCopiado) {
            Toast.makeText(this, "Los sonidos GM aún se están preparando, o esta versión no los "
                    + "incluye. Puedes cargar tu propio .sf2.", Toast.LENGTH_LONG).show();
            return;
        }
        if (ocupado) {
            return;
        }
        final String ruta = new File(new File(getFilesDir(), "sf2"), FLUID_NAME)
                .getAbsolutePath();
        ocupado = true;
        new Thread(() -> {
            try {
                if (!ruta.equals(sfCargado)) {
                    estado("Cargando sonidos…");
                    if (!Native.nativeSfLoad(ruta)) {
                        estado("ERROR: no se pudieron cargar los sonidos GM.");
                        return;
                    }
                    sfCargado = ruta;
                    estado("");
                }
                int idx = Native.nativeSfFindPreset(0, programa);
                if (idx < 0) {
                    estado("Ese instrumento no está en este archivo.");
                    return;
                }
                Native.nativeSfSelect(idx);
                Native.nativeSetMode(1);
                prefs.edit().putString("ultimo", "gm:" + pos).apply();
                ui.post(() -> {
                    marcarChip(pos);
                    sonidoActual.setText(etiqueta);
                    sonidoDetalle.setText("Instrumento General MIDI (FluidR3), programa "
                            + programa);
                });
            } finally {
                ocupado = false;
            }
        }, "elegir-sonido").start();
    }

    /** Lista completa de instrumentos del SoundFont cargado (carga FluidR3 si no hay ninguno). */
    private void mostrarTodos() {
        if (!pianoListo) {
            return;
        }
        if (sfCargado == null && !Native.nativeSfLoaded()) {
            if (!gmCopiado) {
                Toast.makeText(this, "No hay sonidos adicionales todavía. Carga un .sf2.",
                        Toast.LENGTH_LONG).show();
                return;
            }
            final String ruta = new File(new File(getFilesDir(), "sf2"), FLUID_NAME)
                    .getAbsolutePath();
            estadoCarga.setText("Cargando sonidos…");
            new Thread(() -> {
                boolean ok = Native.nativeSfLoad(ruta);
                if (ok) {
                    sfCargado = ruta;
                }
                ui.post(() -> {
                    estadoCarga.setText(ok ? "" : "ERROR: no se pudieron cargar los sonidos.");
                    if (ok) {
                        mostrarListaPresets("Sonidos GM");
                    }
                });
            }, "cargar-sf").start();
            return;
        }
        mostrarListaPresets(sfCargado != null && sfCargado.endsWith(FLUID_NAME)
                ? "Sonidos GM" : "Instrumentos del archivo");
    }

    private void mostrarListaPresets(String titulo) {
        String[] crudo = Native.nativeSfPresets();
        final List<int[]> claves = new ArrayList<>(); // {banco, programa, indice}
        final List<String> nombres = new ArrayList<>();
        List<String[]> filas = new ArrayList<>();
        for (String s : crudo) {
            String[] p = s.split("\\|", 4);
            if (p.length < 4) {
                continue;
            }
            if (Integer.parseInt(p[1]) >= 128) {
                continue; // juegos de percusion: no se tocan como un instrumento
            }
            filas.add(p);
        }
        Collections.sort(filas, (a, b) -> {
            int c = Integer.compare(Integer.parseInt(a[1]), Integer.parseInt(b[1]));
            return c != 0 ? c : Integer.compare(Integer.parseInt(a[2]), Integer.parseInt(b[2]));
        });
        for (String[] p : filas) {
            int banco = Integer.parseInt(p[1]);
            int prog = Integer.parseInt(p[2]);
            claves.add(new int[]{banco, prog, Integer.parseInt(p[0])});
            nombres.add(String.format(Locale.US, "%s%s", p[3],
                    banco == 0 ? "" : "   (banco " + banco + ")"));
        }
        if (nombres.isEmpty()) {
            Toast.makeText(this, "Este archivo no tiene instrumentos tocables.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(titulo + " (" + nombres.size() + ")")
                .setItems(nombres.toArray(new String[0]), (d, which) -> {
                    int[] k = claves.get(which);
                    Native.nativeSfSelect(k[2]);
                    Native.nativeSetMode(1);
                    desmarcarChips();
                    sonidoActual.setText(nombres.get(which));
                    sonidoDetalle.setText(sfCargado != null && sfCargado.endsWith(FLUID_NAME)
                            ? "Instrumento General MIDI (FluidR3)" : "Archivo propio (.sf2)");
                })
                .setNegativeButton("Cerrar", null)
                .show();
    }

    // =========================================================================
    // Cargar un .sf2 propio
    // =========================================================================

    private void pedirArchivoSf2() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_SF2);
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo abrir el selector de archivos.",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_SF2 || res != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        if (!pianoListo || ocupado) {
            Toast.makeText(this, "Espera a que termine la carga actual.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        ocupado = true;
        new Thread(() -> importarSf2(uri), "importar-sf2").start();
    }

    private void importarSf2(Uri uri) {
        try {
            String nombre = "sonido.sf2";
            long tam = -1;
            try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    int s = c.getColumnIndex(OpenableColumns.SIZE);
                    if (n >= 0) {
                        nombre = c.getString(n);
                    }
                    if (s >= 0 && !c.isNull(s)) {
                        tam = c.getLong(s);
                    }
                }
            }
            if (!nombre.toLowerCase(Locale.ROOT).endsWith(".sf2")) {
                estado("Ese archivo no es un SoundFont (.sf2). Los .sf3 y .sfz no están soportados.");
                return;
            }
            // El sintetizador guarda las muestras como numeros de 32 bits: ~2x el tamano.
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            if (tam > 0 && tam * 2 > (long) (mi.totalMem * 0.35)) {
                estado(String.format(Locale.US, "Ese archivo (%d MB) es demasiado grande para la "
                        + "memoria de este teléfono.", tam / (1024 * 1024)));
                return;
            }
            File dir = new File(getFilesDir(), "sf2");
            dir.mkdirs();
            String seguro = nombre.replaceAll("[^A-Za-z0-9._-]", "_");
            File destino = new File(dir, "user_" + seguro);
            estado("Copiando " + nombre + "…");
            File tmp = new File(dir, destino.getName() + ".tmp");
            byte[] buf = new byte[256 * 1024];
            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(tmp)) {
                if (in == null) {
                    throw new IllegalStateException("no se pudo leer el archivo");
                }
                int r;
                while ((r = in.read(buf)) > 0) {
                    out.write(buf, 0, r);
                }
            }
            if (!tmp.renameTo(destino)) {
                throw new IllegalStateException("no se pudo guardar el archivo");
            }
            estado("Cargando " + nombre + "…");
            if (!Native.nativeSfLoad(destino.getAbsolutePath())) {
                estado("ERROR: ese archivo no es un SoundFont válido.");
                return;
            }
            sfCargado = destino.getAbsolutePath();
            estado("");
            prefs.edit().putString("ultimo", "concierto").apply();
            ui.post(() -> mostrarListaPresets("Instrumentos del archivo"));
        } catch (Exception e) {
            estado("ERROR: " + e);
        } finally {
            ocupado = false;
        }
    }

    // =========================================================================
    // Creditos
    // =========================================================================

    private void mostrarCreditos() {
        String texto;
        try (InputStream in = getAssets().open("licenses/NOTICE.txt")) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) {
                b.write(buf, 0, r);
            }
            texto = b.toString("UTF-8");
        } catch (Exception e) {
            texto = "No se pudo leer el archivo de créditos.";
        }
        TextView tv = texto(texto, 12, C_TEXT);
        tv.setPadding(dp(20), dp(12), dp(20), dp(12));
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Créditos y licencias")
                .setView(sv)
                .setPositiveButton("Cerrar", null)
                .show();
    }

    // =========================================================================
    // MIDI
    // =========================================================================

    private void iniciarMidi() {
        midiManager = (MidiManager) getSystemService(MIDI_SERVICE);
        if (midiManager == null) {
            pildora.setText("Sin MIDI en este teléfono");
            return;
        }
        midiManager.registerDeviceCallback(new MidiManager.DeviceCallback() {
            @Override
            public void onDeviceAdded(MidiDeviceInfo info) {
                agregar("Conectado: " + nombre(info));
                abrir(info);
                actualizarPildora();
            }

            @Override
            public void onDeviceRemoved(MidiDeviceInfo info) {
                agregar("Desconectado: " + nombre(info));
                actualizarPildora();
            }
        }, ui);
        for (MidiDeviceInfo info : midiManager.getDevices()) {
            abrir(info);
        }
        actualizarPildora();
    }

    private static String nombre(MidiDeviceInfo info) {
        String n = info.getProperties().getString(MidiDeviceInfo.PROPERTY_NAME);
        return n != null ? n : "dispositivo MIDI";
    }

    private void actualizarPildora() {
        MidiDeviceInfo[] lista = midiManager.getDevices();
        if (lista.length == 0) {
            pildora.setText("Sin piano");
            pildora.setBackground(fondo(C_SURFACE2, 20));
            pildora.setTextColor(C_MUTED);
        } else {
            pildora.setText("● " + nombre(lista[0]));
            pildora.setBackground(fondo(0xFF1D3B2C, 20));
            pildora.setTextColor(C_OK);
        }
    }

    private void abrir(final MidiDeviceInfo info) {
        if (info.getOutputPortCount() == 0) {
            return; // el teclado ENVIA al telefono por su puerto de salida
        }
        midiManager.openDevice(info, device -> {
            if (device == null) {
                agregar("No se pudo abrir: " + nombre(info));
                return;
            }
            abiertos.add(device);
            MidiOutputPort puerto = device.openOutputPort(0);
            if (puerto == null) {
                agregar("No se pudo abrir el puerto de salida 0");
                return;
            }
            puertos.add(puerto);
            puerto.connect(new MidiReceiver() {
                @Override
                public void onSend(byte[] datos, int offset, int count, long ts) {
                    interpretar(datos, offset, count);
                }
            });
            agregar("Escuchando: " + nombre(info));
        }, ui);
    }

    /** Mensajes de canal; el sonido se dispara antes de actualizar la pantalla. */
    private void interpretar(byte[] d, int offset, int count) {
        int i = offset;
        int fin = offset + count;
        while (i < fin) {
            int st = d[i] & 0xFF;
            if (st >= 0xF8 || st < 0x80) { // tiempo real o dato suelto
                i++;
                continue;
            }
            int tipo = st & 0xF0;
            if (tipo == 0xF0) { // sistema: saltar
                i++;
                while (i < fin && (d[i] & 0x80) == 0) {
                    i++;
                }
                continue;
            }
            int largo = (tipo == 0xC0 || tipo == 0xD0) ? 2 : 3;
            if (i + largo > fin) {
                break;
            }
            int a = d[i + 1] & 0x7F;
            int b = largo == 3 ? d[i + 2] & 0x7F : 0;
            if (tipo == 0x90 && b > 0) {
                Native.nativeNoteOn(a, b);
                teclado.setKey(a, true);
                mostrarNota(a);
                agregar("ON  tecla=" + a + " fuerza=" + b);
            } else if (tipo == 0x80 || tipo == 0x90) {
                Native.nativeNoteOff(a);
                teclado.setKey(a, false);
                agregar("OFF tecla=" + a);
            } else if (tipo == 0xB0) {
                Native.nativeControl(a, b);
                if (a == 64) {
                    agregar("pedal " + (b >= 64 ? "ABAJO" : "arriba") + " (" + b + ")");
                }
            } else if (tipo == 0xE0) {
                Native.nativePitchBend((b << 7) | a);
            }
            i += largo;
        }
    }

    private void agregar(final String texto) {
        ui.post(() -> {
            lineas.add(texto);
            while (lineas.size() > MAX_LINEAS) {
                lineas.remove(0);
            }
            registro.setText(String.join("\n", lineas));
        });
    }

    /** Pico de salida y cortes de audio, cada medio segundo. */
    private void actualizarMedidor() {
        if (destruida) {
            return;
        }
        float p = Native.nativePeak();
        picoVisto = Math.max(p, picoVisto * 0.9f);
        int xr = Native.nativeXRuns();
        String nivel = picoVisto >= 0.84f ? "  << NIVEL ALTO (limitando)" : "";
        medidor.setText(String.format(Locale.US,
                "Nivel de salida: %.2f (máx. 0.85)%s\nCortes de audio: %s",
                picoVisto, nivel, xr < 0 ? "?" : String.valueOf(xr)));
        ui.postDelayed(this::actualizarMedidor, 500);
    }

    @Override
    protected void onDestroy() {
        destruida = true;
        for (MidiOutputPort p : puertos) {
            try { p.close(); } catch (Exception ignorada) { }
        }
        for (MidiDevice dev : abiertos) {
            try { dev.close(); } catch (Exception ignorada) { }
        }
        Native.nativeStop();
        super.onDestroy();
    }
}
