package com.chimeraant.terminal.view;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.chimeraant.terminal.R;
import com.chimeraant.terminal.suggest.CommandDatabase;

import java.util.List;

/**
 * The dropdown that appears above the keyboard while you type.
 *
 * It lists commands that start with what you have typed. Tapping a row inserts
 * the command. The "i" next to each row asks the bot to explain it.
 */
public class SuggestionView extends LinearLayout {

    public interface Listener {
        /** The user picked a command; insert it into the shell input. */
        void onCommandChosen(String command);

        /** The user asked what a command does. */
        void onExplainRequested(String command, String summary);
    }

    private static final int MAX_ROWS = 5;

    private Listener listener;
    private final int accent;
    private final int surface;
    private final int onSurface;
    private final int onSurfaceMuted;
    private final int divider;

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

        GradientDrawable background = new GradientDrawable();
        background.setColor(surface);
        background.setCornerRadii(new float[]{dp(10), dp(10), dp(10), dp(10), 0, 0, 0, 0});
        background.setStroke(dp(1), divider);
        setBackground(background);
        setPadding(dp(4), dp(4), dp(4), dp(4));
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * Show suggestions for the word being typed. An empty word lists the most
     * useful commands so a new user sees what is available straight away.
     */
    public void update(String word) {
        List<CommandDatabase.Command> matches =
                CommandDatabase.startingWith(word, MAX_ROWS);
        if (matches.isEmpty()) {
            setVisibility(GONE);
            return;
        }
        removeAllViews();
        for (CommandDatabase.Command command : matches) {
            addView(buildRow(command));
        }
        setVisibility(VISIBLE);
    }

    public void hide() {
        setVisibility(GONE);
    }

    private View buildRow(CommandDatabase.Command command) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(6), dp(4), dp(6));
        row.setBackground(makeRipple());

        TextView name = new TextView(getContext());
        name.setText(command.name);
        name.setTextColor(onSurface);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        name.setTypeface(TerminalFonts.mono(getContext()));
        name.setSingleLine(true);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                0, LayoutParams.WRAP_CONTENT, 0f);
        nameParams.width = dp(110);
        name.setLayoutParams(nameParams);

        TextView summary = new TextView(getContext());
        summary.setText(command.group);
        summary.setTextColor(onSurfaceMuted);
        summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        summary.setSingleLine(true);
        summary.setLayoutParams(new LinearLayout.LayoutParams(
                0, LayoutParams.WRAP_CONTENT, 1f));

        ImageButton info = new ImageButton(getContext());
        info.setImageResource(android.R.drawable.ic_menu_info_details);
        info.setColorFilter(accent);
        info.setBackground(null);
        info.setContentDescription("Explain " + command.name);
        info.setPadding(dp(8), dp(8), dp(8), dp(8));
        info.setOnClickListener(v -> {
            if (listener != null) {
                listener.onExplainRequested(command.name, command.summary);
            }
        });

        row.addView(name);
        row.addView(summary);
        row.addView(info);
        row.setOnClickListener(v -> {
            if (listener != null) listener.onCommandChosen(command.name);
        });
        return row;
    }

    private android.graphics.drawable.Drawable makeRipple() {
        TypedValue outValue = new TypedValue();
        getContext().getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, outValue, true);
        return getContext().getDrawable(outValue.resourceId);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
