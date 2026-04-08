package com.game.input;

import android.view.MotionEvent;

public class Joystick {
    private int screenW;

    private volatile float leftDX, leftDY;
    private volatile float rightDX, rightDY;
    private volatile int leftId  = -1;
    private volatile int rightId = -1;
    private volatile float leftStartX, leftStartY;
    private volatile float rightStartX;
    private volatile float rightLastX, rightLastY;

    private static final float DEAD = 0.08f;
    private static final float MAX  = 100f;

    public void init(int w, int h) { screenW = w; }

    public float getMoveDX() { return leftDX; }
    public float getMoveDY() { return leftDY; }
    public float getRotate()  { return rightDX; }
    public float getRotateY() { return rightDY; }

    public void handleTouch(MotionEvent e) {
        int action = e.getActionMasked();
        int idx    = e.getActionIndex();
        int pid    = e.getPointerId(idx);
        float ex   = e.getX(idx);
        float ey   = e.getY(idx);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                if (ex < screenW * 0.5f) {
                    if (leftId == -1) {
                        leftId = pid;
                        leftStartX = ex;
                        leftStartY = ey;
                        leftDX = leftDY = 0;
                    }
                } else {
                    if (rightId == -1) {
                        rightId = pid;
                        rightLastX = ex;
                        rightLastY = ey;
                        rightDX = rightDY = 0;
                    }
                }
                break;

            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) {
                    int id = e.getPointerId(i);
                    float mx = e.getX(i);
                    float my = e.getY(i);

                    if (id == leftId) {
                        float dx = mx - leftStartX;
                        float dy = my - leftStartY;
                        float len = (float)Math.sqrt(dx*dx+dy*dy);
                        if (len > MAX) { dx=dx/len*MAX; dy=dy/len*MAX; }
                        leftDX = Math.abs(dx/MAX) > DEAD ? dx/MAX : 0;
                        leftDY = Math.abs(dy/MAX) > DEAD ? dy/MAX : 0;
                    }

                    if (id == rightId) {
                        float ddx = mx - rightLastX;
                        float ddy = my - rightLastY;
                        rightDX = ddx / 180f;
                        rightDY = ddy / 300f;
                        rightLastX = mx;
                        rightLastY = my;
                    }
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL:
                if (pid == leftId) {
                    leftId = -1;
                    leftDX = leftDY = 0;
                }
                if (pid == rightId) {
                    rightId = -1;
                    rightDX = rightDY = 0;
                }
                break;
        }
    }
}
