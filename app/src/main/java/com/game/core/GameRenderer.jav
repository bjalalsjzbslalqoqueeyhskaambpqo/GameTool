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
import java.util.concurrent.ConcurrentHashMap;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class GameRenderer implements GLSurfaceView.Renderer {

    private static final int RW = 320;
    private static final int RH = 200;

    public static final int MODE_KILLER    = 0;
    public static final int MODE_INFECTION = 1;
    public static final int MODE_FFA       = 2;
    public static final int MODE_DETECTIVE = 3;
    public static final int MODE_BLACKOUT  = 4;
    public static final int MODE_SHRINK    = 5;

    public enum GameState {
        NAME_INPUT, MENU, CREATING, JOINING,
        WAITING, PLAYING, DEAD, END, SPECTATING, CONNECTING
    }

    public volatile GameState state = GameState.NAME_INPUT;
    public volatile int   gameMode     = MODE_KILLER;
    public volatile boolean amKiller   = false;
    public volatile boolean amInfected = false;
    public volatile boolean amDetective= false;
    public volatile boolean attackPressed = false;
    public volatile String  playerName  = "";
    public volatile String  roomCodeInput = "";
    public volatile boolean isCreating  = false;
    public volatile int     selectedMode = 0;

    public volatile int   leftId     = -1;
    public volatile int   rightId    = -1;
    public volatile float leftStartX, leftStartY;
    public volatile float leftDX, leftDY;
    public volatile float rightLastX, rightLastY;
    public volatile float rightDX, rightDY;

    private final Context ctx;
    private Player    player;
    private Raycaster raycaster;
    private NetClient netClient;

    private int   texId=-1, program, posHandle, uvHandle, texHandle;
    private FloatBuffer quadVerts;
    private final int[] pixelBuf = new int[RW*RH];
    private Bitmap frameBmp;

    private long  prevTime;
    private float delta = 1f;
    private long  frameCount = 0;
    private long  lastReconnect = 0;

    private volatile int     myHp         = 100;
    private volatile int     gameTimer    = 0;
    private volatile int     playerCount  = 0;
    private volatile int     minPlayers   = 2;
    private volatile String  statusMsg    = "";
    private volatile boolean endKillerWon = false;
    private volatile boolean showAttackFX = false;
    private volatile boolean blackoutActive = false;
    private volatile float   detectorDist  = -1f;
    private volatile long    detectorTime  = 0;
    private volatile String  currentRoomId = "";
    private volatile boolean allReady      = false;
    private volatile int     readyCount    = 0;

    private final ConcurrentHashMap<Integer, RemoteState>
        remoteStates = new ConcurrentHashMap<>();

    private static class RemoteState {
        float x,y,angle,rx,ry,ra;
        boolean init=false;
        void update(float nx,float ny,float na){
            x=nx;y=ny;angle=na;
            if(!init){rx=nx;ry=ny;ra=na;init=true;}
        }
        void lerp(float f){
            rx+=(x-rx)*f; ry+=(y-ry)*f; ra+=(angle-ra)*f;
        }
    }

    private static final String VERT =
        "attribute vec2 aPos;attribute vec2 aUV;varying vec2 vUV;" +
        "void main(){gl_Position=vec4(aPos,0,1);vUV=aUV;}";
    private static final String FRAG =
        "precision mediump float;varying vec2 vUV;uniform sampler2D uTex;" +
        "void main(){gl_FragColor=texture2D(uTex,vUV);}";
    private static final float[] QUAD = {
        -1f,1f,0f,0f,-1f,-1f,0f,1f,1f,1f,1f,0f,1f,-1f,1f,1f};

    public GameRenderer(Context ctx){ this.ctx=ctx; }

    public void connectToRoom(String roomId){
        if(netClient!=null){ netClient.disconnect(); netClient=null; }
        remoteStates.clear();
        currentRoomId=roomId;
        state=GameState.CONNECTING;
        statusMsg="Conectando...";

        netClient=new NetClient(playerName.isEmpty()
            ?"Player"+(int)(Math.random()*900+100):playerName,
            new NetClient.Listener(){
            public void onConnected(){ statusMsg="Conectado"; }
            public void onJoined(int id,long seed,boolean spec,int mode){
                Map.generate(seed);
                player=new Player(Map.getSpawnX(),Map.getSpawnY());
                gameMode=mode;
                state=spec?GameState.SPECTATING:GameState.WAITING;
                statusMsg=spec?"Espectador":"Sala: "+currentRoomId;
            }
            public void onRoomInfo(int cnt,int min,boolean started){
                playerCount=cnt; minPlayers=min;
                if(!started&&state==GameState.END){
                    resetToWaiting();
                } else if(!started&&state!=GameState.PLAYING){
                    state=GameState.WAITING;
                    statusMsg=cnt+"/"+min+" jugadores";
                }
            }
            public void onGameStart(boolean killer,boolean inf,
                    boolean det,int mode,int dur){
                gameMode=mode; amKiller=killer;
                amInfected=inf; amDetective=det;
                myHp=100; state=GameState.PLAYING; statusMsg="";
            }
            public void onHit(int hp){ myHp=hp; }
            public void onPlayerDied(int id){
                if(netClient!=null&&id==netClient.myId){
                    myHp=0; state=GameState.DEAD;
                }
            }
            public void onGameEnd(boolean kw){
                endKillerWon=kw; state=GameState.END;
            }
            public void onBlackout(boolean on){ blackoutActive=on; }
            public void onDetectorPing(float dist){
                detectorDist=dist;
                detectorTime=System.currentTimeMillis();
            }
            public void onDisconnected(){
                if(state==GameState.PLAYING||state==GameState.DEAD){
                    state=GameState.CONNECTING;
                    statusMsg="Reconectando...";
                    long t=System.currentTimeMillis();
                    if(t-lastReconnect>3000){
                        lastReconnect=t;
                        netClient.connect(currentRoomId);
                    }
                }
            }
        });
        netClient.connect(roomId);
    }

    private void resetToWaiting(){
        state=GameState.WAITING;
        amKiller=false; amInfected=false; amDetective=false;
        myHp=100; blackoutActive=false; detectorDist=-1;
        statusMsg=playerCount+"/"+minPlayers+" jugadores";
        remoteStates.clear();
        Map.generate(System.currentTimeMillis());
        player=new Player(Map.getSpawnX(),Map.getSpawnY());
    }

    @Override
    public void onSurfaceCreated(GL10 gl,EGLConfig cfg){
        Map.generate(System.currentTimeMillis());
        player=new Player(Map.getSpawnX(),Map.getSpawnY());
        raycaster=new Raycaster(RW,RH);
        frameBmp=Bitmap.createBitmap(RW,RH,Bitmap.Config.ARGB_8888);
        program=buildProgram(VERT,FRAG);
        posHandle=GLES20.glGetAttribLocation(program,"aPos");
        uvHandle =GLES20.glGetAttribLocation(program,"aUV");
        texHandle=GLES20.glGetUniformLocation(program,"uTex");
        ByteBuffer bb=ByteBuffer.allocateDirect(QUAD.length*4);
        bb.order(ByteOrder.nativeOrder());
        quadVerts=bb.asFloatBuffer();
        quadVerts.put(QUAD).position(0);
        int[] ids=new int[1];
        GLES20.glGenTextures(1,ids,0);
        texId=ids[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_NEAREST);
        GLES20.glClearColor(0,0,0,1);
        prevTime=System.nanoTime();
    }

    @Override public void onSurfaceChanged(GL10 gl,int w,int h){
        GLES20.glViewport(0,0,w,h);
    }

    @Override
    public void onDrawFrame(GL10 gl){
        long now=System.nanoTime();
        delta=Math.min((now-prevTime)/16_666_667f,3f);
        prevTime=now; frameCount++;

        switch(state){
            case NAME_INPUT: drawNameScreen(); return;
            case MENU:       drawMenuScreen(); return;
            case CREATING:   drawCreateScreen(); return;
            case JOINING:    drawJoinScreen(); return;
            case CONNECTING:
            case WAITING:
            case SPECTATING: drawWaitingScreen(); return;
            case END:        drawEndScreen(); return;
            case DEAD:
                raycaster.gameMode=gameMode;
                raycaster.blackout=false;
                raycaster.render(pixelBuf,player,frameCount);
                frameBmp.setPixels(pixelBuf,0,RW,0,0,RW,RH);
                Canvas dc=new Canvas(frameBmp);
                Paint dp=new Paint(Paint.ANTI_ALIAS_FLAG);
                dp.setColor(Color.argb(120,180,0,0));
                dc.drawRect(0,0,RW,RH,dp);
                dp.setColor(Color.WHITE);
                dp.setTextSize(RH*0.10f);
                dp.setFakeBoldText(true);
                dp.setTextAlign(Paint.Align.CENTER);
                dc.drawText("HAS MUERTO",RW/2f,RH*0.45f,dp);
                dp.setFakeBoldText(false);
                dp.setTextSize(RH*0.042f);
                dp.setColor(Color.argb(160,200,200,200));
                dc.drawText("Esperando fin...",RW/2f,RH*0.58f,dp);
                flushFrame(); return;
            case PLAYING: break;
            default: return;
        }

        update();
        if(attackPressed&&canAttack()){
            if(netClient!=null) netClient.sendAttack();
            attackPressed=false; showAttackFX=true;
        }
        if(netClient!=null)
            netClient.sendInput(player.x,player.y,player.angle);

        float interp=Math.min(1f,delta*0.25f);
        if(netClient!=null){
            for(NetClient.RemotePlayer rp:netClient.remotePlayers){
                RemoteState rs=remoteStates.computeIfAbsent(
                    rp.id,k->new RemoteState());
                rs.update(rp.x,rp.y,rp.angle);
            }
        }
        for(RemoteState rs:remoteStates.values()) rs.lerp(interp);

        gameTimer=netClient!=null?netClient.lastTimer:0;
        raycaster.gameMode=gameMode;
        raycaster.blackout=blackoutActive&&gameMode==MODE_BLACKOUT;
        raycaster.render(pixelBuf,player,frameCount);

        List<float[]> sprites=new ArrayList<>();
        if(netClient!=null){
            int myId=netClient.myId;
            for(NetClient.RemotePlayer rp:netClient.remotePlayers){
                if(rp.id==myId||rp.spectator||!rp.alive) continue;
                RemoteState rs=remoteStates.get(rp.id);
                if(rs!=null&&rs.init)
                    sprites.add(new float[]{rs.rx,rs.ry});
            }
        }
        if(!sprites.isEmpty())
            raycaster.renderSprites(pixelBuf,player,sprites,frameCount);

        frameBmp.setPixels(pixelBuf,0,RW,0,0,RW,RH);
        drawHUD();
        flushFrame();
    }

    public boolean canAttack(){
        return (gameMode==MODE_KILLER&&amKiller)
            ||(gameMode==MODE_INFECTION&&amInfected)
            ||(gameMode==MODE_FFA)
            ||(gameMode==MODE_BLACKOUT&&amKiller)
            ||(gameMode==MODE_DETECTIVE&&amKiller)
            ||(gameMode==MODE_SHRINK);
    }

    private void update(){
        float spd;
        if((gameMode==MODE_KILLER||gameMode==MODE_BLACKOUT)&&amKiller)
            spd=1.25f*delta;
        else if(gameMode==MODE_INFECTION&&amInfected) spd=1.15f*delta;
        else spd=0.9f*delta;

        float a=player.angle;
        float fx=(float)Math.cos(a),fy=(float)Math.sin(a);
        float rx=(float)Math.cos(a+Math.PI/2);
        float ry=(float)Math.sin(a+Math.PI/2);
        float jx=leftDX,jy=leftDY;
        if(Math.abs(jx)>0.05f||Math.abs(jy)>0.05f)
            player.move((fx*(-jy)+rx*jx)*spd,(fy*(-jy)+ry*jx)*spd);
        if(Math.abs(rightDX)>0.005f)
            player.angle+=rightDX*0.07f*delta;
        rightDX=0; rightDY=0;

        if(gameMode==MODE_FFA&&myHp>0&&myHp<100&&frameCount%300==0)
            myHp=Math.min(100,myHp+1);

        Assets.updateSteps(Math.abs(jx)>0.05f||Math.abs(jy)>0.05f);
    }

    private void drawNameScreen(){
        Canvas c=new Canvas(frameBmp);
        c.drawColor(Color.BLACK);
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextAlign(Paint.Align.CENTER);

        p.setColor(Color.rgb(180,30,30));
        p.setTextSize(RH*0.13f);
        p.setFakeBoldText(true);
        c.drawText("DUNGEON",RW/2f,RH*0.20f,p);
        p.setFakeBoldText(false);

        p.setColor(Color.rgb(80,20,20));
        p.setStrokeWidth(1.5f);
        c.drawLine(RW*0.1f,RH*0.25f,RW*0.9f,RH*0.25f,p);

        p.setColor(Color.rgb(140,140,140));
        p.setTextSize(RH*0.050f);
        c.drawText("Ingresá tu nombre",RW/2f,RH*0.38f,p);

        // Campo de nombre
        p.setColor(Color.argb(180,40,40,40));
        p.setStyle(Paint.Style.FILL);
        c.drawRect(RW*0.1f,RH*0.44f,RW*0.9f,RH*0.58f,p);
        p.setColor(Color.rgb(100,80,80));
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.5f);
        c.drawRect(RW*0.1f,RH*0.44f,RW*0.9f,RH*0.58f,p);
        p.setStyle(Paint.Style.FILL);

        String display=playerName.isEmpty()?"Toca para escribir...":
            playerName+"_";
        p.setColor(playerName.isEmpty()
            ?Color.rgb(80,80,80):Color.WHITE);
        p.setTextSize(RH*0.055f);
        c.drawText(display,RW/2f,RH*0.53f,p);

        // Botón continuar
        if(!playerName.isEmpty()){
            p.setColor(Color.rgb(120,30,30));
            p.setStyle(Paint.Style.FILL);
            c.drawRect(RW*0.25f,RH*0.68f,RW*0.75f,RH*0.82f,p);
            p.setColor(Color.WHITE);
            p.setTextSize(RH*0.058f);
            p.setFakeBoldText(true);
            c.drawText("CONTINUAR",RW/2f,RH*0.77f,p);
            p.setFakeBoldText(false);
        }
        flushFrame();
    }

    private static final String[] MODE_NAMES={
        "Asesino","Infección","Free for All",
        "Detective","Apagón","Zona Cierre"};
    private static final String[] MODE_DESC={
        "1 asesino — todos escapan",
        "El infectado contagia a todos",
        "Todos armados — último gana",
        "Detective rastrea al asesino",
        "Luces que se apagan...",
        "La zona se cierra — movete"};
    private static final int[] MODE_COLORS={
        0xFFCC2222,0xFF44BB22,0xFFCCAA11,
        0xFF2266CC,0xFF441188,0xFFCC6611};

    private void drawMenuScreen(){
        Canvas c=new Canvas(frameBmp);
        c.drawColor(Color.BLACK);
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextAlign(Paint.Align.CENTER);

        p.setColor(Color.rgb(180,30,30));
        p.setTextSize(RH*0.10f);
        p.setFakeBoldText(true);
        c.drawText("DUNGEON",RW/2f,RH*0.11f,p);
        p.setFakeBoldText(false);

        p.setColor(Color.argb(120,100,100,100));
        p.setTextSize(RH*0.035f);
        c.drawText("Hola, "+playerName,RW/2f,RH*0.17f,p);

        // Botón CREAR SALA
        p.setColor(Color.rgb(100,30,30));
        p.setStyle(Paint.Style.FILL);
        c.drawRect(RW*0.05f,RH*0.21f,RW*0.95f,RH*0.33f,p);
        p.setColor(Color.rgb(220,80,80));
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1f);
        c.drawRect(RW*0.05f,RH*0.21f,RW*0.95f,RH*0.33f,p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.WHITE);
        p.setTextSize(RH*0.055f);
        p.setFakeBoldText(true);
        c.drawText("CREAR SALA",RW/2f,RH*0.29f,p);
        p.setFakeBoldText(false);

        // Botón UNIRSE
        p.setColor(Color.rgb(30,30,100));
        p.setStyle(Paint.Style.FILL);
        c.drawRect(RW*0.05f,RH*0.36f,RW*0.95f,RH*0.48f,p);
        p.setColor(Color.rgb(80,80,220));
        p.setStyle(Paint.Style.STROKE);
        c.drawRect(RW*0.05f,RH*0.36f,RW*0.95f,RH*0.48f,p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.WHITE);
        p.setTextSize(RH*0.055f);
        p.setFakeBoldText(true);
        c.drawText("UNIRSE CON CÓDIGO",RW/2f,RH*0.44f,p);
        p.setFakeBoldText(false);

        // Separador
        p.setColor(Color.rgb(50,50,50));
        p.setStrokeWidth(1f);
        c.drawLine(RW*0.1f,RH*0.52f,RW*0.9f,RH*0.52f,p);

        p.setColor(Color.rgb(80,80,80));
        p.setTextSize(RH*0.038f);
        c.drawText("Salas rápidas",RW/2f,RH*0.57f,p);

        // 6 botones de modo rápido — 2 columnas
        for(int i=0;i<6;i++){
            int col=i%2, row=i/2;
            float bx=RW*(col==0?0.05f:0.52f);
            float by=RH*(0.60f+row*0.13f);
            float bw=RW*0.43f, bh=RH*0.11f;
            int mc=MODE_COLORS[i];
            p.setColor(Color.argb(120,
                Color.red(mc),Color.green(mc),Color.blue(mc)));
            p.setStyle(Paint.Style.FILL);
            c.drawRect(bx,by,bx+bw,by+bh,p);
            p.setColor(Color.argb(180,
                Color.red(mc),Color.green(mc),Color.blue(mc)));
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1f);
            c.drawRect(bx,by,bx+bw,by+bh,p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.WHITE);
            p.setTextSize(RH*0.040f);
            p.setTextAlign(Paint.Align.CENTER);
            c.drawText(MODE_NAMES[i],bx+bw/2,by+bh*0.45f,p);
            p.setColor(Color.argb(150,180,180,180));
            p.setTextSize(RH*0.030f);
            c.drawText(MODE_DESC[i],bx+bw/2,by+bh*0.78f,p);
        }
        flushFrame();
    }

    private void drawCreateScreen(){
        Canvas c=new Canvas(frameBmp);
        c.drawColor(Color.BLACK);
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextAlign(Paint.Align.CENTER);

        p.setColor(Color.rgb(180,30,30));
        p.setTextSize(RH*0.10f);
        p.setFakeBoldText(true);
        c.drawText("CREAR SALA",RW/2f,RH*0.12f,p);
        p.setFakeBoldText(false);

        p.setColor(Color.rgb(80,80,80));
        p.setTextSize(RH*0.042f);
        c.drawText("Elige el modo de juego:",RW/2f,RH*0.20f,p);

        for(int i=0;i<6;i++){
            float by=RH*(0.25f+i*0.11f);
            boolean sel=selectedMode==i;
            int mc=MODE_COLORS[i];
            p.setColor(sel?Color.argb(200,Color.red(mc),
                Color.green(mc),Color.blue(mc)):
                Color.argb(80,Color.red(mc),
                Color.green(mc),Color.blue(mc)));
            p.setStyle(Paint.Style.FILL);
            c.drawRect(RW*0.05f,by,RW*0.95f,by+RH*0.09f,p);
            if(sel){
                p.setColor(Color.argb(255,Color.red(mc),
                    Color.green(mc),Color.blue(mc)));
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2f);
                c.drawRect(RW*0.05f,by,RW*0.95f,by+RH*0.09f,p);
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.WHITE);
            p.setTextSize(RH*0.045f);
            p.setFakeBoldText(sel);
            c.drawText(MODE_NAMES[i]+" — "+MODE_DESC[i],
                RW/2f,by+RH*0.062f,p);
            p.setFakeBoldText(false);
        }

        // Botón crear
        p.setColor(Color.rgb(120,30,30));
        p.setStyle(Paint.Style.FILL);
        c.drawRect(RW*0.15f,RH*0.92f,RW*0.85f,RH*0.99f,p);
        p.setColor(Color.WHITE);
        p.setTextSize(RH*0.050f);
        p.setFakeBoldText(true);
        c.drawText("CREAR",RW/2f,RH*0.97f,p);
        p.setFakeBoldText(false);

        flushFrame();
    }

    private void drawJoinScreen(){
        Canvas c=new Canvas(frameBmp);
        c.drawColor(Color.BLACK);
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextAlign(Paint.Align.CENTER);

        p.setColor(Color.rgb(80,80,220));
        p.setTextSize(RH*0.10f);
        p.setFakeBoldText(true);
        c.drawText("UNIRSE",RW/2f,RH*0.15f,p);
        p.setFakeBoldText(false);

        p.setColor(Color.rgb(140,140,140));
        p.setTextSize(RH*0.048f);
        c.drawText("Código de sala:",RW/2f,RH*0.30f,p);

        // Campo código
        p.setColor(Color.argb(180,30,30,80));
        p.setStyle(Paint.Style.FILL);
        c.drawRect(RW*0.1f,RH*0.37f,RW*0.9f,RH*0.52f,p);
        p.setColor(Color.rgb(80,80,200));
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2f);
        c.drawRect(RW*0.1f,RH*0.37f,RW*0.9f,RH*0.52f,p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.WHITE);
        p.setTextSize(RH*0.085f);
        p.setFakeBoldText(true);
        c.drawText(roomCodeInput.isEmpty()?"----":roomCodeInput,
            RW/2f,RH*0.48f,p);
        p.setFakeBoldText(false);

        // Teclado numérico 3x4
        String[] keys={"1","2","3","4","5","6","7","8","9","⌫","0","✓"};
        for(int i=0;i<12;i++){
            int col=i%3, row=i/3;
            float kx=RW*(0.05f+col*0.32f);
            float ky=RH*(0.56f+row*0.11f);
            float kw=RW*0.28f, kh=RH*0.09f;
            boolean isConfirm=i==11;
            boolean isDelete=i==9;
            p.setColor(isConfirm?Color.rgb(30,100,30):
                isDelete?Color.rgb(100,30,30):
                Color.rgb(40,40,60));
            p.setStyle(Paint.Style.FILL);
            c.drawRect(kx,ky,kx+kw,ky+kh,p);
            p.setColor(Color.argb(100,100,100,150));
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1f);
            c.drawRect(kx,ky,kx+kw,ky+kh,p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.WHITE);
            p.setTextSize(RH*0.055f);
            p.setFakeBoldText(true);
            c.drawText(keys[i],kx+kw/2,ky+kh*0.70f,p);
            p.setFakeBoldText(false);
        }
        flushFrame();
    }

    private void drawWaitingScreen(){
        Canvas c=new Canvas(frameBmp);
        c.drawColor(Color.BLACK);
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextAlign(Paint.Align.CENTER);

        p.setColor(Color.rgb(180,30,30));
        p.setTextSize(RH*0.11f);
        p.setFakeBoldText(true);
        c.drawText("DUNGEON",RW/2f,RH*0.13f,p);
        p.setFakeBoldText(false);

        // Código de sala
        if(!currentRoomId.isEmpty()){
            p.setColor(Color.rgb(80,80,80));
            p.setTextSize(RH*0.038f);
            c.drawText("SALA: "+currentRoomId,RW/2f,RH*0.20f,p);
        }

        if(state==GameState.CONNECTING){
            p.setColor(Color.rgb(120,120,120));
            p.setTextSize(RH*0.052f);
            c.drawText(statusMsg,RW/2f,RH*0.40f,p);
            int dots=(int)(frameCount/15)%4;
            c.drawText(".".repeat(dots),RW/2f,RH*0.50f,p);
        } else if(state==GameState.SPECTATING){
            p.setColor(Color.rgb(200,150,50));
            p.setTextSize(RH*0.062f);
            c.drawText("ESPECTADOR",RW/2f,RH*0.40f,p);
            p.setColor(Color.rgb(70,70,70));
            p.setTextSize(RH*0.040f);
            c.drawText("Esperando próxima partida...",RW/2f,RH*0.52f,p);
        } else {
            int m=Math.min(gameMode,MODE_NAMES.length-1);
            int mc=MODE_COLORS[m];
            p.setColor(Color.argb(200,
                Color.red(mc),Color.green(mc),Color.blue(mc)));
            p.setTextSize(RH*0.050f);
            p.setFakeBoldText(true);
            c.drawText(MODE_NAMES[m],RW/2f,RH*0.28f,p);
            p.setFakeBoldText(false);

            float cy=RH*0.44f,cr=RH*0.038f;
            float sp=RW/(minPlayers+1f);
            for(int i=0;i<minPlayers;i++){
                float cx2=sp*(i+1);
                p.setColor(i<playerCount
                    ?Color.rgb(50,180,80):Color.rgb(40,40,40));
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx2,cy,cr,p);
                if(i>=playerCount){
                    p.setColor(Color.rgb(70,70,70));
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(1.5f);
                    c.drawCircle(cx2,cy,cr,p);
                }
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.rgb(80,180,80));
            p.setTextSize(RH*0.065f);
            p.setFakeBoldText(true);
            c.drawText(playerCount+"/"+minPlayers,RW/2f,RH*0.57f,p);
            p.setFakeBoldText(false);
            p.setColor(Color.rgb(60,60,60));
            p.setTextSize(RH*0.036f);
            c.drawText("jugadores conectados",RW/2f,RH*0.64f,p);
            c.drawText("La partida inicia automáticamente",
                RW/2f,RH*0.71f,p);

            // Botón volver al menú
            p.setColor(Color.argb(100,80,80,80));
            p.setStyle(Paint.Style.FILL);
            c.drawRect(RW*0.3f,RH*0.82f,RW*0.7f,RH*0.92f,p);
            p.setColor(Color.argb(150,150,150,150));
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1f);
            c.drawRect(RW*0.3f,RH*0.82f,RW*0.7f,RH*0.92f,p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(180,180,180,180));
            p.setTextSize(RH*0.040f);
            c.drawText("← Volver",RW/2f,RH*0.89f,p);
        }
        flushFrame();
    }

    private void drawEndScreen(){
        Canvas c=new Canvas(frameBmp);
        c.drawColor(Color.BLACK);
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextAlign(Paint.Align.CENTER);

        String titulo,sub;
        int color;
        boolean iWon;
        switch(gameMode){
            case MODE_KILLER: case MODE_BLACKOUT:
                titulo=endKillerWon?"EL ASESINO GANÓ":"SOBREVIVIENTES GANAN";
                iWon=endKillerWon==amKiller;
                sub=endKillerWon?(amKiller?"¡Eliminaste a todos!":
                    "No lograste escapar..."):(amKiller?
                    "No pudiste con todos...":"¡Sobreviviste!");
                color=endKillerWon?Color.rgb(220,40,40):Color.rgb(50,220,80);
                break;
            case MODE_INFECTION:
                titulo=endKillerWon?"INFECCIÓN TOTAL":"LOS SANOS GANAN";
                iWon=endKillerWon==amInfected;
                sub=amInfected?"Infectaste a todos":"Resististe";
                color=endKillerWon?Color.rgb(120,200,50):Color.rgb(80,180,180);
                break;
            case MODE_DETECTIVE:
                titulo=endKillerWon?"EL ASESINO ESCAPÓ":"DETECTIVE GANA";
                iWon=endKillerWon==amKiller||(!endKillerWon&&amDetective);
                sub=amDetective?(endKillerWon?"No lo atrapaste":
                    "¡Atrapaste al asesino!"):
                    amKiller?(endKillerWon?"Escapaste":"Te atraparon"):
                    "Sobreviviste";
                color=endKillerWon?Color.rgb(220,40,40):Color.rgb(80,150,220);
                break;
            default:
                titulo="PARTIDA TERMINADA";
                iWon=myHp>0;
                sub=iWon?"¡Sobreviviste!":"Fuiste eliminado";
                color=iWon?Color.rgb(220,180,50):Color.rgb(150,150,150);
        }

        p.setColor(Color.argb(iWon?50:40,
            iWon?0:150,iWon?80:0,0));
        c.drawRect(0,0,RW,RH,p);
        p.setColor(color);
        p.setTextSize(RH*0.088f);
        p.setFakeBoldText(true);
        c.drawText(titulo,RW/2f,RH*0.28f,p);
        p.setFakeBoldText(false);
        p.setTextSize(RH*0.048f);
        p.setColor(Color.argb(200,200,200,200));
        c.drawText(sub,RW/2f,RH*0.44f,p);
        p.setColor(Color.rgb(60,60,60));
        p.setTextSize(RH*0.036f);
        c.drawText("Volviendo a la sala...",RW/2f,RH*0.75f,p);
        flushFrame();
    }

    private void drawHUD(){
        Canvas c=new Canvas(frameBmp);
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);

        if(gameTimer>0){
            int mins=gameTimer/60,secs=gameTimer%60;
            p.setTextAlign(Paint.Align.CENTER);
            p.setColor(gameTimer<30?Color.rgb(220,60,60):
                Color.argb(180,220,220,220));
            p.setTextSize(RH*0.072f);
            p.setFakeBoldText(true);
            c.drawText(String.format("%d:%02d",mins,secs),
                RW/2f,RH*0.09f,p);
            p.setFakeBoldText(false);
        }

        p.setColor(Color.argb(160,0,0,0));
        c.drawRect(4,RH-16,90,RH-4,p);
        float hpPct=myHp/100f;
        p.setColor(hpPct>0.5f?Color.rgb(40,200,60):
            hpPct>0.25f?Color.rgb(220,160,0):Color.rgb(200,40,40));
        c.drawRect(5,RH-15,5+hpPct*84,RH-5,p);
        p.setColor(Color.argb(120,255,255,255));
        p.setTextSize(RH*0.042f);
        p.setTextAlign(Paint.Align.LEFT);
        c.drawText(myHp+"hp",7,RH-6,p);

        String roleText="",roleSub="";
        int roleColor=Color.WHITE;
        switch(gameMode){
            case MODE_KILLER: case MODE_BLACKOUT:
                if(amKiller){roleText="⚔ ASESINO";roleColor=Color.rgb(220,40,40);}
                else{roleText="SUPERVIVIENTE";roleColor=Color.rgb(80,180,80);}
                break;
            case MODE_INFECTION:
                if(amInfected){roleText="INFECTADO";roleColor=Color.rgb(120,200,50);}
                else{roleText="SANO";roleColor=Color.rgb(80,200,80);}
                break;
            case MODE_FFA:case MODE_SHRINK:
                roleText="FREE FOR ALL";roleColor=Color.rgb(200,150,50);break;
            case MODE_DETECTIVE:
                if(amDetective){roleText="🔍 DETECTIVE";roleColor=Color.rgb(80,150,220);}
                else if(amKiller){roleText="⚔ ASESINO";roleColor=Color.rgb(220,40,40);}
                else{roleText="INOCENTE";roleColor=Color.rgb(80,180,80);}
                break;
        }
        if(!roleText.isEmpty()){
            p.setColor(roleColor);
            p.setTextSize(RH*0.052f);
            p.setFakeBoldText(true);
            p.setTextAlign(Paint.Align.LEFT);
            c.drawText(roleText,RW*0.02f,RH*0.17f,p);
            p.setFakeBoldText(false);
        }

        if(gameMode==MODE_DETECTIVE&&amDetective&&detectorDist>=0){
            long el=System.currentTimeMillis()-detectorTime;
            if(el<3000){
                float al=1f-(float)el/3000f;
                p.setColor(Color.argb((int)(180*al),80,150,255));
                p.setTextSize(RH*0.048f);
                p.setTextAlign(Paint.Align.CENTER);
                String dist=detectorDist<96?"¡MUY CERCA!":
                    detectorDist<200?"CERCA":"LEJOS";
                c.drawText("📡 "+dist,RW/2f,RH*0.23f,p);
            }
        }

        if(amInfected&&gameMode==MODE_INFECTION){
            float pulse=(float)Math.sin(frameCount*0.08f)*0.5f+0.5f;
            int a2=(int)(40+pulse*40);
            p.setColor(Color.argb(a2,50,200,50));
            c.drawRect(0,0,RW,5,p);c.drawRect(0,RH-5,RW,RH,p);
            c.drawRect(0,0,5,RH,p);c.drawRect(RW-5,0,RW,RH,p);
        }

        if(canAttack()){
            p.setColor(Color.argb(70,200,40,40));
            c.drawRect(RW*0.72f,RH*0.62f,RW*0.98f,RH*0.93f,p);
            p.setColor(Color.argb(140,255,80,80));
            p.setStrokeWidth(1.5f);
            p.setStyle(Paint.Style.STROKE);
            c.drawRect(RW*0.72f,RH*0.62f,RW*0.98f,RH*0.93f,p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(200,255,255,255));
            p.setTextSize(RH*0.072f);
            p.setFakeBoldText(true);
            p.setTextAlign(Paint.Align.CENTER);
            c.drawText("⚔",RW*0.85f,RH*0.81f,p);
            p.setFakeBoldText(false);
            p.setTextSize(RH*0.036f);
            c.drawText("ATACAR",RW*0.85f,RH*0.90f,p);
        }

        p.setColor(Color.argb(100,255,255,255));
        p.setStrokeWidth(1f);
        c.drawLine(RW/2f-5,RH/2f,RW/2f+5,RH/2f,p);
        c.drawLine(RW/2f,RH/2f-5,RW/2f,RH/2f+5,p);

        if(showAttackFX){
            p.setColor(Color.argb(70,255,40,40));
            p.setStyle(Paint.Style.FILL);
            c.drawRect(0,0,RW,RH,p);
            showAttackFX=false;
        }

        if(netClient!=null){
            int alive=0;
            for(NetClient.RemotePlayer rp:netClient.remotePlayers)
                if(!rp.spectator&&rp.alive) alive++;
            p.setColor(Color.argb(150,200,80,80));
            p.setTextSize(RH*0.048f);
            p.setTextAlign(Paint.Align.LEFT);
            c.drawText(alive+" vivos",RW*0.02f,RH*0.09f,p);
        }
    }

    public void handleTouch(float ex,float ey,int sw,int sh){
        float rx2=ex*(RW/(float)sw);
        float ry2=ey*(RH/(float)sh);

        switch(state){
            case NAME_INPUT:
                if(!playerName.isEmpty()&&
                        ry2>RH*0.68f&&ry2<RH*0.82f){
                    state=GameState.MENU;
                }
                break;
            case MENU:
                if(ry2>RH*0.21f&&ry2<RH*0.33f){
                    state=GameState.CREATING; selectedMode=0;
                } else if(ry2>RH*0.36f&&ry2<RH*0.48f){
                    state=GameState.JOINING; roomCodeInput="";
                } else {
                    for(int i=0;i<6;i++){
                        int col=i%2,row=i/2;
                        float bx=RW*(col==0?0.05f:0.52f);
                        float by=RH*(0.60f+row*0.13f);
                        float bw=RW*0.43f,bh=RH*0.11f;
                        if(rx2>=bx&&rx2<=bx+bw&&ry2>=by&&ry2<=by+bh){
                            String[] quickRooms={"0000","0001","0002",
                                "0003","0004","0005"};
                            connectToRoom(quickRooms[i]);
                            return;
                        }
                    }
                }
                break;
            case CREATING:
                for(int i=0;i<6;i++){
                    float by=RH*(0.25f+i*0.11f);
                    if(ry2>by&&ry2<by+RH*0.09f){
                        selectedMode=i; return;
                    }
                }
                if(ry2>RH*0.92f){
                    String[] rooms={"0000","0001","0002",
                        "0003","0004","0005"};
                    connectToRoom(rooms[selectedMode]);
                }
                break;
            case JOINING:
                String[] keys2={"1","2","3","4","5","6","7","8","9","⌫","0","✓"};
                for(int i=0;i<12;i++){
                    int col=i%3,row=i/3;
                    float kx=RW*(0.05f+col*0.32f);
                    float ky=RH*(0.56f+row*0.11f);
                    float kw=RW*0.28f,kh=RH*0.09f;
                    if(rx2>=kx&&rx2<=kx+kw&&ry2>=ky&&ry2<=ky+kh){
                        if(i==9){
                            if(!roomCodeInput.isEmpty())
                                roomCodeInput=roomCodeInput
                                    .substring(0,roomCodeInput.length()-1);
                        } else if(i==11){
                            if(roomCodeInput.length()==4)
                                connectToRoom(roomCodeInput);
                        } else {
                            if(roomCodeInput.length()<4)
                                roomCodeInput+=keys2[i];
                        }
                        return;
                    }
                }
                break;
            case WAITING:
                if(ry2>RH*0.82f&&ry2<RH*0.92f){
                    if(netClient!=null){
                        netClient.disconnect(); netClient=null;}
                    remoteStates.clear();
                    state=GameState.MENU;
                }
                break;
            default: break;
        }
    }

    private void flushFrame(){
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texId);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,frameBmp,0);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(program);
        quadVerts.position(0);
        GLES20.glVertexAttribPointer(posHandle,2,
            GLES20.GL_FLOAT,false,16,quadVerts);
        GLES20.glEnableVertexAttribArray(posHandle);
        quadVerts.position(2);
        GLES20.glVertexAttribPointer(uvHandle,2,
            GLES20.GL_FLOAT,false,16,quadVerts);
        GLES20.glEnableVertexAttribArray(uvHandle);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texId);
        GLES20.glUniform1i(texHandle,0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
    }

    private int buildProgram(String v,String f){
        int vs=GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER);
        GLES20.glShaderSource(vs,v);GLES20.glCompileShader(vs);
        int fs=GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER);
        GLES20.glShaderSource(fs,f);GLES20.glCompileShader(fs);
        int pg=GLES20.glCreateProgram();
        GLES20.glAttachShader(pg,vs);GLES20.glAttachShader(pg,fs);
        GLES20.glLinkProgram(pg); return pg;
    }
}
