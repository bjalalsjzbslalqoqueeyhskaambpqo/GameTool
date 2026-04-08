package com.game.core;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import com.game.input.Joystick;

public class GameSurface extends SurfaceView implements SurfaceHolder.Callback {
    private GameLoop gameLoop;
    private Joystick joystick;
    private Paint paint;

    // Posición del jugador
    private float playerX, playerY;
    private float playerR = 30f;

    public GameSurface(Context context) {
        super(context);
        getHolder().addCallback(this);
        paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        joystick = new Joystick();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        playerX = getWidth() / 2f;
        playerY = getHeight() / 2f;
        joystick.init(getWidth(), getHeight());
        gameLoop = new GameLoop(this, holder);
        gameLoop.start();
    }

    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int h2) {}
    @Override public void surfaceDestroyed(SurfaceHolder holder) { pause(); }

    public void update() {
        float spd = 8f;
        playerX += joystick.getDX() * spd;
        playerY += joystick.getDY() * spd;
        // Limitar a pantalla
        playerX = Math.max(playerR, Math.min(getWidth()  - playerR, playerX));
        playerY = Math.max(playerR, Math.min(getHeight() - playerR, playerY));
    }

    public void draw(Canvas canvas) {
        // Fondo
        canvas.drawColor(Color.rgb(20, 30, 20));

        // Jugador
        paint.setColor(Color.rgb(50, 150, 255));
        canvas.drawCircle(playerX, playerY, playerR, paint);

        // Joystick
        joystick.draw(canvas, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        joystick.handleTouch(event);
        return true;
    }

    public void pause()  { if (gameLoop != null) gameLoop.stopLoop(); }
    public void resume() { if (gameLoop != null && !gameLoop.isRunning()) {
        gameLoop = new GameLoop(this, getHolder()); gameLoop.start(); } }
}
