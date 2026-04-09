package com.game.net;

import android.util.Log;
import com.google.gson.*;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import java.net.URI;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class NetClient {
    public static final String SERVER="ws://200.58.126.204:7777";
    private static final String TAG="NetClient";

    public static class RemotePlayer {
        public int id;
        public String name;
        public float x,y,angle;
        public int hp;
        public boolean spectator;
        public boolean alive;
        public int team=-1;
    }

    public static class EndResult {
        public String name;
        public boolean won;
        public int kills;
        public boolean wasKiller;
    }

    public interface Listener {
        void onConnected();
        void onJoined(int myId, boolean isHost, String roomId);
        void onPlayerJoined(int id, String name, int total);
        void onPlayerLeft(int id, String name, int total);
        void onYouAreHost();
        void onGameStart(int mode, int team,
            boolean isKiller, float spawnX, float spawnY,
            int duration, long mapSeed);
        void onState(List<RemotePlayer> players, int timer);
        void onHit(int hp, String killerName);
        void onPlayerDied(int id, String killerName);
        void onGameEnd(boolean killerWon, List<EndResult> results);
        void onReadyUpdate(int ready, int total, int[] votes);
        void onOtherPlayerReady(int playerId, int mode);
        void onRemotePlayerUpdated(int id, float x, float y,
            float angle, int hp, boolean alive);
        void onReadyReset();
        void onRoomBusy(int players, int mode);
        void onPong(int ms);
        void onDisconnected();
    }

    private final Gson gson=new Gson();
    private final Listener listener;
    private final String playerName;
    private WebSocketClient ws;

    public volatile int myId=-1;
    public volatile boolean isHost=false;
    public volatile float spawnX=72f,spawnY=72f;
    public volatile long lastPingSent=0;
    public volatile int lastTimer=0;
    public final CopyOnWriteArrayList<RemotePlayer>
        remotePlayers=new CopyOnWriteArrayList<>();

    public NetClient(String name,Listener listener){
        this.playerName=name; this.listener=listener;
    }

    public void connect(String roomId){
        if(ws!=null){try{ws.close();}catch(Exception ignored){}ws=null;}
        remotePlayers.clear();
        try{
            ws=new WebSocketClient(new URI(SERVER)){
                @Override public void onOpen(ServerHandshake h){
                    JsonObject join=new JsonObject();
                    join.addProperty("type","join");
                    join.addProperty("room_id",roomId);
                    join.addProperty("name",playerName);
                    send(gson.toJson(join));
                    listener.onConnected();
                }
                @Override public void onMessage(String m){handleMessage(m);}
                @Override public void onClose(int c,String r,boolean remote){
                    listener.onDisconnected();
                }
                @Override public void onError(Exception e){
                    Log.e(TAG,"WS: "+e.getMessage());
                }
            };
            ws.setConnectionLostTimeout(10);
            ws.connect();
        }catch(Exception e){Log.e(TAG,"Connect: "+e.getMessage());}
    }

    private void handleMessage(String raw){
        try{
            JsonObject msg=gson.fromJson(raw,JsonObject.class);
            String type=msg.get("type").getAsString();
            switch(type){
                case "joined":
                    myId=msg.get("player_id").getAsInt();
                    isHost=msg.get("is_host").getAsBoolean();
                    listener.onJoined(myId,isHost,
                        msg.get("room_id").getAsString());
                    break;
                case "player_joined":
                    listener.onPlayerJoined(
                        msg.get("player_id").getAsInt(),
                        msg.get("name").getAsString(),
                        msg.get("total").getAsInt());
                    break;
                case "player_left":
                    listener.onPlayerLeft(
                        msg.get("player_id").getAsInt(),
                        msg.get("name").getAsString(),
                        msg.get("total").getAsInt());
                    break;
                case "you_are_host":
                    isHost=true;
                    listener.onYouAreHost();
                    break;
                case "game_start":
                    spawnX=msg.has("spawn_x")?msg.get("spawn_x").getAsFloat():72f;
                    spawnY=msg.has("spawn_y")?msg.get("spawn_y").getAsFloat():72f;
                    listener.onGameStart(
                        msg.has("mode")?msg.get("mode").getAsInt():0,
                        msg.has("team")?msg.get("team").getAsInt():-1,
                        msg.has("is_killer")&&msg.get("is_killer").getAsBoolean(),
                        spawnX,spawnY,
                        msg.has("duration")?msg.get("duration").getAsInt():180,
                        msg.has("map_seed")?msg.get("map_seed").getAsLong():0L);
                    break;
                case "game_init":{
                    long seed=msg.has("map_seed")?
                        msg.get("map_seed").getAsLong():0L;
                    int mode=msg.has("mode")?msg.get("mode").getAsInt():0;
                    int dur=msg.has("duration")?msg.get("duration").getAsInt():180;
                    if(msg.has("assignments")){
                        for(JsonElement el:msg.get("assignments").getAsJsonArray()){
                            JsonObject a=el.getAsJsonObject();
                            if(a.get("player_id").getAsInt()==myId){
                                float sx=a.get("spawn_x").getAsFloat();
                                float sy=a.get("spawn_y").getAsFloat();
                                boolean killer=a.has("is_killer")&&
                                    a.get("is_killer").getAsBoolean();
                                int team=a.has("team")?a.get("team").getAsInt():-1;
                                spawnX=sx; spawnY=sy;
                                listener.onGameStart(mode,team,killer,
                                    sx,sy,dur,seed);
                                break;
                            }
                        }
                    }
                    break;
                }
                case "state":{
                    if(msg.has("timer")) lastTimer=msg.get("timer").getAsInt();
                    JsonArray arr=msg.get("players").getAsJsonArray();
                    List<RemotePlayer> list=new ArrayList<>();
                    for(JsonElement el:arr){
                        JsonObject p=el.getAsJsonObject();
                        RemotePlayer rp=new RemotePlayer();
                        rp.id   =p.get("id").getAsInt();
                        rp.name =p.get("name").getAsString();
                        rp.x    =p.get("x").getAsFloat();
                        rp.y    =p.get("y").getAsFloat();
                        rp.angle=p.get("angle").getAsFloat();
                        rp.hp   =p.get("hp").getAsInt();
                        rp.spectator=p.has("spec")&&p.get("spec").getAsBoolean();
                        rp.alive=p.has("alive")&&p.get("alive").getAsBoolean();
                        rp.team =p.has("team")?p.get("team").getAsInt():-1;
                        list.add(rp);
                    }
                    remotePlayers.clear();
                    remotePlayers.addAll(list);
                    listener.onState(list,lastTimer);
                    break;
                }
                case "hit":
                    listener.onHit(
                        msg.get("hp").getAsInt(),
                        msg.has("killer_name")?msg.get("killer_name").getAsString():"");
                    break;
                case "player_died":
                    listener.onPlayerDied(
                        msg.get("id").getAsInt(),
                        msg.has("killer_name")?msg.get("killer_name").getAsString():"");
                    break;
                case "game_end":{
                    boolean kw=msg.get("killer_won").getAsBoolean();
                    List<EndResult> results=new ArrayList<>();
                    if(msg.has("results")){
                        for(JsonElement el:msg.get("results").getAsJsonArray()){
                            JsonObject r=el.getAsJsonObject();
                            EndResult er=new EndResult();
                            er.name=r.get("name").getAsString();
                            er.won=r.get("won").getAsBoolean();
                            er.kills=r.has("kills")?r.get("kills").getAsInt():0;
                            er.wasKiller=r.has("killer")&&r.get("killer").getAsBoolean();
                            results.add(er);
                        }
                    }
                    listener.onGameEnd(kw,results);
                    break;
                }
                case "ready":{
                    int fromId=msg.has("from_id")?
                        msg.get("from_id").getAsInt():-1;
                    if(fromId!=myId && fromId!=-1){
                        int vm=msg.has("voted_mode")?
                            msg.get("voted_mode").getAsInt():0;
                        listener.onOtherPlayerReady(fromId,vm);
                    }
                    break;
                }
                case "state_update":{
                    int fromId=msg.has("from_id")?
                        msg.get("from_id").getAsInt():-1;
                    float x=msg.has("x")?msg.get("x").getAsFloat():0;
                    float y=msg.has("y")?msg.get("y").getAsFloat():0;
                    float angle=msg.has("angle")?
                        msg.get("angle").getAsFloat():0;
                    int hp=msg.has("hp")?msg.get("hp").getAsInt():100;
                    boolean alive=!msg.has("alive")||
                        msg.get("alive").getAsBoolean();
                    for(RemotePlayer rp:remotePlayers){
                        if(rp.id==fromId){
                            rp.x=x; rp.y=y; rp.angle=angle;
                            rp.hp=hp; rp.alive=alive;
                            break;
                        }
                    }
                    listener.onRemotePlayerUpdated(fromId,
                        x,y,angle,hp,alive);
                    break;
                }
                case "ready_update":{
                    int[] votes=new int[3];
                    if(msg.has("votes")){
                        JsonArray va=msg.get("votes").getAsJsonArray();
                        for(int i=0;i<Math.min(3,va.size());i++)
                            votes[i]=va.get(i).getAsInt();
                    }
                    listener.onReadyUpdate(
                        msg.get("ready").getAsInt(),
                        msg.get("total").getAsInt(),votes);
                    break;
                }
                case "ready_reset": listener.onReadyReset(); break;
                case "room_busy":
                    listener.onRoomBusy(
                        msg.get("players").getAsInt(),
                        msg.get("mode").getAsInt());
                    break;
                case "pong":
                    listener.onPong((int)(System.currentTimeMillis()-lastPingSent));
                    break;
            }
        }catch(Exception e){Log.w(TAG,"Parse: "+e.getMessage());}
    }

    public void send(JsonObject msg){
        if(ws==null||!ws.isOpen()) return;
        ws.send(gson.toJson(msg));
    }

    public void sendInput(float x,float y,float angle,int hp,boolean alive){
        JsonObject o=new JsonObject();
        o.addProperty("type","state_update");
        o.addProperty("x",x); o.addProperty("y",y);
        o.addProperty("angle",angle);
        o.addProperty("hp",hp);
        o.addProperty("alive",alive);
        send(o);
    }

    public void sendAttack(String targetContext){
        JsonObject o=new JsonObject();
        o.addProperty("type","attack");
        o.addProperty("ctx",targetContext);
        send(o);
    }

    public void sendReady(int votedMode){
        JsonObject o=new JsonObject();
        o.addProperty("type","ready");
        o.addProperty("voted_mode",votedMode);
        send(o);
    }

    public void sendPing(){
        if(!isOpen()) return;
        lastPingSent=System.currentTimeMillis();
        JsonObject o=new JsonObject();
        o.addProperty("type","ping");
        send(o);
    }

    public void hostBroadcast(JsonObject msg){
        if(!isOpen()||!isHost) return;
        msg.addProperty("type","host_broadcast");
        ws.send(gson.toJson(msg));
    }

    public boolean isOpen(){return ws!=null&&ws.isOpen();}

    public void disconnect(){
        if(ws!=null){try{ws.close();}catch(Exception ignored){}ws=null;}
    }
}
