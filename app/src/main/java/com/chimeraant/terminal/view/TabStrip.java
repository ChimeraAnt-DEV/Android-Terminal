package com.chimeraant.terminal.view;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.chimeraant.terminal.R;
import com.chimeraant.terminal.session.TerminalSession;

import java.util.ArrayList;
import java.util.List;

/**
 * Kitty-style tab strip: one compact, clickable tab per sandbox, with a close
 * affordance on the active tab. Tabs are plain views rather than a RecyclerView
 * so the strip stays cheap on low-end tablets.
 */
public class TabStrip extends HorizontalScrollView {

    public interface Listener {
        void onTabSelected(TerminalSession session);

        void onTabClosed(TerminalSession session);

        void onNewTabRequested();
    }

    private LinearLayout container;
    private Listener listener;
    private List<TerminalSession> sessions = new ArrayList<>();
    private TerminalSession active;

    private int colorActive;
    private int colorInactive;
    private int colorText;
    private int colorTextMuted;
    private int colorAccent;

    public TabStrip(Context context) {
        this(context, null);
    }

    public TabStrip(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setHorizontalScrollBarEnabled(false);
        setFillViewport(false);
        setBackgroundColor(Color.TRANSPARENT);

        colorActive = context.getColor(R.color.tab_active);
        colorInactive = context.getColor(R.color.tab_inactive);
        colorText = context.getColor(R.color.on_surface);
        colorTextMuted = context.getColor(R.color.on_surface_muted);
        colorAccent = context.getColor(R.color.accent);

        container = new LinearLayout(context);
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setPadding(dp(6), dp(6), dp(6), dp(6));
        addView(container, new LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<TerminalSession> sessions, TerminalSession active) {
        this.sessions = sessions;
        this.active = active;
        rebuild();
    }

    private void rebuild() {
        container.removeAllViews();
        for (TerminalSession session : sessions) {
            container.addView(buildTab(session, session == active));
        }
        container.addView(buildNewTabButton());
        post(() -> scrollToActive());
    }

    private View buildTab(TerminalSession session, boolean isActive) {
        TextView tab = new TextView(getContext());
        tab.setText(tabLabel(session));
        tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tab.setTypeface(TerminalFonts.mono(getContext()));
        tab.setGravity(Gravity.CENTER_VERTICAL);
        tab.setSingleLine(true);
        tab.setPadding(dp(14), dp(8), dp(14), dp(8));
        tab.setTextColor(isActive ? colorText : colorTextMuted);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadii(new float[]{dp(8), dp(8), dp(8), dp(8), 0, 0, 0, 0});
        bg.setColor(isActive ? colorActive : colorInactive);
        bg.setStroke(dp(1), isActive ? colorAccent : getContext().getColor(R.color.divider));
        tab.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT);
        lp.setMargins(dp(2), 0, dp(2), 0);
        tab.setLayoutParams(lp);

        tab.setOnClickListener(v -> {
            if (listener != null) listener.onTabSelected(session);
        });
        tab.setOnLongClickListener(v -> {
            if (listener != null) listener.onTabClosed(session);
            return true;
        });
        return tab;
    }

    private View buildNewTabButton() {
        TextView plus = new TextView(getContext());
        plus.setText("+");
        plus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        plus.setTypeface(TerminalFonts.mono(getContext()));
        plus.setGravity(Gravity.CENTER);
        plus.setTextColor(colorAccent);
        plus.setPadding(dp(14), dp(8), dp(14), dp(8));

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(8));
        bg.setColor(colorInactive);
        bg.setStroke(dp(1), colorAccent);
        plus.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT);
        lp.setMargins(dp(6), 0, dp(2), 0);
        plus.setLayoutParams(lp);
        plus.setOnClickListener(v -> {
            if (listener != null) listener.onNewTabRequested();
        });
        return plus;
    }

    private String tabLabel(TerminalSession session) {
        String name = session.getName();
        if (session.isFinished()) {
            return name + " \u2715";
        }
        return name;
    }

    private void scrollToActive() {
        int index = sessions.indexOf(active);
        if (index < 0) return;
        View child = container.getChildAt(index);
        if (child != null) {
            smoothScrollTo(Math.max(0, child.getLeft() - dp(16)), 0);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
