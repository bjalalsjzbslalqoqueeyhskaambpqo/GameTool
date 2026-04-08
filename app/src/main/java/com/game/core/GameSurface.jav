package com.game.core;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
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
    private AudioTrack audioTrack;
    private long startTimeNs = 0L;
    private float sanity = 100f;

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
        Map.generate(System.currentTimeMillis());
        float startX = Map.getSpawnX();
        float startY = Map.getSpawnY();
        player = new Player(startX, startY);
        raycaster = new Raycaster(W, H);
        startTimeNs = System.nanoTime();
        sanity = 100f;

        generateAmbientSound();

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

        float spd = 0.35f * delta;
        float angle = player.angle;
        float forwardX = (float)Math.cos(angle);
        float forwardY = (float)Math.sin(angle);
        float rightX   = (float)Math.cos(angle + Math.PI/2);
        float rightY   = (float)Math.sin(angle + Math.PI/2);

        float jDX = joystick.getMoveDX();
        float jDY = joystick.getMoveDY();

        boolean moving = false;
        if (Math.abs(jDX) > 0.08f || Math.abs(jDY) > 0.08f) {
            float moveX = (forwardX*(-jDY) + rightX*jDX) * spd;
            float moveY = (forwardY*(-jDY) + rightY*jDX) * spd;
            player.move(moveX, moveY);
            moving = true;
        }

        float rot = joystick.getRotate();
        if (Math.abs(rot) > 0.008f) {
            player.angle += rot * 0.055f * delta;
            moving = true;
        }

        // Cordura: baja si está quieto mucho tiempo
        if (moving) {
            sanity = Math.min(100f, sanity + 0.05f * delta);
        } else {
            sanity = Math.max(0f, sanity - 0.22f * delta);
        }
    }

    public void draw(Canvas canvas) {
        raycaster.render(canvas, player);

        int W = getWidth(), H = getHeight();

        // Viñeta agresiva
        paint.setColor(Color.argb(180, 0, 0, 0));
        int v = 140;
        canvas.drawRect(0, 0, W, v, paint);
        canvas.drawRect(0, H-v, W, H, paint);
        canvas.drawRect(0, 0, v, H, paint);
        canvas.drawRect(W-v, 0, W, H, paint);

        raycaster.drawMinimap(canvas, player);
        joystick.draw(canvas, paint);

        // HUD cordura
        float barW = W * 0.32f;
        float barH = 10f;
        float x = 14f;
        float y = 14f;
        paint.setColor(Color.argb(120, 0, 0, 0));
        canvas.drawRect(x, y, x + barW, y + barH, paint);
        int r = (int)(255 * (1f - sanity / 100f));
        int g = (int)(220 * (sanity / 100f));
        paint.setColor(Color.rgb(r, g, 30));
        canvas.drawRect(x, y, x + barW * (sanity / 100f), y + barH, paint);

        // Timer supervivencia MM:SS
        long sec = (System.nanoTime() - startTimeNs) / 1_000_000_000L;
        long mm = sec / 60;
        long ss = sec % 60;
        String timeTxt = String.format("%02d:%02d", mm, ss);
        paint.setColor(Color.argb(180, 255, 255, 255));
        paint.setTextSize(22f);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(timeTxt, W / 2f, 22f, paint);
        paint.setTextAlign(Paint.Align.LEFT);

        // Pulso rojo si cordura baja
        if (sanity < 30f) {
            float pulse = (float)(0.5f + 0.5f * Math.sin(System.nanoTime() * 1e-9 * Math.PI));
            int alpha = (int)(30 + pulse * 40);
            paint.setColor(Color.argb(alpha, 120, 0, 0));
            canvas.drawRect(0, 0, W, H, paint);
        }

        paint.setColor(Color.argb(160, 255, 255, 255));
        paint.setTextSize(20f);
        canvas.drawText("FPS: " + (int)fps, 16f, H - 16f, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        joystick.handleTouch(event);
        return true;
    }

    private void generateAmbientSound() {
        int sampleRate = 22050;
        int duration = 2;
        int numSamples = sampleRate * duration;
        short[] buffer = new short[numSamples];
        for (int i = 0; i < numSamples; i++) {
            double t = (double)i / sampleRate;
            double buzz = Math.sin(2 * Math.PI * 60 * t) * 0.15;
            double noise = (Math.random() - 0.5) * 0.04;
            buffer[i] = (short)((buzz + noise) * Short.MAX_VALUE);
        }
        audioTrack = new AudioTrack(
            AudioManager.STREAM_MUSIC,
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            buffer.length * 2,
            AudioTrack.MODE_STATIC);
        audioTrack.write(buffer, 0, buffer.length);
        audioTrack.setLoopPoints(0, buffer.length, -1);
        audioTrack.play();
    }

    public void pause() {
        if (audioTrack != null) audioTrack.pause();
        if (gameLoop != null) gameLoop.stopLoop();
    }

    public void resume() {
        if (audioTrack != null) audioTrack.play();
        if (gameLoop != null && !gameLoop.isRunning()) {
            gameLoop = new GameLoop(this, getHolder());
            gameLoop.start();
        }
    }
}
