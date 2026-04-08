package com.game.input;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;

public class Joystick {
    private float baseX, baseY;
    private float knobX, knobY;
    private float baseR = 120f;
    private float knobR = 55f;
    private float dx, dy;
    private boolean active = false;
    private int touchId = -1;

    public void init(int screenW, int screenH) {
        baseX = knobX = baseR + 40;
        baseY = knobY = screenH - baseR - 40;
    }

    public float getDX() { return dx; }
    public float getDY() { return dy; }

    public void handleTouch(MotionEvent e) {
        int action = e.getActionMasked();
        int idx    = e.getActionIndex();
        int pid    = e.getPointerId(idx);

        if (action == MotionEvent.ACTION_DOWN
         || action == MotionEvent.ACTION_POINTER_DOWN) {
            float tx = e.getX(idx);
            float ty = e.getY(idx);
            float dist = (float) Math.hypot(tx - baseX, ty - baseY);
            if (!active && dist < baseR * 1.5f) {
                active = true;
                touchId = pid;
                updateKnob(tx, ty);
            }
        } else if (action == MotionEvent.ACTION_MOVE) {
            for (int i = 0; i < e.getPointerCount(); i++) {
                if (e.getPointerId(i) == touchId) {
                    updateKnob(e.getX(i), e.getY(i));
                }
            }
        } else if (action == MotionEvent.ACTION_UP
                || action == MotionEvent.ACTION_POINTER_UP
                || action == MotionEvent.ACTION_CANCEL) {
            if (pid == touchId) {
                active = false;
                touchId = -1;
                dx = dy = 0;
                knobX = baseX;
                knobY = baseY;
            }
        }
    }

    private void updateKnob(float tx, float ty) {
        float ddx = tx - baseX;
        float ddy = ty - baseY;
        float dist = (float) Math.hypot(ddx, ddy);
        if (dist > baseR) { ddx = ddx/dist*baseR; ddy = ddy/dist*baseR; }
        knobX = baseX + ddx;
        knobY = baseY + ddy;
        dx = ddx / baseR;
        dy = ddy / baseR;
    }

    public void draw(Canvas canvas, Paint paint) {
        paint.setAlpha(40);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(baseX, baseY, baseR, paint);
        paint.setAlpha(80);
        canvas.drawCircle(knobX, knobY, knobR, paint);
        paint.setAlpha(255);
    }
}
