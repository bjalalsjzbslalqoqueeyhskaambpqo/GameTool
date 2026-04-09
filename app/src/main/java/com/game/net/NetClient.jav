package com.game.net;

import android.util.Log;
import com.google.gson.*;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import java.net.URI;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class NetClient {
    public static final String SERVER = "ws://200.58.126.204:7777";
    public static final String ROOM_0 = "0000";
    public static final String ROOM_1 = "0001";
    public static final String ROOM_2 = "0002";
    public static final String ROOM_3 = "0003";
    public static final String ROOM_4 = "0004";
    public static final String ROOM_5 = "0005";
    private static final String TAG = "NetClient";

    public static class RemotePlayer {
        public int id;
        public String name;
        public float x, y, angle;
        public int hp;
        public boolean spectator;
        public boolean alive;
    }

    public interface Listener {
        void onConnected();
        void onJoined(int myId, long seed, boolean spectator, int mode);
        void onRoomInfo(int count, int min, boolean started);
        void onGameStart(boolean isKiller, boolean isInfected,
            boolean isDetective, int mode, int duration);
        void onHit(int hp);
        void onPlayerDied(int id);
        void onGameEnd(boolean killerWon);
        void onBlackout(boolean active);
        void onDetectorPing(float dist);
        void onReadyUpdate(int ready, int total);
        void onDisconnected();
    }

    private final Gson gson = new Gson();
    private final Listener listener;
    private final String playerName;
    private WebSocketClient ws;

    public volatile int myId = -1;
    public volatile boolean spectator = false;
    public volatile int lastTimer = 0;
    public volatile boolean blackoutActive = false;
    public final CopyOnWriteArrayList<RemotePlayer>
        remotePlayers = new CopyOnWriteArrayList<>();

    public NetClient(String name, Listener listener) {
        this.playerName = name;
        this.listener   = listener;
    }

    public void connect(String roomId) {
        if (ws != null) {
            try { ws.close(); } catch (Exception ignored) {}
        }
        try {
            ws = new WebSocketClient(new URI(SERVER)) {
                @Override public void onOpen(ServerHandshake h) {
                    JsonObject join = new JsonObject();
                    join.addProperty("type",    "join");
                    join.addProperty("room_id", roomId);
                    join.addProperty("name",    playerName);
                    send(gson.toJson(join));
                    listener.onConnected();
                }
                @Override public void onMessage(String msg) {
                    handleMessage(msg);
                }
                @Override public void onClose(int c,String r,boolean remote){
                    listener.onDisconnected();
                }
                @Override public void onError(Exception e) {
                    Log.e(TAG, "WS error: " + e.getMessage());
                }
            };
            ws.setConnectionLostTimeout(10);
            ws.connect();
        } catch (Exception e) {
            Log.e(TAG, "Connect failed: " + e.getMessage());
        }
    }

    private void handleMessage(String raw) {
        try {
            JsonObject msg = gson.fromJson(raw, JsonObject.class);
            String type = msg.get("type").getAsString();
            switch (type) {
                case "joined":
                    myId = msg.get("player_id").getAsInt();
                    spectator = msg.get("spectator").getAsBoolean();
                    int mode = msg.has("mode") ?
                        msg.get("mode").getAsInt() : 0;
                    listener.onJoined(myId,
                        msg.get("map_seed").getAsLong(),
                        spectator, mode);
                    break;
                case "room_info":
                    listener.onRoomInfo(
                        msg.get("players").getAsInt(),
                        msg.get("min_players").getAsInt(),
                        msg.get("game_started").getAsBoolean());
                    break;
                case "game_start":
                    listener.onGameStart(
                        msg.has("is_killer") &&
                            msg.get("is_killer").getAsBoolean(),
                        msg.has("is_infected") &&
                            msg.get("is_infected").getAsBoolean(),
                        msg.has("is_detective") &&
                            msg.get("is_detective").getAsBoolean(),
                        msg.has("mode") ?
                            msg.get("mode").getAsInt() : 0,
                        msg.has("duration") ?
                            msg.get("duration").getAsInt() : 180);
                    break;
                case "hit":
                    listener.onHit(msg.get("hp").getAsInt());
                    break;
                case "player_died":
                    listener.onPlayerDied(msg.get("id").getAsInt());
                    break;
                case "game_end":
                    listener.onGameEnd(
                        msg.get("killer_won").getAsBoolean());
                    break;
                case "blackout":
                    boolean on = msg.get("active").getAsBoolean();
                    blackoutActive = on;
                    listener.onBlackout(on);
                    break;
                case "detector_ping":
                    float dist = msg.get("dist").getAsFloat();
                    listener.onDetectorPing(dist);
                    break;
                case "ready_update":
                    int ready = msg.get("ready").getAsInt();
                    int total = msg.get("total").getAsInt();
                    listener.onReadyUpdate(ready, total);
                    break;
                case "state":
                    if (msg.has("timer"))
                        lastTimer = msg.get("timer").getAsInt();
                    JsonArray arr = msg.get("players").getAsJsonArray();
                    List<RemotePlayer> list = new ArrayList<>();
                    for (JsonElement el : arr) {
                        JsonObject p = el.getAsJsonObject();
                        RemotePlayer rp = new RemotePlayer();
                        rp.id       = p.get("id").getAsInt();
                        rp.name     = p.get("name").getAsString();
                        rp.x        = p.get("x").getAsFloat();
                        rp.y        = p.get("y").getAsFloat();
                        rp.angle    = p.get("angle").getAsFloat();
                        rp.hp       = p.get("hp").getAsInt();
                        rp.spectator= p.get("spec").getAsBoolean();
                        rp.alive    = p.has("alive") &&
                            p.get("alive").getAsBoolean();
                        list.add(rp);
                    }
                    remotePlayers.clear();
                    remotePlayers.addAll(list);
                    break;
            }
        } catch (Exception e) {
            Log.w(TAG, "Parse error: " + e.getMessage());
        }
    }

    public void sendInput(float x, float y, float angle) {
        if (ws==null||!ws.isOpen()||spectator) return;
        JsonObject o = new JsonObject();
        o.addProperty("type",  "input");
        o.addProperty("x",     x);
        o.addProperty("y",     y);
        o.addProperty("angle", angle);
        ws.send(gson.toJson(o));
    }

    public void sendAttack() {
        if (ws==null||!ws.isOpen()||spectator) return;
        JsonObject o = new JsonObject();
        o.addProperty("type", "attack");
        ws.send(gson.toJson(o));
    }

    public void sendReady(){
        if(!isOpen()) return;
        JsonObject o = new JsonObject();
        o.addProperty("type","ready");
        ws.send(gson.toJson(o));
    }

    public boolean isOpen() {
        return ws!=null && ws.isOpen();
    }

    public void disconnect() {
        if (ws!=null) {
            try { ws.close(); } catch (Exception ignored) {}
            ws = null;
        }
    }
}
