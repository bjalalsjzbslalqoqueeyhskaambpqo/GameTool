package com.game.core;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.os.Handler;
import android.os.Looper;
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
    public enum GameState {
        CONNECTING, WAITING, PLAYING, DEAD, END, SPECTATING
    }
    private static final int MODE_KILLER    = 0;
    private static final int MODE_INFECTION = 1;
    private static final int MODE_FFA       = 2;

    private static class RemoteState {
        float x, y, angle;
        float renderX, renderY, renderAngle;
        boolean initialized = false;

        void updateTarget(float nx, float ny, float na) {
            x = nx; y = ny; angle = na;
            if (!initialized) {
                renderX = nx; renderY = ny;
                renderAngle = na;
                initialized = true;
            }
        }

        void interpolate(float factor) {
            renderX     += (x - renderX)     * factor;
            renderY     += (y - renderY)     * factor;
            renderAngle += (angle - renderAngle) * factor;
        }
    }

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

    public volatile GameState state = GameState.CONNECTING;
    private volatile String  statusMsg     = "Conectando...";
    private volatile int     playerCount   = 0;
    private volatile int     minPlayers    = 2;
    private volatile boolean showAttackFX  = false;
    private volatile int     myHp          = 100;
    public volatile boolean amKiller      = false;
    private volatile String  endMsg        = "";
    private volatile int     gameDuration  = 180;
    public volatile int gameMode   = MODE_KILLER;
    public volatile boolean amInfected = false;
    private volatile int myRole = 0;
    // 0=normal 1=killer 2=infected
    private volatile int gameTimer = 0;
    private volatile boolean endKillerWon = false;
    private long lastReconnect = 0;
    private final java.util.concurrent.ConcurrentHashMap
        <Integer, RemoteState> remoteStates =
        new java.util.concurrent.ConcurrentHashMap<>();

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
                state = GameState.CONNECTING;
                statusMsg = "Conectando...";
            }
            public void onJoined(int id, long seed, boolean spec) {
                Map.generate(seed);
                player = new Player(Map.getSpawnX(), Map.getSpawnY());
                state = spec ? GameState.SPECTATING : GameState.WAITING;
                statusMsg = spec ? "Espectador" : "Esperando jugadores...";
            }
            public void onRoomInfo(int count, int min, boolean started) {
                playerCount = count;
                minPlayers  = min;
            }
            public void onGameStart(boolean isKiller,
                    boolean isInfected, int mode, int duration) {
                state = GameState.PLAYING;
                gameMode   = mode;
                amKiller   = isKiller;
                amInfected = isInfected;
                gameDuration = duration;
                gameTimer = duration;
                if (isKiller) myRole = 1;
                else if (isInfected) myRole = 2;
                else myRole = 0;
                statusMsg   = "";
            }
            public void onState(List<NetClient.RemotePlayer> list) {
                for (NetClient.RemotePlayer rp : list) {
                    RemoteState rs = remoteStates.get(rp.id);
                    if (rs == null) {
                        rs = new RemoteState();
                        remoteStates.put(rp.id, rs);
                    }
                    rs.updateTarget(rp.x, rp.y, rp.angle);
                }
            }
            public void onHit(int newHp) {
                myHp = newHp;
                if (myHp <= 0) state = GameState.DEAD;
            }
            public void onPlayerDied(int id) {
                if (netClient != null && id == netClient.myId) {
                    state = GameState.DEAD;
                }
            }
            public void onGameEnd(boolean killerWon) {
                state = GameState.END;
                endKillerWon = killerWon;
                endMsg = killerWon ? "El asesino ganó" : "¡Sobreviviste!";
                new Handler(Looper.getMainLooper())
                    .postDelayed(() -> resetToWaiting(), 4000);
            }
            public void onDisconnected() {
                state = GameState.CONNECTING;
                statusMsg   = "Reconectando...";
            }
        });
        netClient.connect();
    }

    private void resetToWaiting() {
        state       = GameState.WAITING;
        amKiller    = false;
        amInfected  = false;
        myRole      = 0;
        myHp        = 100;
        endMsg      = "";
        playerCount = 0;
        statusMsg   = "Esperando jugadores...";
        remoteStates.clear();
        Map.generate(System.currentTimeMillis());
        player = new Player(Map.getSpawnX(), Map.getSpawnY());
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

        switch (state) {
            case CONNECTING:
            case WAITING:
            case SPECTATING:
                drawWaitingScreen();
                return;
            case END:
                drawEndScreen();
                return;
            case DEAD:
                raycaster.render(pixelBuf, player, frameCount);
                drawDeadOverlay();
                flushFrame();
                return;
            case PLAYING:
                break;
        }

        if (state == GameState.PLAYING) {
            gameTimer = gameDuration;
            boolean canAttack = (gameMode==MODE_KILLER && amKiller)
                || (gameMode==MODE_INFECTION && amInfected)
                || (gameMode==MODE_FFA);
            update();
            if (attackPressed && canAttack) {
                netClient.sendAttack();
                attackPressed = false;
                showAttackFX  = true;
            }
            if (netClient != null)
                netClient.sendInput(player.x, player.y, player.angle);
        }

        raycaster.render(pixelBuf, player, frameCount);
        float interpFactor = Math.min(1f, delta * 0.25f);
        for (RemoteState rs : remoteStates.values())
            rs.interpolate(interpFactor);

        List<float[]> sprites = new ArrayList<>();
        if (netClient != null) {
            for (NetClient.RemotePlayer rp : netClient.remotePlayers) {
                if (rp.id == netClient.myId || rp.spectator) continue;
                RemoteState rs = remoteStates.get(rp.id);
                if (rs != null && rs.initialized)
                    sprites.add(new float[]{rs.renderX, rs.renderY});
            }
        }
        if (!sprites.isEmpty())
            raycaster.renderSprites(pixelBuf, player, sprites, frameCount);

        frameBmp.setPixels(pixelBuf, 0, RW, 0, 0, RW, RH);

        Canvas hud = new Canvas(frameBmp);
        Paint hp = new Paint(Paint.ANTI_ALIAS_FLAG);
        boolean canAttack = (gameMode==MODE_KILLER && amKiller)
            || (gameMode==MODE_INFECTION && amInfected)
            || (gameMode==MODE_FFA);

        if (gameTimer > 0) {
            int mins = gameTimer / 60;
            int secs = gameTimer % 60;
            hp.setTextAlign(Paint.Align.CENTER);
            hp.setColor(gameTimer < 30
                ? Color.rgb(220,60,60)
                : Color.argb(180,220,220,220));
            hp.setTextSize(RH * 0.072f);
            hp.setFakeBoldText(true);
            hud.drawText(String.format("%d:%02d", mins, secs),
                RW/2f, RH*0.09f, hp);
            hp.setFakeBoldText(false);
        }

        hp.setColor(Color.argb(160, 0, 0, 0));
        hud.drawRect(4, RH-16, 90, RH-4, hp);
        float hpPct = myHp / 100f;
        hp.setColor(hpPct > 0.5f
            ? Color.rgb(40,200,60)
            : hpPct > 0.25f
                ? Color.rgb(220,160,0)
                : Color.rgb(200,40,40));
        hud.drawRect(5, RH-15, 5+hpPct*84, RH-5, hp);
        hp.setColor(Color.argb(120,255,255,255));
        hp.setTextSize(RH*0.042f);
        hp.setTextAlign(Paint.Align.LEFT);
        hud.drawText(myHp+"hp", 7, RH-6, hp);

        String roleText = "";
        int roleColor = Color.WHITE;
        if (gameMode == MODE_KILLER && amKiller) {
            roleText = "⚔ ASESINO";
            roleColor = Color.rgb(220,40,40);
        } else if (gameMode == MODE_KILLER && !amKiller) {
            roleText = "SUPERVIVIENTE";
            roleColor = Color.rgb(80,180,80);
        } else if (gameMode == MODE_INFECTION && amInfected) {
            roleText = "INFECTADO";
            roleColor = Color.rgb(120,200,50);
        } else if (gameMode == MODE_INFECTION && !amInfected) {
            roleText = "SANO";
            roleColor = Color.rgb(80,180,80);
        } else if (gameMode == MODE_FFA) {
            roleText = "TODOS vs TODOS";
            roleColor = Color.rgb(200,150,50);
        }
        if (!roleText.isEmpty()) {
            hp.setColor(roleColor);
            hp.setTextSize(RH * 0.055f);
            hp.setFakeBoldText(true);
            hp.setTextAlign(Paint.Align.LEFT);
            hud.drawText(roleText, RW*0.02f, RH*0.16f, hp);
            hp.setFakeBoldText(false);
        }

        if (canAttack) {
            hp.setColor(Color.argb(70,200,40,40));
            hud.drawRect(RW*0.72f, RH*0.62f, RW*0.98f, RH*0.93f, hp);
            hp.setColor(Color.argb(140,255,80,80));
            hp.setStrokeWidth(1.5f);
            hp.setStyle(Paint.Style.STROKE);
            hud.drawRect(RW*0.72f, RH*0.62f, RW*0.98f, RH*0.93f, hp);
            hp.setStyle(Paint.Style.FILL);
            hp.setColor(Color.argb(180,255,255,255));
            hp.setTextSize(RH * 0.07f);
            hp.setFakeBoldText(true);
            hp.setTextAlign(Paint.Align.CENTER);
            hud.drawText("⚔", RW*0.85f, RH*0.81f, hp);
            hp.setFakeBoldText(false);
            hp.setTextSize(RH * 0.038f);
            hud.drawText("ATACAR", RW*0.85f, RH*0.90f, hp);
        }

        if (amInfected) {
            float pulse = (float)Math.sin(frameCount*0.08)*0.5f+0.5f;
            int alpha = (int)(40 + pulse*40);
            hp.setColor(Color.argb(alpha, 50, 200, 50));
            hud.drawRect(0,0,RW,5,hp);
            hud.drawRect(0,RH-5,RW,RH,hp);
            hud.drawRect(0,0,5,RH,hp);
            hud.drawRect(RW-5,0,RW,RH,hp);
        }

        hp.setColor(Color.argb(100,255,255,255));
        hp.setStrokeWidth(1f);
        hud.drawLine(RW/2f-5, RH/2f, RW/2f+5, RH/2f, hp);
        hud.drawLine(RW/2f, RH/2f-5, RW/2f, RH/2f+5, hp);

        if (showAttackFX) {
            hp.setColor(Color.argb(80,255,40,40));
            hp.setStyle(Paint.Style.FILL);
            hud.drawRect(0, 0, RW, RH, hp);
            showAttackFX = false;
        }
        flushFrame();
    }

    private void update() {
        float spd;
        if (gameMode == MODE_KILLER && amKiller)
            spd = 1.25f * delta;
        else if (gameMode == MODE_INFECTION && amInfected)
            spd = 1.15f * delta;
        else if (gameMode == MODE_FFA)
            spd = 1.0f * delta;
        else
            spd = 0.9f * delta;
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
        if (gameMode == MODE_FFA && myHp > 0
                && myHp < 100 && frameCount % 300 == 0)
            myHp = Math.min(100, myHp + 1);
    }

    private void drawWaitingScreen() {
        Canvas c = new Canvas(frameBmp);
        c.drawColor(Color.BLACK);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextAlign(Paint.Align.CENTER);

        p.setColor(Color.rgb(180, 30, 30));
        p.setTextSize(RH * 0.14f);
        p.setFakeBoldText(true);
        c.drawText("DUNGEON", RW/2f, RH*0.22f, p);

        p.setColor(Color.rgb(80, 20, 20));
        p.setStrokeWidth(1.5f);
        c.drawLine(RW*0.1f, RH*0.27f, RW*0.9f, RH*0.27f, p);

        p.setFakeBoldText(false);

        if (state == GameState.CONNECTING) {
            p.setColor(Color.rgb(120,120,120));
            p.setTextSize(RH * 0.055f);
            c.drawText(statusMsg, RW/2f, RH*0.42f, p);
            int dots = (int)(frameCount / 15) % 4;
            String anim = ".".repeat(dots);
            c.drawText(anim, RW/2f, RH*0.52f, p);
        } else if (state == GameState.SPECTATING) {
            p.setColor(Color.rgb(200,150,50));
            p.setTextSize(RH * 0.065f);
            c.drawText("ESPECTADOR", RW/2f, RH*0.42f, p);
            p.setColor(Color.rgb(80,80,80));
            p.setTextSize(RH * 0.042f);
            c.drawText("Esperando próxima partida...", RW/2f, RH*0.54f, p);
        } else {
            p.setColor(Color.rgb(100,100,100));
            p.setTextSize(RH * 0.048f);
            c.drawText("Sala de espera", RW/2f, RH*0.36f, p);

            float circleY = RH * 0.52f;
            float circleR = RH * 0.045f;
            float spacing = RW / (minPlayers + 1f);
            for (int i = 0; i < minPlayers; i++) {
                float cx = spacing * (i + 1);
                if (i < playerCount) {
                    p.setColor(Color.rgb(50, 180, 80));
                    p.setStyle(Paint.Style.FILL);
                    c.drawCircle(cx, circleY, circleR, p);
                } else {
                    p.setColor(Color.rgb(50, 50, 50));
                    p.setStyle(Paint.Style.FILL);
                    c.drawCircle(cx, circleY, circleR, p);
                    p.setColor(Color.rgb(80,80,80));
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(1.5f);
                    c.drawCircle(cx, circleY, circleR, p);
                }
            }
            p.setStyle(Paint.Style.FILL);

            p.setColor(Color.rgb(80, 180, 80));
            p.setTextSize(RH * 0.062f);
            p.setFakeBoldText(true);
            c.drawText(playerCount + " / " + minPlayers,
                RW/2f, RH*0.67f, p);
            p.setFakeBoldText(false);
            p.setColor(Color.rgb(60,60,60));
            p.setTextSize(RH * 0.038f);
            c.drawText("jugadores conectados", RW/2f, RH*0.75f, p);
            c.drawText("La partida inicia automáticamente",
                RW/2f, RH*0.84f, p);
        }
        String modeLabel = gameMode==0 ? "Asesino" :
            gameMode==1 ? "Infección" : "Todos vs Todos";
        p.setColor(Color.rgb(100,100,100));
        p.setTextSize(RH*0.04f);
        c.drawText("Modo: " + modeLabel, RW/2f, RH*0.88f, p);
        flushFrame();
    }

    private void drawEndScreen() {
        Canvas c = new Canvas(frameBmp);
        c.drawColor(Color.BLACK);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextAlign(Paint.Align.CENTER);
        String titulo;
        String sub;
        int color;
        if (gameMode == MODE_KILLER) {
            titulo = endKillerWon ? "EL ASESINO GANÓ" : "SOBREVIVIENTES GANAN";
            sub = endKillerWon
                ? (amKiller ? "¡Eliminaste a todos!" : "No lograste escapar...")
                : (amKiller ? "No pudiste con todos..." : "¡Sobreviviste!");
            color = endKillerWon ? Color.rgb(220,40,40) : Color.rgb(50,220,80);
        } else if (gameMode == MODE_INFECTION) {
            titulo = endKillerWon ? "LA INFECCIÓN SE EXTENDIÓ" : "LOS SANOS GANAN";
            sub = amInfected ? "Infectaste a todos" : "Resististe la infección";
            color = endKillerWon ? Color.rgb(120,200,50) : Color.rgb(80,180,180);
        } else {
            titulo = "¡PARTIDA TERMINADA!";
            sub = myHp > 0 ? "¡Sobreviviste!" : "Fuiste eliminado";
            color = myHp > 0 ? Color.rgb(220,180,50) : Color.rgb(150,150,150);
        }
        p.setColor(Color.argb(80, 0, 0, 0));
        c.drawRect(0, 0, RW, RH, p);
        p.setColor(color);
        p.setTextSize(RH * 0.085f);
        p.setFakeBoldText(true);
        c.drawText(titulo, RW/2f, RH*0.38f, p);
        p.setFakeBoldText(false);
        p.setTextSize(RH * 0.048f);
        c.drawText(sub, RW/2f, RH*0.58f, p);

        p.setColor(Color.rgb(80, 80, 80));
        p.setTextSize(RH * 0.038f);
        c.drawText("Nueva partida en breve...", RW/2f, RH*0.80f, p);
        flushFrame();
    }

    private void drawDeadOverlay() {
        Canvas c = new Canvas(frameBmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.argb(120, 180, 0, 0));
        c.drawRect(0, 0, RW, RH, p);
        p.setColor(Color.argb(220, 255, 255, 255));
        p.setTextSize(RH * 0.10f);
        p.setFakeBoldText(true);
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText("HAS MUERTO", RW/2f, RH*0.45f, p);
        p.setFakeBoldText(false);
        p.setTextSize(RH * 0.042f);
        p.setColor(Color.argb(160, 200, 200, 200));
        c.drawText("Esperando fin de partida...", RW/2f, RH*0.58f, p);
    }

    private void flushFrame() {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, frameBmp, 0);
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
