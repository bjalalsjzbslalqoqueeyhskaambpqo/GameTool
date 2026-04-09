package com.game.core;

import android.content.Context;
import android.content.SharedPreferences;
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
    private Bitmap frameBmpHD;
    private int screenW = 1080;
    private int screenH = 1920;

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
    private android.content.SharedPreferences prefs;
    private volatile float camSensitivity = 0.07f;
    private volatile int   myVotedMode    = 0;
    private volatile int[] modeVotes      = new int[6];
    private volatile int     readyCount    = 0;
    private volatile boolean iAmReady      = false;
    private volatile int pingMs = 0;
    private volatile boolean showSettingsOverlay = false;
    private long lastPingTime = 0;
    private volatile boolean roomBusy = false;
    private volatile int roomBusyPlayers = 0;
    private volatile int roomBusyMode = 0;
    private volatile List<NetClient.EndResult>
        endResults = new ArrayList<>();
    private volatile String lastKillerName = "";

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
                roomBusy=false;
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
                roomBusy=false;
                myHp=100; state=GameState.PLAYING;
                if(netClient!=null){
                    player=new Player(
                        netClient.spawnX,
                        netClient.spawnY);
                }
                statusMsg="";
            }
            public void onHit(int hp){ myHp=hp; }
            public void onPlayerDied(int id, String killerName){
                if(netClient!=null&&id==netClient.myId){
                    myHp=0; state=GameState.DEAD;
                    lastKillerName=killerName;
                }
            }
            public void onGameEnd(boolean kw,
                    List<NetClient.EndResult> results){
                endKillerWon=kw;
                endResults=results;
                state=GameState.END;
            }
            public void onBlackout(boolean on){ blackoutActive=on; }
            public void onDetectorPing(float dist){
                detectorDist=dist;
                detectorTime=System.currentTimeMillis();
            }
            public void onPong(int ms){ pingMs = ms; }
            public void onRoomBusy(int pl, int mode){
                roomBusy = true;
                roomBusyPlayers = pl;
                roomBusyMode = mode;
                state = GameState.SPECTATING;
            }
            public void onReadyReset(){
                iAmReady = false;
                readyCount = 0;
                modeVotes = new int[6];
            }
            public void onReadyUpdate(int ready,
                    int total, int[] votes){
                readyCount = ready;
                minPlayers = total;
                modeVotes = votes;
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
        roomBusy=false;
        iAmReady = false;
        readyCount = 0;
        modeVotes=new int[6];
        myVotedMode=0;
        Map.generate(System.currentTimeMillis());
        player=new Player(Map.getSpawnX(),Map.getSpawnY());
    }

    @Override
    public void onSurfaceCreated(GL10 gl,EGLConfig cfg){
        Map.generate(System.currentTimeMillis());
        player=new Player(Map.getSpawnX(),Map.getSpawnY());
        raycaster=new Raycaster(RW,RH);
        frameBmp=Bitmap.createBitmap(RW,RH,Bitmap.Config.ARGB_8888);
        frameBmpHD=Bitmap.createBitmap(screenW,screenH,Bitmap.Config.ARGB_8888);
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
        prefs = ctx.getSharedPreferences(
            "dungeon_prefs",
            android.content.Context.MODE_PRIVATE);
        playerName = prefs.getString("player_name","");
        camSensitivity = prefs.getFloat(
            "sensitivity", 0.07f);
        if(!playerName.isEmpty()) state = GameState.MENU;
    }

    @Override public void onSurfaceChanged(GL10 gl,int w,int h){
        GLES20.glViewport(0,0,w,h);
        screenW = w;
        screenH = h;
        frameBmpHD = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
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
                if(!lastKillerName.isEmpty()){
                    dp.setColor(Color.argb(180,220,100,100));
                    dp.setTextSize(RH*0.048f);
                    dc.drawText("Eliminado por: "+lastKillerName,
                        RW/2f,RH*0.65f,dp);
                }
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
        if(System.currentTimeMillis()-lastPingTime>3000){
            lastPingTime=System.currentTimeMillis();
            if(netClient!=null) netClient.sendPing();
        }

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
        if(showSettingsOverlay) drawSettingsOverlay();
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
            player.angle += rightDX * camSensitivity * delta;
        rightDX=0; rightDY=0;

        if(gameMode==MODE_FFA&&myHp>0&&myHp<100&&frameCount%300==0)
            myHp=Math.min(100,myHp+1);

        Assets.updateSteps(Math.abs(jx)>0.05f||Math.abs(jy)>0.05f);
    }

    private void drawNameScreen(){
        int BG      = Color.rgb(8,  6,  10);
        int BG2     = Color.rgb(15, 12, 18);
        int RED     = Color.rgb(180,30, 30);
        int RED2    = Color.rgb(220,60, 60);
        int GOLD    = Color.rgb(200,160,60);
        int DIM     = Color.argb(180,80,80,90);
        int WHITE   = Color.rgb(230,225,220);
        int WHITE2  = Color.argb(120,180,175,170);
        float SW=screenW, SH=screenH;
        Canvas c = new Canvas(frameBmpHD);

        c.drawColor(BG);
        Paint grad = new Paint();
        android.graphics.LinearGradient lg =
            new android.graphics.LinearGradient(
            0,0,0,SH,
            Color.argb(80,180,30,30),
            Color.TRANSPARENT,
            android.graphics.Shader.TileMode.CLAMP);
        grad.setShader(lg);
        c.drawRect(0,0,SW,SH*0.4f,grad);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextAlign(Paint.Align.CENTER);

        p.setColor(Color.argb(60,180,20,20));
        p.setTextSize(SH*0.11f);
        p.setFakeBoldText(true);
        c.drawText("DUNGEON",SW/2+4,SH*0.16f+4,p);
        p.setColor(RED2);
        c.drawText("DUNGEON",SW/2,SH*0.16f,p);
        p.setFakeBoldText(false);

        p.setColor(Color.argb(80,200,50,50));
        p.setStrokeWidth(1f);
        c.drawLine(SW*0.3f,SH*0.20f,SW*0.7f,SH*0.20f,p);

        p.setColor(WHITE2);
        p.setTextSize(SH*0.022f);
        c.drawText("INGRESÁ TU NOMBRE",SW/2,SH*0.28f,p);

        p.setColor(BG2);
        p.setStyle(Paint.Style.FILL);
        android.graphics.RectF nameBox =
            new android.graphics.RectF(
            SW*0.08f,SH*0.31f,SW*0.92f,SH*0.41f);
        c.drawRoundRect(nameBox,16,16,p);
        p.setColor(playerName.isEmpty()?
            Color.argb(60,150,50,50):RED);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2f);
        c.drawRoundRect(nameBox,16,16,p);
        p.setStyle(Paint.Style.FILL);

        if(playerName.isEmpty()){
            p.setColor(Color.argb(80,150,140,130));
            p.setTextSize(SH*0.032f);
            c.drawText("tu nombre...",SW/2,SH*0.37f,p);
        } else {
            p.setColor(WHITE);
            p.setTextSize(SH*0.038f);
            p.setFakeBoldText(true);
            String cursor=(frameCount/20%2==0)?"_":"";
            c.drawText(playerName+cursor,SW/2,SH*0.37f,p);
            p.setFakeBoldText(false);
        }

        float kbTop=SH*0.44f;
        float kbH=SH*0.44f;
        float keyH=kbH/4.2f;
        float keyR=10f;

        p.setColor(Color.argb(200,12,10,15));
        p.setStyle(Paint.Style.FILL);
        c.drawRect(0,kbTop-SH*0.01f,SW,SH,p);

        float kw1=SW/10.5f;
        float off1=(SW-ROW1.length*kw1)/2f;
        for(int i=0;i<ROW1.length;i++){
            float kx=off1+i*kw1;
            float ky=kbTop+keyH*0.05f;
            p.setColor(Color.rgb(28,24,32));
            p.setStyle(Paint.Style.FILL);
            android.graphics.RectF kr=
                new android.graphics.RectF(
                kx+2,ky+2,kx+kw1-4,ky+keyH-4);
            c.drawRoundRect(kr,keyR,keyR,p);
            p.setColor(Color.argb(40,200,150,150));
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1f);
            c.drawRoundRect(kr,keyR,keyR,p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(WHITE);
            p.setTextSize(keyH*0.45f);
            p.setTextAlign(Paint.Align.CENTER);
            c.drawText(ROW1[i],kx+kw1/2,ky+keyH*0.70f,p);
        }

        float kw2=SW/10.2f;
        float off2=(SW-ROW2.length*kw2)/2f;
        for(int i=0;i<ROW2.length;i++){
            float kx=off2+i*kw2;
            float ky=kbTop+keyH*1.1f;
            p.setColor(Color.rgb(28,24,32));
            p.setStyle(Paint.Style.FILL);
            android.graphics.RectF kr=
                new android.graphics.RectF(
                kx+2,ky+2,kx+kw2-4,ky+keyH-4);
            c.drawRoundRect(kr,keyR,keyR,p);
            p.setColor(Color.argb(40,200,150,150));
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1f);
            c.drawRoundRect(kr,keyR,keyR,p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(WHITE);
            p.setTextSize(keyH*0.45f);
            p.setTextAlign(Paint.Align.CENTER);
            c.drawText(ROW2[i],kx+kw2/2,ky+keyH*0.70f,p);
        }

        float kw3=SW/9.2f;
        float off3=(SW-ROW3.length*kw3)/2f;
        for(int i=0;i<ROW3.length;i++){
            float kx=off3+i*kw3;
            float ky=kbTop+keyH*2.15f;
            boolean isDel=ROW3[i].equals("⌫");
            p.setColor(isDel?
                Color.rgb(60,20,20):Color.rgb(28,24,32));
            p.setStyle(Paint.Style.FILL);
            android.graphics.RectF kr=
                new android.graphics.RectF(
                kx+2,ky+2,kx+kw3-4,ky+keyH-4);
            c.drawRoundRect(kr,keyR,keyR,p);
            p.setColor(isDel?
                Color.argb(80,220,60,60):
                Color.argb(40,200,150,150));
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1f);
            c.drawRoundRect(kr,keyR,keyR,p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(isDel?Color.rgb(220,100,100):WHITE);
            p.setTextSize(keyH*0.45f);
            p.setTextAlign(Paint.Align.CENTER);
            c.drawText(ROW3[i],kx+kw3/2,ky+keyH*0.70f,p);
        }

        float btnY=kbTop+keyH*3.25f;
        float btnH=keyH*0.90f;
        if(!playerName.isEmpty()){
            p.setColor(RED);
            p.setStyle(Paint.Style.FILL);
            android.graphics.RectF btn=
                new android.graphics.RectF(
                SW*0.15f,btnY,SW*0.85f,btnY+btnH);
            c.drawRoundRect(btn,16,16,p);
            p.setColor(Color.argb(180,255,200,200));
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2f);
            c.drawRoundRect(btn,16,16,p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(WHITE);
            p.setTextSize(btnH*0.52f);
            p.setFakeBoldText(true);
            p.setTextAlign(Paint.Align.CENTER);
            c.drawText("CONTINUAR",SW/2,btnY+btnH*0.68f,p);
            p.setFakeBoldText(false);
        } else {
            p.setColor(Color.argb(40,150,50,50));
            p.setStyle(Paint.Style.FILL);
            android.graphics.RectF btn=
                new android.graphics.RectF(
                SW*0.15f,btnY,SW*0.85f,btnY+btnH);
            c.drawRoundRect(btn,16,16,p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(60,150,130,120));
            p.setTextSize(btnH*0.45f);
            p.setTextAlign(Paint.Align.CENTER);
            c.drawText("ingresá tu nombre primero",
                SW/2,btnY+btnH*0.68f,p);
        }
        flushFrameHD();
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
    private static final String[] ROW1 =
        {"Q","W","E","R","T","Y","U","I","O","P"};
    private static final String[] ROW2 =
        {"A","S","D","F","G","H","J","K","L"};
    private static final String[] ROW3 =
        {"Z","X","C","V","B","N","M","⌫"};
    private static final String ROW4_OK = "LISTO";

    private void drawMenuScreen(){
        int BG      = Color.rgb(8,  6,  10);
        int BG2     = Color.rgb(15, 12, 18);
        int RED     = Color.rgb(180,30, 30);
        int RED2    = Color.rgb(220,60, 60);
        int GOLD    = Color.rgb(200,160,60);
        int DIM     = Color.argb(180,80,80,90);
        int WHITE   = Color.rgb(230,225,220);
        int WHITE2  = Color.argb(120,180,175,170);
        float SW=screenW, SH=screenH;
        Canvas c = new Canvas(frameBmpHD);
        c.drawColor(BG);

        Paint grad=new Paint();
        android.graphics.LinearGradient lg=
            new android.graphics.LinearGradient(
            0,0,0,SH*0.35f,
            Color.argb(100,180,20,20),
            Color.TRANSPARENT,
            android.graphics.Shader.TileMode.CLAMP);
        grad.setShader(lg);
        c.drawRect(0,0,SW,SH*0.35f,grad);

        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);

        p.setTextAlign(Paint.Align.CENTER);
        p.setColor(Color.argb(50,200,30,30));
        p.setTextSize(SH*0.095f);
        p.setFakeBoldText(true);
        c.drawText("DUNGEON",SW/2+3,SH*0.13f+3,p);
        p.setColor(RED2);
        c.drawText("DUNGEON",SW/2,SH*0.13f,p);
        p.setFakeBoldText(false);

        p.setColor(Color.argb(100,180,160,140));
        p.setTextSize(SH*0.020f);
        c.drawText("jugando como",SW/2,SH*0.175f,p);
        p.setColor(GOLD);
        p.setTextSize(SH*0.028f);
        p.setFakeBoldText(true);
        c.drawText(playerName,SW/2,SH*0.210f,p);
        p.setFakeBoldText(false);
        p.setColor(Color.argb(80,200,160,60));
        p.setTextSize(SH*0.020f);
        c.drawText("✏ cambiar",SW/2,SH*0.238f,p);

        p.setColor(Color.argb(60,180,40,40));
        p.setStrokeWidth(1f);
        c.drawLine(SW*0.15f,SH*0.255f,
            SW*0.85f,SH*0.255f,p);

        android.graphics.RectF btnCrear=
            new android.graphics.RectF(
            SW*0.06f,SH*0.275f,SW*0.94f,SH*0.365f);
        p.setColor(Color.rgb(100,20,20));
        p.setStyle(Paint.Style.FILL);
        c.drawRoundRect(btnCrear,20,20,p);
        p.setColor(RED);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2f);
        c.drawRoundRect(btnCrear,20,20,p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(WHITE);
        p.setTextSize(SH*0.040f);
        p.setFakeBoldText(true);
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText("CREAR SALA",SW/2,SH*0.330f,p);
        p.setFakeBoldText(false);
        p.setColor(Color.argb(100,200,150,150));
        p.setTextSize(SH*0.017f);
        c.drawText("elegí el modo y esperá amigos",
            SW/2,SH*0.352f,p);

        android.graphics.RectF btnUnir=
            new android.graphics.RectF(
            SW*0.06f,SH*0.380f,SW*0.94f,SH*0.465f);
        p.setColor(Color.rgb(15,15,60));
        p.setStyle(Paint.Style.FILL);
        c.drawRoundRect(btnUnir,20,20,p);
        p.setColor(Color.rgb(60,60,180));
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2f);
        c.drawRoundRect(btnUnir,20,20,p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(WHITE);
        p.setTextSize(SH*0.040f);
        p.setFakeBoldText(true);
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText("UNIRSE CON CÓDIGO",SW/2,SH*0.430f,p);
        p.setFakeBoldText(false);
        p.setColor(Color.argb(100,150,150,200));
        p.setTextSize(SH*0.017f);
        c.drawText("ingresá el código de sala",
            SW/2,SH*0.452f,p);

        p.setColor(Color.argb(90,120,110,100));
        p.setTextSize(SH*0.018f);
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText("creá una sala o unite con código",
            SW/2,SH*0.520f,p);
        flushFrameHD();
    }

    private void drawCreateScreen(){
        int BG      = Color.rgb(8,  6,  10);
        int BG2     = Color.rgb(15, 12, 18);
        int RED     = Color.rgb(180,30, 30);
        int RED2    = Color.rgb(220,60, 60);
        int GOLD    = Color.rgb(200,160,60);
        int DIM     = Color.argb(180,80,80,90);
        int WHITE   = Color.rgb(230,225,220);
        int WHITE2  = Color.argb(120,180,175,170);
        float SW=screenW, SH=screenH;
        Canvas c=new Canvas(frameBmpHD);
        c.drawColor(BG);
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);

        String[] modeNames={"Asesino","Infección",
            "Free for All","Detective","Apagón","Zona"};
        String[] modeIcons={"⚔","🦠","💀","🔍","💡","⬛"};
        String[] modeDescs={
            "1 asesino — todos los demás escapan",
            "El infectado contagia a los sanos",
            "Todos armados — último en pie gana",
            "Detective rastrea al asesino",
            "Las luces se apagan cada 30 segundos",
            "La zona se cierra — sobrevivir o morir"};
        int[] modeColors={
            Color.rgb(140,25,25),
            Color.rgb(30,100,20),
            Color.rgb(140,110,20),
            Color.rgb(20,50,120),
            Color.rgb(60,15,100),
            Color.rgb(120,55,15)};
        int[] modeBorders={
            Color.rgb(200,50,50),
            Color.rgb(60,180,50),
            Color.rgb(200,160,30),
            Color.rgb(50,90,200),
            Color.rgb(100,40,160),
            Color.rgb(180,90,30)};

        p.setTextAlign(Paint.Align.CENTER);
        p.setColor(RED2);
        p.setTextSize(SH*0.050f);
        p.setFakeBoldText(true);
        c.drawText("CREAR SALA",SW/2,SH*0.075f,p);
        p.setFakeBoldText(false);
        p.setColor(WHITE2);
        p.setTextSize(SH*0.020f);
        c.drawText("elegí el modo de juego y esperá",
            SW/2,SH*0.105f,p);

        for(int i=0;i<6;i++){
            float by=SH*(0.130f+i*0.115f);
            boolean sel=(selectedMode==i);
            android.graphics.RectF btn=
                new android.graphics.RectF(
                SW*0.05f,by,SW*0.95f,by+SH*0.100f);
            p.setColor(sel?modeColors[i]:
                Color.argb(60,
                    Color.red(modeColors[i]),
                    Color.green(modeColors[i]),
                    Color.blue(modeColors[i])));
            p.setStyle(Paint.Style.FILL);
            c.drawRoundRect(btn,16,16,p);
            p.setColor(sel?modeBorders[i]:
                Color.argb(80,
                    Color.red(modeBorders[i]),
                    Color.green(modeBorders[i]),
                    Color.blue(modeBorders[i])));
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(sel?2.5f:1f);
            c.drawRoundRect(btn,16,16,p);
            p.setStyle(Paint.Style.FILL);
            p.setTextSize(SH*0.038f);
            p.setTextAlign(Paint.Align.LEFT);
            p.setColor(Color.argb(sel?220:120,255,255,255));
            c.drawText(modeIcons[i],SW*0.07f,by+SH*0.068f,p);
            p.setColor(sel?WHITE:Color.argb(160,200,190,180));
            p.setTextSize(SH*0.030f);
            p.setFakeBoldText(sel);
            c.drawText(modeNames[i],SW*0.17f,by+SH*0.052f,p);
            p.setFakeBoldText(false);
            p.setColor(Color.argb(sel?120:70,180,170,160));
            p.setTextSize(SH*0.018f);
            c.drawText(modeDescs[i],SW*0.17f,by+SH*0.076f,p);
            if(sel){
                p.setColor(GOLD);
                p.setTextSize(SH*0.032f);
                p.setTextAlign(Paint.Align.RIGHT);
                c.drawText("✓",SW*0.93f,by+SH*0.062f,p);
            }
        }

        android.graphics.RectF btnOk=
            new android.graphics.RectF(
            SW*0.10f,SH*0.835f,SW*0.90f,SH*0.905f);
        p.setColor(modeColors[selectedMode]);
        p.setStyle(Paint.Style.FILL);
        c.drawRoundRect(btnOk,20,20,p);
        p.setColor(modeBorders[selectedMode]);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2f);
        c.drawRoundRect(btnOk,20,20,p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(WHITE);
        p.setTextSize(SH*0.038f);
        p.setFakeBoldText(true);
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText("CREAR — "+modeNames[selectedMode],
            SW/2,SH*0.878f,p);
        p.setFakeBoldText(false);

        p.setColor(Color.argb(80,100,100,100));
        p.setStyle(Paint.Style.FILL);
        c.drawRect(SW*0.3f,SH*0.925f,SW*0.7f,SH*0.978f,p);
        p.setColor(WHITE2);
        p.setTextSize(SH*0.022f);
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText("← volver",SW/2,SH*0.960f,p);
        flushFrameHD();
    }

    private void drawJoinScreen(){
        int BG      = Color.rgb(8,  6,  10);
        int BG2     = Color.rgb(15, 12, 18);
        int RED     = Color.rgb(180,30, 30);
        int RED2    = Color.rgb(220,60, 60);
        int GOLD    = Color.rgb(200,160,60);
        int DIM     = Color.argb(180,80,80,90);
        int WHITE   = Color.rgb(230,225,220);
        int WHITE2  = Color.argb(120,180,175,170);
        Canvas c=new Canvas(frameBmpHD);
        float SW=screenW, SH=screenH;
        c.drawColor(BG);
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextAlign(Paint.Align.CENTER);

        p.setColor(Color.rgb(80,80,220));
        p.setTextSize(SH*0.10f);
        p.setFakeBoldText(true);
        c.drawText("UNIRSE",SW/2f,SH*0.15f,p);
        p.setFakeBoldText(false);

        p.setColor(Color.rgb(140,140,140));
        p.setTextSize(SH*0.048f);
        c.drawText("Código de sala:",SW/2f,SH*0.30f,p);

        p.setColor(Color.argb(180,30,30,80));
        p.setStyle(Paint.Style.FILL);
        c.drawRect(SW*0.1f,SH*0.37f,SW*0.9f,SH*0.52f,p);
        p.setColor(Color.rgb(80,80,200));
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2f);
        c.drawRect(SW*0.1f,SH*0.37f,SW*0.9f,SH*0.52f,p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.WHITE);
        p.setTextSize(SH*0.085f);
        p.setFakeBoldText(true);
        c.drawText(roomCodeInput.isEmpty()?"----":roomCodeInput,
            SW/2f,SH*0.48f,p);
        p.setFakeBoldText(false);

        String[] keys={"1","2","3","4","5","6","7","8","9","⌫","0","✓"};
        for(int i=0;i<12;i++){
            int col=i%3, row=i/3;
            float kx=SW*(0.05f+col*0.32f);
            float ky=SH*(0.56f+row*0.11f);
            float kw=SW*0.28f, kh=SH*0.09f;
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
            p.setTextSize(SH*0.055f);
            p.setFakeBoldText(true);
            c.drawText(keys[i],kx+kw/2,ky+kh*0.70f,p);
            p.setFakeBoldText(false);
        }
        flushFrameHD();
    }

    private void drawWaitingScreen(){
        int BG      = Color.rgb(8,  6,  10);
        int BG2     = Color.rgb(15, 12, 18);
        int RED     = Color.rgb(180,30, 30);
        int RED2    = Color.rgb(220,60, 60);
        int GOLD    = Color.rgb(200,160,60);
        int DIM     = Color.argb(180,80,80,90);
        int WHITE   = Color.rgb(230,225,220);
        int WHITE2  = Color.argb(120,180,175,170);
        float SW=screenW, SH=screenH;
        Canvas c=new Canvas(frameBmpHD);
        c.drawColor(BG);
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);

        String[] modeNames={"Asesino","Infección",
            "FFA","Detective","Apagón","Zona"};
        if(state==GameState.SPECTATING||roomBusy){
            c.drawColor(Color.rgb(8,6,10));
            p.setTextAlign(Paint.Align.CENTER);
            p.setColor(Color.rgb(180,150,50));
            p.setTextSize(screenH*0.048f);
            p.setFakeBoldText(true);
            c.drawText("PARTIDA EN CURSO",
                screenW/2f,screenH*0.30f,p);
            p.setFakeBoldText(false);
            p.setColor(Color.argb(150,150,140,130));
            p.setTextSize(screenH*0.025f);
            String mn=roomBusyMode<modeNames.length?
                modeNames[roomBusyMode]:"?";
            c.drawText(roomBusyPlayers+
                " jugadores · modo "+mn,
                screenW/2f,screenH*0.38f,p);
            p.setColor(Color.argb(100,120,110,100));
            p.setTextSize(screenH*0.020f);
            c.drawText("Esperando que termine para unirte",
                screenW/2f,screenH*0.46f,p);
            android.graphics.RectF bv=
                new android.graphics.RectF(
                screenW*0.3f,screenH*0.60f,
                screenW*0.7f,screenH*0.66f);
            p.setColor(Color.argb(80,80,80,80));
            p.setStyle(Paint.Style.FILL);
            c.drawRoundRect(bv,14,14,p);
            p.setColor(Color.argb(150,160,150,140));
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1f);
            c.drawRoundRect(bv,14,14,p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(180,180,170,160));
            p.setTextSize(screenH*0.022f);
            c.drawText("← volver",screenW/2f,screenH*0.635f,p);
            flushFrameHD();
            return;
        }
        int[] modeColors={Color.rgb(140,25,25),Color.rgb(30,100,20),
            Color.rgb(140,110,20),Color.rgb(20,50,120),
            Color.rgb(60,15,100),Color.rgb(120,55,15)};
        int[] modeBorders={Color.rgb(200,50,50),Color.rgb(60,180,50),
            Color.rgb(200,160,30),Color.rgb(50,90,200),
            Color.rgb(100,40,160),Color.rgb(180,90,30)};

        p.setTextAlign(Paint.Align.CENTER);
        p.setColor(RED2);
        p.setTextSize(SH*0.042f);
        p.setFakeBoldText(true);
        c.drawText("DUNGEON",SW/2,SH*0.055f,p);
        p.setFakeBoldText(false);

        p.setColor(Color.argb(120,150,140,130));
        p.setTextSize(SH*0.018f);
        c.drawText("SALA "+currentRoomId+" · "+
            modeNames[Math.min(gameMode,5)],
            SW/2,SH*0.082f,p);

        float circleY = SH*0.14f;
        if(netClient!=null){
            List<NetClient.RemotePlayer> rps =
                new ArrayList<>(netClient.remotePlayers);
            int n = Math.max(rps.size(),1);
            float spacing = SW/(n+1f);
            for(int i=0;i<rps.size();i++){
                NetClient.RemotePlayer rp = rps.get(i);
                float cx2 = spacing*(i+1);
                p.setColor(rp.ready ?
                    Color.rgb(50,180,80):Color.rgb(60,60,60));
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx2,circleY,SH*0.028f,p);
                if(!rp.ready){
                    p.setColor(Color.rgb(90,90,90));
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(2f);
                    c.drawCircle(cx2,circleY,SH*0.028f,p);
                }
                p.setStyle(Paint.Style.FILL);
                p.setColor(Color.argb(180,180,180,180));
                p.setTextSize(SH*0.018f);
                p.setTextAlign(Paint.Align.CENTER);
                String nm = rp.name.length()>6 ?
                    rp.name.substring(0,6):rp.name;
                c.drawText(nm,cx2,circleY+SH*0.046f,p);
            }
        }

        p.setColor(Color.rgb(60,180,80));
        p.setTextSize(SH*0.048f);
        p.setFakeBoldText(true);
        p.setTextAlign(Paint.Align.CENTER);
        int totalPlayers=Math.max(1,playerCount);
        c.drawText(readyCount+"/"+
            totalPlayers+" listos",
            SW/2,SH*0.280f,p);
        p.setFakeBoldText(false);

        p.setColor(Color.argb(100,150,140,130));
        p.setTextSize(SH*0.018f);
        c.drawText("votar modo:",SW/2,SH*0.310f,p);

        float vBtnW=(SW-SW*0.12f)/3f;
        float vBtnH=SH*0.070f;
        float vStartY=SH*0.325f;
        for(int i=0;i<6;i++){
            int col=i%3, row=i/3;
            float bx=SW*0.06f+col*(vBtnW+SW*0.010f);
            float by=vStartY+row*(vBtnH+SH*0.008f);
            boolean selected=(myVotedMode==i && iAmReady);
            int mc=modeColors[i];
            p.setColor(selected?Color.argb(200,
                Color.red(mc),Color.green(mc),
                Color.blue(mc)):
                Color.argb(70,Color.red(mc),
                    Color.green(mc),Color.blue(mc)));
            p.setStyle(Paint.Style.FILL);
            android.graphics.RectF vb=
                new android.graphics.RectF(
                bx,by,bx+vBtnW,by+vBtnH);
            c.drawRoundRect(vb,10,10,p);
            p.setColor(selected?Color.argb(255,
                Color.red(modeBorders[i]),
                Color.green(modeBorders[i]),
                Color.blue(modeBorders[i])):
                Color.argb(60,
                Color.red(modeBorders[i]),
                Color.green(modeBorders[i]),
                Color.blue(modeBorders[i])));
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(selected?2f:1f);
            c.drawRoundRect(vb,10,10,p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(selected?WHITE:Color.argb(160,200,190,180));
            p.setTextSize(vBtnH*0.28f);
            p.setFakeBoldText(selected);
            p.setTextAlign(Paint.Align.CENTER);
            c.drawText(modeNames[i],bx+vBtnW/2,
                by+vBtnH*0.52f,p);
            p.setFakeBoldText(false);
            if(modeVotes!=null && i<modeVotes.length
                    && modeVotes[i]>0){
                p.setColor(GOLD);
                p.setTextSize(vBtnH*0.26f);
                c.drawText(modeVotes[i]+"✓",
                    bx+vBtnW/2,by+vBtnH*0.80f,p);
            }
        }

        float listoY=SH*0.535f;
        if(!iAmReady){
            android.graphics.RectF bListo=
                new android.graphics.RectF(
                SW*0.08f,listoY,SW*0.92f,listoY+SH*0.075f);
            p.setColor(Color.rgb(20,110,30));
            p.setStyle(Paint.Style.FILL);
            c.drawRoundRect(bListo,18,18,p);
            p.setColor(Color.rgb(50,200,70));
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2f);
            c.drawRoundRect(bListo,18,18,p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.WHITE);
            p.setTextSize(SH*0.036f);
            p.setFakeBoldText(true);
            p.setTextAlign(Paint.Align.CENTER);
            c.drawText("¡LISTO!",SW/2,
                listoY+SH*0.050f,p);
            p.setFakeBoldText(false);
        } else {
            android.graphics.RectF bEsp=
                new android.graphics.RectF(
                SW*0.08f,listoY,SW*0.92f,listoY+SH*0.075f);
            p.setColor(Color.argb(60,30,100,40));
            p.setStyle(Paint.Style.FILL);
            c.drawRoundRect(bEsp,18,18,p);
            p.setColor(Color.argb(120,50,160,70));
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.5f);
            c.drawRoundRect(bEsp,18,18,p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(180,100,200,120));
            p.setTextSize(SH*0.026f);
            p.setTextAlign(Paint.Align.CENTER);
            int dots=(int)(frameCount/15)%4;
            c.drawText("esperando otros jugadores"+
                ".".repeat(dots),SW/2,
                listoY+SH*0.048f,p);
        }

        float backY=listoY+SH*0.100f;
        p.setColor(Color.argb(60,80,80,80));
        p.setStyle(Paint.Style.FILL);
        android.graphics.RectF bBack=
            new android.graphics.RectF(
            SW*0.3f,backY,SW*0.7f,backY+SH*0.050f);
        c.drawRoundRect(bBack,12,12,p);
        p.setColor(Color.argb(120,160,150,140));
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1f);
        c.drawRoundRect(bBack,12,12,p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.argb(160,180,170,160));
        p.setTextSize(SH*0.020f);
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText("← volver al menú",SW/2,
            backY+SH*0.033f,p);

        flushFrameHD();
    }

    private void drawEndScreen(){
        int BG      = Color.rgb(8,  6,  10);
        int BG2     = Color.rgb(15, 12, 18);
        int RED     = Color.rgb(180,30, 30);
        int RED2    = Color.rgb(220,60, 60);
        int GOLD    = Color.rgb(200,160,60);
        int DIM     = Color.argb(180,80,80,90);
        int WHITE   = Color.rgb(230,225,220);
        int WHITE2  = Color.argb(120,180,175,170);
        Canvas c = new Canvas(frameBmpHD);
        c.drawColor(BG);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float SW=screenW, SH=screenH;
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

        p.setColor(color);
        p.setTextSize(SH*0.07f);
        p.setFakeBoldText(true);
        c.drawText(titulo,SW/2f,SH*0.28f,p);
        p.setFakeBoldText(false);
        p.setTextSize(SH*0.036f);
        p.setColor(Color.argb(200,200,200,200));
        c.drawText(sub,SW/2f,SH*0.44f,p);

        if(!endResults.isEmpty()){
            p.setColor(Color.rgb(100,100,100));
            p.setTextSize(SH*0.025f);
            c.drawText("── Resultados ──",SW/2,SH*0.52f,p);

            for(int i=0;i<endResults.size();i++){
                NetClient.EndResult er=endResults.get(i);
                float ry2=SH*(0.56f+i*0.065f);
                p.setColor(er.won ?
                    Color.argb(60,0,150,0):
                    Color.argb(40,150,0,0));
                p.setStyle(Paint.Style.FILL);
                c.drawRect(SW*0.05f,ry2-SH*0.022f,
                    SW*0.95f,ry2+SH*0.034f,p);
                p.setColor(er.won ?
                    Color.rgb(100,220,100):
                    Color.rgb(180,180,180));
                p.setTextSize(SH*0.030f);
                p.setTextAlign(Paint.Align.LEFT);
                String badge=er.wasKiller?"⚔ ":"";
                c.drawText(badge+er.name,
                    SW*0.08f,ry2+SH*0.010f,p);
                p.setTextAlign(Paint.Align.RIGHT);
                p.setColor(er.won ?
                    Color.rgb(80,200,80):
                    Color.rgb(200,80,80));
                c.drawText(er.won?"GANÓ":"PERDIÓ",
                    SW*0.92f,ry2+SH*0.010f,p);
            }
        }

        p.setColor(WHITE2);
        p.setTextSize(SH*0.022f);
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText("Volviendo a la sala...",
            SW/2,SH*0.92f,p);

        flushFrameHD();
    }

    private void drawSettingsOverlay(){
        Canvas c = new Canvas(frameBmpHD);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float SW=screenW, SH=screenH;
        c.drawBitmap(frameBmp, null,
            new android.graphics.RectF(0,0,SW,SH), null);

        p.setColor(Color.argb(200,8,6,10));
        p.setStyle(Paint.Style.FILL);
        c.drawRect(SW*0.05f,SH*0.25f,
            SW*0.95f,SH*0.75f,p);
        p.setColor(Color.argb(150,180,30,30));
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2f);
        android.graphics.RectF box=
            new android.graphics.RectF(
            SW*0.05f,SH*0.25f,SW*0.95f,SH*0.75f);
        c.drawRoundRect(box,20,20,p);
        p.setStyle(Paint.Style.FILL);

        p.setColor(Color.rgb(220,220,215));
        p.setTextSize(SH*0.030f);
        p.setFakeBoldText(true);
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText("AJUSTES",SW/2,SH*0.305f,p);
        p.setFakeBoldText(false);

        p.setColor(Color.argb(150,180,170,160));
        p.setTextSize(SH*0.020f);
        c.drawText("Sensibilidad de cámara",SW/2,SH*0.360f,p);

        float barX=SW*0.12f, barY=SH*0.375f;
        float barW=SW*0.76f, barH=SH*0.022f;
        p.setColor(Color.argb(80,40,40,60));
        p.setStyle(Paint.Style.FILL);
        android.graphics.RectF barBg=
            new android.graphics.RectF(
            barX,barY,barX+barW,barY+barH);
        c.drawRoundRect(barBg,barH/2,barH/2,p);
        float sPos=(camSensitivity-0.02f)/(0.15f-0.02f);
        p.setColor(Color.rgb(80,80,160));
        android.graphics.RectF barFg=
            new android.graphics.RectF(
            barX,barY,barX+barW*sPos,barY+barH);
        c.drawRoundRect(barFg,barH/2,barH/2,p);
        p.setColor(Color.rgb(140,140,220));
        c.drawCircle(barX+barW*sPos,
            barY+barH/2,barH*1.4f,p);

        p.setColor(Color.argb(120,150,150,200));
        p.setTextSize(SH*0.018f);
        c.drawText(String.format("%.2f",camSensitivity),
            SW/2,SH*0.420f,p);

        p.setColor(pingMs<80?Color.rgb(60,200,80):
            pingMs<150?Color.rgb(200,180,40):
            Color.rgb(200,60,60));
        p.setTextSize(SH*0.020f);
        c.drawText("Ping: "+pingMs+"ms",SW/2,SH*0.470f,p);

        android.graphics.RectF btnClose=
            new android.graphics.RectF(
            SW*0.3f,SH*0.640f,SW*0.7f,SH*0.700f);
        p.setColor(Color.rgb(80,20,20));
        p.setStyle(Paint.Style.FILL);
        c.drawRoundRect(btnClose,14,14,p);
        p.setColor(Color.rgb(160,40,40));
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.5f);
        c.drawRoundRect(btnClose,14,14,p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.rgb(220,210,200));
        p.setTextSize(SH*0.024f);
        p.setFakeBoldText(true);
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText("CERRAR",SW/2,SH*0.678f,p);
        p.setFakeBoldText(false);

        flushFrameHD();
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
        p.setColor(pingMs<80?Color.argb(120,60,200,80):
            pingMs<150?Color.argb(120,200,180,40):
            Color.argb(120,200,60,60));
        p.setTextSize(RH*0.038f);
        p.setTextAlign(Paint.Align.LEFT);
        c.drawText(pingMs+"ms",RW*0.02f,RH*0.16f,p);

        Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
        sp.setColor(Color.argb(80,80,80,80));
        sp.setStyle(Paint.Style.FILL);
        c.drawRect(RW-18, 2, RW-2, 18, sp);
        sp.setColor(Color.argb(150,200,200,200));
        sp.setTextSize(RH*0.055f);
        sp.setTextAlign(Paint.Align.CENTER);
        c.drawText("⚙", RW-10f, 14f, sp);
    }

    public void handleNameKeyboard(
            float screenX, float screenY,
            int screenW, int screenH) {
        float rx = screenX;
        float ry = screenY;

        float kbTop = screenH * 0.44f;
        float kbH   = screenH * 0.44f;
        float keyH  = kbH / 4.2f;

        if (ry < kbTop) return;

        if (ry >= kbTop && ry < kbTop + keyH) {
            float kw1=screenW/10.5f;
            float off1=(screenW-ROW1.length*kw1)/2f;
            int col = (int)((rx-off1) / kw1);
            if (col >= 0 && col < ROW1.length && playerName.length() < 10)
                playerName += ROW1[col];
            return;
        }

        if (ry >= kbTop + keyH*1.1f && ry < kbTop + keyH*1.95f) {
            float kw2=screenW/10.2f;
            float offX2 = (screenW - ROW2.length*kw2) / 2f;
            int col2 = (int)((rx - offX2) / kw2);
            if (col2 >= 0 && col2 < ROW2.length && playerName.length() < 10)
                playerName += ROW2[col2];
            return;
        }

        if (ry >= kbTop + keyH*2.15f && ry < kbTop + keyH*3.00f) {
            float kw3=screenW/9.2f;
            float offX3 = (screenW - ROW3.length*kw3) / 2f;
            int col3 = (int)((rx - offX3) / kw3);
            if (col3 >= 0 && col3 < ROW3.length) {
                if ("⌫".equals(ROW3[col3])) {
                    if (!playerName.isEmpty())
                        playerName = playerName.substring(0, playerName.length()-1);
                } else if (playerName.length() < 10) {
                    playerName += ROW3[col3];
                }
            }
            return;
        }

        float btnY = kbTop + keyH*3.25f;
        float btnH = keyH*0.90f;
        if (ry > btnY && ry < btnY+btnH && !playerName.isEmpty()) {
            if(prefs!=null){
                prefs.edit().putString(
                    "player_name",playerName).apply();
            }
            state = GameState.MENU;
        }
    }

    public void handleTouch(float ex,float ey,int sw,int sh){
        float rx2=ex;
        float ry2=ey;

        switch(state){
            case NAME_INPUT:
                handleNameKeyboard(ex, ey, sw, sh);
                break;
            case MENU:
                if(ry2>sh*0.175f&&ry2<sh*0.245f){
                    state=GameState.NAME_INPUT;
                } else if(ry2>sh*0.275f&&ry2<sh*0.365f){
                    state=GameState.CREATING; selectedMode=0;
                } else if(ry2>sh*0.380f&&ry2<sh*0.465f){
                    state=GameState.JOINING; roomCodeInput="";
                }
                break;
            case CREATING:
                for(int i=0;i<6;i++){
                    float by=sh*(0.130f+i*0.115f);
                    if(rx2>sw*0.05f&&rx2<sw*0.95f
                            &&ry2>by&&ry2<by+sh*0.100f){
                        selectedMode=i; return;
                    }
                }
                if(rx2>sw*0.10f&&rx2<sw*0.90f
                        &&ry2>sh*0.835f&&ry2<sh*0.905f){
                    String[] rooms={"0000","0001","0002",
                        "0003","0004","0005"};
                    connectToRoom(rooms[selectedMode]);
                } else if(rx2>sw*0.3f&&rx2<sw*0.7f
                        &&ry2>sh*0.925f&&ry2<sh*0.978f){
                    state=GameState.MENU;
                }
                break;
            case JOINING:
                String[] keys2={"1","2","3","4","5","6","7","8","9","⌫","0","✓"};
                for(int i=0;i<12;i++){
                    int col=i%3,row=i/3;
                    float kx=sw*(0.05f+col*0.32f);
                    float ky=sh*(0.56f+row*0.11f);
                    float kw=sw*0.28f,kh=sh*0.09f;
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
            case PLAYING:
                if(showSettingsOverlay){
                    float barX=screenW*0.12f;
                    float barY=screenH*0.375f;
                    float barW=screenW*0.76f;
                    float barH=screenH*0.022f;
                    if(ey>barY-20 && ey<barY+barH+20
                            && ex>barX && ex<barX+barW){
                        float pos=(ex-barX)/barW;
                        pos=Math.max(0,Math.min(1,pos));
                        camSensitivity=0.02f+pos*(0.15f-0.02f);
                        if(prefs!=null)
                            prefs.edit()
                                .putFloat("sensitivity",camSensitivity)
                                .apply();
                        return;
                    }
                    if(ey>screenH*0.640f && ey<screenH*0.700f
                            && ex>screenW*0.3f && ex<screenW*0.7f){
                        showSettingsOverlay=false;
                        return;
                    }
                    return;
                }

                float gearX=screenW*(1f-18f/RW);
                float gearY=screenH*(18f/RH);
                if(ex>gearX && ey<gearY){
                    showSettingsOverlay=!showSettingsOverlay;
                    leftId=-1; rightId=-1;
                    leftDX=leftDY=rightDX=rightDY=0;
                    return;
                }
                break;
            case WAITING:
                float vBtnW=(screenW-screenW*0.12f)/3f;
                float vBtnH=screenH*0.070f;
                float vStartY=screenH*0.325f;
                for(int i=0;i<6;i++){
                    int col=i%3, row=i/3;
                    float bx=screenW*0.06f+col*(vBtnW+screenW*0.010f);
                    float by=vStartY+row*(vBtnH+screenH*0.008f);
                    if(ex>=bx&&ex<=bx+vBtnW&&ey>=by&&ey<=by+vBtnH){
                        myVotedMode=i;
                        return;
                    }
                }

                float listoY=screenH*0.535f;
                if(!iAmReady && ey>listoY
                        && ey<listoY+screenH*0.075f){
                    iAmReady=true;
                    if(netClient!=null)
                        netClient.sendReady(myVotedMode);
                    return;
                }

                float backY=listoY+screenH*0.100f;
                if(ey>backY && ey<backY+screenH*0.050f){
                    if(netClient!=null){
                        netClient.disconnect();
                        netClient=null;
                    }
                    remoteStates.clear();
                    iAmReady=false;
                    roomBusy=false;
                    state=GameState.MENU;
                }
                break;
            case SPECTATING:
                if(ey>screenH*0.60f && ey<screenH*0.66f
                        && ex>screenW*0.3f && ex<screenW*0.7f){
                    if(netClient!=null){
                        netClient.disconnect();
                        netClient=null;
                    }
                    remoteStates.clear();
                    roomBusy=false;
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

    private void flushFrameHD(){
        if(frameBmpHD==null) return;
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0,
            frameBmpHD, 0);
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
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
        GLES20.glUniform1i(texHandle, 0);
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
