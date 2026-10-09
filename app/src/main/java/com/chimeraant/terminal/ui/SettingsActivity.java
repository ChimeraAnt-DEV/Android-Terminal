package com.chimeraant.terminal.ui;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.chimeraant.terminal.ChimeraApp;
import com.chimeraant.terminal.R;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.materialswitch.MaterialSwitch;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        MaterialToolbar toolbar = findViewById(R.id.settings_toolbar);
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(v -> finish());

        ChimeraApp app = (ChimeraApp) getApplication();

        SeekBar fontSeek = findViewById(R.id.seek_font);
        TextView fontLabel = findViewById(R.id.label_font);
        float current = app.settings().getFloat(ChimeraApp.KEY_FONT_SIZE, 14f);
        int progress = Math.round(current * 2f) - 16; // 8sp..24sp -> 0..32
        fontSeek.setProgress(Math.max(0, Math.min(32, progress)));
        fontLabel.setText(getString(R.string.settings_font_size) + ": " + (int) current + "sp");
        fontSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                float size = (value + 16) / 2f;
                fontLabel.setText(getString(R.string.settings_font_size) + ": " + (int) size + "sp");
                app.settings().edit().putFloat(ChimeraApp.KEY_FONT_SIZE, size).apply();
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        MaterialSwitch keepOn = findViewById(R.id.switch_keep_screen_on);
        keepOn.setChecked(app.settings().getBoolean(ChimeraApp.KEY_KEEP_SCREEN_ON, false));
        keepOn.setOnCheckedChangeListener((b, checked) ->
                app.settings().edit().putBoolean(ChimeraApp.KEY_KEEP_SCREEN_ON, checked).apply());

        findViewById(R.id.row_ime_settings).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));

        findViewById(R.id.row_ime_picker).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SUBTYPE_SETTINGS)));
    }
}
