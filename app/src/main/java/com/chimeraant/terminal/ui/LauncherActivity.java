package com.chimeraant.terminal.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AppCompatActivity;

import com.chimeraant.terminal.R;

/**
 * Entry point. Shows the frosty loading animation, then sends the user to the
 * first-launch guide or straight to the terminal.
 */
public class LauncherActivity extends AppCompatActivity {

    private static final long MIN_SPLASH_MS = 1400L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean navigated = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launcher);

        boolean seenOnboarding = getSharedPreferences("chimera_onboarding", MODE_PRIVATE)
                .getBoolean("seen", false);

        handler.postDelayed(() -> navigate(seenOnboarding), MIN_SPLASH_MS);
    }

    private void navigate(boolean seenOnboarding) {
        if (navigated) return;
        navigated = true;
        Intent intent = new Intent(this,
                seenOnboarding ? MainActivity.class : OnboardingActivity.class);
        startActivity(intent);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
