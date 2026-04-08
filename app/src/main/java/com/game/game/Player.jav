package com.game.game;

public class Player {
    public float x, y;
    public float angle;  // en radianes
    public float radius = 16f;
    public int hp = 100;
    public boolean alive = true;

    public Player(float x, float y) {
        this.x = x;
        this.y = y;
        this.angle = 0f;
    }

    public void move(float dx, float dy) {
        float nx = x + dx;
        float ny = y + dy;
        if (!Map.isWallAt(nx, y)) x = nx;
        if (!Map.isWallAt(x, ny)) y = ny;
        // NO actualizar angle aquí — la cámara
        // solo la controla el joystick derecho
    }
}
