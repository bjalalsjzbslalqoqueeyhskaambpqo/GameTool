package com.game.core;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import com.game.input.Joystick;
import com.game.game.Map;
import com.game.game.Player;
import com.game.game.Raycaster;

public class GameSurface extends SurfaceView
        implements SurfaceHolder.Callback {

    private GameLoop   gameLoop;
    private Joystick   joystick;
    private Paint      paint;
    private Player     player;
    private Raycaster  raycaster;

    public GameSurface(Context context) {
        super(context);
        getHolder().addCallback(this);
        paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        joystick = new Joystick();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        int W = getWidth();
        int H = getHeight();
        joystick.init(W, H);

        // Spawn en centro del mapa
        float startX = Map.TILE * 1.5f;
        float startY = Map.TILE * 1.5f;
        player = new Player(startX, startY);
        raycaster = new Raycaster(W, H);

        gameLoop = new GameLoop(this, holder);
        gameLoop.start();
    }

    @Override public void surfaceChanged(SurfaceHolder h,int f,int w,int hh){}
    @Override public void surfaceDestroyed(SurfaceHolder h){ pause(); }

    public void update() {
        float spd = 2.5f;

        // Movimiento relativo a donde mira el jugador
        float dx = joystick.getMoveDX();
        float dy = joystick.getMoveDY();

        float moveX = (float)(Math.cos(player.angle) * (-dy)
                    + Math.cos(player.angle + Math.PI/2) * dx) * spd;
        float moveY = (float)(Math.sin(player.angle) * (-dy)
                    + Math.sin(player.angle + Math.PI/2) * dx) * spd;

        player.move(moveX, moveY);

        // Rotar cámara con lado derecho
        player.angle += joystick.getRotate() * 0.05f;
    }

    public void draw(Canvas canvas) {
        raycaster.render(canvas, player);
        joystick.draw(canvas, paint);

        // Viñeta oscura en bordes para más inmersión
        paint.setColor(Color.argb(120, 0, 0, 0));
        int W = getWidth(), H = getHeight();
        int v = 80;
        canvas.drawRect(0, 0, W, v, paint);
        canvas.drawRect(0, H-v, W, H, paint);
        canvas.drawRect(0, 0, v, H, paint);
        canvas.drawRect(W-v, 0, W, H, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        joystick.handleTouch(event);
        return true;
    }

    public void pause() {
        if (gameLoop != null) gameLoop.stopLoop();
    }

    public void resume() {
        if (gameLoop != null && !gameLoop.isRunning()) {
            gameLoop = new GameLoop(this, getHolder());
            gameLoop.start();
        }
    }
}
