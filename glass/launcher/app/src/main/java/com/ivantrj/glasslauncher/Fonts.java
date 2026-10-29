package com.ivantrj.glasslauncher;

import android.content.Context;
import android.graphics.Typeface;
import android.util.SparseArray;

/** Outfit (SIL OFL 1.1) is a variable font: one file, any weight from 100 to 900. */
final class Fonts {
    private static final SparseArray<Typeface> CACHE = new SparseArray<>();

    static Typeface outfit(Context context, int weight) {
        Typeface t = CACHE.get(weight);
        if (t == null) {
            t = new Typeface.Builder(context.getAssets(), "fonts/Outfit.ttf")
                    .setFontVariationSettings("'wght' " + weight)
                    .build();
            CACHE.put(weight, t);
        }
        return t;
    }

    private Fonts() {}
}
