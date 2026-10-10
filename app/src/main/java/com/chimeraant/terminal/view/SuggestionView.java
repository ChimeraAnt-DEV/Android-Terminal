package com.chimeraant.terminal.view;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.chimeraant.terminal.R;
import com.chimeraant.terminal.suggest.CommandDatabase;

import java.util.List;

/**
 * A small dropdown of command suggestions.
 *
 * It is kept deliberately short, roughly three rows at a time, and scrolls
 * inside a capped height so it never covers the terminal. Rows are separated by
 * a hairline. Tapping a row inserts the full command with its arguments; the
 * info button asks the bot to explain the command.
 */
public class SuggestionView extends LinearLayout {

    public interface Listener {
        /** The user picked a command. The argument is the full command line. */
        void onCommandChosen(String completion);

        /** The user asked what a command does. */
        void onExplainRequested(String command, String summary);
    }

    /** Show at most this many rows before the list starts to scroll. */
    private static final int VISIBLE_ROWS = 3;
    private static final float ROW_HEIGHT_DP = 40f;
    private static final int MAX_RESULTS = 24;

    private Listener listener;
    private final ScrollView scroller;
    private final LinearLayout rows;

    private final int surface;
    private final int onSurface;
    private final int onSurfaceMuted;
    private final int divider;
    private final int accent;
    private final int highlight;

    public SuggestionView(Context context) {
        this(context, null);
    }

    public SuggestionView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        setVisibility(GONE);

        accent = context.getColor(R.color.accent);
        surface = context.getColor(R.color.surface_raised);
        onSurface = context.getColor(R.color.on_surface);
        onSurfaceMuted = context.getColor(R.color.on_surface_muted);
        divider = context.getColor(R.color.divider);
        highlight = context.getColor(R.color.tab_active);

        GradientDrawable background = new GradientDrawable();
        background.setColor(surface);
        background.setCornerRadius(dp(10));
        background.setStroke(dp(1), divider);
        setBackground(background);
        setPadding(dp(2), dp(2), dp(2), dp(2));

        scroller = new ScrollView(context);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS);

        rows = new LinearLayout(context);
        rows.setOrientation(VERTICAL);
        scroller.addView(rows, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        addView(scroller, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * Show suggestions for the word being typed. An empty word lists the most
     * useful commands first, so a new user sees what is available immediately.
     */
    public void update(String word) {
        List<CommandDatabase.Command> matches =
                CommandDatabase.startingWith(word, MAX_RESULTS);
        if (matches.isEmpty()) {
            setVisibility(GONE);
            return;
        }

        rows.removeAllViews();
        for (int i = 0; i < matches.size(); i++) {
            if (i > 0) rows.addView(buildSeparator());
            rows.addView(buildRow(matches.get(i)));
        }

        // Cap the height so the list scrolls instead of taking over the screen.
        int maxHeight = Math.round(ROW_HEIGHT_DP * VISIBLE_ROWS
                * getResources().getDisplayMetrics().density);
        int contentHeight = rows.getChildCount()
                * Math.round(ROW_HEIGHT_DP * getResources().getDisplayMetrics().density);
        android.view.ViewGroup.LayoutParams params = scroller.getLayoutParams();
        params.height = Math.min(contentHeight, maxHeight);
        scroller.setLayoutParams(params);

        scroller.scrollTo(0, 0);
        setVisibility(VISIBLE);
    }

    public void hide() {
        setVisibility(GONE);
    }

    private View buildRow(CommandDatabase.Command command) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Math.round(ROW_HEIGHT_DP
                * getResources().getDisplayMetrics().density));
        row.setPadding(dp(10), dp(4), dp(4), dp(4));
        row.setBackground(selectableBackground());
        row.setClickable(true);

        TextView name = new TextView(getContext());
        name.setText(command.name);
        name.setTextColor(onSurface);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        name.setTypeface(TerminalFonts.mono(getContext()));
        name.setSingleLine(true);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                dp(96), LayoutParams.WRAP_CONTENT);
        name.setLayoutParams(nameParams);

        TextView summary = new TextView(getContext());
        summary.setText(command.group);
        summary.setTextColor(onSurfaceMuted);
        summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        summary.setSingleLine(true);
        summary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        summary.setLayoutParams(new LinearLayout.LayoutParams(
                0, LayoutParams.WRAP_CONTENT, 1f));

        ImageButton info = new ImageButton(getContext());
        info.setImageResource(android.R.drawable.ic_menu_info_details);
        info.setImageTintList(ColorStateList.valueOf(accent));
        info.setBackground(null);
        info.setContentDescription("Explain " + command.name);
        info.setPadding(dp(10), dp(10), dp(10), dp(10));
        info.setOnClickListener(v -> {
            if (listener != null) {
                listener.onExplainRequested(command.name, command.summary);
            }
        });

        row.addView(name);
        row.addView(summary);
        row.addView(info);

        row.setOnClickListener(v -> {
            if (listener != null) {
                listener.onCommandChosen(CommandDatabase.completionFor(command.name));
            }
        });
        row.setBackground(selectableBackground());
        return row;
    }

    private View buildSeparator() {
        View line = new View(getContext());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, Math.max(1, dp(0.6f)));
        params.setMargins(dp(10), 0, dp(10), 0);
        line.setLayoutParams(params);
        line.setBackgroundColor(divider);
        return line;
    }

    private Drawable selectableBackground() {
        TypedValue outValue = new TypedValue();
        getContext().getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, outValue, true);
        Drawable ripple = getContext().getDrawable(outValue.resourceId);
        return ripple != null ? ripple : new GradientDrawable();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
