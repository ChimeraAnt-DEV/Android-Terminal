package com.chimeraant.terminal.ui;

import android.os.Bundle;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.chimeraant.terminal.R;
import com.chimeraant.terminal.root.ProotInstaller;
import com.chimeraant.terminal.root.RootManager;
import com.chimeraant.terminal.session.SessionManager;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.Locale;

public class RootActivity extends AppCompatActivity {

    private RootManager rootManager;
    private TextView modeLabel;
    private TextView statusLabel;
    private ProgressBar progressBar;
    private TextView progressText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_root);

        MaterialToolbar toolbar = findViewById(R.id.root_toolbar);
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(v -> finish());

        rootManager = SessionManager.get(this).getRootManager();
        modeLabel = findViewById(R.id.root_mode_value);
        statusLabel = findViewById(R.id.root_status_value);
        progressBar = findViewById(R.id.root_progress);
        progressText = findViewById(R.id.root_progress_text);

        MaterialSwitch enableSwitch = findViewById(R.id.switch_root_enable);
        enableSwitch.setChecked(rootManager.isRootEnabled());
        enableSwitch.setOnCheckedChangeListener((b, checked) -> {
            rootManager.setEnabled(checked);
            refreshStatus();
        });

        findViewById(R.id.button_check_root).setOnClickListener(v -> checkRoot());
        findViewById(R.id.button_install_proot).setOnClickListener(v -> installProot());

        refreshStatus();
    }

    private void refreshStatus() {
        RootManager.Mode mode = rootManager.getMode();
        modeLabel.setText(mode.name() + (rootManager.isAmazonFireDevice() ? " \u2022 Amazon Fire" : ""));
        String status = rootManager.describeCapability();
        if (rootManager.isRootGranted()) {
            status += "\n" + rootManager.getRootIdentity();
        }
        statusLabel.setText(status);
    }

    private void checkRoot() {
        statusLabel.setText(R.string.root_checking);
        rootManager.requestDeviceRoot((granted, mode, message) -> runOnUiThread(() -> {
            Toast.makeText(this, mode.name(), Toast.LENGTH_SHORT).show();
            refreshStatus();
        }));
    }

    private void installProot() {
        ProotInstaller installer = new ProotInstaller(this);
        if (installer.isInstalled()) {
            Toast.makeText(this, R.string.root_already_installed, Toast.LENGTH_SHORT).show();
            refreshStatus();
            return;
        }
        progressBar.setVisibility(ProgressBar.VISIBLE);
        progressText.setVisibility(TextView.VISIBLE);
        findViewById(R.id.button_install_proot).setEnabled(false);

        installer.install(new ProotInstaller.ProgressListener() {
            @Override
            public void onProgress(String stage, int percent) {
                runOnUiThread(() -> {
                    progressBar.setProgress(percent);
                    progressText.setText(String.format(Locale.US, "%s (%d%%)", stage, percent));
                });
            }

            @Override
            public void onComplete(boolean success, String message) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(ProgressBar.GONE);
                    progressText.setText(message);
                    findViewById(R.id.button_install_proot).setEnabled(true);
                    refreshStatus();
                });
            }
        });
    }
}
