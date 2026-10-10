package com.chimeraant.terminal.view;

import android.content.Context;
import android.graphics.Typeface;

import java.util.HashMap;
import java.util.Map;

/**
 * Loads the bundled JetBrains Mono faces from {@code assets/fonts} once and
 * caches them. Falls back to the platform monospace font if a face is missing
 * so the terminal always renders something usable.
 */
public final class TerminalFonts {

    private static final String REGULAR = "fonts/JetBrainsMono-Regular.ttf";
    private static final String BOLD = "fonts/JetBrainsMono-Bold.ttf";
    private static final String ITALIC = "fonts/JetBrainsMono-Italic.ttf";
    private static final String BOLD_ITALIC = "fonts/JetBrainsMono-BoldItalic.ttf";

    private static final Map<String, Typeface> CACHE = new HashMap<>();

    private TerminalFonts() {
    }

    public static Typeface mono(Context context) {
        return load(context, REGULAR);
    }

    public static Typeface bold(Context context) {
        return load(context, BOLD);
    }

    public static Typeface italic(Context context) {
        return load(context, ITALIC);
    }

    public static Typeface boldItalic(Context context) {
        return load(context, BOLD_ITALIC);
    }

    /** Face for the given attribute combination. */
    public static Typeface forAttributes(Context context, boolean bold, boolean italic) {
        if (bold && italic) return boldItalic(context);
        if (bold) return bold(context);
        if (italic) return italic(context);
        return mono(context);
    }

    private static Typeface load(Context context, String assetPath) {
        String key = assetPath;
        Typeface cached = CACHE.get(key);
        if (cached != null) return cached;

        Typeface typeface;
        try {
            typeface = Typeface.createFromAsset(context.getAssets(), assetPath);
        } catch (Exception e) {
            typeface = Typeface.MONOSPACE;
        }
        CACHE.put(key, typeface);
        return typeface;
    }
}
