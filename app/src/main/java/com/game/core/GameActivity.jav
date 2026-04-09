package com.game.core;

import android.app.Activity;
import android.os.Bundle;
import android.view.Window;
import android.view.WindowManager;

public class GameActivity extends Activity {
    private GameSurface surface;
    private android.widget.EditText hiddenInput;

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
        hiddenInput = new android.widget.EditText(this);
        hiddenInput.setVisibility(android.view.View.INVISIBLE);
        hiddenInput.setInputType(
            android.text.InputType.TYPE_CLASS_TEXT);
        addContentView(hiddenInput,
            new android.view.ViewGroup.LayoutParams(1,1));

        hiddenInput.addTextChangedListener(
            new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence s,
                int st,int c,int a){}
            public void onTextChanged(CharSequence s,
                int st,int b,int c){}
            public void afterTextChanged(android.text.Editable s){
                String txt=s.toString();
                if(txt.length()>12) txt=txt.substring(0,12);
                surface.renderer.playerName=txt;
            }
        });
    }

    public void showKeyboard(){
        hiddenInput.requestFocus();
        android.view.inputmethod.InputMethodManager imm=
            (android.view.inputmethod.InputMethodManager)
            getSystemService(INPUT_METHOD_SERVICE);
        if(imm!=null) imm.showSoftInput(hiddenInput,
            android.view.inputmethod.InputMethodManager
            .SHOW_IMPLICIT);
    }
    public void hideKeyboard(){
        android.view.inputmethod.InputMethodManager imm=
            (android.view.inputmethod.InputMethodManager)
            getSystemService(INPUT_METHOD_SERVICE);
        if(imm!=null) imm.hideSoftInputFromWindow(
            hiddenInput.getWindowToken(),0);
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
