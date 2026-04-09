package com.game.game;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import com.game.core.Assets;
import com.game.core.SpriteSheet;
import java.util.List;

public class Raycaster {

    private static final float FOV      = (float)(Math.PI / 3.0);
    private static final int   NUM_RAYS = 320;
    private static final float MAX_DIST = 5f;

    private final int   W, H;
    private final int   HALF_H;
    private final Paint paint   = new Paint();
    private final Paint mmPaint = new Paint();

    private final int[]    screenBuf;
    private final Bitmap   screenBmp;
    private final float[]  zBuf = new float[NUM_RAYS];

    private final Paint vignette = new Paint();
    private Bitmap minimapBmp;
    private long   frameCount = 0;

    public Raycaster(int w, int h) {
        W = w; H = h; HALF_H = h / 2;
        paint.setAntiAlias(false);
        screenBuf = new int[W * H];
        screenBmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);

        RadialGradient vg = new RadialGradient(
            W/2f, H/2f, Math.max(W,H)*0.65f,
            Color.TRANSPARENT, Color.argb(200,0,0,0),
            Shader.TileMode.CLAMP);
        vignette.setShader(vg);
    }

    public void render(int[] pixelBuf, Player player, long extFrameCount) {
        frameCount = extFrameCount;
        float px = player.x / Map.TILE;
        float py = player.y / Map.TILE;
        float angle = player.angle;

        float dirX  = (float)Math.cos(angle);
        float dirY  = (float)Math.sin(angle);
        float planeX = (float)Math.cos(angle + Math.PI/2) * 0.66f;
        float planeY = (float)Math.sin(angle + Math.PI/2) * 0.66f;

        int   texW = Assets.texW, texH = Assets.texH;
        int[] wallTex  = Assets.wallPixels;
        int[] floorTex = Assets.floorPixels;
        int[] ceilTex  = Assets.ceilPixels;

        float torchFlicker = 0.85f +
            (float)Math.sin(frameCount * 0.05) * 0.10f +
            (float)Math.sin(frameCount * 0.17) * 0.05f +
            (float)(Math.random() * 0.04);

        for (int x = 0; x < W; x++) {
            float cameraX = 2f * x / W - 1f;
            float rayDirX = dirX + planeX * cameraX;
            float rayDirY = dirY + planeY * cameraX;

            int mapX = (int)px, mapY = (int)py;

            float deltaX = Math.abs(rayDirX) < 1e-6f ? 1e6f : Math.abs(1f/rayDirX);
            float deltaY = Math.abs(rayDirY) < 1e-6f ? 1e6f : Math.abs(1f/rayDirY);

            int stepX, stepY;
            float sideX, sideY;

            if (rayDirX < 0) { stepX=-1; sideX=(px-mapX)*deltaX; }
            else              { stepX= 1; sideX=(mapX+1f-px)*deltaX; }
            if (rayDirY < 0) { stepY=-1; sideY=(py-mapY)*deltaY; }
            else              { stepY= 1; sideY=(mapY+1f-py)*deltaY; }

            boolean sideHit = false;
            int iter = 0;
            while (iter++ < 64) {
                if (sideX < sideY) { sideX+=deltaX; mapX+=stepX; sideHit=false; }
                else               { sideY+=deltaY; mapY+=stepY; sideHit=true;  }
                if (Map.isWall(mapX, mapY)) break;
            }

            float perpDist = sideHit
                ? (mapY - py + (1-stepY)/2f) / rayDirY
                : (mapX - px + (1-stepX)/2f) / rayDirX;
            perpDist = Math.max(0.0001f, perpDist);
            if (x < zBuf.length) zBuf[x] = perpDist;

            int lineH = (int)(H / perpDist);
            int drawStart = Math.max(0, HALF_H - lineH/2);
            int drawEnd   = Math.min(H-1, HALF_H + lineH/2);

            float wallX = sideHit
                ? px + perpDist * rayDirX
                : py + perpDist * rayDirY;
            wallX -= Math.floor(wallX);
            int texX = (int)(wallX * texW);
            if ((!sideHit && rayDirX > 0) || (sideHit && rayDirY < 0))
                texX = texW - texX - 1;
            texX = Math.max(0, Math.min(texW-1, texX));

            float fog  = Math.max(0f, 1f - perpDist / MAX_DIST);
            fog = fog * fog * fog * fog * torchFlicker;
            float dark = sideHit ? 0.6f : 1.0f;
            float bright = fog * dark;

            float step    = texH / (float)lineH;
            float texPosY = (drawStart - HALF_H + lineH/2f) * step;

            for (int y = drawStart; y <= drawEnd; y++) {
                int ty = Math.max(0, Math.min(texH-1, (int)texPosY));
                texPosY += step;
                int c = wallTex != null
                    ? wallTex[ty * texW + texX]
                    : (sideHit ? 0xFF505050 : 0xFF808080);
                int r2 = Math.min(255,(int)(Color.red(c)   * bright));
                int g2 = Math.min(255,(int)(Color.green(c) * bright));
                int b2 = Math.min(255,(int)(Color.blue(c)  * bright));
                screenBuf[y * W + x] = 0xFF000000|(r2<<16)|(g2<<8)|b2;
            }

            float floorWallX, floorWallY;
            if (!sideHit && rayDirX > 0) { floorWallX=mapX;   floorWallY=mapY+wallX; }
            else if (!sideHit)           { floorWallX=mapX+1f; floorWallY=mapY+wallX; }
            else if (sideHit && rayDirY > 0){ floorWallX=mapX+wallX; floorWallY=mapY; }
            else                         { floorWallX=mapX+wallX; floorWallY=mapY+1f; }

            float distWall = perpDist;
            for (int y = drawEnd+1; y < H; y++) {
                float distFloor = H / (2f*y - H);
                float weight    = distFloor / distWall;
                float cfX = weight*floorWallX + (1-weight)*px;
                float cfY = weight*floorWallY + (1-weight)*py;
                int fx = Math.abs((int)(cfX * texW)) % texW;
                int fy = Math.abs((int)(cfY * texH)) % texH;

                float ff = Math.max(0f, 1f-(distFloor/MAX_DIST));
                ff = ff*ff*torchFlicker;
                int fc = floorTex != null ? floorTex[fy*texW+fx] : 0xFF303030;
                int fr = Math.min(255,(int)(Color.red(fc)  *ff));
                int fg = Math.min(255,(int)(Color.green(fc)*ff));
                int fb = Math.min(255,(int)(Color.blue(fc) *ff));
                screenBuf[y*W+x] = 0xFF000000|(fr<<16)|(fg<<8)|fb;

                int cc = ceilTex != null ? ceilTex[fy*texW+fx] : 0xFF181818;
                float cf2 = ff * 0.7f;
                int cr = Math.min(255,(int)(Color.red(cc)  *cf2));
                int cg = Math.min(255,(int)(Color.green(cc)*cf2));
                int cb = Math.min(255,(int)(Color.blue(cc) *cf2));
                int cy2 = H - y - 1;
                if (cy2 >= 0 && cy2 < H)
                    screenBuf[cy2*W+x] = 0xFF000000|(cr<<16)|(cg<<8)|cb;
            }
        }

        screenBmp.setPixels(screenBuf, 0, W, 0, 0, W, H);
        Canvas c = new Canvas(screenBmp);
        c.drawRect(0, 0, W, H, vignette);
        screenBmp.getPixels(pixelBuf, 0, W, 0, 0, W, H);
    }

    public void renderSprites(int[] pixelBuf, Player player,
            List<float[]> sprites, long frameCount) {

        if (SpriteSheet.pixels == null) SpriteSheet.generate();

        int RW = W;
        int RH = H;
        float px    = player.x / Map.TILE;
        float py    = player.y / Map.TILE;
        float angle = player.angle;
        float dirX  = (float)Math.cos(angle);
        float dirY  = (float)Math.sin(angle);
        float planeX = (float)Math.cos(angle + Math.PI/2) * 0.66f;
        float planeY = (float)Math.sin(angle + Math.PI/2) * 0.66f;

        sprites.sort((a, b) -> {
            float da = (a[0]-player.x)*(a[0]-player.x)
                     + (a[1]-player.y)*(a[1]-player.y);
            float db = (b[0]-player.x)*(b[0]-player.x)
                     + (b[1]-player.y)*(b[1]-player.y);
            return Float.compare(db, da);
        });

        int SW = SpriteSheet.W;
        int SH = SpriteSheet.H;

        for (float[] sp : sprites) {
            float sx = sp[0] / Map.TILE - px;
            float sy = sp[1] / Map.TILE - py;

            float invDet = 1f / (planeX*dirY - dirX*planeY);
            float tX = invDet * (dirY*sx  - dirX*sy);
            float tY = invDet * (-planeY*sx + planeX*sy);

            if (tY <= 0.15f) continue;

            int sprScreenX = (int)((RW/2f) * (1 + tX/tY));
            int sprH = Math.abs((int)(RH / tY));
            int sprW = sprH * SW / SH;

            int startY = Math.max(0, RH/2 - sprH/2);
            int endY   = Math.min(RH-1, RH/2 + sprH/2);
            int startX = Math.max(0, sprScreenX - sprW/2);
            int endX   = Math.min(RW-1, sprScreenX + sprW/2);

            float fog = Math.max(0f, 1f - tY / MAX_DIST);
            fog = fog * fog * fog;

            for (int stripe = startX; stripe <= endX; stripe++) {
                if (stripe < 0 || stripe >= zBuf.length) continue;
                if (tY >= zBuf[stripe]) continue;

                int texX = (stripe - (sprScreenX - sprW/2)) * SW / sprW;
                texX = Math.max(0, Math.min(SW-1, texX));

                for (int y = startY; y <= endY; y++) {
                    int texY = (y - (RH/2 - sprH/2)) * SH / sprH;
                    texY = Math.max(0, Math.min(SH-1, texY));

                    int col = SpriteSheet.pixels[texY * SW + texX];
                    if (android.graphics.Color.alpha(col) < 128) continue;

                    int r2 = Math.min(255,
                        (int)(android.graphics.Color.red(col)   * fog));
                    int g2 = Math.min(255,
                        (int)(android.graphics.Color.green(col) * fog));
                    int b2 = Math.min(255,
                        (int)(android.graphics.Color.blue(col)  * fog));

                    pixelBuf[y * RW + stripe] =
                        0xFF000000|(r2<<16)|(g2<<8)|b2;
                }
            }
        }
    }

    public void buildMinimap() {
        int CELL = 4;
        int rows = Map.getRows(), cols = Map.getCols();
        minimapBmp = Bitmap.createBitmap(
            cols*CELL, rows*CELL, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(minimapBmp);
        Paint p = new Paint();
        for (int r = 0; r < rows; r++)
            for (int col = 0; col < cols; col++) {
                p.setColor(Map.isWall(col,r)
                    ? Color.rgb(120,40,40)
                    : Color.rgb(25,25,25));
                c.drawRect(col*CELL,r*CELL,
                    col*CELL+CELL-1,r*CELL+CELL-1,p);
            }
    }

    public void drawMinimap(Canvas canvas, Player player) {
        if (minimapBmp == null) buildMinimap();
        int offX = W - minimapBmp.getWidth()  - 8;
        int offY = 8;
        canvas.drawBitmap(minimapBmp, offX, offY, null);

        float px = offX + (player.x/Map.TILE) * 4;
        float py = offY + (player.y/Map.TILE) * 4;
        mmPaint.setColor(Color.rgb(80,180,255));
        canvas.drawCircle(px, py, 3, mmPaint);
        mmPaint.setStrokeWidth(2);
        canvas.drawLine(px, py,
            px+(float)Math.cos(player.angle)*7,
            py+(float)Math.sin(player.angle)*7, mmPaint);
    }
}
