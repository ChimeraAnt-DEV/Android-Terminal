package com.chimeraant.terminal;

import android.app.Application;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

import com.chimeraant.terminal.session.SessionManager;

public class ChimeraApp extends Application {

    public static final String PREFS_SETTINGS = "chimera_settings";
    public static final String KEY_FONT_SIZE = "font_size";
    public static final String KEY_KEEP_SCREEN_ON = "keep_screen_on";
    public static final String KEY_SCROLLBACK = "scrollback";

    @Override
    public void onCreate() {
        super.onCreate();
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        SessionManager.get(this);
    }

    public SharedPreferences settings() {
        return getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE);
    }
}
