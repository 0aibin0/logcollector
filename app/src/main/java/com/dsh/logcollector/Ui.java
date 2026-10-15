package com.dsh.logcollector;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;

/**
 * Tiny programmatic UI toolkit.
 *
 * <p>The whole interface is built in code rather than in layout XML. That keeps the APK small and,
 * more importantly, keeps every widget's behaviour next to the logic that drives it - which matters
 * a lot for a diagnostic tool where each control maps to exactly one collector option.
 */
final class Ui {

    private Ui() { }

    static final int BG     = 0xFF0B1118;
    static final int CARD   = 0xFF16202B;
    static final int ACCENT = 0xFF4ADE80;
    static final int WARN   = 0xFFF59E0B;
    static final int DANGER = 0xFFEF4444;
    static final int TEXT   = 0xFFE6EDF3;
    static final int DIM    = 0xFF94A3B8;
    static final int LINE   = 0xFF2A3644;

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static ScrollView scroll(Context c, View child) {
        ScrollView s = new ScrollView(c);
        s.setFillViewport(true);
        s.setBackgroundColor(BG);
        s.addView(child, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return s;
    }

    static TextView text(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    static TextView title(Context c, String s) {
        TextView t = text(c, s, 15f, TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static TextView body(Context c, String s) {
        return text(c, s, 12.5f, DIM);
    }

    static TextView mono(Context c, String s) {
        TextView t = text(c, s, 11f, 0xFFCBD5E1);
        t.setTypeface(Typeface.MONOSPACE);
        return t;
    }

    static TextView sectionHeader(Context c, String s) {
        TextView t = text(c, s.toUpperCase(), 11.5f, ACCENT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(c, 16), 0, dp(c, 6));
        return t;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = column(c);
        l.setBackgroundColor(CARD);
        int p = dp(c, 14);
        l.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 10);
        l.setLayoutParams(lp);
        return l;
    }

    static Button button(Context c, String label, boolean primary) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(13.5f);
        if (primary) {
            b.setBackgroundColor(ACCENT);
            b.setTextColor(0xFF06210F);
        } else {
            b.setBackgroundColor(0xFF243040);
            b.setTextColor(TEXT);
        }
        return b;
    }

    static CheckBox check(Context c, String label, boolean checked) {
        CheckBox cb = new CheckBox(c);
        cb.setText(label);
        cb.setTextSize(13f);
        cb.setTextColor(TEXT);
        cb.setChecked(checked);
        return cb;
    }

    /** A labelled single-line text field. */
    static LinearLayout field(Context c, String label, String value, int inputType) {
        LinearLayout col = column(c);
        TextView l = text(c, label, 12f, DIM);
        l.setPadding(0, dp(c, 6), 0, dp(c, 2));
        EditText e = new EditText(c);
        e.setText(value == null ? "" : value);
        e.setTextSize(13f);
        e.setTextColor(TEXT);
        e.setInputType(inputType);
        e.setSingleLine(true);
        col.addView(l);
        col.addView(e);
        return col;
    }

    static EditText editTextOf(LinearLayout field) {
        return (EditText) field.getChildAt(1);
    }

    /** A labelled spinner over a fixed set of choices. */
    static LinearLayout spinner(Context c, String label, String[] choices, int selected) {
        LinearLayout col = column(c);
        TextView l = text(c, label, 12f, DIM);
        l.setPadding(0, dp(c, 6), 0, dp(c, 2));
        Spinner s = new Spinner(c);
        ArrayAdapter<String> a = new ArrayAdapter<String>(c,
                android.R.layout.simple_spinner_dropdown_item, choices);
        s.setAdapter(a);
        if (selected >= 0 && selected < choices.length) s.setSelection(selected);
        col.addView(l);
        col.addView(s);
        return col;
    }

    static Spinner spinnerOf(LinearLayout container) {
        return (Spinner) container.getChildAt(1);
    }

    static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 0.6f)));
        lp.topMargin = dp(c, 10);
        lp.bottomMargin = dp(c, 10);
        v.setLayoutParams(lp);
        return v;
    }

    static void space(Context c, LinearLayout parent, float dpHeight) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(c, dpHeight)));
        parent.addView(v);
    }

    /** Colours a status word consistently everywhere it appears. */
    static int statusColor(String status) {
        if (status == null) return DIM;
        String s = status.toLowerCase();
        if (s.contains("granted") || s.contains("yes") || s.contains("ok") || s.contains("readable")
                || s.contains("present") || s.contains("found") || s.contains("running")
                || s.contains("direct")) {
            return ACCENT;
        }
        if (s.contains("denied") || s.contains("blocked") || s.contains("fail")
                || s.contains("missing") || s.contains("fallback")) {
            return DANGER;
        }
        if (s.contains("no") || s.contains("partial")) return WARN;
        return DIM;
    }

    static int colorWithAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
}
