package com.game.core;

import android.graphics.Canvas;
import android.view.SurfaceHolder;

public class GameLoop extends Thread {
    private static final int TARGET_FPS = 60;
    private static final long TARGET_TIME = 1000000000L / TARGET_FPS;

    private final GameSurface surface;
    private final SurfaceHolder holder;
    private volatile boolean running = false;

    public GameLoop(GameSurface surface, SurfaceHolder holder) {
        this.surface = surface;
        this.holder = holder;
    }

    public boolean isRunning() { return running; }

    public void stopLoop() {
        running = false;
        try { join(); } catch (InterruptedException e) { e.printStackTrace(); }
    }

    @Override
    public void run() {
        running = true;
        while (running) {
            long start = System.nanoTime();
            Canvas canvas = null;
            try {
                canvas = holder.lockCanvas();
                if (canvas != null) {
                    synchronized (holder) {
                        surface.update();
                        surface.draw(canvas);
                    }
                }
            } finally {
                if (canvas != null) holder.unlockCanvasAndPost(canvas);
            }
            long elapsed = System.nanoTime() - start;
            long sleep = (TARGET_TIME - elapsed) / 1000000;
            if (sleep > 0) {
                try { Thread.sleep(sleep); }
                catch (InterruptedException e) { break; }
            }
        }
    }
}
