package com.game.core;

import android.graphics.Canvas;
import android.os.Build;
import android.view.SurfaceHolder;

public class GameLoop extends Thread {
    private static final int   TARGET_FPS  = 60;
    private static final long  TARGET_NS   = 1_000_000_000L / TARGET_FPS;

    private final GameSurface  surface;
    private final SurfaceHolder holder;
    private volatile boolean   running = false;
    public  float              delta   = 1f; // multiplicador de tiempo

    public GameLoop(GameSurface surface, SurfaceHolder holder) {
        this.surface = surface;
        this.holder  = holder;
        setName("GameLoop");
        setPriority(Thread.MAX_PRIORITY);
    }

    public boolean isRunning() { return running; }

    public void stopLoop() {
        running = false;
        interrupt();
        try { join(500); } catch (InterruptedException ignored) {}
    }

    @Override
    public void run() {
        running = true;
        long prevTime = System.nanoTime();

        while (running) {
            long now     = System.nanoTime();
            long elapsed = now - prevTime;
            prevTime     = now;

            // Delta: 1.0 = exactamente 60fps, 2.0 = 30fps
            delta = (float) elapsed / TARGET_NS;
            delta = Math.min(delta, 3f); // cap para evitar saltos

            Canvas canvas = null;
            try {
                // Hardware canvas desde Android O — usa GPU
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    canvas = holder.lockHardwareCanvas();
                } else {
                    canvas = holder.lockCanvas();
                }
                if (canvas != null) {
                    surface.update(delta);
                    surface.draw(canvas);
                }
            } catch (Exception e) {
                // ignorar errores de surface destruida
            } finally {
                if (canvas != null) {
                    try { holder.unlockCanvasAndPost(canvas); }
                    catch (Exception ignored) {}
                }
            }

            // Limitar a TARGET_FPS
            long sleepNs = TARGET_NS - (System.nanoTime() - prevTime);
            if (sleepNs > 0) {
                try { Thread.sleep(sleepNs / 1_000_000, (int)(sleepNs % 1_000_000)); }
                catch (InterruptedException e) { break; }
            }
        }
    }
}
