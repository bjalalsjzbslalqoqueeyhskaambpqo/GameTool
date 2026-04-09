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
    private static AudioTrack remoteStepTrack;
    private static AudioTrack tensionTrack;
    private static boolean wasMoving = false;
    private static long lastRemoteStepAt = 0;

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

    public static void playRemoteStepCue(float dist) {
        if (dist < 0 || dist > 120f) return;
        long now = System.currentTimeMillis();
        if (now - lastRemoteStepAt < 160) return;
        lastRemoteStepAt = now;
        try {
            if (remoteStepTrack == null) remoteStepTrack = buildRemoteStep();
            float vol = 0.06f + (1f - (dist / 120f)) * 0.28f;
            remoteStepTrack.setStereoVolume(vol, vol);
            remoteStepTrack.stop();
            remoteStepTrack.reloadStaticData();
            remoteStepTrack.play();
        } catch (Exception ignored) {}
    }

    public static void updateTension(float nearestDangerDist,
            boolean active) {
        if (!active || nearestDangerDist < 0 || nearestDangerDist > 170f) {
            if (tensionTrack != null) {
                try { tensionTrack.pause(); } catch (Exception ignored) {}
            }
            return;
        }
        try {
            if (tensionTrack == null) tensionTrack = buildTensionLoop();
            float t = 1f - Math.min(1f, nearestDangerDist / 170f);
            float vol = 0.03f + t * 0.17f;
            tensionTrack.setStereoVolume(vol, vol);
            if (tensionTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING)
                tensionTrack.play();
        } catch (Exception ignored) {}
    }

    private static AudioTrack buildRemoteStep() {
        int rate = 22050;
        int len = rate / 8;
        short[] buf = new short[len];
        java.util.Random r = new java.util.Random();
        int hit = (int)(len * 0.30f);
        for (int i = 0; i < len; i++) {
            float t = (float)i / rate;
            float env = i < hit
                ? (float)i / hit
                : (float)Math.exp(-(i-hit) * 12.0 / rate);
            double thud = Math.sin(2*Math.PI*95*t) * 0.7;
            double grit = (r.nextDouble()-0.5) * (i < hit ? 0.8 : 0.25);
            buf[i] = (short)((thud + grit) * env
                * Short.MAX_VALUE * 0.55);
        }
        AudioTrack t = new AudioTrack(
            android.media.AudioManager.STREAM_MUSIC,
            rate,
            android.media.AudioFormat.CHANNEL_OUT_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT,
            buf.length * 2,
            AudioTrack.MODE_STATIC);
        t.write(buf, 0, buf.length);
        return t;
    }

    private static AudioTrack buildTensionLoop() {
        int rate = 22050;
        int len = (int)(rate * 1.2f);
        short[] buf = new short[len];
        for (int i = 0; i < len; i++) {
            float t = (float)i / rate;
            float beat = (float)Math.sin(2*Math.PI*1.6*t);
            float env = beat > 0.80f ? (beat - 0.80f) / 0.20f : 0f;
            double low = Math.sin(2*Math.PI*58*t) * env;
            double rumble = Math.sin(2*Math.PI*31*t) * env * 0.5;
            buf[i] = (short)((low + rumble) * Short.MAX_VALUE * 0.55);
        }
        AudioTrack t = new AudioTrack(
            android.media.AudioManager.STREAM_MUSIC,
            rate,
            android.media.AudioFormat.CHANNEL_OUT_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT,
            buf.length * 2,
            AudioTrack.MODE_STATIC);
        t.write(buf, 0, buf.length);
        t.setLoopPoints(0, buf.length, -1);
        return t;
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
        if (remoteStepTrack != null) {
            remoteStepTrack.release(); remoteStepTrack = null;
        }
        if (tensionTrack != null) {
            tensionTrack.release(); tensionTrack = null;
        }
    }
}
