package com.ivantrj.glasslauncher;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/**
 * Physical inputs (HDMI ports, AV, ...) from the TV input framework, plus Live TV.
 * Tuner-type inputs are skipped: apps like VLC and Prime Video register those for
 * their own channels; the Live TV entry covers the real tuner.
 */
final class InputsMenu {

    static final class Entry {
        final String label;
        final Intent intent; // null = informational row, not selectable
        final String inputId; // set for physical inputs, which can be renamed
        final String systemLabel;
        final Runnable action; // menu rows that do something other than launch (app actions)
        IntFunction<String> adjust; // Left/Right on the row: step by -1/+1, returns the row's new text

        Entry(String label, Intent intent) { this(label, intent, null, null, null); }

        Entry(String label, Runnable action) { this(label, null, null, null, action); }

        Entry(String label, Intent intent, String inputId, String systemLabel) {
            this(label, intent, inputId, systemLabel, null);
        }

        private Entry(String label, Intent intent, String inputId, String systemLabel, Runnable action) {
            this.label = label;
            this.intent = intent;
            this.inputId = inputId;
            this.systemLabel = systemLabel;
            this.action = action;
        }

        boolean selectable() { return intent != null || action != null; }
    }

    /** Names given in Glass (Menu on an input), keyed by TV input id. */
    static SharedPreferences names(Context context) {
        return context.getSharedPreferences("inputNames", Context.MODE_PRIVATE);
    }

    static List<Entry> load(Context context) {
        List<TvInputInfo> inputs = new ArrayList<>();
        TvInputManager tim = context.getSystemService(TvInputManager.class);
        if (tim != null) {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (rank(info.getType()) >= 0 && !info.isHidden(context)) inputs.add(info);
            }
        }
        // HDMI first, then the other video ports (AV etc.)
        inputs.sort((a, b) -> Integer.compare(rank(a.getType()), rank(b.getType())));

        List<Entry> out = new ArrayList<>();
        for (TvInputInfo info : inputs) {
            CharSequence label = info.loadCustomLabel(context);
            if (TextUtils.isEmpty(label)) label = info.loadLabel(context);
            if (TextUtils.isEmpty(label) && info.getType() == TvInputInfo.TYPE_COMPOSITE) label = "AV";
            String systemLabel = String.valueOf(label);
            String custom = names(context).getString(info.getId(), null);
            Intent intent = new Intent(Intent.ACTION_VIEW,
                    TvContract.buildChannelUriForPassthroughInput(info.getId()));
            out.add(new Entry(TextUtils.isEmpty(custom) ? systemLabel : custom,
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), info.getId(), systemLabel));
        }
        boolean renamable = !out.isEmpty();

        Intent liveTv = context.getPackageManager().getLeanbackLaunchIntentForPackage("com.amazon.tv.livetv");
        if (liveTv != null) out.add(new Entry("Live TV", liveTv.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));

        if (out.isEmpty()) out.add(new Entry(context.getString(R.string.no_inputs), (Intent) null));
        else if (renamable) out.add(new Entry(context.getString(R.string.rename_hint), (Intent) null));
        return out;
    }

    /** Sort order for physical input types; -1 for tuners and virtual inputs (app channels, ARC audio). */
    private static int rank(int type) {
        switch (type) {
            case TvInputInfo.TYPE_HDMI: return 0;
            case TvInputInfo.TYPE_DISPLAY_PORT: return 1;
            case TvInputInfo.TYPE_DVI: return 2;
            case TvInputInfo.TYPE_COMPONENT: return 3;
            case TvInputInfo.TYPE_COMPOSITE: return 4;
            case TvInputInfo.TYPE_SVIDEO: return 5;
            case TvInputInfo.TYPE_SCART: return 6;
            case TvInputInfo.TYPE_VGA: return 7;
            default: return -1;
        }
    }

    private InputsMenu() {}
}
