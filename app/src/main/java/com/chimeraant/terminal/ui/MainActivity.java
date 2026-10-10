package com.chimeraant.terminal.ui;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.chimeraant.terminal.ChimeraApp;
import com.chimeraant.terminal.R;
import com.chimeraant.terminal.core.TerminalEmulator;
import com.chimeraant.terminal.session.SessionManager;
import com.chimeraant.terminal.session.TerminalSession;
import com.chimeraant.terminal.view.TerminalView;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.navigation.NavigationView;

import java.util.List;

public class MainActivity extends AppCompatActivity implements SessionManager.Observer {

    private DrawerLayout drawerLayout;
    private NavigationView navigationView;
    private TerminalView terminalView;
    private TextView navSubtitle;
    private MaterialButton emptyButton;
    private com.chimeraant.terminal.view.TabStrip tabStrip;

    private SessionManager sessionManager;
    private boolean ctrlLatched;
    private boolean altLatched;
    private int lastSessionCount = -1;

    private com.chimeraant.terminal.view.SuggestionView suggestionView;
    private com.chimeraant.terminal.view.BotExplainerView botExplainer;
    private final com.chimeraant.terminal.suggest.LineBuffer lineBuffer =
            new com.chimeraant.terminal.suggest.LineBuffer();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        sessionManager = SessionManager.get(this);
        sessionManager.addObserver(this);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START));
        toolbar.setOnMenuItemClickListener(this::onMenuItem);

        drawerLayout = findViewById(R.id.drawer_layout);
        navigationView = findViewById(R.id.nav_view);
        terminalView = findViewById(R.id.terminal_view);
        emptyButton = findViewById(R.id.empty_new_sandbox);
        navSubtitle = navigationView.getHeaderView(0).findViewById(R.id.nav_subtitle);

        tabStrip = findViewById(R.id.tab_strip);
        tabStrip.setListener(new com.chimeraant.terminal.view.TabStrip.Listener() {
            @Override
            public void onTabSelected(TerminalSession session) {
                attachSession(session);
            }

            @Override
            public void onTabClosed(TerminalSession session) {
                sessionManager.closeSession(session);
                attachSession(sessionManager.getActiveSession());
                updateEmptyState();
                updateDrawer();
            }

            @Override
            public void onNewTabRequested() {
                showNewSandboxDialog();
            }
        });

        suggestionView = findViewById(R.id.suggestion_view);
        botExplainer = findViewById(R.id.bot_explainer);
        suggestionView.setListener(new com.chimeraant.terminal.view.SuggestionView.Listener() {
            @Override
            public void onCommandChosen(String command) {
                insertSuggestion(command);
            }

            @Override
            public void onExplainRequested(String command, String summary) {
                botExplainer.explain(command, summary);
            }
        });

        // Re-anchor when the terminal changes size, which happens when the
        // soft keyboard opens or closes.
        terminalView.addOnLayoutChangeListener((v, left, top, right, bottom,
                                               oldLeft, oldTop, oldRight, oldBottom) -> {
            if (suggestionView != null
                    && suggestionView.getVisibility() == View.VISIBLE) {
                suggestionView.post(this::anchorSuggestionsAboveCursor);
            }
        });

        terminalView.setInputListener(new TerminalView.InputListener() {
            @Override
            public void onWrite(byte[] data) {
                TerminalSession session = sessionManager.getActiveSession();
                if (session != null) session.write(data);
                // Mirror what we sent so the dropdown knows the current word.
                lineBuffer.accept(data);
                refreshSuggestions();
            }

            @Override
            public void onResize(int rows, int cols) {
                TerminalSession session = sessionManager.getActiveSession();
                if (session != null) session.resize(cols, rows);
            }

            @Override
            public void onRequestKeyboard() {
                showKeyboard();
            }
        });

        emptyButton.setOnClickListener(v -> showNewSandboxDialog());
        navigationView.setNavigationItemSelectedListener(this::onDrawerItem);

        wireExtraKeys();
        applySettings();

        if (sessionManager.getSessionCount() == 0) {
            TerminalSession session = sessionManager.createSession(null);
            attachSession(session);
        } else {
            attachSession(sessionManager.getActiveSession());
        }
        updateEmptyState();

        requestNotificationPermission();
        startSessionService();
    }

    private void requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 100);
            }
        }
    }

    private void startSessionService() {
        try {
            Intent service = new Intent(this,
                    com.chimeraant.terminal.session.TerminalService.class);
            service.setAction(com.chimeraant.terminal.session.TerminalService.ACTION_START);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(service);
            } else {
                startService(service);
            }
        } catch (Exception ignored) {
            // Running in the foreground is enough; the service is best-effort.
        }
    }

    private void wireExtraKeys() {
        findViewById(R.id.key_esc).setOnClickListener(v -> sendSequence("\u001B"));
        findViewById(R.id.key_tab).setOnClickListener(v -> sendSequence("\t"));
        findViewById(R.id.key_arrow_up).setOnClickListener(v -> sendSequence("\u001B[A"));
        findViewById(R.id.key_arrow_down).setOnClickListener(v -> sendSequence("\u001B[B"));
        findViewById(R.id.key_arrow_left).setOnClickListener(v -> sendSequence("\u001B[D"));
        findViewById(R.id.key_arrow_right).setOnClickListener(v -> sendSequence("\u001B[C"));
        findViewById(R.id.key_ctrl).setOnClickListener(v -> {
            ctrlLatched = !ctrlLatched;
            altLatched = false;
            highlightModifier(R.id.key_ctrl, ctrlLatched);
            highlightModifier(R.id.key_alt, false);
        });
        findViewById(R.id.key_alt).setOnClickListener(v -> {
            altLatched = !altLatched;
            ctrlLatched = false;
            highlightModifier(R.id.key_alt, altLatched);
            highlightModifier(R.id.key_ctrl, false);
        });
        findViewById(R.id.key_clear).setOnClickListener(v -> clearLine());
        findViewById(R.id.key_toggle_ime).setOnClickListener(v -> switchKeyboard());
    }

    private void highlightModifier(int id, boolean active) {
        MaterialButton button = findViewById(id);
        button.setAlpha(active ? 1f : 0.7f);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                active ? getColor(R.color.accent) : getColor(R.color.surface_alt)));
    }

    private void applySettings() {
        ChimeraApp app = (ChimeraApp) getApplication();
        float fontSize = app.settings().getFloat(ChimeraApp.KEY_FONT_SIZE, 14f);
        terminalView.setFontSize(fontSize);
        boolean keepOn = app.settings().getBoolean(ChimeraApp.KEY_KEEP_SCREEN_ON, false);
        if (keepOn) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void attachSession(TerminalSession session) {
        if (session == null) {
            terminalView.setEmulator(null);
            updateToolbarTitle(null);
            return;
        }
        sessionManager.setActiveSession(session);
        terminalView.setEmulator(session.getEmulator());
        terminalView.scrollToBottom();
        lineBuffer.reset();
        if (suggestionView != null) suggestionView.hide();
        updateToolbarTitle(session);
        updateDrawer();
    }

    private void updateToolbarTitle(TerminalSession session) {
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        if (session == null) {
            toolbar.setTitle(R.string.app_name);
        } else {
            toolbar.setTitle(session.getName());
        }
        navSubtitle.setText(getString(R.string.sessions) + ": " + sessionManager.getSessionCount());
    }

    private void updateDrawer() {
        Menu menu = navigationView.getMenu();
        menu.removeGroup(R.id.drawer_sessions_group);
        List<TerminalSession> sessions = sessionManager.getSessions();
        if (tabStrip != null) {
            tabStrip.submit(sessions, sessionManager.getActiveSession());
        }
        for (int i = 0; i < sessions.size(); i++) {
            TerminalSession session = sessions.get(i);
            MenuItem item = menu.add(R.id.drawer_sessions_group, Menu.FIRST + i, i,
                    session.getName() + (session.isFinished() ? " (exited)" : ""));
            item.setCheckable(true);
            item.setChecked(session == sessionManager.getActiveSession());
        }
        navSubtitle.setText(getString(R.string.sessions) + ": " + sessions.size()
                + " \u2022 running: " + sessionManager.runningCount());
    }

    private void updateEmptyState() {
        boolean empty = sessionManager.getSessionCount() == 0;
        emptyButton.setVisibility(empty ? View.VISIBLE : View.GONE);
        terminalView.setVisibility(empty ? View.INVISIBLE : View.VISIBLE);
    }

    private boolean onDrawerItem(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.drawer_new_session) {
            showNewSandboxDialog();
        } else if (id == R.id.drawer_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
        } else if (id == R.id.drawer_root) {
            startActivity(new Intent(this, RootActivity.class));
        } else if (id == R.id.drawer_debloat) {
            startActivity(new Intent(this, DebloatActivity.class));
        } else if (id == R.id.drawer_termux) {
            startActivity(new Intent(this, TermuxActivity.class));
        } else if (id >= Menu.FIRST) {
            int index = id - Menu.FIRST;
            List<TerminalSession> sessions = sessionManager.getSessions();
            if (index < sessions.size()) {
                attachSession(sessions.get(index));
            }
        }
        drawerLayout.closeDrawer(GravityCompat.START);
        return true;
    }

    private boolean onMenuItem(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_new_session) {
            showNewSandboxDialog();
            return true;
        } else if (id == R.id.action_close_session) {
            TerminalSession session = sessionManager.getActiveSession();
            if (session != null) {
                sessionManager.closeSession(session);
                attachSession(sessionManager.getActiveSession());
                updateEmptyState();
            }
            return true;
        } else if (id == R.id.action_paste) {
            terminalView.pasteFromClipboard();
            return true;
        } else if (id == R.id.action_copy) {
            terminalView.copySelection();
            Toast.makeText(this, "Selection copied", Toast.LENGTH_SHORT).show();
            return true;
        } else if (id == R.id.action_scroll_bottom) {
            terminalView.scrollToBottom();
            return true;
        } else if (id == R.id.action_keyboard) {
            switchKeyboard();
            return true;
        } else if (id == R.id.action_root) {
            startActivity(new Intent(this, RootActivity.class));
            return true;
        } else if (id == R.id.action_debloat) {
            startActivity(new Intent(this, DebloatActivity.class));
            return true;
        } else if (id == R.id.action_termux) {
            startActivity(new Intent(this, TermuxActivity.class));
            return true;
        } else if (id == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return false;
    }

    private void showNewSandboxDialog() {
        View content = getLayoutInflater().inflate(R.layout.dialog_new_sandbox, null);
        com.google.android.material.textfield.TextInputEditText nameInput = content.findViewById(R.id.input_name);
        com.google.android.material.materialswitch.MaterialSwitch rootSwitch = content.findViewById(R.id.switch_root);
        com.google.android.material.materialswitch.MaterialSwitch prootSwitch = content.findViewById(R.id.switch_proot);

        boolean rootAvailable = sessionManager.getRootManager().isRootEnabled();
        rootSwitch.setChecked(rootAvailable);
        prootSwitch.setChecked(false);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.new_sandbox_title)
                .setView(content)
                .setPositiveButton(R.string.create, (d, w) -> {
                    String name = nameInput.getText() == null ? null : nameInput.getText().toString().trim();
                    boolean root = rootSwitch.isChecked();
                    boolean proot = prootSwitch.isChecked();
                    if ((root || proot) && !sessionManager.getRootManager().isRootEnabled()) {
                        sessionManager.getRootManager().setEnabled(true);
                    }
                    TerminalSession session = sessionManager.createSession(
                            name == null || name.isEmpty() ? null : name, root, proot);
                    attachSession(session);
                    updateEmptyState();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void sendSequence(String sequence) {
        TerminalSession session = sessionManager.getActiveSession();
        if (session == null) return;
        if (ctrlLatched && sequence.length() == 1) {
            char c = sequence.charAt(0);
            int code = c >= 'a' && c <= 'z' ? c - 'a' + 1 : (c >= 'A' && c <= 'Z' ? c - 'A' + 1 : -1);
            if (code >= 0) {
                session.write(new byte[]{(byte) code});
            }
            ctrlLatched = false;
            highlightModifier(R.id.key_ctrl, false);
        } else if (altLatched) {
            session.write(("\u001B" + sequence).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            altLatched = false;
            highlightModifier(R.id.key_alt, false);
        } else {
            session.write(sequence.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        terminalView.scrollToBottom();
    }

    /** Refresh the dropdown for the word being typed. */
    private void refreshSuggestions() {
        if (suggestionView == null) return;
        String word = lineBuffer.currentWord();
        // Only suggest while the user is typing a command name, not arguments.
        if (!lineBuffer.atCommandStart() && word.isEmpty()) {
            suggestionView.hide();
            return;
        }
        suggestionView.update(word);
        // Anchor after layout so the measured height is known.
        suggestionView.post(this::anchorSuggestionsAboveCursor);
        // Keep the bot in sync with the leading command on the line.
        String line = lineBuffer.currentLine().trim();
        if (!line.isEmpty()) {
            String firstWord = line.split("\\s+")[0];
            com.chimeraant.terminal.suggest.CommandDatabase.Command known =
                    com.chimeraant.terminal.suggest.CommandDatabase.find(firstWord);
            if (known != null && known.name.equals(word)) {
                botExplainer.explain(known.name, known.summary);
            }
        }
    }

    /**
     * Replace the whole line with the chosen command, so the user gets the
     * full command with its arguments rather than a bare word.
     */
    private void insertSuggestion(String completion) {
        String line = lineBuffer.currentLine();
        if (!line.trim().isEmpty()) {
            // Ctrl+U tells the shell to erase the line it is holding. That
            // covers words typed before an arrow key moved the cursor, which a
            // plain run of backspaces would not.
            sendAndTrack("\u0015");
        }
        sendAndTrack(completion);
        suggestionView.hide();
        botExplainer.explain(completion.split("\\s+")[0],
                explanationFor(completion.split("\\s+")[0]));
        hideKeyboard();
    }

    private String explanationFor(String commandName) {
        com.chimeraant.terminal.suggest.CommandDatabase.Command command =
                com.chimeraant.terminal.suggest.CommandDatabase.find(commandName);
        return command == null ? "" : command.summary;
    }

    /**
     * Sit the dropdown just above the line the user is typing on, instead of
     * always pinning it to the bottom of the screen. When the cursor is near
     * the top there is no room above it, so the list drops below the line.
     */
    private void anchorSuggestionsAboveCursor() {
        if (suggestionView == null || suggestionView.getVisibility() != View.VISIBLE) {
            return;
        }
        float cursorBottom = terminalView.getCursorBottomY();
        if (cursorBottom < 0f) {
            return;
        }
        View parent = (View) suggestionView.getParent();
        if (parent == null) return;
        int available = parent.getHeight();
        int listHeight = suggestionView.getMeasuredHeight();
        int gap = Math.round(4 * getResources().getDisplayMetrics().density);

        float wanted = cursorBottom - listHeight - gap;
        // Not enough room above? Put it under the cursor line instead.
        if (wanted < 0f) {
            wanted = cursorBottom + gap;
        }
        wanted = Math.max(0f, Math.min(wanted, Math.max(0, available - listHeight)));

        if (suggestionView.getLayoutParams() instanceof android.widget.FrameLayout.LayoutParams) {
            android.widget.FrameLayout.LayoutParams params =
                    (android.widget.FrameLayout.LayoutParams) suggestionView.getLayoutParams();
            params.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
            params.topMargin = Math.round(wanted);
            suggestionView.setLayoutParams(params);
        }
    }

    /** Wipe everything the user has typed on the current line. */
    private void clearLine() {
        if (lineBuffer.currentLine().isEmpty()) {
            return;
        }
        sendAndTrack("\u0015");
        suggestionView.hide();
        botExplainer.hide();
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(terminalView.getWindowToken(), 0);
        }
    }

    /** Send text to the shell and keep the mirror in step. */
    private void sendAndTrack(String text) {
        TerminalSession session = sessionManager.getActiveSession();
        if (session == null) return;
        byte[] data = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        session.write(data);
        lineBuffer.accept(data);
        terminalView.scrollToBottom();
    }

    private void showKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT);
        }
        terminalView.requestFocus();
    }

    private void switchKeyboard() {
        // The user chooses which keyboard to use; we only surface the picker.
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            try {
                imm.showInputMethodPicker();
                return;
            } catch (Exception ignored) {
            }
        }
        startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (terminalView != null && terminalView.hasFocus()) {
            if (terminalView.onKeyDown(keyCode, event)) {
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void onSessionsChanged() {
        // Session output changed, or a session was added or removed. Repaint
        // the visible terminal so new output appears immediately; rebuild the
        // drawer only when the session list itself changed.
        if (terminalView != null) {
            TerminalSession active = sessionManager.getActiveSession();
            if (active != null && terminalView.getEmulator() == active.getEmulator()) {
                terminalView.requestRender();
            }
        }
        if (sessionManager.getSessionCount() != lastSessionCount) {
            lastSessionCount = sessionManager.getSessionCount();
            updateDrawer();
            updateToolbarTitle(sessionManager.getActiveSession());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        applySettings();
        updateDrawer();
    }

    @Override
    protected void onDestroy() {
        sessionManager.removeObserver(this);
        super.onDestroy();
    }
}
