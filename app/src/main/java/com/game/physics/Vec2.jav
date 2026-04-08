package com.game.physics;

public class Vec2 {
    public float x, y;

    public Vec2(float x, float y) { this.x = x; this.y = y; }
    public Vec2() { this(0, 0); }

    public Vec2 add(Vec2 o)   { return new Vec2(x+o.x, y+o.y); }
    public Vec2 sub(Vec2 o)   { return new Vec2(x-o.x, y-o.y); }
    public Vec2 scale(float s){ return new Vec2(x*s, y*s); }
    public float dot(Vec2 o)  { return x*o.x + y*o.y; }
    public float len()        { return (float)Math.sqrt(x*x + y*y); }
    public Vec2 norm()        {
        float l = len();
        return l > 0 ? scale(1f/l) : new Vec2();
    }
    public void set(float x, float y) { this.x=x; this.y=y; }
    public void setFrom(Vec2 o)       { this.x=o.x; this.y=o.y; }
}
