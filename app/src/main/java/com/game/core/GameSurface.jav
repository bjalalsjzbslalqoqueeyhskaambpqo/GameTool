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
    private long       lastFrameNs = 0L;
    private float      fps = 0f;

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
        float startX = Map.TILE * 11.5f;
        float startY = Map.TILE * 11.5f;
        player = new Player(startX, startY);
        raycaster = new Raycaster(W, H);

        gameLoop = new GameLoop(this, holder);
        gameLoop.start();
    }

    @Override public void surfaceChanged(SurfaceHolder h,int f,int w,int hh){}
    @Override public void surfaceDestroyed(SurfaceHolder h){ pause(); }

    public void update(float delta) {
        long now = System.nanoTime();
        if (lastFrameNs != 0L) {
            long dt = now - lastFrameNs;
            if (dt > 0) fps = 1_000_000_000f / dt;
        }
        lastFrameNs = now;

        // Movimiento — más lento
        float spd = 0.35f * delta;
        float angle = player.angle;
        float forwardX = (float)Math.cos(angle);
        float forwardY = (float)Math.sin(angle);
        float rightX   = (float)Math.cos(angle + Math.PI/2);
        float rightY   = (float)Math.sin(angle + Math.PI/2);

        float jDX = joystick.getMoveDX();
        float jDY = joystick.getMoveDY();

        // Solo mover si hay input significativo
        if (Math.abs(jDX) > 0.08f || Math.abs(jDY) > 0.08f) {
            float moveX = (forwardX*(-jDY) + rightX*jDX) * spd;
            float moveY = (forwardY*(-jDY) + rightY*jDX) * spd;
            player.move(moveX, moveY);
        }

        // Rotación cámara
        float rot = joystick.getRotate();
        if (Math.abs(rot) > 0.008f) {
            player.angle += rot * 0.055f * delta;
        }
    }

    public void draw(Canvas canvas) {
        raycaster.render(canvas, player);

        // Viñeta oscura en bordes para más inmersión
        paint.setColor(Color.argb(120, 0, 0, 0));
        int W = getWidth(), H = getHeight();
        int v = 80;
        canvas.drawRect(0, 0, W, v, paint);
        canvas.drawRect(0, H-v, W, H, paint);
        canvas.drawRect(0, 0, v, H, paint);
        canvas.drawRect(W-v, 0, W, H, paint);

        raycaster.drawMinimap(canvas, player);
        joystick.draw(canvas, paint);

        paint.setColor(Color.argb(180, 255, 255, 255));
        paint.setTextSize(24f);
        canvas.drawText("FPS: " + (int)fps, 16f, 28f, paint);
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
