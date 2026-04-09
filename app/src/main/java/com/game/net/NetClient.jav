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
        public int id; public String name;
        public float x,y,angle; public int hp;
        public boolean alive,spectator;
        public int team=-1; public boolean isKiller;
    }

    public static class EndResult {
        public String name; public boolean won;
        public int kills; public boolean wasKiller;
    }

    public interface Listener {
        void onConnected();
        void onJoined(int myId,long seed,boolean spec,int mode);
        void onRoomInfo(int total,boolean started);
        void onPlayerJoined(int id,String name,int total);
        void onReadyUpdate(int ready,int total,int[] votes);
        void onReadyReset();
        void onGameStart(int mode,boolean killer,int team,
            float spawnX,float spawnY,int duration,long seed);
        void onState(List<RemotePlayer> players,int timer);
        void onHit(int targetId,int hp);
        void onPlayerDied(int id,String killerName);
        void onGameEnd(boolean kw,List<EndResult> results);
        void onRoomBusy(int players,int mode);
        void onPong(int ms);
        void onDisconnected();
    }

    private final Gson gson=new Gson();
    private final Listener listener;
    private final String name;
    private WebSocketClient ws;

    public volatile int myId=-1;
    public volatile int lastTimer=0;
    public volatile long lastPingSent=0;
    public final CopyOnWriteArrayList<RemotePlayer>
        remotePlayers=new CopyOnWriteArrayList<>();

    public NetClient(String name,Listener l){
        this.name=name; this.listener=l;
    }

    public void connect(String roomId){
        if(ws!=null){try{ws.close();}catch(Exception e){} ws=null;}
        remotePlayers.clear();
        try{
            ws=new WebSocketClient(new URI(SERVER)){
                @Override public void onOpen(ServerHandshake h){
                    JsonObject j=new JsonObject();
                    j.addProperty("type","join");
                    j.addProperty("room_id",roomId);
                    j.addProperty("name",name);
                    send(gson.toJson(j));
                    listener.onConnected();
                }
                @Override public void onMessage(String m){
                    handle(m);
                }
                @Override public void onClose(int c,String r,boolean x){
                    listener.onDisconnected();
                }
                @Override public void onError(Exception e){
                    Log.e(TAG,e.getMessage());
                }
            };
            ws.setConnectionLostTimeout(10);
            ws.connect();
        }catch(Exception e){Log.e(TAG,e.getMessage());}
    }

    private void handle(String raw){
        try{
            JsonObject m=gson.fromJson(raw,JsonObject.class);
            switch(m.get("type").getAsString()){
                case "joined":
                    myId=m.get("player_id").getAsInt();
                    listener.onJoined(myId,
                        m.get("map_seed").getAsLong(),
                        m.get("spectator").getAsBoolean(),
                        m.has("mode")?m.get("mode").getAsInt():0);
                    break;
                case "room_info":
                    listener.onRoomInfo(
                        m.get("players").getAsInt(),
                        m.get("started").getAsBoolean());
                    break;
                case "player_joined":
                    listener.onPlayerJoined(
                        m.get("player_id").getAsInt(),
                        m.get("name").getAsString(),
                        m.get("total").getAsInt());
                    break;
                case "ready_update":{
                    int[] votes=new int[3];
                    if(m.has("votes")){
                        JsonArray va=m.get("votes").getAsJsonArray();
                        for(int i=0;i<Math.min(3,va.size());i++)
                            votes[i]=va.get(i).getAsInt();
                    }
                    listener.onReadyUpdate(
                        m.get("ready").getAsInt(),
                        m.get("total").getAsInt(),votes);
                    break;
                }
                case "ready_reset":
                    listener.onReadyReset(); break;
                case "game_start":
                    listener.onGameStart(
                        m.has("mode")?m.get("mode").getAsInt():0,
                        m.has("is_killer")&&m.get("is_killer").getAsBoolean(),
                        m.has("team")?m.get("team").getAsInt():-1,
                        m.has("spawn_x")?m.get("spawn_x").getAsFloat():72f,
                        m.has("spawn_y")?m.get("spawn_y").getAsFloat():72f,
                        m.has("duration")?m.get("duration").getAsInt():180,
                        m.has("map_seed")?m.get("map_seed").getAsLong():0L);
                    break;
                case "state":{
                    lastTimer=m.has("timer")?m.get("timer").getAsInt():0;
                    JsonArray arr=m.get("players").getAsJsonArray();
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
                        rp.alive=p.has("alive")&&p.get("alive").getAsBoolean();
                        rp.spectator=p.has("spec")&&p.get("spec").getAsBoolean();
                        rp.team =p.has("team")?p.get("team").getAsInt():-1;
                        rp.isKiller=p.has("killer")&&p.get("killer").getAsBoolean();
                        list.add(rp);
                    }
                    remotePlayers.clear();
                    remotePlayers.addAll(list);
                    listener.onState(list,lastTimer);
                    break;
                }
                case "hit":
                    listener.onHit(
                        m.get("target_id").getAsInt(),
                        m.get("hp").getAsInt());
                    break;
                case "player_died":
                    listener.onPlayerDied(
                        m.get("id").getAsInt(),
                        m.has("killer_name")?
                            m.get("killer_name").getAsString():"");
                    break;
                case "game_end":{
                    boolean kw=m.get("killer_won").getAsBoolean();
                    List<EndResult> res=new ArrayList<>();
                    if(m.has("results")){
                        for(JsonElement el:
                                m.get("results").getAsJsonArray()){
                            JsonObject r=el.getAsJsonObject();
                            EndResult er=new EndResult();
                            er.name=r.get("name").getAsString();
                            er.won=r.get("won").getAsBoolean();
                            er.kills=r.has("kills")?
                                r.get("kills").getAsInt():0;
                            er.wasKiller=r.has("killer")&&
                                r.get("killer").getAsBoolean();
                            res.add(er);
                        }
                    }
                    listener.onGameEnd(kw,res);
                    break;
                }
                case "room_busy":
                    listener.onRoomBusy(
                        m.get("players").getAsInt(),
                        m.get("mode").getAsInt());
                    break;
                case "pong":
                    listener.onPong((int)(
                        System.currentTimeMillis()-lastPingSent));
                    break;
            }
        }catch(Exception e){Log.w(TAG,"Parse: "+e.getMessage());}
    }

    public void sendMove(float x,float y,float angle){
        if(!isOpen()) return;
        JsonObject o=new JsonObject();
        o.addProperty("type","move");
        o.addProperty("x",x);
        o.addProperty("y",y);
        o.addProperty("angle",angle);
        ws.send(gson.toJson(o));
    }

    public void sendHit(int targetId,int dmg){
        if(!isOpen()) return;
        JsonObject o=new JsonObject();
        o.addProperty("type","hit");
        o.addProperty("target_id",targetId);
        o.addProperty("dmg",dmg);
        ws.send(gson.toJson(o));
    }

    public void sendReady(int votedMode){
        if(!isOpen()) return;
        JsonObject o=new JsonObject();
        o.addProperty("type","ready");
        o.addProperty("voted_mode",votedMode);
        ws.send(gson.toJson(o));
    }

    public void sendPing(){
        if(!isOpen()) return;
        lastPingSent=System.currentTimeMillis();
        JsonObject o=new JsonObject();
        o.addProperty("type","ping");
        ws.send(gson.toJson(o));
    }

    public boolean isOpen(){return ws!=null&&ws.isOpen();}

    public void disconnect(){
        if(ws!=null){try{ws.close();}catch(Exception e){} ws=null;}
    }
}
