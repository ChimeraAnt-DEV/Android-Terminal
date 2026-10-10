package com.chimeraant.terminal.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.chimeraant.terminal.R;
import com.chimeraant.terminal.debloat.CommandRunner;
import com.chimeraant.terminal.debloat.DebloatEngine;
import com.chimeraant.terminal.debloat.DebloatHistory;
import com.chimeraant.terminal.debloat.DebloatSafety;
import com.chimeraant.terminal.debloat.LocalCommandRunner;
import com.chimeraant.terminal.debloat.RootCommandRunner;
import com.chimeraant.terminal.debloat.ShizukuBridge;
import com.chimeraant.terminal.session.SessionManager;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.List;

/**
 * Debloater screen: lists installed apps, shows the privilege backend actually
 * in use, and performs disable / uninstall / restore.
 */
public class DebloatActivity extends AppCompatActivity {

    private DebloatEngine engine;
    private CommandRunner runner;
    private ShizukuBridge shizukuBridge;
    private AppAdapter adapter;

    private TextView backendLabel;
    private TextView statusLabel;
    private MaterialSwitch showSystemSwitch;
    private boolean showSystem = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_debloat);

        MaterialToolbar toolbar = findViewById(R.id.debloat_toolbar);
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(v -> finish());

        engine = new DebloatEngine(this);
        shizukuBridge = new ShizukuBridge();
        runner = pickRunner();

        backendLabel = findViewById(R.id.debloat_backend_value);
        statusLabel = findViewById(R.id.debloat_status);
        showSystemSwitch = findViewById(R.id.debloat_show_system);

        RecyclerView list = findViewById(R.id.debloat_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new AppAdapter();
        list.setAdapter(adapter);

        findViewById(R.id.debloat_scan).setOnClickListener(v -> scan());
        findViewById(R.id.debloat_grant).setOnClickListener(v -> {
            shizukuBridge.requestPermission();
            Toast.makeText(this, R.string.debloat_grant, Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.debloat_shizuku_setup).setOnClickListener(v -> openShizuku());
        findViewById(R.id.debloat_restore_all).setOnClickListener(v -> restoreAll());

        showSystemSwitch.setChecked(showSystem);
        showSystemSwitch.setOnCheckedChangeListener((b, checked) -> {
            showSystem = checked;
            scan();
        });

        updateBackendLabel();
        scan();
    }

    private CommandRunner pickRunner() {
        RootCommandRunner root = new RootCommandRunner(SessionManager.get(this).getRootManager());
        if (root.isAvailable()) return root;
        if (shizukuBridge.isAvailable()) return shizukuBridge.asRunner();
        return new LocalCommandRunner();
    }

    private void updateBackendLabel() {
        String text = runner.name();
        String detail;
        if (runner.isAvailable()) {
            detail = "ready";
        } else {
            detail = runner.unavailableReason();
        }
        backendLabel.setText(text + " \u2014 " + detail);
    }

    private void scan() {
        runner = pickRunner();
        updateBackendLabel();
        List<DebloatEngine.AppEntry> entries = engine.listInstalled(showSystem);
        adapter.submit(entries);
        statusLabel.setText(entries.size() + " packages listed");
    }

    private void restoreAll() {
        List<String> restorable = DebloatHistory.restorablePackages(this);
        if (restorable.isEmpty()) {
            Toast.makeText(this, "Nothing to restore", Toast.LENGTH_SHORT).show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.debloat_restore)
                .setMessage("Restore " + restorable.size() + " package(s)?")
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    for (String pkg : restorable) {
                        engine.restore(runner, pkg, null);
                    }
                    Toast.makeText(this, "Restore requested", Toast.LENGTH_SHORT).show();
                    scan();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void openShizuku() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://shizuku.rikka.app/")));
        } catch (Exception e) {
            Toast.makeText(this, R.string.debloat_shizuku_help, Toast.LENGTH_LONG).show();
        }
    }

    private void confirmAction(String action, String packageName) {
        DebloatSafety.Risk risk = DebloatSafety.classify(packageName);
        if (risk == DebloatSafety.Risk.CRITICAL) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.debloat_blocked)
                    .setMessage(DebloatSafety.explain(packageName))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        String message = DebloatSafety.explain(packageName);
        if (risk == DebloatSafety.Risk.CAUTION) {
            message += "\n\nThis is reversible with Restore.";
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(action + ": " + packageName)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, (d, w) -> perform(action, packageName))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void perform(String action, String packageName) {
        DebloatEngine.ProgressListener listener = new DebloatEngine.ProgressListener() {
            @Override
            public void onProgress(String stage, int percent) {
                runOnUiThread(() -> statusLabel.setText(stage + " (" + percent + "%)"));
            }

            @Override
            public void onComplete(boolean success, String message) {
                runOnUiThread(() -> {
                    statusLabel.setText(message);
                    Toast.makeText(DebloatActivity.this, message, Toast.LENGTH_LONG).show();
                    scan();
                });
            }
        };
        switch (action) {
            case "Disable":
                engine.disable(runner, packageName, listener);
                break;
            case "Uninstall":
                engine.uninstall(runner, packageName, listener);
                break;
            case "Restore":
                engine.restore(runner, packageName, listener);
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------- adapter

    private class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

        private final List<DebloatEngine.AppEntry> items = new ArrayList<>();

        void submit(List<DebloatEngine.AppEntry> entries) {
            items.clear();
            items.addAll(entries);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_debloat_app, parent, false);
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            DebloatEngine.AppEntry entry = items.get(position);
            holder.label.setText(entry.label);
            holder.packageName.setText(entry.packageName
                    + (entry.system ? " \u2022 system" : " \u2022 user")
                    + (entry.enabled ? "" : " \u2022 disabled"));

            int color;
            String riskText;
            switch (entry.risk) {
                case CRITICAL:
                    color = getColor(R.color.danger);
                    riskText = getString(R.string.debloat_risk_critical);
                    break;
                case CAUTION:
                    color = getColor(R.color.warning);
                    riskText = getString(R.string.debloat_risk_caution);
                    break;
                default:
                    color = getColor(R.color.success);
                    riskText = getString(R.string.debloat_risk_safe);
                    break;
            }
            holder.risk.setText(riskText);
            holder.risk.setTextColor(color);

            boolean protectedPackage = entry.risk == DebloatSafety.Risk.CRITICAL;
            holder.disable.setEnabled(!protectedPackage);
            holder.uninstall.setEnabled(!protectedPackage);

            holder.disable.setOnClickListener(v ->
                    confirmAction("Disable", entry.packageName));
            holder.uninstall.setOnClickListener(v ->
                    confirmAction("Uninstall", entry.packageName));
            holder.restore.setOnClickListener(v ->
                    perform("Restore", entry.packageName));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final TextView label;
            final TextView packageName;
            final TextView risk;
            final ImageButton disable;
            final ImageButton uninstall;
            final MaterialButton restore;

            Holder(@NonNull View itemView) {
                super(itemView);
                label = itemView.findViewById(R.id.item_label);
                packageName = itemView.findViewById(R.id.item_package);
                risk = itemView.findViewById(R.id.item_risk);
                disable = itemView.findViewById(R.id.item_disable);
                uninstall = itemView.findViewById(R.id.item_uninstall);
                restore = itemView.findViewById(R.id.item_restore);
            }
        }
    }
}
