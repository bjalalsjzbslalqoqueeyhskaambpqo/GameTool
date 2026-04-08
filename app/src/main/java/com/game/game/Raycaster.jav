package com.game.game;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;

import java.util.Random;

public class Raycaster {
    private static final float FOV      = (float)(Math.PI / 3);
    private static final float HALF_FOV = FOV / 2f;
    private static final int   NUM_RAYS = 180;
    private static final float MAX_DIST = Map.TILE * 6f;

    private final Paint paint = new Paint();
    private final Paint torchPaint = new Paint();
    private final Paint vignettePaint = new Paint();
    private final Paint minimapPaint = new Paint();
    private final Paint noisePaint = new Paint();
    private final Random noiseRandom = new Random();

    private final int screenW, screenH;
    private Bitmap minimapBitmap;
    private Bitmap wallTex;
    private Bitmap floorTex;
    private Bitmap ceilingTex;
    private Paint floorPaint = new Paint();
    private Paint ceilingPaint = new Paint();

    private static class Hit {
        float dist;
        boolean vertical;
    }

    public Raycaster(Context context, int w, int h) {
        screenW = w;
        screenH = h;

        paint.setAntiAlias(false);
        noisePaint.setColor(Color.argb(20, 255, 255, 255));

        RadialGradient torchGradient = new RadialGradient(
            screenW * 0.5f,
            screenH * 0.6f,
            Math.min(screenW, screenH) * 0.40f,
            Color.argb(28, 255, 180, 100),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP);
        torchPaint.setShader(torchGradient);

        RadialGradient vignetteGradient = new RadialGradient(
            screenW/2f,
            screenH/2f,
            Math.max(screenW, screenH) * 0.72f,
            Color.TRANSPARENT,
            Color.argb(245, 0, 0, 0),
            Shader.TileMode.CLAMP);
        vignettePaint.setShader(vignetteGradient);

        loadTextures(context);
        buildMinimapBitmap();
    }



    private void loadTextures(Context context) {
        try {
            wallTex = BitmapFactory.decodeStream(context.getAssets().open("wall.png"));
            floorTex = BitmapFactory.decodeStream(context.getAssets().open("floor.png"));
            ceilingTex = BitmapFactory.decodeStream(context.getAssets().open("ceiling.png"));

            if (floorTex != null) {
                floorPaint.setShader(new BitmapShader(floorTex,
                    Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
            }
            if (ceilingTex != null) {
                ceilingPaint.setShader(new BitmapShader(ceilingTex,
                    Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
            }
        } catch (Exception ignored) {
        }
    }

    private void buildMinimapBitmap() {
        int CELL = 4;
        int rows = Map.getRows();
        int cols = Map.getCols();
        minimapBitmap = Bitmap.createBitmap(
            cols * CELL, rows * CELL,
            Bitmap.Config.RGB_565);
        Canvas c = new Canvas(minimapBitmap);
        Paint p = new Paint();
        for (int r = 0; r < rows; r++) {
            for (int col = 0; col < cols; col++) {
                p.setColor(Map.isWall(col, r)
                    ? Color.rgb(140, 60, 60)
                    : Color.rgb(30, 30, 30));
                c.drawRect(col * CELL, r * CELL,
                    col * CELL + CELL - 1,
                    r * CELL + CELL - 1, p);
            }
        }
    }

    public void render(Canvas canvas, Player player) {
        float time = System.nanoTime() * 1e-9f;
        float breathe = (float)Math.sin(time * 2f) * 3f;
        float horizon = screenH / 2f + breathe;

        // Techo y suelo con texturas si existen
        if (ceilingTex != null) {
            canvas.drawRect(0, 0, screenW, horizon, ceilingPaint);
        } else {
            for (int y = 0; y < horizon; y += 4) {
                float t = y / Math.max(1f, horizon);
                int c = (int)(18 - t * 10);
                paint.setColor(Color.rgb(c, c, c + 3));
                canvas.drawRect(0, y, screenW, y + 4, paint);
            }
        }

        if (floorTex != null) {
            canvas.drawRect(0, horizon, screenW, screenH, floorPaint);
        } else {
            for (int y = (int)horizon; y < screenH; y += 4) {
                float t = (y - horizon) / Math.max(1f, (screenH - horizon));
                int c = (int)(25 + t * 8);
                int centerDark = (int)(Math.abs((y - horizon) / (screenH - horizon) - 0.5f) * 25f);
                c = Math.max(8, c - centerDark);
                paint.setColor(Color.rgb(c, c - 2, c - 5));
                canvas.drawRect(0, y, screenW, y + 4, paint);
            }
        }

        float angleStep = FOV / NUM_RAYS;
        float sliceW = (float)screenW / NUM_RAYS;

        for (int i = 0; i < NUM_RAYS; i++) {
            float rayAngle = player.angle - HALF_FOV + i * angleStep;
            Hit hit = castRay(player.x, player.y, rayAngle);
            float corrected = hit.dist * (float)Math.cos(rayAngle - player.angle);
            float wallH = Math.min((Map.TILE * screenH) / (corrected + 0.001f), screenH);
            float top = horizon - wallH / 2f;
            float left = i * sliceW;

            float flicker = 0.92f + 0.08f * (float)Math.sin(time * 8f);
            float light = Math.max(0.02f, 1f - (corrected / MAX_DIST));
            light = light * light * flicker;

            int baseR = hit.vertical ? 75 : 55;
            int baseG = hit.vertical ? 75 : 55;
            int baseB = hit.vertical ? 80 : 60;

            if (wallTex != null) {
                int texX = (int)((i / (float)NUM_RAYS) * (wallTex.getWidth() - 1));
                int texC = wallTex.getPixel(Math.max(0, texX), wallTex.getHeight() / 2);
                baseR = Color.red(texC);
                baseG = Color.green(texC);
                baseB = Color.blue(texC);
                if (!hit.vertical) {
                    baseR = (int)(baseR * 0.75f);
                    baseG = (int)(baseG * 0.75f);
                    baseB = (int)(baseB * 0.75f);
                }
            }

            int r = (int)(baseR * light);
            int g = (int)(baseG * light);
            int b = (int)(baseB * light);

            paint.setColor(Color.rgb(
                Math.max(0, Math.min(255, r)),
                Math.max(0, Math.min(255, g)),
                Math.max(0, Math.min(255, b))));
            canvas.drawRect(left, top, left + sliceW + 1, top + wallH, paint);
        }

        drawVignette(canvas);
        drawTorchGlow(canvas);
        drawFilmNoise(canvas);
    }

    private void drawFilmNoise(Canvas canvas) {
        for (int i = 0; i < 200; i++) {
            float x = noiseRandom.nextInt(screenW);
            float y = noiseRandom.nextInt(screenH);
            canvas.drawPoint(x, y, noisePaint);
        }
    }

    private void drawTorchGlow(Canvas canvas) {
        canvas.drawRect(0, 0, screenW, screenH, torchPaint);
    }

    private void drawVignette(Canvas canvas) {
        canvas.drawRect(0, 0, screenW, screenH, vignettePaint);
    }

    private Hit castRay(float px, float py, float angle) {
        float dirX = (float)Math.cos(angle);
        float dirY = (float)Math.sin(angle);

        int tile = Map.TILE;
        int mapX = (int)(px / tile);
        int mapY = (int)(py / tile);

        float invDirX = Math.abs(dirX) < 0.00001f ? 100000f : 1f / dirX;
        float invDirY = Math.abs(dirY) < 0.00001f ? 100000f : 1f / dirY;

        float deltaDistX = Math.abs(tile * invDirX);
        float deltaDistY = Math.abs(tile * invDirY);

        int stepX;
        int stepY;
        float sideDistX;
        float sideDistY;

        if (dirX < 0) {
            stepX = -1;
            sideDistX = (px - (mapX * tile)) * Math.abs(invDirX);
        } else {
            stepX = 1;
            sideDistX = (((mapX + 1) * tile) - px) * Math.abs(invDirX);
        }

        if (dirY < 0) {
            stepY = -1;
            sideDistY = (py - (mapY * tile)) * Math.abs(invDirY);
        } else {
            stepY = 1;
            sideDistY = (((mapY + 1) * tile) - py) * Math.abs(invDirY);
        }

        Hit hit = new Hit();
        hit.dist = MAX_DIST;
        for (int i = 0; i < 256; i++) {
            if (sideDistX < sideDistY) {
                hit.dist = sideDistX;
                sideDistX += deltaDistX;
                mapX += stepX;
                hit.vertical = true;
            } else {
                hit.dist = sideDistY;
                sideDistY += deltaDistY;
                mapY += stepY;
                hit.vertical = false;
            }

            if (Map.isWall(mapX, mapY)) {
                hit.dist = Math.min(hit.dist, MAX_DIST);
                return hit;
            }
            if (hit.dist >= MAX_DIST) break;
        }

        hit.dist = MAX_DIST;
        return hit;
    }

    // Minimap
    public void drawMinimap(Canvas canvas, Player player) {
        int CELL = 8;
        int rows = Map.getRows();
        int cols = Map.getCols();
        int mmW  = cols * CELL;
        int mmH  = rows * CELL;
        int offX = screenW - mmW - 10;
        int offY = 10;

        minimapPaint.setColor(Color.argb(140, 0, 0, 0));
        canvas.drawRect(offX-2, offY-2,
            offX+mmW+2, offY+mmH+2, minimapPaint);

        if (minimapBitmap != null) {
            canvas.drawBitmap(minimapBitmap, offX, offY, null);
        }

        float px = offX + (player.x / Map.TILE) * CELL;
        float py = offY + (player.y / Map.TILE) * CELL;
        minimapPaint.setColor(Color.rgb(80, 180, 255));
        canvas.drawCircle(px, py, 4, minimapPaint);

        minimapPaint.setColor(Color.rgb(80, 180, 255));
        minimapPaint.setStrokeWidth(2);
        canvas.drawLine(px, py,
            px + (float)Math.cos(player.angle)*8,
            py + (float)Math.sin(player.angle)*8, minimapPaint);
    }
}
