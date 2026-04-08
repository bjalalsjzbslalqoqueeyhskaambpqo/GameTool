package com.game.physics;

import java.util.ArrayList;
import java.util.List;

public class World {
    private List<Body> bodies = new ArrayList<>();
    private float worldW, worldH;

    public World(float worldW, float worldH) {
        this.worldW = worldW;
        this.worldH = worldH;
    }

    public void addBody(Body b) { bodies.add(b); }
    public List<Body> getBodies() { return bodies; }

    public void step() {
        // Actualizar posiciones
        for (Body b : bodies) b.update();

        // Colisiones círculo vs círculo
        for (int i = 0; i < bodies.size(); i++) {
            Body a = bodies.get(i);
            for (int j = i+1; j < bodies.size(); j++) {
                Body b = bodies.get(j);
                resolveCircleCircle(a, b);
            }
        }

        // Limitar a bordes del mundo
        for (Body b : bodies) {
            if (b.isStatic) continue;
            if (b.pos.x - b.radius < 0) {
                b.pos.x = b.radius; b.vel.x = Math.abs(b.vel.x) * 0.5f;
            }
            if (b.pos.x + b.radius > worldW) {
                b.pos.x = worldW - b.radius; b.vel.x = -Math.abs(b.vel.x) * 0.5f;
            }
            if (b.pos.y - b.radius < 0) {
                b.pos.y = b.radius; b.vel.y = Math.abs(b.vel.y) * 0.5f;
            }
            if (b.pos.y + b.radius > worldH) {
                b.pos.y = worldH - b.radius; b.vel.y = -Math.abs(b.vel.y) * 0.5f;
            }
        }
    }

    private void resolveCircleCircle(Body a, Body b) {
        float dx = b.pos.x - a.pos.x;
        float dy = b.pos.y - a.pos.y;
        float dist = (float)Math.sqrt(dx*dx + dy*dy);
        float minDist = a.radius + b.radius;
        if (dist >= minDist || dist == 0) return;

        float nx = dx / dist;
        float ny = dy / dist;
        float overlap = (minDist - dist) * 0.5f;

        if (!a.isStatic) { a.pos.x -= nx*overlap; a.pos.y -= ny*overlap; }
        if (!b.isStatic) { b.pos.x += nx*overlap; b.pos.y += ny*overlap; }

        // Transferencia de velocidad
        float dvx = b.vel.x - a.vel.x;
        float dvy = b.vel.y - a.vel.y;
        float dot = dvx*nx + dvy*ny;
        if (dot > 0) return;

        float impulse = dot * 0.8f;
        if (!a.isStatic) { a.vel.x += impulse*nx; a.vel.y += impulse*ny; }
        if (!b.isStatic) { b.vel.x -= impulse*nx; b.vel.y -= impulse*ny; }
    }
}
