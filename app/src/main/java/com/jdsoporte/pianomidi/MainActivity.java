package com.jdsoporte.pianomidi;

import android.app.Activity;
import android.media.midi.MidiDevice;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiManager;
import android.media.midi.MidiOutputPort;
import android.media.midi.MidiReceiver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Etapa 2: piano con sonido. Recibe MIDI por USB y lo reproduce con el motor nativo
 * (muestras de Salamander Grand Piano V3).
 */
public class MainActivity extends Activity {

    private static final int MAX_LINEAS = 25;
    private static final String VERSION_SONIDOS = "piano-v1";
    private static final float VOLUMEN_MAXIMO = 3.0f; // ganancia con la barra al 100%

    private MidiManager midiManager;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<String> lineas = new ArrayList<>();
    private final List<MidiDevice> abiertos = new ArrayList<>();
    private final List<MidiOutputPort> puertos = new ArrayList<>();

    private TextView estadoSonido;
    private TextView estadoMidi;
    private TextView infoAudio;
    private TextView medidor;
    private float picoVisto = 0f;
    private TextView registro;
    private ScrollView scroll;
    private volatile boolean audioListo = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (16 * getResources().getDisplayMetrics().density);
        raiz.setPadding(p, p * 2, p, p);

        TextView titulo = new TextView(this);
        titulo.setText("Piano MIDI");
        titulo.setTextSize(22);
        raiz.addView(titulo);

        estadoSonido = texto(raiz, 15, p / 2);
        estadoMidi = texto(raiz, 15, p / 4);
        infoAudio = texto(raiz, 12, p / 4);
        medidor = texto(raiz, 13, p / 4);

        TextView vol = texto(raiz, 14, p / 2);
        vol.setText("Volumen del piano (si suena bajo, sube tambien el volumen multimedia del celular)");
        SeekBar barra = new SeekBar(this);
        barra.setMax(100);
        barra.setProgress(50);
        barra.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int v, boolean user) {
                Native.nativeSetVolume(v / 100f * VOLUMEN_MAXIMO);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        raiz.addView(barra);
        Native.nativeSetVolume(0.5f * VOLUMEN_MAXIMO);

        scroll = new ScrollView(this);
        registro = new TextView(this);
        registro.setTextSize(13);
        registro.setTypeface(android.graphics.Typeface.MONOSPACE);
        scroll.addView(registro);
        raiz.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView creditos = texto(raiz, 11, p / 2);
        creditos.setText("Piano: Salamander Grand Piano V3 - grabaciones de Alexander Holm "
                + "(CC BY 3.0, creativecommons.org/licenses/by/3.0)");

        setContentView(raiz);

        estadoSonido.setText("Preparando sonidos...");
        new Thread(this::iniciarSonido, "carga-sonido").start();

        midiManager = (MidiManager) getSystemService(MIDI_SERVICE);
        if (midiManager == null) {
            estadoMidi.setText("Este telefono no soporta MIDI.");
            return;
        }
        midiManager.registerDeviceCallback(new MidiManager.DeviceCallback() {
            @Override
            public void onDeviceAdded(MidiDeviceInfo info) {
                agregar("Conectado: " + nombre(info));
                abrir(info);
                actualizarMidi();
            }

            @Override
            public void onDeviceRemoved(MidiDeviceInfo info) {
                agregar("Desconectado: " + nombre(info));
                actualizarMidi();
            }
        }, ui);
        for (MidiDeviceInfo info : midiManager.getDevices()) {
            abrir(info);
        }
        actualizarMidi();
    }

    private TextView texto(LinearLayout padre, int sp, int top) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setPadding(0, top, 0, 0);
        padre.addView(t);
        return t;
    }

    // ---- Sonido -----------------------------------------------------------

    private void iniciarSonido() {
        try {
            File dir = new File(getFilesDir(), "piano");
            File marca = new File(dir, "ok-" + VERSION_SONIDOS);
            if (!marca.exists()) {
                copiarSonidos(dir);
                if (!marca.createNewFile()) {
                    throw new IllegalStateException("no se pudo guardar la marca");
                }
            }
            ui.post(() -> estadoSonido.setText("Cargando piano en memoria..."));
            int n = Native.nativeLoad(dir.getAbsolutePath());
            if (n == 0) {
                ui.post(() -> estadoSonido.setText("ERROR: no se encontraron sonidos."));
                return;
            }
            if (!Native.nativeStart()) {
                ui.post(() -> estadoSonido.setText("ERROR: no se pudo abrir el audio."));
                return;
            }
            audioListo = true;
            final int cargadas = n;
            ui.post(() -> {
                estadoSonido.setText("Piano listo (" + cargadas + " grabaciones). Toca el piano.");
                infoAudio.setText(Native.nativeInfo());
                actualizarMedidor();
            });
        } catch (Exception e) {
            final String msg = e.toString();
            ui.post(() -> estadoSonido.setText("ERROR: " + msg));
        }
    }

    /** Muestra el pico de salida y los cortes de audio, cada medio segundo. */
    private void actualizarMedidor() {
        float p = Native.nativePeak();
        picoVisto = Math.max(p, picoVisto * 0.9f);
        int xr = Native.nativeXRuns();
        String nivel = picoVisto >= 0.84f ? "  << NIVEL ALTO (limitando)" : "";
        medidor.setText(String.format(java.util.Locale.US,
                "Nivel de salida: %.2f (max 0.85)%s\nCortes de audio: %s",
                picoVisto, nivel, xr < 0 ? "?" : String.valueOf(xr)));
        ui.postDelayed(this::actualizarMedidor, 500);
    }

    private void copiarSonidos(File dir) throws Exception {
        dir.mkdirs();
        String[] nombres = getAssets().list("piano");
        if (nombres == null || nombres.length == 0) {
            throw new IllegalStateException("la app no trae sonidos");
        }
        byte[] buf = new byte[256 * 1024];
        for (int i = 0; i < nombres.length; i++) {
            final int hecho = i + 1;
            final int total = nombres.length;
            ui.post(() -> estadoSonido.setText(
                    "Preparando sonidos (solo la primera vez): " + hecho + " de " + total));
            File destino = new File(dir, nombres[i]);
            File tmp = new File(dir, nombres[i] + ".tmp");
            try (InputStream in = getAssets().open("piano/" + nombres[i]);
                 OutputStream out = new FileOutputStream(tmp)) {
                int r;
                while ((r = in.read(buf)) > 0) {
                    out.write(buf, 0, r);
                }
            }
            if (!tmp.renameTo(destino)) {
                throw new IllegalStateException("no se pudo guardar " + nombres[i]);
            }
        }
    }

    // ---- MIDI -------------------------------------------------------------

    private static String nombre(MidiDeviceInfo info) {
        String n = info.getProperties().getString(MidiDeviceInfo.PROPERTY_NAME);
        return n != null ? n : "dispositivo MIDI";
    }

    private void actualizarMidi() {
        MidiDeviceInfo[] lista = midiManager.getDevices();
        estadoMidi.setText(lista.length == 0
                ? "Piano: no conectado (conecta el cable USB)"
                : "Piano: conectado (" + nombre(lista[0]) + ")");
    }

    private void abrir(MidiDeviceInfo info) {
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

    /** Mensajes de canal; el sonido se dispara antes de escribir en pantalla. */
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
                agregar("ON  tecla=" + a + " fuerza=" + b);
            } else if (tipo == 0x80 || tipo == 0x90) {
                Native.nativeNoteOff(a);
                agregar("OFF tecla=" + a);
            } else if (tipo == 0xB0) {
                Native.nativeControl(a, b);
                if (a == 64) {
                    agregar("pedal " + (b >= 64 ? "ABAJO" : "arriba") + " (" + b + ")");
                }
            }
            i += largo;
        }
    }

    private void agregar(String texto) {
        ui.post(() -> {
            lineas.add(texto);
            while (lineas.size() > MAX_LINEAS) {
                lineas.remove(0);
            }
            registro.setText(String.join("\n", lineas));
            scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    @Override
    protected void onDestroy() {
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
