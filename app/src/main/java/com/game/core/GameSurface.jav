package com.game.core;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.MotionEvent;

public class GameSurface extends GLSurfaceView {
    private GameRenderer renderer;

    public GameSurface(Context context) {
        super(context);
        setEGLContextClientVersion(2);
        renderer = new GameRenderer(context);
        setRenderer(renderer);
        setRenderMode(RENDERMODE_CONTINUOUSLY);
        Assets.load(context);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        int idx    = e.getActionIndex();
        int pid    = e.getPointerId(idx);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                float x = e.getX(idx);
                float y = e.getY(idx);
                if (x < getWidth() * 0.5f) {
                    renderer.leftId     = pid;
                    renderer.leftStartX = x;
                    renderer.leftStartY = y;
                    renderer.leftDX     = 0;
                    renderer.leftDY     = 0;
                } else {
                    renderer.rightId    = pid;
                    renderer.rightLastX = x;
                    renderer.rightLastY = y;
                    renderer.rightDX    = 0;
                    renderer.rightDY    = 0;
                }
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                for (int i = 0; i < e.getPointerCount(); i++) {
                    int id = e.getPointerId(i);
                    if (id == renderer.leftId) {
                        float dx = e.getX(i) - renderer.leftStartX;
                        float dy = e.getY(i) - renderer.leftStartY;
                        float len = (float)Math.sqrt(dx*dx+dy*dy);
                        float max = 100f;
                        if (len > max) { dx=dx/len*max; dy=dy/len*max; }
                        renderer.leftDX = dx / max;
                        renderer.leftDY = dy / max;
                    }
                    if (id == renderer.rightId) {
                        renderer.rightDX = (e.getX(i) - renderer.rightLastX) / 45f;
                        renderer.rightDY = (e.getY(i) - renderer.rightLastY) / 300f;
                        renderer.rightLastX = e.getX(i);
                        renderer.rightLastY = e.getY(i);
                    }
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (pid == renderer.leftId) {
                    renderer.leftId = -1;
                    renderer.leftDX = renderer.leftDY = 0;
                }
                if (pid == renderer.rightId) {
                    renderer.rightId = -1;
                    renderer.rightDX = renderer.rightDY = 0;
                }
                break;
            }
        }
        return true;
    }
}
