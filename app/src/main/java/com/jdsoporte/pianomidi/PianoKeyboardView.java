package com.jdsoporte.pianomidi;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.SparseIntArray;
import android.view.MotionEvent;
import android.view.View;

/**
 * Teclado de piano (61 teclas, Do2 a Do7): se ilumina con las notas que llegan por MIDI
 * y tambien se puede tocar con los dedos.
 */
public class PianoKeyboardView extends View {

    public interface Listener {
        void onKeyDown(int key, int velocity);

        void onKeyUp(int key);
    }

    public static final int FIRST = 36; // Do2
    public static final int LAST = 96;  // Do7

    private static final int WHITE = 0xFFF2EDE4;
    private static final int WHITE_DOWN = 0xFFE2B24C;
    private static final int BLACK = 0xFF15181E;
    private static final int BLACK_DOWN = 0xFFB8892F;
    private static final int EDGE = 0xFF0B0D11;

    private final boolean[] midiDown = new boolean[128];
    private final boolean[] touchDown = new boolean[128];
    private final SparseIntArray pointerKey = new SparseIntArray();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final int whiteCount;
    private final int[] whiteIndex = new int[128];
    private Listener listener;

    public PianoKeyboardView(Context context) {
        super(context);
        int w = 0;
        for (int k = FIRST; k <= LAST; k++) {
            whiteIndex[k] = w;
            if (!isBlack(k)) {
                w++;
            }
        }
        whiteCount = w;
    }

    public void setListener(Listener l) {
        listener = l;
    }

    private static boolean isBlack(int key) {
        int n = key % 12;
        return n == 1 || n == 3 || n == 6 || n == 8 || n == 10;
    }

    /** Desde el hilo MIDI: enciende o apaga una tecla. */
    public void setKey(int key, boolean on) {
        if (key < FIRST || key > LAST) {
            return;
        }
        midiDown[key] = on;
        postInvalidate();
    }

    public void clearMidi() {
        for (int i = 0; i < 128; i++) {
            midiDown[i] = false;
        }
        postInvalidate();
    }

    private boolean lit(int key) {
        return midiDown[key] || touchDown[key];
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float ww = w / whiteCount;
        float bw = ww * 0.62f;
        float bh = h * 0.62f;
        float r = ww * 0.14f;

        for (int k = FIRST; k <= LAST; k++) {
            if (isBlack(k)) {
                continue;
            }
            float x = whiteIndex[k] * ww;
            rect.set(x + 0.8f, 0, x + ww - 0.8f, h);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(lit(k) ? WHITE_DOWN : WHITE);
            canvas.drawRoundRect(rect, r, r, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1f);
            paint.setColor(EDGE);
            canvas.drawRoundRect(rect, r, r, paint);
        }
        for (int k = FIRST; k <= LAST; k++) {
            if (!isBlack(k)) {
                continue;
            }
            float x = (whiteIndex[k]) * ww - bw / 2f;
            rect.set(x, 0, x + bw, bh);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(lit(k) ? BLACK_DOWN : BLACK);
            canvas.drawRoundRect(rect, r, r, paint);
        }
    }

    private int keyAt(float x, float y) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0) {
            return -1;
        }
        float ww = w / whiteCount;
        float bw = ww * 0.62f;
        float bh = h * 0.62f;
        if (y < bh) {
            for (int k = FIRST; k <= LAST; k++) {
                if (!isBlack(k)) {
                    continue;
                }
                float left = whiteIndex[k] * ww - bw / 2f;
                if (x >= left && x <= left + bw) {
                    return k;
                }
            }
        }
        int wi = (int) (x / ww);
        if (wi < 0 || wi >= whiteCount) {
            return -1;
        }
        for (int k = FIRST; k <= LAST; k++) {
            if (!isBlack(k) && whiteIndex[k] == wi) {
                return k;
            }
        }
        return -1;
    }

    private void press(int pointerId, int key, float y) {
        if (key < 0) {
            return;
        }
        pointerKey.put(pointerId, key);
        if (!touchDown[key]) {
            touchDown[key] = true;
            int vel = 55 + (int) (60f * Math.max(0f, Math.min(1f, y / Math.max(1, getHeight()))));
            if (listener != null) {
                listener.onKeyDown(key, vel);
            }
        }
    }

    private void release(int pointerId) {
        int key = pointerKey.get(pointerId, -1);
        pointerKey.delete(pointerId);
        if (key >= 0 && touchDown[key]) {
            // solo suelta si ningun otro dedo sigue en esa tecla
            for (int i = 0; i < pointerKey.size(); i++) {
                if (pointerKey.valueAt(i) == key) {
                    return;
                }
            }
            touchDown[key] = false;
            if (listener != null) {
                listener.onKeyUp(key);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int idx = e.getActionIndex();
                press(e.getPointerId(idx), keyAt(e.getX(idx), e.getY(idx)), e.getY(idx));
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                for (int i = 0; i < e.getPointerCount(); i++) {
                    int id = e.getPointerId(i);
                    int now = keyAt(e.getX(i), e.getY(i));
                    int before = pointerKey.get(id, -1);
                    if (now != before) {
                        release(id);
                        press(id, now, e.getY(i));
                    }
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                release(e.getPointerId(e.getActionIndex()));
                break;
            }
            case MotionEvent.ACTION_CANCEL: {
                for (int i = pointerKey.size() - 1; i >= 0; i--) {
                    release(pointerKey.keyAt(i));
                }
                break;
            }
            default:
                break;
        }
        invalidate();
        return true;
    }
}
