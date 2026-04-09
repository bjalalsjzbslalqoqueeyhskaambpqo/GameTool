package com.game.core;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import com.game.game.Map;
import com.game.game.Player;
import com.game.game.Raycaster;
import com.game.net.NetClient;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class GameRenderer implements GLSurfaceView.Renderer {
    private static final int RW = 320;
    private static final int RH = 200;
    private static final String SERVER_URL = "ws://10.0.2.2:8080/ws";

    public volatile int leftId = -1;
    public volatile int rightId = -1;
    public volatile float leftStartX = 0;
    public volatile float leftStartY = 0;
    public volatile float leftDX = 0;
    public volatile float leftDY = 0;
    public volatile float rightLastX = 0;
    public volatile float rightLastY = 0;
    public volatile float rightDX = 0;
    public volatile float rightDY = 0;

    private final Context ctx;
    private Player player;
    private Raycaster raycaster;

    private int texId = -1;
    private int program;
    private int posHandle;
    private int uvHandle;
    private int texHandle;
    private FloatBuffer quadVerts;
    private final int[] pixelBuf = new int[RW * RH];
    private Bitmap frameBmp;

    private long prevTime;
    private float delta = 1f;
    private long frameCount = 0;
    private float pitch = 0f;

    private volatile boolean gameStarted = false;
    private volatile boolean spectator = false;
    private volatile boolean waitingForPlayers = true;
    private volatile String roomCode = "----";
    private volatile int connectedPlayers = 1;
    private volatile String statusMsg = "Esperando jugadores...";
    private volatile int minPlayers = 2;
    private final List<float[]> remotePlayers = new ArrayList<>();

    private long lastReconnectMs = 0L;

    private final NetClient netClient = new NetClient(new NetClient.Listener() {
        @Override
        public void onConnected() {
            waitingForPlayers = true;
            statusMsg = "Conectado al servidor";
        }

        @Override
        public void onWaiting(String room, int players) {
            roomCode = room;
            connectedPlayers = players;
            waitingForPlayers = true;
            gameStarted = false;
            statusMsg = "Sala " + room;
        }

        @Override
        public void onStart(boolean isSpectator) {
            spectator = isSpectator;
            gameStarted = true;
            waitingForPlayers = false;
            statusMsg = spectator
                ? "Entraste como espectador"
                : "Comienza la partida";
        }

        @Override
        public void onDisconnected() {
            waitingForPlayers = true;
            gameStarted = false;
            statusMsg = "Reconectando...";
        }
    });

    private static final String VERT_SRC =
        "attribute vec2 aPos;" +
        "attribute vec2 aUV;" +
        "varying vec2 vUV;" +
        "void main(){" +
        "  gl_Position = vec4(aPos, 0.0, 1.0);" +
        "  vUV = aUV;" +
        "}";

    private static final String FRAG_SRC =
        "precision mediump float;" +
        "varying vec2 vUV;" +
        "uniform sampler2D uTex;" +
        "void main(){" +
        "  gl_FragColor = texture2D(uTex, vUV);" +
        "}";

    private static final float[] QUAD = {
        -1f, 1f, 0f, 0f,
        -1f, -1f, 0f, 1f,
        1f, 1f, 1f, 0f,
        1f, -1f, 1f, 1f,
    };

    public GameRenderer(Context ctx) {
        this.ctx = ctx;
    }

    public void connectToServer(Context context) {
        netClient.connect(SERVER_URL);
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        Map.generate(System.currentTimeMillis());

        player = new Player(Map.getSpawnX(), Map.getSpawnY());
        raycaster = new Raycaster(RW, RH);
        frameBmp = Bitmap.createBitmap(RW, RH, Bitmap.Config.ARGB_8888);

        program = buildProgram(VERT_SRC, FRAG_SRC);
        GLES20.glUseProgram(program);

        posHandle = GLES20.glGetAttribLocation(program, "aPos");
        uvHandle = GLES20.glGetAttribLocation(program, "aUV");
        texHandle = GLES20.glGetUniformLocation(program, "uTex");

        ByteBuffer bb = ByteBuffer.allocateDirect(QUAD.length * 4);
        bb.order(ByteOrder.nativeOrder());
        quadVerts = bb.asFloatBuffer();
        quadVerts.put(QUAD).position(0);

        int[] ids = new int[1];
        GLES20.glGenTextures(1, ids, 0);
        texId = ids[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);

        GLES20.glClearColor(0f, 0f, 0f, 1f);
        prevTime = System.nanoTime();
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int w, int h) {
        GLES20.glViewport(0, 0, w, h);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        reconnectIfNeeded();

        long now = System.nanoTime();
        delta = Math.min((now - prevTime) / 16_666_667f, 3f);
        prevTime = now;
        frameCount++;

        if (waitingForPlayers || !gameStarted) {
            drawWaitingScreen();
            return;
        }

        if (!spectator) {
            update();
        }

        netClient.sendInput(leftDX, leftDY, rightDX, rightDY);

        raycaster.render(pixelBuf, player, frameCount);

        remotePlayers.clear();
        remotePlayers.addAll(readRemotePlayerPositions());

        List<float[]> sprites = new ArrayList<>();
        int myId = readMyId();
        for (float[] rp : remotePlayers) {
            int id = (int)rp[2];
            boolean isSpectator = rp[3] > 0.5f;
            if (id == myId) continue;
            if (isSpectator) continue;
            sprites.add(new float[]{rp[0], rp[1]});
        }
        if (!sprites.isEmpty()) {
            raycaster.renderSprites(pixelBuf, player, sprites, frameCount);
        }

        frameBmp.setPixels(pixelBuf, 0, RW, 0, 0, RW, RH);
        if (gameStarted) {
            Canvas hudCanvas = new Canvas(frameBmp);
            Paint hudP = new Paint(Paint.ANTI_ALIAS_FLAG);
            hudP.setColor(Color.argb(180, 255, 255, 255));
            hudP.setTextSize(RH * 0.06f);
            int alive = 0;
            for (float[] rp : remotePlayers) {
                if (rp[3] < 0.5f) alive++;
            }
            hudP.setColor(Color.argb(200, 200, 80, 80));
            hudCanvas.drawText(alive + " jugadores",
                RW * 0.02f, RH * 0.09f, hudP);
        }
        frameBmp.getPixels(pixelBuf, 0, RW, 0, 0, RW, RH);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, frameBmp, 0);

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(program);

        quadVerts.position(0);
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 16, quadVerts);
        GLES20.glEnableVertexAttribArray(posHandle);

        quadVerts.position(2);
        GLES20.glVertexAttribPointer(uvHandle, 2, GLES20.GL_FLOAT, false, 16, quadVerts);
        GLES20.glEnableVertexAttribArray(uvHandle);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
        GLES20.glUniform1i(texHandle, 0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        drawHUD(gl);
    }

    private void reconnectIfNeeded() {
        if (netClient.isConnected()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastReconnectMs > 3000) {
            lastReconnectMs = now;
            netClient.connect(SERVER_URL);
        }
    }

    private void drawWaitingScreen() {
        Canvas c = new Canvas(frameBmp);
        c.drawColor(Color.BLACK);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        p.setTextAlign(Paint.Align.CENTER);

        p.setColor(Color.rgb(180, 50, 50));
        p.setTextSize(RH * 0.12f);
        c.drawText("DUNGEON", RW/2f, RH*0.22f, p);

        p.setColor(Color.rgb(140, 140, 140));
        p.setTextSize(RH * 0.055f);
        c.drawText(statusMsg, RW/2f, RH*0.40f, p);
        c.drawText("Sala: " + roomCode, RW/2f, RH*0.47f, p);

        if (!spectator && !gameStarted) {
            p.setColor(Color.rgb(80, 180, 80));
            p.setTextSize(RH * 0.065f);
            c.drawText(connectedPlayers + " / " + minPlayers
                + " jugadores", RW/2f, RH*0.54f, p);

            p.setColor(Color.rgb(100, 100, 100));
            p.setTextSize(RH * 0.038f);
            c.drawText("La partida inicia cuando haya",
                RW/2f, RH*0.68f, p);
            c.drawText(minPlayers + " o mas jugadores conectados",
                RW/2f, RH*0.74f, p);
        }

        if (spectator) {
            p.setColor(Color.rgb(200, 150, 50));
            p.setTextSize(RH * 0.05f);
            c.drawText("Modo espectador", RW/2f, RH*0.54f, p);
            p.setColor(Color.rgb(100,100,100));
            p.setTextSize(RH * 0.038f);
            c.drawText("Esperando proxima partida...",
                RW/2f, RH*0.64f, p);
        }

        frameBmp.getPixels(pixelBuf, 0, RW, 0, 0, RW, RH);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, frameBmp, 0);

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(program);

        quadVerts.position(0);
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 16, quadVerts);
        GLES20.glEnableVertexAttribArray(posHandle);

        quadVerts.position(2);
        GLES20.glVertexAttribPointer(uvHandle, 2, GLES20.GL_FLOAT, false, 16, quadVerts);
        GLES20.glEnableVertexAttribArray(uvHandle);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
        GLES20.glUniform1i(texHandle, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private List<float[]> readRemotePlayerPositions() {
        List<float[]> out = new ArrayList<>();
        try {
            java.lang.reflect.Field playersField =
                netClient.getClass().getDeclaredField("remotePlayers");
            playersField.setAccessible(true);
            Object raw = playersField.get(netClient);
            if (!(raw instanceof List)) {
                return out;
            }
            List list = (List) raw;
            for (Object rp : list) {
                float x = readFloatField(rp, "x");
                float y = readFloatField(rp, "y");
                int id = (int) readFloatField(rp, "id");
                float spec = readBooleanField(rp, "spectator") ? 1f : 0f;
                out.add(new float[]{x, y, id, spec});
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private int readMyId() {
        try {
            java.lang.reflect.Field myIdField =
                netClient.getClass().getDeclaredField("myId");
            myIdField.setAccessible(true);
            Object v = myIdField.get(netClient);
            if (v instanceof Number) {
                return ((Number) v).intValue();
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private float readFloatField(Object obj, String field) {
        try {
            java.lang.reflect.Field f = obj.getClass().getDeclaredField(field);
            f.setAccessible(true);
            Object v = f.get(obj);
            if (v instanceof Number) return ((Number) v).floatValue();
        } catch (Exception ignored) {
        }
        return 0f;
    }

    private boolean readBooleanField(Object obj, String field) {
        try {
            java.lang.reflect.Field f = obj.getClass().getDeclaredField(field);
            f.setAccessible(true);
            Object v = f.get(obj);
            if (v instanceof Boolean) return (Boolean) v;
        } catch (Exception ignored) {
        }
        return false;
    }

    private void update() {
        float spd = 0.9f * delta;
        float angle = player.angle;
        float fx = (float) Math.cos(angle);
        float fy = (float) Math.sin(angle);
        float rx = (float) Math.cos(angle + Math.PI / 2);
        float ry = (float) Math.sin(angle + Math.PI / 2);

        float jx = leftDX;
        float jy = leftDY;
        if (Math.abs(jx) > 0.05f || Math.abs(jy) > 0.05f) {
            player.move((fx * (-jy) + rx * jx) * spd, (fy * (-jy) + ry * jx) * spd);
        }

        if (Math.abs(rightDX) > 0.005f) {
            player.angle += rightDX * 0.045f * delta;
        }

        if (Math.abs(rightDY) > 0.005f) {
            pitch = Math.max(-0.4f, Math.min(0.4f, pitch + rightDY * 0.03f * delta));
        }

        rightDX = 0;
        rightDY = 0;
    }

    private void drawHUD(GL10 gl) {
        // Reserved for HUD rendering.
    }

    private int buildProgram(String vert, String frag) {
        int v = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER);
        GLES20.glShaderSource(v, vert);
        GLES20.glCompileShader(v);
        int f = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER);
        GLES20.glShaderSource(f, frag);
        GLES20.glCompileShader(f);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        return p;
    }
}
