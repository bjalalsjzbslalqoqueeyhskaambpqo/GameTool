package com.game.core;

import android.content.Context;
import android.graphics.Bitmap;
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

    private long lastReconnectMs = 0L;

    private final NetClient netClient = new NetClient(new NetClient.Listener() {
        @Override
        public void onConnected() {
            waitingForPlayers = true;
        }

        @Override
        public void onWaiting(String room, int players) {
            roomCode = room;
            connectedPlayers = players;
            waitingForPlayers = true;
            gameStarted = false;
        }

        @Override
        public void onStart(boolean isSpectator) {
            spectator = isSpectator;
            gameStarted = true;
            waitingForPlayers = false;
        }

        @Override
        public void onDisconnected() {
            waitingForPlayers = true;
            gameStarted = false;
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

        frameBmp.setPixels(pixelBuf, 0, RW, 0, 0, RW, RH);
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
        int bg = 0xFF101010;
        int txt = 0xFFFFFFFF;
        for (int i = 0; i < pixelBuf.length; i++) {
            pixelBuf[i] = bg;
        }

        drawTextBlock(8, 12, "WAITING ROOM", txt);
        drawTextBlock(8, 30, "ROOM: " + roomCode, txt);
        drawTextBlock(8, 48, "PLAYERS: " + connectedPlayers + "/2", txt);

        frameBmp.setPixels(pixelBuf, 0, RW, 0, 0, RW, RH);
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

    private void drawTextBlock(int x, int y, String text, int color) {
        int px = x;
        for (int i = 0; i < text.length(); i++) {
            drawGlyph(px, y, text.charAt(i), color);
            px += 6;
        }
    }

    private void drawGlyph(int x, int y, char c, int color) {
        if (c == ' ') {
            return;
        }
        for (int gy = 0; gy < 7; gy++) {
            for (int gx = 0; gx < 5; gx++) {
                if (gx == 0 || gx == 4 || gy == 0 || gy == 6) {
                    int sx = x + gx;
                    int sy = y + gy;
                    if (sx >= 0 && sx < RW && sy >= 0 && sy < RH) {
                        pixelBuf[sy * RW + sx] = color;
                    }
                }
            }
        }
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
