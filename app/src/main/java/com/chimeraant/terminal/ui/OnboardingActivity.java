package com.chimeraant.terminal.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager2.widget.ViewPager2;

import com.chimeraant.terminal.R;

/**
 * First-launch guide.
 *
 * A new user opens a terminal and sees a prompt with no idea what to type. This
 * explains, in order: what the app is for, what to type first, and how to get
 * more commands. It shows once, then never again unless the user asks for it.
 */
public class OnboardingActivity extends AppCompatActivity {

    /** Each page: a short heading, a line of guidance, and example commands. */
    private static final String[][] PAGES = {
            {
                    "A terminal, explained simply",
                    "This is a command line. You type a command, press Enter, and "
                            + "the device runs it. It is the same idea as the Terminal "
                            + "app on a computer.",
                    "help"
            },
            {
                    "Try your first command",
                    "Type help and press Enter. It lists every command this sandbox "
                            + "can run. Nothing you type here can break your device.",
                    "help\nls\ngetprop ro.build.version.release"
            },
            {
                    "Each sandbox is separate",
                    "Every tab is its own sandbox with its own files. A long job in "
                            + "one tab will not slow down another, and you can close a "
                            + "tab without affecting the rest.",
                    "pwd\nmkdir notes\ncd notes"
            },
            {
                    "Get more commands",
                    "Open the drawer and choose Termux userland to install the full "
                            + "Termux package set. That adds pkg and apt, so you can "
                            + "install git, python, ssh and thousands more.",
                    "pkg update\npkg install python"
            },
            {
                    "Remove preinstalled apps",
                    "Open the drawer and choose Debloater to disable or remove apps "
                            + "that came with the device. Protected system apps are "
                            + "blocked, and anything you change can be restored.",
                    "pm list packages"
            }
    };

    private ViewPager2 pager;
    private LinearLayout dots;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_onboarding);

        pager = findViewById(R.id.onboarding_pager);
        dots = findViewById(R.id.onboarding_dots);

        pager.setAdapter(new PageAdapter());
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateDots(position);
                TextView next = findViewById(R.id.onboarding_next);
                next.setText(position == PAGES.length - 1
                        ? R.string.onboarding_start
                        : R.string.onboarding_next);
            }
        });

        findViewById(R.id.onboarding_skip).setOnClickListener(v -> finishOnboarding());
        findViewById(R.id.onboarding_next).setOnClickListener(v -> {
            int next = pager.getCurrentItem() + 1;
            if (next < PAGES.length) {
                pager.setCurrentItem(next, true);
            } else {
                finishOnboarding();
            }
        });

        updateDots(0);
    }

    private void finishOnboarding() {
        getSharedPreferences("chimera_onboarding", MODE_PRIVATE)
                .edit().putBoolean("seen", true).apply();
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    private void updateDots(int position) {
        dots.removeAllViews();
        for (int i = 0; i < PAGES.length; i++) {
            View dot = new View(this);
            LinearLayout.LayoutParams params =
                    new LinearLayout.LayoutParams(dp(8), dp(8));
            params.setMargins(dp(4), 0, dp(4), 0);
            dot.setLayoutParams(params);
            dot.setBackgroundResource(i == position
                    ? R.drawable.dot_active
                    : R.drawable.dot_inactive);
            dots.addView(dot);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private class PageAdapter extends androidx.recyclerview.widget.RecyclerView.Adapter<PageHolder> {

        @androidx.annotation.NonNull
        @Override
        public PageHolder onCreateViewHolder(@androidx.annotation.NonNull
                                                     android.view.ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_onboarding_page, parent, false);
            return new PageHolder(view);
        }

        @Override
        public void onBindViewHolder(@androidx.annotation.NonNull PageHolder holder, int position) {
            String[] page = PAGES[position];
            holder.title.setText(page[0]);
            holder.body.setText(page[1]);
            holder.examples.setText(page[2]);
        }

        @Override
        public int getItemCount() {
            return PAGES.length;
        }
    }

    private static class PageHolder extends androidx.recyclerview.widget.RecyclerView.ViewHolder {
        final TextView title;
        final TextView body;
        final TextView examples;

        PageHolder(View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.page_title);
            body = itemView.findViewById(R.id.page_body);
            examples = itemView.findViewById(R.id.page_examples);
        }
    }
}
