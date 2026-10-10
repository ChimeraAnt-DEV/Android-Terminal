package com.chimeraant.terminal.ui;

import android.os.Bundle;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.chimeraant.terminal.R;
import com.chimeraant.terminal.root.ProotInstaller;
import com.chimeraant.terminal.session.SessionManager;
import com.chimeraant.terminal.termux.TermuxBootstrapInstaller;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import java.util.Locale;

/** Installs and reports on the Termux userland. */
public class TermuxActivity extends AppCompatActivity {

    private TermuxBootstrapInstaller installer;
    private ProgressBar progressBar;
    private TextView progressText;
    private TextView statusText;
    private MaterialButton installButton;
    private MaterialButton openButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_termux);

        MaterialToolbar toolbar = findViewById(R.id.termux_toolbar);
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(v -> finish());

        installer = new TermuxBootstrapInstaller(this);
        progressBar = findViewById(R.id.termux_progress);
        progressText = findViewById(R.id.termux_progress_text);
        statusText = findViewById(R.id.termux_status);
        installButton = findViewById(R.id.termux_install);
        openButton = findViewById(R.id.termux_open);

        installButton.setOnClickListener(v -> install());
        openButton.setOnClickListener(v -> {
            SessionManager.get(this).createSession("termux", false, false);
            finish();
        });

        refreshStatus();
    }

    private void refreshStatus() {
        boolean installed = installer.isInstalled();
        ProotInstaller proot = new ProotInstaller(this);
        boolean prootReady = proot.getProotBinary().exists();

        StringBuilder sb = new StringBuilder();
        if (installed) {
            sb.append(getString(R.string.termux_installed));
        } else {
            sb.append(getString(R.string.termux_desc));
        }
        if (!prootReady) {
            sb.append("\n\n").append(getString(R.string.termux_needs_proot));
        }
        sb.append("\n\n").append(getString(R.string.termux_commands_hint));
        statusText.setText(sb.toString());

        installButton.setText(installed
                ? R.string.termux_reinstall
                : R.string.termux_install);
        openButton.setEnabled(installed);
        installButton.setEnabled(prootReady);
    }

    private void install() {
        progressBar.setVisibility(ProgressBar.VISIBLE);
        progressText.setVisibility(TextView.VISIBLE);
        installButton.setEnabled(false);

        installer.install(new TermuxBootstrapInstaller.ProgressListener() {
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
                    installButton.setEnabled(true);
                    Toast.makeText(TermuxActivity.this, message, Toast.LENGTH_LONG).show();
                    refreshStatus();
                });
            }
        });
    }
}
