package com.game.core;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.MotionEvent;

public class GameSurface extends GLSurfaceView {
    public GameRenderer renderer;

    public GameSurface(Context context) {
        super(context);
        setEGLContextClientVersion(2);
        renderer=new GameRenderer(context);
        setRenderer(renderer);
        setRenderMode(RENDERMODE_CONTINUOUSLY);
        Assets.load(context);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e){
        int action=e.getActionMasked();
        int idx=e.getActionIndex();
        int pid=e.getPointerId(idx);
        float ex=e.getX(idx), ey=e.getY(idx);

        if(action==MotionEvent.ACTION_DOWN
                ||action==MotionEvent.ACTION_POINTER_DOWN){

            if(renderer.state==GameRenderer.GameState.MENU){
                renderer.handleMenuTap(ex, ey, getWidth(), getHeight());
                return true;
            }

            // Minijuego en pantalla de espera
            if(renderer.state==GameRenderer.GameState.WAITING){
                renderer.handleMinigameTap(ex,ey,getWidth(),getHeight());
                return true;
            }

            // Botón ATK en partida
            if(renderer.state==GameRenderer.GameState.PLAYING
                    && renderer.canAttack()){
                float atkX=getWidth()*0.72f;
                float atkY=getHeight()*0.62f;
                if(ex>=atkX && ey>=atkY){
                    renderer.attackPressed=true;
                    return true;
                }
            }

            // Joystick
            if(ex<getWidth()*0.5f){
                renderer.leftId=pid;
                renderer.leftStartX=ex;
                renderer.leftStartY=ey;
                renderer.leftDX=renderer.leftDY=0;
            } else {
                renderer.rightId=pid;
                renderer.rightLastX=ex;
                renderer.rightLastY=ey;
                renderer.rightDX=renderer.rightDY=0;
            }
        } else if(action==MotionEvent.ACTION_MOVE){
            for(int i=0;i<e.getPointerCount();i++){
                int id=e.getPointerId(i);
                if(id==renderer.leftId){
                    float dx=e.getX(i)-renderer.leftStartX;
                    float dy=e.getY(i)-renderer.leftStartY;
                    float len=(float)Math.sqrt(dx*dx+dy*dy);
                    float max=100f;
                    if(len>max){dx=dx/len*max;dy=dy/len*max;}
                    renderer.leftDX=dx/max;
                    renderer.leftDY=dy/max;
                }
                if(id==renderer.rightId){
                    renderer.rightDX=
                        (e.getX(i)-renderer.rightLastX)/45f;
                    renderer.rightDY=
                        (e.getY(i)-renderer.rightLastY)/300f;
                    renderer.rightLastX=e.getX(i);
                    renderer.rightLastY=e.getY(i);
                }
            }
        } else if(action==MotionEvent.ACTION_UP
                ||action==MotionEvent.ACTION_POINTER_UP
                ||action==MotionEvent.ACTION_CANCEL){
            if(pid==renderer.leftId){
                renderer.leftId=-1;
                renderer.leftDX=renderer.leftDY=0;
            }
            if(pid==renderer.rightId){
                renderer.rightId=-1;
                renderer.rightDX=renderer.rightDY=0;
            }
        }
        return true;
    }
}
