package com.game.game;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;

public class Raycaster {
    private static final float FOV      = (float)(Math.PI / 3);
    private static final float HALF_FOV = FOV / 2f;
    private static final int   NUM_RAYS = 240;
    private static final float MAX_DIST = 220f;
    private static final float FOG_START = 8f;

    private Paint paint   = new Paint();
    private Paint fogPaint = new Paint();
    private int   screenW, screenH;

    public Raycaster(int w, int h) {
        screenW = w;
        screenH = h;
        paint.setAntiAlias(false);
    }

    public void render(Canvas canvas, Player player) {
        // Techo
        paint.setColor(Color.rgb(5, 5, 8));
        canvas.drawRect(0, 0, screenW, screenH/2f, paint);
        // Suelo
        paint.setColor(Color.rgb(12, 10, 8));
        canvas.drawRect(0, screenH/2f, screenW, screenH, paint);

        float angleStep = FOV / NUM_RAYS;
        float sliceW    = (float)screenW / NUM_RAYS;

        for (int i = 0; i < NUM_RAYS; i++) {
            float rayAngle = player.angle - HALF_FOV + i * angleStep;
            float dist = castRay(player.x, player.y, rayAngle);
            float corrected = dist *
                (float)Math.cos(rayAngle - player.angle);

            float wallH = Math.min(
                (Map.TILE * screenH) / (corrected + 0.001f),
                screenH);
            float top  = (screenH - wallH) / 2f;
            float left = i * sliceW;

            // Niebla — más lejos más oscuro
            float fog = 1f - Math.min(1f,
                Math.max(0f, (corrected - FOG_START) /
                (MAX_DIST - FOG_START)));
            fog = fog * fog; // curva cuadrática más dramática

            int r = (int)(120 * fog);
            int g = (int)(40  * fog);
            int b = (int)(40  * fog);

            // Alternar tono para dar textura
            if (i % 2 == 0) {
                r = (int)(r * 0.85f);
                g = (int)(g * 0.85f);
                b = (int)(b * 0.85f);
            }

            paint.setColor(Color.rgb(
                Math.max(0,Math.min(255,r)),
                Math.max(0,Math.min(255,g)),
                Math.max(0,Math.min(255,b))));
            canvas.drawRect(left, top,
                left + sliceW + 1, top + wallH, paint);
        }

        // Viñeta oscura en bordes
        drawVignette(canvas);

        // Efectos ligeros de ambiente
        drawScanlines(canvas);
        drawTorchGlow(canvas);
    }



    private void drawScanlines(Canvas canvas) {
        Paint p = new Paint();
        p.setColor(Color.argb(18, 0, 0, 0));
        for (int y = 0; y < screenH; y += 4) {
            canvas.drawRect(0, y, screenW, y + 1, p);
        }
    }

    private void drawTorchGlow(Canvas canvas) {
        Paint p = new Paint();
        float cx = screenW * 0.5f;
        float cy = screenH * 0.6f;
        RadialGradient g = new RadialGradient(
            cx, cy,
            Math.min(screenW, screenH) * 0.35f,
            Color.argb(20, 255, 160, 90),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP);
        p.setShader(g);
        canvas.drawRect(0, 0, screenW, screenH, p);
    }

    private void drawVignette(Canvas canvas) {
        Paint p = new Paint();
        RadialGradient gradient = new RadialGradient(
            screenW/2f, screenH/2f,
            Math.max(screenW, screenH) * 0.7f,
            Color.TRANSPARENT,
            Color.argb(235, 0, 0, 0),
            Shader.TileMode.CLAMP);
        p.setShader(gradient);
        canvas.drawRect(0, 0, screenW, screenH, p);
    }

    private float castRay(float px, float py, float angle) {
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

        float dist = MAX_DIST;
        for (int i = 0; i < 256; i++) {
            if (sideDistX < sideDistY) {
                dist = sideDistX;
                sideDistX += deltaDistX;
                mapX += stepX;
            } else {
                dist = sideDistY;
                sideDistY += deltaDistY;
                mapY += stepY;
            }

            if (Map.isWall(mapX, mapY)) {
                return Math.min(dist, MAX_DIST);
            }
            if (dist >= MAX_DIST) break;
        }

        return MAX_DIST;
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

        Paint p = new Paint();

        // Fondo
        p.setColor(Color.argb(140, 0, 0, 0));
        canvas.drawRect(offX-2, offY-2,
            offX+mmW+2, offY+mmH+2, p);

        // Tiles
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                p.setColor(Map.isWall(c, r)
                    ? Color.rgb(140, 60, 60)
                    : Color.rgb(30, 30, 30));
                canvas.drawRect(
                    offX + c*CELL, offY + r*CELL,
                    offX + c*CELL + CELL-1,
                    offY + r*CELL + CELL-1, p);
            }
        }

        // Jugador en minimap
        float px = offX + (player.x / Map.TILE) * CELL;
        float py = offY + (player.y / Map.TILE) * CELL;
        p.setColor(Color.rgb(80, 180, 255));
        canvas.drawCircle(px, py, 4, p);

        // Dirección del jugador
        p.setColor(Color.rgb(80, 180, 255));
        p.setStrokeWidth(2);
        canvas.drawLine(px, py,
            px + (float)Math.cos(player.angle)*8,
            py + (float)Math.sin(player.angle)*8, p);
    }
}
