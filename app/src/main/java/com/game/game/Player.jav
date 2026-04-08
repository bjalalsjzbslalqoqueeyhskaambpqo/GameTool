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
        // Colisión con mapa — separar ejes
        if (!Map.isWallAt(nx, y)) x = nx;
        if (!Map.isWallAt(x, ny)) y = ny;
        // Actualizar ángulo de visión según movimiento
        if (Math.abs(dx) > 0.01f || Math.abs(dy) > 0.01f) {
            angle = (float)Math.atan2(dy, dx);
        }
    }
}
