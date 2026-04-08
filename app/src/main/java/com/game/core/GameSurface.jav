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
    private Joystick   joyLeft;
    private Joystick   joyRight;
    private Paint      paint;
    private Player     player;
    private Raycaster  raycaster;

    public GameSurface(Context context) {
        super(context);
        getHolder().addCallback(this);
        paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        joyLeft = new Joystick();
        joyLeft.side = "left";
        joyRight = new Joystick();
        joyRight.side = "right";
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        int W = getWidth();
        int H = getHeight();
        joyLeft.init(W, H);
        joyRight.init(W, H);

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
        float spd = 2f;
        float angle = player.angle;
        float moveX = (float)(Math.cos(angle) * joyLeft.getDY() * (-spd)
                + Math.cos(angle + Math.PI / 2f) * joyLeft.getDX() * spd);
        float moveY = (float)(Math.sin(angle) * joyLeft.getDY() * (-spd)
                + Math.sin(angle + Math.PI / 2f) * joyLeft.getDX() * spd);

        player.move(moveX, moveY);
        player.angle += joyRight.getDX() * 0.04f;
    }

    public void draw(Canvas canvas) {
        raycaster.render(canvas, player);
        joyLeft.draw(canvas, paint);
        joyRight.draw(canvas, paint);

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
        joyLeft.handleTouch(event);
        joyRight.handleTouch(event);
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
