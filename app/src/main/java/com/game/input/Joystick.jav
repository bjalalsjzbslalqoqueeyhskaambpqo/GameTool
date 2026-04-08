package com.game.input;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;

public class Joystick {
    private int screenW, screenH;
    private float leftDX, leftDY;
    private float rightDX;
    private int leftId = -1, rightId = -1;
    private float leftTouchX, leftTouchY;
    private float rightTouchX, rightTouchY;

    public void init(int w, int h) {
        screenW = w;
        screenH = h;
    }

    public float getMoveDX() { return leftDX; }
    public float getMoveDY() { return leftDY; }
    public float getRotate()  { return rightDX; }

    public void handleTouch(MotionEvent e) {
        int action = e.getActionMasked();
        int idx = e.getActionIndex();
        int pid = e.getPointerId(idx);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                float tx = e.getX(idx);
                float ty = e.getY(idx);
                if (tx < screenW / 2f && leftId == -1) {
                    leftId = pid;
                    leftTouchX = tx;
                    leftTouchY = ty;
                } else if (tx >= screenW / 2f && rightId == -1) {
                    rightId = pid;
                    rightTouchX = tx;
                }
                break;

            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) {
                    int id = e.getPointerId(i);
                    if (id == leftId) {
                        float dx = e.getX(i) - leftTouchX;
                        float dy = e.getY(i) - leftTouchY;
                        float len = (float)Math.sqrt(dx*dx+dy*dy);
                        float max = 80f;
                        if (len > max) { dx=dx/len*max; dy=dy/len*max; }
                        leftDX = dx / max;
                        leftDY = dy / max;
                    }
                    if (id == rightId) {
                        float dx = e.getX(i) - rightTouchX;
                        rightDX = dx / 120f;
                        rightTouchX = e.getX(i);
                    }
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL:
                if (pid == leftId)  {
                    leftId=-1; leftDX=0; leftDY=0;
                }
                if (pid == rightId) {
                    rightId=-1; rightDX=0;
                }
                break;
        }
    }

    public void draw(Canvas canvas, Paint paint) {
        // Línea divisoria sutil
        paint.setColor(Color.argb(30, 255, 255, 255));
        paint.setStrokeWidth(2f);
        canvas.drawLine(screenW/2f, 0, screenW/2f, screenH, paint);

        // Indicador izquierdo
        paint.setColor(Color.argb(25, 255, 255, 255));
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRect(0, 0, screenW/2f, screenH, paint);

        // Texto guía
        paint.setColor(Color.argb(60, 255, 255, 255));
        paint.setTextSize(28f);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("MOVER", screenW/4f, screenH-30, paint);
        canvas.drawText("CAMARA", screenW*3/4f, screenH-30, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.LEFT);
    }
}
