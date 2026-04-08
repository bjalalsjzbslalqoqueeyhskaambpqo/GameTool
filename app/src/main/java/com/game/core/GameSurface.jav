package com.game.core;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import com.game.input.Joystick;
import com.game.physics.Body;
import com.game.physics.World;

public class GameSurface extends SurfaceView implements SurfaceHolder.Callback {
    private GameLoop gameLoop;
    private Joystick joystick;
    private Paint paint;
    private World world;
    private Body player;

    // Bolas de prueba para ver colisiones
    private Body[] balls;

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
        world = new World(W, H);

        // Jugador
        player = new Body(W/2f, H/2f, 30f, false);
        player.damping = 0.75f;
        world.addBody(player);

        // Bolas estáticas de prueba (obstáculos)
        balls = new Body[5];
        float[][] pos = {
            {W*0.25f, H*0.3f},
            {W*0.75f, H*0.3f},
            {W*0.5f,  H*0.5f},
            {W*0.2f,  H*0.7f},
            {W*0.8f,  H*0.7f},
        };
        for (int i = 0; i < 5; i++) {
            balls[i] = new Body(pos[i][0], pos[i][1], 25f, true);
            world.addBody(balls[i]);
        }

        gameLoop = new GameLoop(this, holder);
        gameLoop.start();
    }

    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {}
    @Override public void surfaceDestroyed(SurfaceHolder holder) { pause(); }

    public void update() {
        float spd = 12f;
        player.applyForce(
            joystick.getDX() * spd,
            joystick.getDY() * spd
        );
        world.step();
    }

    public void draw(Canvas canvas) {
        canvas.drawColor(Color.rgb(20, 30, 20));

        // Obstáculos
        paint.setColor(Color.rgb(120, 100, 70));
        for (Body b : balls) {
            canvas.drawCircle(b.pos.x, b.pos.y, b.radius, paint);
        }

        // Jugador
        paint.setColor(Color.rgb(50, 150, 255));
        canvas.drawCircle(player.pos.x, player.pos.y, player.radius, paint);

        // Joystick
        joystick.draw(canvas, paint);
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
