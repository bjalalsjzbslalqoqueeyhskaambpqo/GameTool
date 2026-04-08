package com.game.physics;

public class Body {
    public Vec2 pos;
    public Vec2 vel;
    public float radius;
    public float mass;
    public boolean isStatic;
    public float damping;
    public Object userData;

    public Body(float x, float y, float radius, boolean isStatic) {
        this.pos      = new Vec2(x, y);
        this.vel      = new Vec2();
        this.radius   = radius;
        this.isStatic = isStatic;
        this.mass     = isStatic ? 0f : 1f;
        this.damping  = 0.85f;
    }

    public void applyForce(float fx, float fy) {
        if (isStatic) return;
        vel.x += fx;
        vel.y += fy;
    }

    public void update() {
        if (isStatic) return;
        pos.x += vel.x;
        pos.y += vel.y;
        vel.x *= damping;
        vel.y *= damping;
    }
}
