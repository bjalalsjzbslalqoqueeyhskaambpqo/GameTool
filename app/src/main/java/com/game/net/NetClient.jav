package com.game.net;

import android.util.Log;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.URISyntaxException;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

public class NetClient {
    public interface Listener {
        void onConnected();
        void onWaiting(String roomCode, int players);
        void onStart(boolean spectator);
        void onDisconnected();
    }

    private static final String TAG = "NetClient";

    private final Gson gson = new Gson();
    private final Listener listener;
    private WebSocketClient socket;

    public NetClient(Listener listener) {
        this.listener = listener;
    }

    public void connect(String url) {
        disconnect();
        try {
            socket = new WebSocketClient(new URI(url)) {
                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    if (listener != null) {
                        listener.onConnected();
                    }
                }

                @Override
                public void onMessage(String message) {
                    handleMessage(message);
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    if (listener != null) {
                        listener.onDisconnected();
                    }
                }

                @Override
                public void onError(Exception ex) {
                    Log.e(TAG, "Socket error", ex);
                }
            };
            socket.connect();
        } catch (URISyntaxException e) {
            Log.e(TAG, "Invalid socket URL: " + url, e);
        }
    }

    public void disconnect() {
        if (socket != null) {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
            socket = null;
        }
    }

    public boolean isConnected() {
        return socket != null && socket.isOpen();
    }

    public void sendInput(float moveX, float moveY, float lookX, float lookY) {
        if (!isConnected()) {
            return;
        }
        JsonObject root = new JsonObject();
        root.addProperty("type", "input");
        JsonObject payload = new JsonObject();
        payload.addProperty("moveX", moveX);
        payload.addProperty("moveY", moveY);
        payload.addProperty("lookX", lookX);
        payload.addProperty("lookY", lookY);
        root.add("payload", payload);
        socket.send(gson.toJson(root));
    }

    private void handleMessage(String raw) {
        try {
            JsonObject msg = gson.fromJson(raw, JsonObject.class);
            if (msg == null || !msg.has("type")) {
                return;
            }
            String type = msg.get("type").getAsString();
            if ("waiting".equals(type)) {
                String room = msg.has("roomCode") ? msg.get("roomCode").getAsString() : "----";
                int players = msg.has("players") ? msg.get("players").getAsInt() : 1;
                if (listener != null) {
                    listener.onWaiting(room, players);
                }
            } else if ("start".equals(type)) {
                boolean spectator = msg.has("spectator") && msg.get("spectator").getAsBoolean();
                if (listener != null) {
                    listener.onStart(spectator);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Invalid message: " + raw, e);
        }
    }
}
