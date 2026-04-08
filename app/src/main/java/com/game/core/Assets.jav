package com.game.core;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaPlayer;
import android.media.AudioTrack;

public class Assets {
    public static int[] wallPixels;
    public static int[] floorPixels;
    public static int[] ceilPixels;
    public static int texW = 64, texH = 64;
    private static MediaPlayer ambient;
    private static AudioTrack stepsTrack;
    private static boolean wasMoving = false;

    public static void load(Context ctx) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inScaled = false;

        wallPixels  = loadPixels(ctx, "wall",    o);
        floorPixels = loadPixels(ctx, "floor",   o);
        ceilPixels  = loadPixels(ctx, "ceiling", o);

        if (wallPixels  == null) wallPixels  = genBrick();
        if (floorPixels == null) floorPixels = genFloor();
        if (ceilPixels  == null) ceilPixels  = genCeil();

        try {
            int id = ctx.getResources().getIdentifier(
                "ambient", "raw", ctx.getPackageName());
            if (id != 0) {
                ambient = MediaPlayer.create(ctx, id);
                ambient.setLooping(true);
                ambient.setVolume(0.35f, 0.35f);
                ambient.start();
            }
        } catch (Exception ignored) {}
    }

    private static int[] loadPixels(Context ctx, String name,
            BitmapFactory.Options o) {
        try {
            int id = ctx.getResources().getIdentifier(
                name, "drawable", ctx.getPackageName());
            if (id == 0) return null;
            Bitmap b = BitmapFactory.decodeResource(
                ctx.getResources(), id, o);
            if (b == null) return null;
            Bitmap s = Bitmap.createScaledBitmap(b, texW, texH, true);
            int[] px = new int[texW * texH];
            s.getPixels(px, 0, texW, 0, 0, texW, texH);
            return px;
        } catch (Exception e) { return null; }
    }

    private static int[] genBrick() {
        int[] p = new int[texW * texH];
        java.util.Random r = new java.util.Random(1);
        for (int y = 0; y < texH; y++) {
            for (int x = 0; x < texW; x++) {
                boolean mortar = (y % 16 < 2) ||
                    ((y/16)%2==0 && x%32 < 2) ||
                    ((y/16)%2==1 && (x+16)%32 < 2);
                int v = mortar ? 40 : 80 + r.nextInt(30);
                p[y*texW+x] = 0xFF000000|(v<<16)|((v-5)<<8)|(v-10);
            }
        }
        return p;
    }

    private static int[] genFloor() {
        int[] p = new int[texW * texH];
        java.util.Random r = new java.util.Random(2);
        for (int i = 0; i < p.length; i++) {
            int v = 25 + r.nextInt(15);
            p[i] = 0xFF000000|(v<<16)|((v-2)<<8)|(v-4);
        }
        return p;
    }

    private static int[] genCeil() {
        int[] p = new int[texW * texH];
        java.util.Random r = new java.util.Random(3);
        for (int i = 0; i < p.length; i++) {
            int v = 15 + r.nextInt(10);
            p[i] = 0xFF000000|(v<<16)|(v<<8)|v;
        }
        return p;
    }


    public static void updateSteps(boolean isMoving) {
        if (isMoving && !wasMoving) startSteps();
        if (!isMoving && wasMoving) stopSteps();
        wasMoving = isMoving;
    }

    private static void startSteps() {
        if (stepsTrack != null) {
            stepsTrack.play();
            return;
        }
        int rate = 22050;
        int stepLen = rate / 2;
        short[] buf = new short[stepLen * 2];
        java.util.Random r = new java.util.Random();
        for (int s = 0; s < 2; s++) {
            int off = s * stepLen;
            int hit = (int)(stepLen * 0.05f);
            for (int i = 0; i < stepLen; i++) {
                float t = (float)i / rate;
                float env = i < hit
                    ? (float)i/hit
                    : (float)Math.exp(-(i-hit) * 8.0/rate);
                double thud = Math.sin(2*Math.PI*120*t) * 0.6;
                double noise = (r.nextDouble()-0.5) * 0.4;
                double crack = i < hit*2
                    ? (r.nextDouble()-0.5)*0.8 : 0;
                buf[off+i] = (short)((thud+noise+crack)*env
                    * Short.MAX_VALUE * 0.7);
            }
        }
        stepsTrack = new AudioTrack(
            android.media.AudioManager.STREAM_MUSIC,
            rate,
            android.media.AudioFormat.CHANNEL_OUT_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT,
            buf.length * 2,
            AudioTrack.MODE_STATIC);
        stepsTrack.write(buf, 0, buf.length);
        stepsTrack.setLoopPoints(0, buf.length, -1);
        stepsTrack.setPlaybackRate((int)(rate * 0.95f));
        stepsTrack.play();
    }

    private static void stopSteps() {
        if (stepsTrack != null) stepsTrack.pause();
    }

    public static void pause()  {
        if (ambient != null && ambient.isPlaying()) ambient.pause();
    }
    public static void resume() {
        if (ambient != null && !ambient.isPlaying()) ambient.start();
    }
    public static void release() {
        if (ambient != null) { ambient.release(); ambient = null; }
        if (stepsTrack != null) { stepsTrack.release(); stepsTrack = null; }
    }
}
