package com.game.core;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.MotionEvent;
import com.game.input.Joystick;

public class GameSurface extends GLSurfaceView {
    private final GameRenderer renderer;
    final Joystick joystick = new Joystick();

    public GameSurface(Context context) {
        super(context);
        setEGLContextClientVersion(2);
        setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        renderer = new GameRenderer(context, joystick);
        setRenderer(renderer);
        setRenderMode(RENDERMODE_CONTINUOUSLY);
        Assets.load(context);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        joystick.handleTouch(event);
        return true;
    }
}
