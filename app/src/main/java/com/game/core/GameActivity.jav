package com.game.core;

import android.app.Activity;
import android.os.Bundle;
import android.view.Window;
import android.view.WindowManager;

public class GameActivity extends Activity {
    private GameSurface surface;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        surface = new GameSurface(this);
        setContentView(surface);
    }

    @Override protected void onPause() {
        super.onPause();
        surface.onPause();
        Assets.pause();
    }
    @Override protected void onResume() {
        super.onResume();
        surface.onResume();
        Assets.resume();
    }
    @Override protected void onDestroy() {
        super.onDestroy();
        Assets.release();
    }
}
