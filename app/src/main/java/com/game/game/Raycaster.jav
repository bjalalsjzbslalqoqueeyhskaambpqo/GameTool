package com.game.game;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

public class Raycaster {
    private static final float FOV = (float)(Math.PI / 3);  // 60 grados
    private static final float HALF_FOV = FOV / 2f;
    private static final int   NUM_RAYS = 120;
    private static final float MAX_DIST = 800f;

    private Paint paint = new Paint();
    private int screenW, screenH;

    public Raycaster(int screenW, int screenH) {
        this.screenW = screenW;
        this.screenH = screenH;
        paint.setAntiAlias(false);
    }

    public void render(Canvas canvas, Player player) {
        // Cielo (techo)
        paint.setColor(Color.rgb(10, 10, 15));
        canvas.drawRect(0, 0, screenW, screenH / 2f, paint);

        // Suelo
        paint.setColor(Color.rgb(25, 20, 15));
        canvas.drawRect(0, screenH / 2f, screenW, screenH, paint);

        float rayAngle = player.angle - HALF_FOV;
        float angleStep = FOV / NUM_RAYS;
        float sliceW = (float) screenW / NUM_RAYS;

        for (int i = 0; i < NUM_RAYS; i++) {
            float dist = castRay(player.x, player.y, rayAngle);

            // Corregir ojo de pez
            float corrected = dist * (float)Math.cos(rayAngle - player.angle);

            // Altura de la pared
            float wallH = (Map.TILE * screenH) / (corrected + 0.0001f);
            wallH = Math.min(wallH, screenH);

            float top    = (screenH - wallH) / 2f;
            float left   = i * sliceW;

            // Color según distancia — más lejos = más oscuro
            float bright = 1f - (corrected / MAX_DIST);
            bright = Math.max(0.05f, Math.min(1f, bright));
            int c = (int)(180 * bright);
            int cDark = (int)(120 * bright);

            // Alternar tono claro/oscuro para dar sensación de profundidad
            paint.setColor(i % 2 == 0
                ? Color.rgb(c, cDark, cDark)
                : Color.rgb(cDark, cDark/2, cDark/2));

            canvas.drawRect(left, top, left + sliceW + 1, top + wallH, paint);

            rayAngle += angleStep;
        }
    }

    private float castRay(float px, float py, float angle) {
        float cosA = (float)Math.cos(angle);
        float sinA = (float)Math.sin(angle);

        for (float dist = 1; dist < MAX_DIST; dist += 2) {
            float rx = px + cosA * dist;
            float ry = py + sinA * dist;
            if (Map.isWallAt(rx, ry)) return dist;
        }
        return MAX_DIST;
    }
}
