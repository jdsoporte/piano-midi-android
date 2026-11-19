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
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Etapa 1: monitor MIDI. Lista los dispositivos MIDI conectados por USB
 * y muestra en pantalla los mensajes que envia el teclado (notas y pedal).
 */
public class MainActivity extends Activity {

    private static final int MAX_LINEAS = 60;

    private MidiManager midiManager;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<String> lineas = new ArrayList<>();
    private final List<MidiDevice> abiertos = new ArrayList<>();
    private final List<MidiOutputPort> puertos = new ArrayList<>();

    private TextView estado;
    private TextView registro;
    private ScrollView scroll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (16 * getResources().getDisplayMetrics().density);
        raiz.setPadding(p, p * 2, p, p);

        TextView titulo = new TextView(this);
        titulo.setText("Piano MIDI - monitor (etapa 1)");
        titulo.setTextSize(20);
        titulo.setGravity(Gravity.START);
        raiz.addView(titulo);

        estado = new TextView(this);
        estado.setTextSize(15);
        estado.setPadding(0, p / 2, 0, p / 2);
        raiz.addView(estado);

        scroll = new ScrollView(this);
        registro = new TextView(this);
        registro.setTextSize(14);
        registro.setTypeface(android.graphics.Typeface.MONOSPACE);
        scroll.addView(registro);
        raiz.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(raiz);

        midiManager = (MidiManager) getSystemService(MIDI_SERVICE);
        if (midiManager == null) {
            estado.setText("Este telefono no soporta MIDI.");
            return;
        }

        midiManager.registerDeviceCallback(new MidiManager.DeviceCallback() {
            @Override
            public void onDeviceAdded(MidiDeviceInfo info) {
                agregar("Conectado: " + nombre(info));
                abrir(info);
                actualizarEstado();
            }

            @Override
            public void onDeviceRemoved(MidiDeviceInfo info) {
                agregar("Desconectado: " + nombre(info));
                actualizarEstado();
            }
        }, ui);

        for (MidiDeviceInfo info : midiManager.getDevices()) {
            agregar("Encontrado: " + nombre(info));
            abrir(info);
        }
        actualizarEstado();
    }

    private static String nombre(MidiDeviceInfo info) {
        String n = info.getProperties().getString(MidiDeviceInfo.PROPERTY_NAME);
        return (n != null ? n : "dispositivo MIDI")
                + " (salidas=" + info.getOutputPortCount()
                + ", entradas=" + info.getInputPortCount() + ")";
    }

    private void actualizarEstado() {
        MidiDeviceInfo[] lista = midiManager.getDevices();
        estado.setText(lista.length == 0
                ? "Sin dispositivos MIDI. Conecta el piano por USB."
                : "Dispositivos MIDI: " + lista.length + " - toca una tecla.");
    }

    private void abrir(MidiDeviceInfo info) {
        // El teclado ENVIA datos al telefono por su puerto de salida.
        if (info.getOutputPortCount() == 0) {
            return;
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

    /** Interpreta mensajes de canal (3 bytes). Ignora reloj y 'active sensing'. */
    private void interpretar(byte[] d, int offset, int count) {
        int i = offset;
        int fin = offset + count;
        while (i < fin) {
            int estadoByte = d[i] & 0xFF;
            if (estadoByte >= 0xF8) { // tiempo real (reloj, active sensing...)
                i++;
                continue;
            }
            if (estadoByte < 0x80) { // dato suelto sin estado: se omite
                i++;
                continue;
            }
            int tipo = estadoByte & 0xF0;
            int canal = (estadoByte & 0x0F) + 1;
            int largo = (tipo == 0xC0 || tipo == 0xD0) ? 2 : 3;
            if (tipo == 0xF0) { // mensajes de sistema: se saltan hasta el siguiente estado
                i++;
                while (i < fin && (d[i] & 0x80) == 0) {
                    i++;
                }
                continue;
            }
            if (i + largo > fin) {
                break;
            }
            int a = d[i + 1] & 0x7F;
            int b = largo == 3 ? d[i + 2] & 0x7F : 0;
            String texto;
            if (tipo == 0x90 && b > 0) {
                texto = "Nota ON  tecla=" + a + " fuerza=" + b + " canal=" + canal;
            } else if (tipo == 0x80 || tipo == 0x90) {
                texto = "Nota OFF tecla=" + a + " canal=" + canal;
            } else if (tipo == 0xB0) {
                texto = "Control cc=" + a + " valor=" + b + (a == 64 ? " (pedal)" : "");
            } else {
                texto = String.format("Mensaje 0x%02X %d %d", estadoByte, a, b);
            }
            agregar(texto);
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
        for (MidiDevice d : abiertos) {
            try { d.close(); } catch (Exception ignorada) { }
        }
        super.onDestroy();
    }
}
