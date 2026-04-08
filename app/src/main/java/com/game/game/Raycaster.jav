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
    private static final float MAX_DIST = 420f;
    private static final float FOG_START = 40f;

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
        paint.setColor(Color.rgb(8, 8, 12));
        canvas.drawRect(0, 0, screenW, screenH/2f, paint);
        // Suelo
        paint.setColor(Color.rgb(20, 16, 12));
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

            int r = (int)(160 * fog);
            int g = (int)(60  * fog);
            int b = (int)(60  * fog);

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
    }

    private void drawVignette(Canvas canvas) {
        Paint p = new Paint();
        RadialGradient gradient = new RadialGradient(
            screenW/2f, screenH/2f,
            Math.max(screenW, screenH) * 0.7f,
            Color.TRANSPARENT,
            Color.argb(220, 0, 0, 0),
            Shader.TileMode.CLAMP);
        p.setShader(gradient);
        canvas.drawRect(0, 0, screenW, screenH, p);
    }

    private float castRay(float px, float py, float angle) {
        float cosA = (float)Math.cos(angle);
        float sinA = (float)Math.sin(angle);
        for (float d = 1; d < MAX_DIST; d += 1.5f) {
            if (Map.isWallAt(px + cosA*d, py + sinA*d))
                return d;
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
