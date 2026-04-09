package com.game.core;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

public class SpriteSheet {
    public static final int W = 32;
    public static final int H = 64;
    public static int[] pixels;

    public static void generate() {
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        int TRANSPARENT = Color.TRANSPARENT;
        c.drawColor(TRANSPARENT);

        int dark  = Color.argb(255, 25, 20, 20);
        int mid   = Color.argb(255, 45, 38, 35);
        int light = Color.argb(255, 65, 55, 50);
        int eye   = Color.argb(255, 180, 40, 40);

        p.setColor(mid);

        // Cabeza — 8x8 centrada arriba
        p.setColor(mid);
        c.drawRect(12, 2, 20, 10, p);
        // Sombra cabeza
        p.setColor(dark);
        c.drawRect(12, 2, 13, 10, p);
        c.drawRect(19, 2, 20, 10, p);
        // Ojos
        p.setColor(eye);
        c.drawRect(13, 4, 15, 6, p);
        c.drawRect(17, 4, 19, 6, p);

        // Cuello
        p.setColor(dark);
        c.drawRect(14, 10, 18, 13, p);

        // Torso — ancho arriba, estrecho abajo
        p.setColor(mid);
        c.drawRect(9, 13, 23, 30, p);
        // Sombra torso
        p.setColor(dark);
        c.drawRect(9, 13, 11, 30, p);
        c.drawRect(21, 13, 23, 30, p);
        // Detalle torso
        p.setColor(light);
        c.drawRect(13, 15, 19, 20, p);

        // Brazo izquierdo
        p.setColor(dark);
        c.drawRect(5, 13, 9, 28, p);
        // Brazo derecho
        c.drawRect(23, 13, 27, 28, p);

        // Mano izquierda
        p.setColor(mid);
        c.drawRect(4, 26, 9, 32, p);
        // Mano derecha
        c.drawRect(23, 26, 28, 32, p);

        // Cadera
        p.setColor(dark);
        c.drawRect(10, 30, 22, 34, p);

        // Pierna izquierda
        p.setColor(mid);
        c.drawRect(10, 34, 16, 54, p);
        p.setColor(dark);
        c.drawRect(10, 34, 11, 54, p);

        // Pierna derecha
        p.setColor(mid);
        c.drawRect(16, 34, 22, 54, p);
        p.setColor(dark);
        c.drawRect(21, 34, 22, 54, p);

        // Pie izquierdo
        p.setColor(dark);
        c.drawRect(9, 54, 17, 58, p);
        // Pie derecho
        c.drawRect(15, 54, 23, 58, p);

        pixels = new int[W * H];
        bmp.getPixels(pixels, 0, W, 0, 0, W, H);
    }
}
