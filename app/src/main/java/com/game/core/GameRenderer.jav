package com.game.core;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;
import com.game.input.Joystick;
import com.game.game.Map;
import com.game.game.Player;
import com.game.game.Raycaster;

public class GameRenderer implements GLSurfaceView.Renderer {

    private static final int RW = 320;
    private static final int RH = 200;

    private final Context  ctx;
    private final Joystick joystick;
    private Player   player;
    private Raycaster raycaster;

    private int   texId = -1;
    private int   program;
    private int   posHandle, uvHandle, texHandle;
    private FloatBuffer quadVerts;
    private final int[] pixelBuf = new int[RW * RH];
    private Bitmap frameBmp;

    private int   screenW, screenH;
    private long  prevTime;
    private float delta = 1f;
    private long  frameCount = 0;
    private final float[] inputSnapshot = new float[3];

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
        -1f,  1f,  0f, 0f,
        -1f, -1f,  0f, 1f,
         1f,  1f,  1f, 0f,
         1f, -1f,  1f, 1f,
    };

    public GameRenderer(Context ctx, Joystick joystick) {
        this.ctx      = ctx;
        this.joystick = joystick;
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        Map.generate(System.currentTimeMillis());

        player    = new Player(Map.getSpawnX(), Map.getSpawnY());
        raycaster = new Raycaster(RW, RH);
        frameBmp  = Bitmap.createBitmap(RW, RH, Bitmap.Config.ARGB_8888);

        program = buildProgram(VERT_SRC, FRAG_SRC);
        GLES20.glUseProgram(program);

        posHandle = GLES20.glGetAttribLocation(program, "aPos");
        uvHandle  = GLES20.glGetAttribLocation(program, "aUV");
        texHandle = GLES20.glGetUniformLocation(program, "uTex");

        ByteBuffer bb = ByteBuffer.allocateDirect(QUAD.length * 4);
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

        GLES20.glClearColor(0f, 0f, 0f, 1f);
        prevTime = System.nanoTime();
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int w, int h) {
        screenW = w; screenH = h;
        GLES20.glViewport(0, 0, w, h);
    }

    private void snapshotInput() {
        inputSnapshot[0] = joystick.getMoveDX();
        inputSnapshot[1] = joystick.getMoveDY();
        inputSnapshot[2] = joystick.getRotate();
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        snapshotInput();
        long now = System.nanoTime();
        delta = Math.min((now - prevTime) / 16_666_667f, 3f);
        prevTime = now;
        frameCount++;

        update();

        raycaster.render(pixelBuf, player, frameCount);

        frameBmp.setPixels(pixelBuf, 0, RW, 0, 0, RW, RH);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, frameBmp, 0);

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(program);

        quadVerts.position(0);
        GLES20.glVertexAttribPointer(posHandle, 2,
            GLES20.GL_FLOAT, false, 16, quadVerts);
        GLES20.glEnableVertexAttribArray(posHandle);

        quadVerts.position(2);
        GLES20.glVertexAttribPointer(uvHandle, 2,
            GLES20.GL_FLOAT, false, 16, quadVerts);
        GLES20.glEnableVertexAttribArray(uvHandle);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
        GLES20.glUniform1i(texHandle, 0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        drawHUD(gl);
    }

    private void update() {
        float spd   = 1.4f * delta;
        float angle = player.angle;
        float fx = (float)Math.cos(angle);
        float fy = (float)Math.sin(angle);
        float rx = (float)Math.cos(angle + Math.PI/2);
        float ry = (float)Math.sin(angle + Math.PI/2);

        float jx = inputSnapshot[0];
        float jy = inputSnapshot[1];
        float rot = inputSnapshot[2];

        if (Math.abs(jx) > 0.08f || Math.abs(jy) > 0.08f) {
            player.move((fx * (-jy) + rx * jx) * spd,
                        (fy * (-jy) + ry * jx) * spd);
        }

        if (Math.abs(rot) > 0.008f) {
            player.angle += rot * 0.045f * delta;
        }
    }

    private void drawHUD(GL10 gl) {
        // HUD con Canvas sobre bitmap, luego textura GL
        // Por ahora dejarlo vacío — se agrega en siguiente iteración
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
