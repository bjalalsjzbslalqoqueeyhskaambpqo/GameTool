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

    public volatile int   leftId     = -1;
    public volatile int   rightId    = -1;
    public volatile float leftStartX = 0;
    public volatile float leftStartY = 0;
    public volatile float leftDX     = 0;
    public volatile float leftDY     = 0;
    public volatile float rightLastX = 0;
    public volatile float rightLastY = 0;
    public volatile float rightDX    = 0;
    public volatile float rightDY    = 0;
    public volatile boolean attackPressed = false;

    private final Context ctx;
    private Player   player;
    private Raycaster raycaster;
    private NetClient netClient;

    private int   texId = -1;
    private int   program;
    private int   posHandle, uvHandle, texHandle;
    private FloatBuffer quadVerts;
    private final int[] pixelBuf = new int[RW * RH];
    private Bitmap frameBmp;

    private long  prevTime;
    private float delta = 1f;
    private long  frameCount = 0;

    private volatile boolean gameStarted   = false;
    private volatile boolean isSpectator   = false;
    private volatile String  statusMsg     = "Conectando...";
    private volatile int     playerCount   = 0;
    private volatile int     minPlayers    = 2;
    private volatile boolean showAttackFX  = false;
    private volatile int     myHp          = 100;
    private volatile boolean amKiller      = false;
    private volatile boolean isDead        = false;
    private volatile boolean showEndScreen = false;
    private volatile String  endMsg        = "";
    private volatile int     gameDuration  = 180;
    private long lastReconnect = 0;

    private static final String VERT =
        "attribute vec2 aPos;attribute vec2 aUV;varying vec2 vUV;" +
        "void main(){gl_Position=vec4(aPos,0,1);vUV=aUV;}";
    private static final String FRAG =
        "precision mediump float;varying vec2 vUV;uniform sampler2D uTex;" +
        "void main(){gl_FragColor=texture2D(uTex,vUV);}";
    private static final float[] QUAD = {
        -1f,1f,0f,0f, -1f,-1f,0f,1f, 1f,1f,1f,0f, 1f,-1f,1f,1f };

    public GameRenderer(Context ctx) { this.ctx = ctx; }

    public void connectToServer(Context context) {
        String name = "P" + (int)(Math.random()*900+100);
        netClient = new NetClient(name, new NetClient.Listener() {
            public void onConnected() {
                statusMsg = "Conectado...";
            }
            public void onJoined(int id, long seed, boolean spec) {
                Map.generate(seed);
                player = new Player(Map.getSpawnX(), Map.getSpawnY());
                isSpectator = spec;
                statusMsg = spec ? "Espectador" : "Esperando jugadores...";
            }
            public void onRoomInfo(int count, int min, boolean started) {
                playerCount = count;
                minPlayers  = min;
                if (!started) statusMsg = count + "/" + min + " jugadores";
            }
            public void onGameStart(boolean isKiller, int dur) {
                gameStarted = true;
                amKiller = isKiller;
                gameDuration = dur;
                statusMsg   = "";
            }
            public void onState(List<NetClient.RemotePlayer> p) {}
            public void onHit(int newHp) {
                myHp = newHp;
            }
            public void onPlayerDied(int id) {
                if (netClient != null && id == netClient.myId) {
                    myHp = 0;
                    isDead = true;
                }
            }
            public void onGameEnd(boolean killerWon) {
                gameStarted = false;
                showEndScreen = true;
                endMsg = killerWon ? "El asesino ganó" : "¡Sobreviviste!";
            }
            public void onDisconnected() {
                gameStarted = false;
                statusMsg   = "Reconectando...";
            }
        });
        netClient.connect();
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig cfg) {
        Map.generate(System.currentTimeMillis());
        player    = new Player(Map.getSpawnX(), Map.getSpawnY());
        raycaster = new Raycaster(RW, RH);
        frameBmp  = Bitmap.createBitmap(RW, RH, Bitmap.Config.ARGB_8888);

        program   = buildProgram(VERT, FRAG);
        posHandle = GLES20.glGetAttribLocation(program, "aPos");
        uvHandle  = GLES20.glGetAttribLocation(program, "aUV");
        texHandle = GLES20.glGetUniformLocation(program, "uTex");

        ByteBuffer bb = ByteBuffer.allocateDirect(QUAD.length*4);
        bb.order(ByteOrder.nativeOrder());
        quadVerts = bb.asFloatBuffer();
        quadVerts.put(QUAD).position(0);

        int[] ids = new int[1];
        GLES20.glGenTextures(1, ids, 0);
        texId = ids[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        GLES20.glClearColor(0,0,0,1);
        prevTime = System.nanoTime();
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int w, int h) {
        GLES20.glViewport(0, 0, w, h);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        long now = System.nanoTime();
        delta = Math.min((now - prevTime) / 16_666_667f, 3f);
        prevTime = now;
        frameCount++;

        if (netClient != null && !netClient.isOpen()) {
            long t = System.currentTimeMillis();
            if (t - lastReconnect > 3000) {
                lastReconnect = t;
                netClient.connect();
            }
        }

        if (!gameStarted && !showEndScreen) {
            drawWaitingScreen();
            return;
        }

        if (!showEndScreen && !isSpectator) {
            update();
            if (attackPressed) {
                netClient.sendAttack();
                attackPressed = false;
                showAttackFX  = true;
            }
            if (netClient != null)
                netClient.sendInput(leftDX, leftDY, player.angle);
        }

        if (!showEndScreen) {
            raycaster.render(pixelBuf, player, frameCount);

            List<float[]> sprites = new ArrayList<>();
            if (netClient != null) {
                int myId = netClient.myId;
                for (NetClient.RemotePlayer rp : netClient.remotePlayers) {
                    if (rp.id == myId || rp.spectator) continue;
                    sprites.add(new float[]{rp.x, rp.y});
                }
            }
            if (!sprites.isEmpty())
                raycaster.renderSprites(pixelBuf, player, sprites, frameCount);
        } else {
            for (int i = 0; i < pixelBuf.length; i++) {
                pixelBuf[i] = Color.BLACK;
            }
        }

        frameBmp.setPixels(pixelBuf, 0, RW, 0, 0, RW, RH);

        Canvas hudCanvas = new Canvas(frameBmp);
        Paint  hudP  = new Paint(Paint.ANTI_ALIAS_FLAG);

        // HP bar
        hudP.setColor(Color.argb(180, 0, 0, 0));
        hudCanvas.drawRect(4, RH-14, 84, RH-4, hudP);
        float hpPct = myHp / 100f;
        hudP.setColor(hpPct > 0.5f
            ? Color.rgb(50,200,50)
            : hpPct > 0.25f
                ? Color.rgb(200,150,0)
                : Color.rgb(200,50,50));
        hudCanvas.drawRect(5, RH-13, 5 + hpPct*78, RH-5, hudP);

        // Contador jugadores
        hudP.setColor(Color.argb(200,200,80,80));
        hudP.setTextSize(RH * 0.07f);
        int alive = 0;
        if (netClient != null)
            for (NetClient.RemotePlayer rp : netClient.remotePlayers)
                if (!rp.spectator) alive++;
        hudCanvas.drawText(alive + " vivos", RW*0.02f, RH*0.09f, hudP);
        if (amKiller) {
            hudP.setColor(Color.argb(220,200,30,30));
            hudP.setTextSize(RH * 0.07f);
            hudP.setTextAlign(Paint.Align.LEFT);
            hudCanvas.drawText("⚔ ASESINO", RW*0.02f, RH*0.18f, hudP);
        }
        if (isDead) {
            hudP.setColor(Color.argb(160,180,0,0));
            hudCanvas.drawRect(0,0,RW,RH,hudP);
            hudP.setColor(Color.WHITE);
            hudP.setTextSize(RH*0.12f);
            hudP.setTextAlign(Paint.Align.CENTER);
            hudCanvas.drawText("HAS MUERTO", RW/2f, RH/2f, hudP);
        }
        if (showEndScreen) {
            hudP.setColor(Color.argb(220,0,0,0));
            hudCanvas.drawRect(0,0,RW,RH,hudP);
            hudP.setColor(Color.WHITE);
            hudP.setTextSize(RH*0.10f);
            hudP.setTextAlign(Paint.Align.CENTER);
            hudCanvas.drawText(endMsg, RW/2f, RH/2f, hudP);
        }

        // Crosshair
        hudP.setColor(Color.argb(150,255,255,255));
        hudP.setStrokeWidth(1);
        hudCanvas.drawLine(RW/2f-6, RH/2f, RW/2f+6, RH/2f, hudP);
        hudCanvas.drawLine(RW/2f, RH/2f-6, RW/2f, RH/2f+6, hudP);

        // Flash de ataque
        if (showAttackFX) {
            hudP.setColor(Color.argb(60,255,50,50));
            hudCanvas.drawRect(0, 0, RW, RH, hudP);
            showAttackFX = false;
        }

        // Botón ataque — indicador visual
        hudP.setColor(Color.argb(60,200,50,50));
        hudCanvas.drawRect(RW*0.7f, RH*0.6f, RW*0.98f, RH*0.95f, hudP);
        hudP.setColor(Color.argb(120,255,255,255));
        hudP.setTextSize(RH * 0.065f);
        hudP.setTextAlign(Paint.Align.CENTER);
        hudCanvas.drawText("ATK", RW*0.84f, RH*0.81f, hudP);

        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, frameBmp, 0);
        drawQuad();
    }

    private void update() {
        float spd = 0.9f * delta;
        float a   = player.angle;
        float fx  = (float)Math.cos(a), fy = (float)Math.sin(a);
        float rx  = (float)Math.cos(a+Math.PI/2);
        float ry  = (float)Math.sin(a+Math.PI/2);
        float jx  = leftDX, jy = leftDY;
        if (Math.abs(jx)>0.05f || Math.abs(jy)>0.05f)
            player.move((fx*(-jy)+rx*jx)*spd, (fy*(-jy)+ry*jx)*spd);
        if (Math.abs(rightDX)>0.005f)
            player.angle += rightDX * 0.045f * delta;
        rightDX = 0; rightDY = 0;

        boolean moving = Math.abs(jx)>0.05f || Math.abs(jy)>0.05f;
        Assets.updateSteps(moving);
    }

    private void drawWaitingScreen() {
        Canvas c = new Canvas(frameBmp);
        c.drawColor(Color.BLACK);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextAlign(Paint.Align.CENTER);
        p.setColor(Color.rgb(180,50,50));
        p.setTextSize(RH*0.13f);
        c.drawText("DUNGEON", RW/2f, RH*0.25f, p);
        p.setColor(Color.rgb(140,140,140));
        p.setTextSize(RH*0.058f);
        c.drawText(statusMsg, RW/2f, RH*0.42f, p);
        if (!isSpectator) {
            p.setColor(Color.rgb(80,180,80));
            p.setTextSize(RH*0.068f);
            c.drawText(playerCount+"/"+minPlayers+" jugadores",
                RW/2f, RH*0.56f, p);
            p.setColor(Color.rgb(80,80,80));
            p.setTextSize(RH*0.04f);
            c.drawText("La partida inicia con "+minPlayers+" jugadores",
                RW/2f, RH*0.72f, p);
        } else {
            p.setColor(Color.rgb(200,150,50));
            p.setTextSize(RH*0.055f);
            c.drawText("Modo espectador", RW/2f, RH*0.56f, p);
        }
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, frameBmp, 0);
        drawQuad();
    }

    private void drawQuad() {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(program);
        quadVerts.position(0);
        GLES20.glVertexAttribPointer(posHandle,2,GLES20.GL_FLOAT,false,16,quadVerts);
        GLES20.glEnableVertexAttribArray(posHandle);
        quadVerts.position(2);
        GLES20.glVertexAttribPointer(uvHandle,2,GLES20.GL_FLOAT,false,16,quadVerts);
        GLES20.glEnableVertexAttribArray(uvHandle);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
        GLES20.glUniform1i(texHandle, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private int buildProgram(String v, String f) {
        int vs = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER);
        GLES20.glShaderSource(vs, v); GLES20.glCompileShader(vs);
        int fs = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER);
        GLES20.glShaderSource(fs, f); GLES20.glCompileShader(fs);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p,vs); GLES20.glAttachShader(p,fs);
        GLES20.glLinkProgram(p); return p;
    }
}
