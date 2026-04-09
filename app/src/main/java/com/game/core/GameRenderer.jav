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
        CONNECTING, MENU, WAITING, PLAYING, DEAD, END, SPECTATING
    }

    public volatile GameState state = GameState.MENU;
    public volatile int   gameMode     = MODE_KILLER;
    public volatile boolean amKiller   = false;
    public volatile boolean amInfected = false;
    public volatile boolean amDetective= false;
    public volatile boolean attackPressed = false;
    public volatile int   selectedRoom = 0;

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

    private volatile int     myHp          = 100;
    private volatile int     gameTimer     = 0;
    private volatile int     playerCount   = 0;
    private volatile int     minPlayers    = 2;
    private volatile String  statusMsg     = "Conectando...";
    private volatile boolean endKillerWon  = false;
    private volatile String  endMsg        = "";
    private volatile boolean showAttackFX  = false;
    private volatile boolean blackoutActive= false;
    private volatile float   detectorDist  = -1f;
    private volatile long    detectorTime  = 0;
    private volatile int     minigameScore = 0;
    private volatile boolean minigameTarget= false;
    private volatile long    minigameTargetTime = 0;
    private volatile float   minigameTX, minigameTY;

    private final ConcurrentHashMap<Integer, RemoteState>
        remoteStates = new ConcurrentHashMap<>();

    private static class RemoteState {
        float x,y,angle;
        float rx,ry,ra;
        boolean init=false;
        void update(float nx,float ny,float na){
            x=nx; y=ny; angle=na;
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
        -1f,1f,0f,0f, -1f,-1f,0f,1f, 1f,1f,1f,0f, 1f,-1f,1f,1f};

    public GameRenderer(Context ctx) { this.ctx=ctx; }

    public void connectToServer() {
        if (netClient != null) {
            netClient.disconnect();
            netClient = null;
        }
        remoteStates.clear();
        String name = "P"+(int)(Math.random()*900+100);
        String[] rooms = {
            NetClient.ROOM_0, NetClient.ROOM_1, NetClient.ROOM_2,
            NetClient.ROOM_3, NetClient.ROOM_4, NetClient.ROOM_5
        };
        String roomId = rooms[Math.min(selectedRoom, rooms.length-1)];

        netClient = new NetClient(name, new NetClient.Listener(){
            public void onConnected(){
                statusMsg="Conectando...";
            }
            public void onJoined(int id,long seed,boolean spec,int mode){
                Map.generate(seed);
                player=new Player(Map.getSpawnX(),Map.getSpawnY());
                gameMode=mode;
                state=spec?GameState.SPECTATING:GameState.WAITING;
                statusMsg=spec?"Espectador":"Esperando jugadores...";
            }
            public void onRoomInfo(int cnt,int min,boolean started){
                playerCount = cnt;
                minPlayers  = min;
                if (!started && state == GameState.END) {
                    resetToWaiting();
                } else if (!started && state != GameState.PLAYING) {
                    statusMsg = cnt + "/" + min + " jugadores";
                    state = GameState.WAITING;
                }
            }
            public void onGameStart(boolean killer,boolean inf,
                    boolean det,int mode,int dur){
                gameMode=mode;
                amKiller=killer; amInfected=inf; amDetective=det;
                myHp=100;
                state=GameState.PLAYING;
                statusMsg="";
            }
            public void onHit(int hp){ myHp=hp; }
            public void onPlayerDied(int id){
                if(netClient!=null && id==netClient.myId){
                    myHp=0; state=GameState.DEAD;
                }
            }
            public void onGameEnd(boolean kw){
                endKillerWon=kw;
                state=GameState.END;
            }
            public void onBlackout(boolean on){
                blackoutActive=on;
            }
            public void onDetectorPing(float dist){
                detectorDist=dist;
                detectorTime=System.currentTimeMillis();
            }
            public void onDisconnected(){
                state=GameState.CONNECTING;
                statusMsg="Reconectando...";
            }
        });
        netClient.connect(roomId);
    }

    private void resetToWaiting(){
        state=GameState.WAITING;
        amKiller=false; amInfected=false; amDetective=false;
        myHp=100; endMsg=""; playerCount=0;
        blackoutActive=false; detectorDist=-1;
        statusMsg="Esperando jugadores...";
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
        state = GameState.MENU;
    }

    @Override
    public void onSurfaceChanged(GL10 gl,int w,int h){
        GLES20.glViewport(0,0,w,h);
    }

    @Override
    public void onDrawFrame(GL10 gl){
        long now=System.nanoTime();
        delta=Math.min((now-prevTime)/16_666_667f,3f);
        prevTime=now; frameCount++;

        if(netClient!=null && !netClient.isOpen()){
            long t=System.currentTimeMillis();
            if(t-lastReconnect>3000){
                lastReconnect=t; netClient.connect(
                    new String[]{NetClient.ROOM_0,NetClient.ROOM_1,
                        NetClient.ROOM_2,NetClient.ROOM_3,
                        NetClient.ROOM_4,NetClient.ROOM_5}
                        [Math.min(selectedRoom,5)]);
            }
        }

        switch(state){
            case MENU:
                drawMenuScreen(); return;
            case CONNECTING:
            case WAITING:
            case SPECTATING:
                updateMinigame();
                drawWaitingScreen(); return;
            case END:
                drawEndScreen(); return;
            case DEAD:
                raycaster.gameMode=gameMode;
                raycaster.blackout=false;
                raycaster.render(pixelBuf,player,frameCount);
                drawDeadOverlay();
                flushHUD(); flushFrame(); return;
            case PLAYING: break;
            default: return;
        }

        update();

        if(attackPressed && canAttack()){
            if(netClient!=null) netClient.sendAttack();
            attackPressed=false; showAttackFX=true;
        }
        if(netClient!=null)
            netClient.sendInput(player.x,player.y,player.angle);

        float interp=Math.min(1f,delta*0.25f);
        for(RemoteState rs:remoteStates.values()) rs.lerp(interp);

        if(netClient!=null){
            for(NetClient.RemotePlayer rp:netClient.remotePlayers){
                RemoteState rs=remoteStates.get(rp.id);
                if(rs==null){rs=new RemoteState();
                    remoteStates.put(rp.id,rs);}
                rs.update(rp.x,rp.y,rp.angle);
            }
        }

        gameTimer=netClient!=null?netClient.lastTimer:0;

        raycaster.gameMode=gameMode;
        raycaster.blackout=blackoutActive && gameMode==MODE_BLACKOUT;
        raycaster.render(pixelBuf,player,frameCount);

        List<float[]> sprites=new ArrayList<>();
        if(netClient!=null){
            int myId = netClient != null ? netClient.myId : -1;
            for(NetClient.RemotePlayer rp:netClient.remotePlayers){
                if (rp.id == myId) continue;
                if (rp.spectator || !rp.alive) continue;
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

    public boolean canAttack() {
        return (gameMode==MODE_KILLER   && amKiller)
            || (gameMode==MODE_INFECTION&& amInfected)
            || (gameMode==MODE_FFA)
            || (gameMode==MODE_BLACKOUT && amKiller)
            || (gameMode==MODE_SHRINK);
    }

    private void update(){
        float spd;
        if((gameMode==MODE_KILLER||gameMode==MODE_BLACKOUT)&&amKiller)
            spd=1.25f*delta;
        else if(gameMode==MODE_INFECTION&&amInfected)
            spd=1.15f*delta;
        else
            spd=0.9f*delta;

        float a=player.angle;
        float fx=(float)Math.cos(a),fy=(float)Math.sin(a);
        float rx=(float)Math.cos(a+Math.PI/2);
        float ry=(float)Math.sin(a+Math.PI/2);
        float jx=leftDX,jy=leftDY;
        if(Math.abs(jx)>0.05f||Math.abs(jy)>0.05f)
            player.move((fx*(-jy)+rx*jx)*spd,(fy*(-jy)+ry*jx)*spd);
        if(Math.abs(rightDX)>0.005f)
            player.angle+=rightDX*0.045f*delta;
        rightDX=0; rightDY=0;

        if(gameMode==MODE_FFA&&myHp>0&&myHp<100&&frameCount%300==0)
            myHp=Math.min(100,myHp+1);

        Assets.updateSteps(Math.abs(jx)>0.05f||Math.abs(jy)>0.05f);
    }

    private void drawHUD(){
        Canvas c=new Canvas(frameBmp);
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);

        // Timer
        if(gameTimer>0){
            int mins=gameTimer/60, secs=gameTimer%60;
            p.setTextAlign(Paint.Align.CENTER);
            p.setColor(gameTimer<30
                ?Color.rgb(220,60,60):Color.argb(180,220,220,220));
            p.setTextSize(RH*0.072f);
            p.setFakeBoldText(true);
            c.drawText(String.format("%d:%02d",mins,secs),
                RW/2f,RH*0.09f,p);
            p.setFakeBoldText(false);
        }

        // HP bar
        p.setColor(Color.argb(160,0,0,0));
        c.drawRect(4,RH-16,90,RH-4,p);
        float hpPct=myHp/100f;
        p.setColor(hpPct>0.5f?Color.rgb(40,200,60)
            :hpPct>0.25f?Color.rgb(220,160,0):Color.rgb(200,40,40));
        c.drawRect(5,RH-15,5+hpPct*84,RH-5,p);
        p.setColor(Color.argb(120,255,255,255));
        p.setTextSize(RH*0.042f);
        p.setTextAlign(Paint.Align.LEFT);
        c.drawText(myHp+"hp",7,RH-6,p);

        // Rol
        String roleText="";
        int roleColor=Color.WHITE;
        if(gameMode==MODE_KILLER&&amKiller){
            roleText="⚔ ASESINO"; roleColor=Color.rgb(220,40,40);
        } else if((gameMode==MODE_KILLER||gameMode==MODE_BLACKOUT)
                &&!amKiller&&!amDetective){
            roleText="SUPERVIVIENTE"; roleColor=Color.rgb(80,180,80);
        } else if(gameMode==MODE_INFECTION&&amInfected){
            roleText="INFECTADO"; roleColor=Color.rgb(120,200,50);
        } else if(gameMode==MODE_INFECTION&&!amInfected){
            roleText="SANO"; roleColor=Color.rgb(80,200,80);
        } else if(gameMode==MODE_FFA){
            roleText="FREE FOR ALL"; roleColor=Color.rgb(200,150,50);
        } else if(gameMode==MODE_DETECTIVE&&amDetective){
            roleText="DETECTIVE"; roleColor=Color.rgb(80,150,220);
        } else if(gameMode==MODE_DETECTIVE&&amKiller){
            roleText="⚔ ASESINO"; roleColor=Color.rgb(220,40,40);
        } else if(gameMode==MODE_DETECTIVE){
            roleText="INOCENTE"; roleColor=Color.rgb(80,180,80);
        } else if(gameMode==MODE_SHRINK){
            roleText="ZONA CIERRA"; roleColor=Color.rgb(200,100,50);
        }
        if(!roleText.isEmpty()){
            p.setColor(roleColor);
            p.setTextSize(RH*0.055f);
            p.setFakeBoldText(true);
            p.setTextAlign(Paint.Align.LEFT);
            c.drawText(roleText,RW*0.02f,RH*0.17f,p);
            p.setFakeBoldText(false);
        }

        // Detector ping (modo detective)
        if(gameMode==MODE_DETECTIVE&&amDetective&&detectorDist>=0){
            long elapsed=System.currentTimeMillis()-detectorTime;
            if(elapsed<3000){
                float alpha=(1f-(float)elapsed/3000f);
                p.setColor(Color.argb((int)(180*alpha),80,150,255));
                p.setTextSize(RH*0.055f);
                p.setTextAlign(Paint.Align.CENTER);
                String dist=detectorDist<96?"¡MUY CERCA!":
                    detectorDist<200?"CERCA":"LEJOS";
                c.drawText("📡 ASESINO: "+dist,RW/2f,RH*0.25f,p);
            }
        }

        // Viñeta infección
        if(amInfected&&gameMode==MODE_INFECTION){
            float pulse=(float)Math.sin(frameCount*0.08f)*0.5f+0.5f;
            int a2=(int)(40+pulse*40);
            p.setColor(Color.argb(a2,50,200,50));
            c.drawRect(0,0,RW,5,p);
            c.drawRect(0,RH-5,RW,RH,p);
            c.drawRect(0,0,5,RH,p);
            c.drawRect(RW-5,0,RW,RH,p);
        }

        // Viñeta apagón
        if(blackoutActive&&gameMode==MODE_BLACKOUT&&!amKiller){
            float pulse=(float)Math.sin(frameCount*0.12f)*0.3f+0.7f;
            p.setColor(Color.argb((int)(60*pulse),0,0,0));
            c.drawRect(0,0,RW,RH,p);
        }

        // Botón ATK
        if(canAttack()){
            p.setColor(Color.argb(70,200,40,40));
            c.drawRect(RW*0.72f,RH*0.62f,RW*0.98f,RH*0.93f,p);
            p.setColor(Color.argb(140,255,80,80));
            p.setStrokeWidth(1.5f);
            p.setStyle(Paint.Style.STROKE);
            c.drawRect(RW*0.72f,RH*0.62f,RW*0.98f,RH*0.93f,p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(200,255,255,255));
            p.setTextSize(RH*0.075f);
            p.setFakeBoldText(true);
            p.setTextAlign(Paint.Align.CENTER);
            c.drawText("⚔",RW*0.85f,RH*0.81f,p);
            p.setFakeBoldText(false);
            p.setTextSize(RH*0.036f);
            c.drawText("ATACAR",RW*0.85f,RH*0.90f,p);
        }

        // Crosshair
        p.setColor(Color.argb(100,255,255,255));
        p.setStrokeWidth(1f);
        c.drawLine(RW/2f-5,RH/2f,RW/2f+5,RH/2f,p);
        c.drawLine(RW/2f,RH/2f-5,RW/2f,RH/2f+5,p);

        // Flash ataque
        if(showAttackFX){
            p.setColor(Color.argb(70,255,40,40));
            p.setStyle(Paint.Style.FILL);
            c.drawRect(0,0,RW,RH,p);
            showAttackFX=false;
        }

        // Contador jugadores vivos
        if(netClient!=null){
            int alive=0;
            for(NetClient.RemotePlayer rp:netClient.remotePlayers)
                if(!rp.spectator&&rp.alive) alive++;
            p.setColor(Color.argb(150,200,80,80));
            p.setTextSize(RH*0.05f);
            p.setTextAlign(Paint.Align.LEFT);
            c.drawText(alive+" vivos",RW*0.02f,RH*0.09f,p);
        }
    }

    private void flushHUD(){
        Canvas c=new Canvas(frameBmp);
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.argb(120,180,0,0));
        c.drawRect(0,0,RW,RH,p);
        p.setColor(Color.WHITE);
        p.setTextSize(RH*0.10f);
        p.setFakeBoldText(true);
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText("HAS MUERTO",RW/2f,RH*0.45f,p);
        p.setFakeBoldText(false);
        p.setTextSize(RH*0.042f);
        p.setColor(Color.argb(160,200,200,200));
        c.drawText("Esperando fin de partida...",RW/2f,RH*0.58f,p);
    }

    private void drawDeadOverlay(){}

    private void drawMenuScreen() {
        Canvas c = new Canvas(frameBmp);
        c.drawColor(Color.BLACK);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextAlign(Paint.Align.CENTER);

        p.setColor(Color.rgb(180,30,30));
        p.setTextSize(RH*0.14f);
        p.setFakeBoldText(true);
        c.drawText("DUNGEON", RW/2f, RH*0.16f, p);
        p.setFakeBoldText(false);

        String[] modes = {
            "0 - Asesino",
            "1 - Infección",
            "2 - Free for All",
            "3 - Detective",
            "4 - Apagón",
            "5 - Zona Cierre"
        };
        float bw = RW * 0.76f;
        float bh = RH * 0.10f;
        float bx = (RW - bw) * 0.5f;
        float top = RH * 0.24f;
        float gap = RH * 0.015f;
        for (int i = 0; i < modes.length; i++) {
            float y0 = top + i * (bh + gap);
            boolean sel = (i == selectedRoom);
            p.setStyle(Paint.Style.FILL);
            p.setColor(sel ? Color.rgb(130,40,40) : Color.rgb(40,40,40));
            c.drawRect(bx, y0, bx + bw, y0 + bh, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.5f);
            p.setColor(sel ? Color.rgb(220,80,80) : Color.rgb(90,90,90));
            c.drawRect(bx, y0, bx + bw, y0 + bh, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.rgb(220,220,220));
            p.setTextSize(RH*0.045f);
            c.drawText(modes[i], RW/2f, y0 + bh*0.65f, p);
        }
        flushFrame();
    }

    public void handleMenuTap(float x, float y, int screenW, int screenH) {
        if (state != GameState.MENU) return;
        float sx = RW / (float)screenW;
        float sy = RH / (float)screenH;
        float rx = x * sx;
        float ry = y * sy;

        float bw = RW * 0.76f;
        float bh = RH * 0.10f;
        float bx = (RW - bw) * 0.5f;
        float top = RH * 0.24f;
        float gap = RH * 0.015f;
        for (int i = 0; i < 6; i++) {
            float y0 = top + i * (bh + gap);
            if (rx >= bx && rx <= bx + bw && ry >= y0 && ry <= y0 + bh) {
                selectedRoom = i;
                connectToServer();
                state = GameState.CONNECTING;
                return;
            }
        }
    }

    private void drawWaitingScreen(){
        Canvas c=new Canvas(frameBmp);
        c.drawColor(Color.BLACK);
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextAlign(Paint.Align.CENTER);

        // Título
        p.setColor(Color.rgb(180,30,30));
        p.setTextSize(RH*0.14f);
        p.setFakeBoldText(true);
        c.drawText("DUNGEON",RW/2f,RH*0.20f,p);
        p.setFakeBoldText(false);

        // Línea
        p.setColor(Color.rgb(80,20,20));
        p.setStrokeWidth(1.5f);
        c.drawLine(RW*0.1f,RH*0.25f,RW*0.9f,RH*0.25f,p);

        String[] modeNames={"Asesino","Infección",
            "Free for All","Detective","Apagón","Zona Cierre"};
        String[] modeDescs={
            "1 asesino, todos los demás escapan",
            "El infectado contagia a todos",
            "Todos armados — último en pie gana",
            "Detective vs Asesino",
            "Las luces se apagan... ¿quién te atacará?",
            "La zona se cierra — muévete o muere"
        };

        if(state==GameState.CONNECTING){
            p.setColor(Color.rgb(120,120,120));
            p.setTextSize(RH*0.055f);
            c.drawText(statusMsg,RW/2f,RH*0.42f,p);
            int dots=(int)(frameCount/15)%4;
            c.drawText(".".repeat(dots),RW/2f,RH*0.52f,p);
        } else if(state==GameState.SPECTATING){
            p.setColor(Color.rgb(200,150,50));
            p.setTextSize(RH*0.065f);
            c.drawText("ESPECTADOR",RW/2f,RH*0.42f,p);
            p.setColor(Color.rgb(80,80,80));
            p.setTextSize(RH*0.042f);
            c.drawText("Esperando próxima partida...",RW/2f,RH*0.54f,p);
        } else {
            // Modo seleccionado
            int m=Math.min(gameMode,modeNames.length-1);
            p.setColor(Color.rgb(150,100,50));
            p.setTextSize(RH*0.042f);
            c.drawText("MODO: "+modeNames[m],RW/2f,RH*0.30f,p);
            p.setColor(Color.rgb(70,70,70));
            p.setTextSize(RH*0.036f);
            c.drawText(modeDescs[m],RW/2f,RH*0.37f,p);

            // Círculos jugadores
            float cy=RH*0.52f,cr=RH*0.042f;
            float sp=RW/(minPlayers+1f);
            for(int i=0;i<minPlayers;i++){
                float cx2=sp*(i+1);
                if(i<playerCount){
                    p.setColor(Color.rgb(50,180,80));
                    p.setStyle(Paint.Style.FILL);
                }else{
                    p.setColor(Color.rgb(40,40,40));
                    p.setStyle(Paint.Style.FILL);
                }
                c.drawCircle(cx2,cy,cr,p);
                if(i>=playerCount){
                    p.setColor(Color.rgb(80,80,80));
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(1.5f);
                    c.drawCircle(cx2,cy,cr,p);
                }
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.rgb(80,180,80));
            p.setTextSize(RH*0.065f);
            p.setFakeBoldText(true);
            c.drawText(playerCount+"/"+minPlayers,RW/2f,RH*0.67f,p);
            p.setFakeBoldText(false);
            p.setColor(Color.rgb(60,60,60));
            p.setTextSize(RH*0.036f);
            c.drawText("jugadores conectados",RW/2f,RH*0.74f,p);

            // Minijuego de espera — toca la silueta
            drawMinigame(c,p);
        }
        flushFrame();
    }

    private void updateMinigame(){
        if(state!=GameState.WAITING) return;
        if(!minigameTarget){
            if(frameCount%120==0){
                minigameTarget=true;
                minigameTX=RW*0.15f+
                    (float)(Math.random()*RW*0.5f);
                minigameTY=RH*0.55f+
                    (float)(Math.random()*RH*0.2f);
                minigameTargetTime=System.currentTimeMillis();
            }
        } else {
            if(System.currentTimeMillis()-minigameTargetTime>2000){
                minigameTarget=false;
            }
        }
    }

    private void drawMinigame(Canvas c,Paint p){
        if(!minigameTarget) return;
        long elapsed=System.currentTimeMillis()-minigameTargetTime;
        float alpha=(1f-(float)elapsed/2000f);
        p.setColor(Color.argb((int)(200*alpha),150,50,50));
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(minigameTX,minigameTY,14,p);
        p.setColor(Color.argb((int)(255*alpha),255,255,255));
        p.setTextSize(RH*0.032f);
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText("¡TOCA!",minigameTX,minigameTY+5,p);
    }

    public boolean handleMinigameTap(float screenX,float screenY,
            int screenW,int screenH){
        if(!minigameTarget||state!=GameState.WAITING) return false;
        float tx=minigameTX*(screenW/(float)RW);
        float ty=minigameTY*(screenH/(float)RH);
        float dx=screenX-tx, dy=screenY-ty;
        if(Math.sqrt(dx*dx+dy*dy)<50){
            minigameScore++;
            minigameTarget=false;
            return true;
        }
        return false;
    }

    private void drawEndScreen(){
        Canvas c=new Canvas(frameBmp);
        c.drawColor(Color.BLACK);
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextAlign(Paint.Align.CENTER);

        String titulo,sub;
        int color;
        boolean iWon=false;

        switch(gameMode){
            case MODE_KILLER:
            case MODE_BLACKOUT:
                titulo=endKillerWon?"EL ASESINO GANÓ":"SOBREVIVIENTES GANAN";
                iWon=endKillerWon==amKiller;
                sub=endKillerWon
                    ?(amKiller?"¡Eliminaste a todos!":"No lograste escapar...")
                    :(amKiller?"No pudiste con todos...":"¡Sobreviviste!");
                color=endKillerWon?Color.rgb(220,40,40):Color.rgb(50,220,80);
                break;
            case MODE_INFECTION:
                titulo=endKillerWon?"INFECCIÓN TOTAL":"LOS SANOS GANAN";
                iWon=endKillerWon==amInfected;
                sub=amInfected?"Infectaste a todos":"Resististe la infección";
                color=endKillerWon?Color.rgb(120,200,50):Color.rgb(80,180,180);
                break;
            case MODE_FFA:
            case MODE_SHRINK:
                titulo="PARTIDA TERMINADA";
                iWon=myHp>0;
                sub=iWon?"¡Sobreviviste!":"Fuiste eliminado";
                color=iWon?Color.rgb(220,180,50):Color.rgb(150,150,150);
                break;
            case MODE_DETECTIVE:
                titulo=endKillerWon?"EL ASESINO ESCAPÓ":"DETECTIVE GANA";
                iWon=endKillerWon==amKiller||(!endKillerWon&&amDetective);
                sub=amDetective?(endKillerWon?"No lograste atraparlo":"¡Atrapaste al asesino!"):
                    amKiller?(endKillerWon?"Escapaste":"Te atraparon"):
                    "Sobreviviste";
                color=endKillerWon?Color.rgb(220,40,40):Color.rgb(80,150,220);
                break;
            default:
                titulo="FIN"; sub=""; color=Color.WHITE; iWon=false;
        }

        p.setColor(Color.argb(iWon?60:40,
            iWon?0:150, iWon?100:0, 0));
        c.drawRect(0,0,RW,RH,p);

        p.setColor(color);
        p.setTextSize(RH*0.09f);
        p.setFakeBoldText(true);
        c.drawText(titulo,RW/2f,RH*0.30f,p);
        p.setFakeBoldText(false);
        p.setTextSize(RH*0.050f);
        p.setColor(Color.argb(200,200,200,200));
        c.drawText(sub,RW/2f,RH*0.48f,p);

        // Tu puntuación del minijuego
        if(minigameScore>0){
            p.setColor(Color.argb(120,150,150,150));
            p.setTextSize(RH*0.038f);
            c.drawText("Reflejos en espera: "+minigameScore+" pts",
                RW/2f,RH*0.64f,p);
        }

        p.setColor(Color.rgb(60,60,60));
        p.setTextSize(RH*0.036f);
        c.drawText("Nueva partida en breve...",RW/2f,RH*0.80f,p);
        flushFrame();
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
        GLES20.glShaderSource(vs,v); GLES20.glCompileShader(vs);
        int fs=GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER);
        GLES20.glShaderSource(fs,f); GLES20.glCompileShader(fs);
        int pg=GLES20.glCreateProgram();
        GLES20.glAttachShader(pg,vs); GLES20.glAttachShader(pg,fs);
        GLES20.glLinkProgram(pg); return pg;
    }
}
