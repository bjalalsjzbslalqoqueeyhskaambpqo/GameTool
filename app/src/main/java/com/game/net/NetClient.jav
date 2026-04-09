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
    public static final String ROOM   = "0000";
    private static final String TAG   = "NetClient";

    public static class RemotePlayer {
        public int id;
        public String name;
        public float x, y, angle;
        public int hp;
        public boolean spectator;
    }

    public interface Listener {
        void onConnected();
        void onJoined(int myId, long seed, boolean spectator);
        void onRoomInfo(int count, int min, boolean started);
        void onGameStart();
        void onState(List<RemotePlayer> players);
        void onDisconnected();
    }

    private final Gson gson = new Gson();
    private final Listener listener;
    private final String playerName;
    private WebSocketClient ws;

    public volatile int myId = -1;
    public volatile boolean spectator = false;
    public final CopyOnWriteArrayList<RemotePlayer>
        remotePlayers = new CopyOnWriteArrayList<>();

    public NetClient(String name, Listener listener) {
        this.playerName = name;
        this.listener   = listener;
    }

    public void connect() {
        if (ws != null) {
            try { ws.close(); } catch (Exception ignored) {}
        }
        try {
            ws = new WebSocketClient(new URI(SERVER)) {
                @Override public void onOpen(ServerHandshake h) {
                    JsonObject join = new JsonObject();
                    join.addProperty("type",    "join");
                    join.addProperty("room_id", ROOM);
                    join.addProperty("name",    playerName);
                    send(gson.toJson(join));
                    listener.onConnected();
                }
                @Override public void onMessage(String msg) {
                    handleMessage(msg);
                }
                @Override public void onClose(int c, String r, boolean remote) {
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
                    myId      = msg.get("player_id").getAsInt();
                    spectator = msg.get("spectator").getAsBoolean();
                    listener.onJoined(myId,
                        msg.get("map_seed").getAsLong(), spectator);
                    break;
                case "room_info":
                    listener.onRoomInfo(
                        msg.get("players").getAsInt(),
                        msg.get("min_players").getAsInt(),
                        msg.get("game_started").getAsBoolean());
                    break;
                case "game_start":
                    listener.onGameStart();
                    break;
                case "state":
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
                        list.add(rp);
                    }
                    remotePlayers.clear();
                    remotePlayers.addAll(list);
                    listener.onState(list);
                    break;
            }
        } catch (Exception e) {
            Log.w(TAG, "Parse error: " + e.getMessage());
        }
    }

    public void sendInput(float dx, float dy, float angle) {
        if (ws == null || !ws.isOpen() || spectator) return;
        JsonObject o = new JsonObject();
        o.addProperty("type",  "input");
        o.addProperty("dx",    dx);
        o.addProperty("dy",    dy);
        o.addProperty("angle", angle);
        ws.send(gson.toJson(o));
    }

    public void sendAttack() {
        if (ws == null || !ws.isOpen() || spectator) return;
        JsonObject o = new JsonObject();
        o.addProperty("type", "attack");
        ws.send(gson.toJson(o));
    }

    public boolean isOpen() {
        return ws != null && ws.isOpen();
    }

    public void disconnect() {
        if (ws != null) {
            try { ws.close(); } catch (Exception ignored) {}
            ws = null;
        }
    }
}
