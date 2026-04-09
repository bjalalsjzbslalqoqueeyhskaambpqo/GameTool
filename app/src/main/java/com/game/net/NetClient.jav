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
    private static final String TAG = "NetClient";

    public static class RemotePlayer {
        public int id;
        public String name;
        public float x, y, angle;
        public int hp;
        public boolean spectator;
        public boolean alive;
        public boolean ready;
        public int votedMode;
    }

    public interface Listener {
        void onConnected();
        void onJoined(int myId, long seed,
            boolean spectator, int mode);
        void onRoomInfo(int count, int min,
            boolean started);
        void onReadyUpdate(int ready, int total,
            int[] modeVotes);
        void onGameStart(boolean isKiller,
            boolean isInfected, boolean isDetective,
            int mode, int duration);
        void onHit(int hp);
        void onPlayerDied(int id, String killerName);
        void onGameEnd(boolean killerWon,
            List<EndResult> results);
        void onBlackout(boolean active);
        void onDetectorPing(float dist);
        void onPong(int pingMs);
        void onRoomBusy(int players, int mode);
        void onReadyReset();
        void onDisconnected();
    }

    public static class EndResult {
        public String name;
        public boolean won;
        public int kills;
        public boolean wasKiller;
    }

    private final Gson gson = new Gson();
    private final Listener listener;
    private final String playerName;
    private WebSocketClient ws;

    public volatile int myId = -1;
    public volatile boolean spectator = false;
    public volatile int lastTimer = 0;
    public volatile boolean blackoutActive = false;
    public volatile float spawnX = 72f;
    public volatile float spawnY = 72f;
    public volatile long lastPingSent = 0;
    public final CopyOnWriteArrayList<RemotePlayer>
        remotePlayers = new CopyOnWriteArrayList<>();

    public NetClient(String name, Listener listener) {
        this.playerName = name;
        this.listener   = listener;
    }

    public void connect(String roomId) {
        if (ws != null) {
            try { ws.close(); } catch (Exception ignored) {}
            ws = null;
        }
        try {
            ws = new WebSocketClient(new URI(SERVER)) {
                @Override
                public void onOpen(ServerHandshake h) {
                    JsonObject join = new JsonObject();
                    join.addProperty("type",    "join");
                    join.addProperty("room_id", roomId);
                    join.addProperty("name",    playerName);
                    send(gson.toJson(join));
                    listener.onConnected();
                }
                @Override public void onMessage(String m){
                    handleMessage(m);
                }
                @Override public void onClose(int c,
                        String r, boolean remote){
                    listener.onDisconnected();
                }
                @Override public void onError(Exception e){
                    Log.e(TAG,"WS: "+e.getMessage());
                }
            };
            ws.setConnectionLostTimeout(10);
            ws.connect();
        } catch (Exception e) {
            Log.e(TAG,"Connect: "+e.getMessage());
        }
    }

    private void handleMessage(String raw) {
        try {
            JsonObject msg = gson.fromJson(raw,
                JsonObject.class);
            String type = msg.get("type").getAsString();
            switch (type) {
                case "joined":
                    myId = msg.get("player_id").getAsInt();
                    spectator = msg.get("spectator")
                        .getAsBoolean();
                    listener.onJoined(myId,
                        msg.get("map_seed").getAsLong(),
                        spectator,
                        msg.has("mode") ?
                            msg.get("mode").getAsInt():0);
                    break;
                case "room_info":
                    listener.onRoomInfo(
                        msg.get("players").getAsInt(),
                        msg.get("min_players").getAsInt(),
                        msg.get("game_started").getAsBoolean());
                    break;
                case "ready_update": {
                    int ready = msg.get("ready").getAsInt();
                    int total = msg.get("total").getAsInt();
                    int[] votes = new int[6];
                    if (msg.has("votes")) {
                        JsonArray va =
                            msg.get("votes").getAsJsonArray();
                        for (int i=0;i<Math.min(6,
                                va.size());i++)
                            votes[i] = va.get(i).getAsInt();
                    }
                    listener.onReadyUpdate(ready,total,votes);
                    break;
                }
                case "game_start":
                    if(msg.has("spawn_x"))
                        spawnX = msg.get("spawn_x").getAsFloat();
                    if(msg.has("spawn_y"))
                        spawnY = msg.get("spawn_y").getAsFloat();
                    listener.onGameStart(
                        msg.has("is_killer") &&
                            msg.get("is_killer").getAsBoolean(),
                        msg.has("is_infected") &&
                            msg.get("is_infected").getAsBoolean(),
                        msg.has("is_detective") &&
                            msg.get("is_detective").getAsBoolean(),
                        msg.has("mode") ?
                            msg.get("mode").getAsInt():0,
                        msg.has("duration") ?
                            msg.get("duration").getAsInt():180);
                    break;
                case "hit":
                    listener.onHit(
                        msg.get("hp").getAsInt());
                    break;
                case "player_died":
                    listener.onPlayerDied(
                        msg.get("id").getAsInt(),
                        msg.has("killer_name") ?
                            msg.get("killer_name")
                            .getAsString():"?");
                    break;
                case "game_end": {
                    boolean kw = msg.get("killer_won")
                        .getAsBoolean();
                    List<EndResult> results =
                        new ArrayList<>();
                    if (msg.has("results")) {
                        JsonArray ra =
                            msg.get("results")
                            .getAsJsonArray();
                        for (JsonElement el : ra) {
                            JsonObject r =
                                el.getAsJsonObject();
                            EndResult er =
                                new EndResult();
                            er.name = r.get("name")
                                .getAsString();
                            er.won  = r.get("won")
                                .getAsBoolean();
                            er.kills= r.has("kills") ?
                                r.get("kills").getAsInt():0;
                            er.wasKiller = r.has("killer")
                                && r.get("killer")
                                .getAsBoolean();
                            results.add(er);
                        }
                    }
                    listener.onGameEnd(kw, results);
                    break;
                }
                case "blackout":
                    boolean on = msg.get("active")
                        .getAsBoolean();
                    blackoutActive = on;
                    listener.onBlackout(on);
                    break;
                case "detector_ping":
                    listener.onDetectorPing(
                        msg.get("dist").getAsFloat());
                    break;
                case "pong": {
                    long now = System.currentTimeMillis();
                    long sent = lastPingSent;
                    listener.onPong((int)(now - sent));
                    break;
                }
                case "room_busy":
                    listener.onRoomBusy(
                        msg.get("players").getAsInt(),
                        msg.get("mode").getAsInt());
                    break;
                case "ready_reset":
                    listener.onReadyReset();
                    break;
                case "state":
                    if (msg.has("timer"))
                        lastTimer =
                            msg.get("timer").getAsInt();
                    JsonArray arr =
                        msg.get("players").getAsJsonArray();
                    List<RemotePlayer> list =
                        new ArrayList<>();
                    for (JsonElement el : arr) {
                        JsonObject p =
                            el.getAsJsonObject();
                        RemotePlayer rp =
                            new RemotePlayer();
                        rp.id    = p.get("id").getAsInt();
                        rp.name  = p.get("name")
                            .getAsString();
                        rp.x     = p.get("x").getAsFloat();
                        rp.y     = p.get("y").getAsFloat();
                        rp.angle = p.get("angle")
                            .getAsFloat();
                        rp.hp    = p.get("hp").getAsInt();
                        rp.spectator = p.get("spec")
                            .getAsBoolean();
                        rp.alive = p.has("alive") &&
                            p.get("alive").getAsBoolean();
                        rp.ready = p.has("ready") &&
                            p.get("ready").getAsBoolean();
                        rp.votedMode = p.has("voted_mode") ?
                            p.get("voted_mode").getAsInt():-1;
                        list.add(rp);
                    }
                    remotePlayers.clear();
                    remotePlayers.addAll(list);
                    break;
            }
        } catch (Exception e) {
            Log.w(TAG,"Parse: "+e.getMessage());
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
        o.addProperty("type","attack");
        ws.send(gson.toJson(o));
    }

    public void sendReady(int votedMode) {
        if (ws==null||!ws.isOpen()) return;
        JsonObject o = new JsonObject();
        o.addProperty("type","ready");
        o.addProperty("voted_mode", votedMode);
        ws.send(gson.toJson(o));
    }

    public void sendPing(){
        if(!isOpen()) return;
        lastPingSent = System.currentTimeMillis();
        JsonObject o = new JsonObject();
        o.addProperty("type","ping");
        ws.send(gson.toJson(o));
    }

    public boolean isOpen() {
        return ws!=null && ws.isOpen();
    }

    public void disconnect() {
        if (ws!=null) {
            try { ws.close(); }
            catch (Exception ignored) {}
            ws = null;
        }
    }
}
