package com.dsh.logcollector;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Live logcat viewer with filtering.
 *
 * <p>Reads from a streaming {@code logcat} child, keeps a bounded ring of lines, and re-renders at
 * most ten times a second so a chatty device cannot make the UI stutter. Filtering happens on the
 * ring, not on the view, so changing a filter is instant.
 */
public class LogcatViewerActivity extends Activity {

    private static final int RING = 3000;              // lines kept in memory
    private static final long RENDER_INTERVAL = 100;   // ms between UI renders
    /**
     * Most lines pushed into the TextView in one pass. A single TextView holding 3000 spanned
     * lines costs a lot to measure and lay out, and this re-renders up to ten times a second, so
     * the view is capped while the ring keeps the full history for filtering and snapshots.
     */
    private static final int MAX_RENDER = 1200;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> ring = new ArrayDeque<String>();
    private final Object ringLock = new Object();

    private Process proc;
    private Thread reader;
    private volatile boolean running;
    private volatile boolean paused;
    private volatile boolean autoScroll = true;
    private volatile boolean dirty;

    private TextView   output;
    private ScrollView scroll;
    private TextView   stats;
    private EditText   filterField;
    private String     filter = "";
    private String     levelFilter = "V";      // minimum level
    private boolean    hideOurTag = false;

    private int totalLines;
    private int shownLines;

    private static final String[] LEVELS = { "V", "D", "I", "W", "E", "F" };

    // ------------------------------------------------------------ lifecycle

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        int pad = Ui.dp(this, 10);
        root.setPadding(pad, pad, pad, pad);

        // ------------------------------------------------------------ controls
        LinearLayout bar = Ui.row(this);
        Button pause = Ui.button(this, "暂停", false);
        pause.setTextSize(12f);
        pause.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                paused = !paused;
                ((Button) v).setText(paused ? "继续" : "暂停");
            }
        });
        bar.addView(pause, weighted());

        Button clear = Ui.button(this, "清空", false);
        clear.setTextSize(12f);
        clear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                synchronized (ringLock) { ring.clear(); }
                totalLines = 0;
                render(true);
            }
        });
        bar.addView(clear, weighted());

        Button save = Ui.button(this, "存快照", false);
        save.setTextSize(12f);
        save.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { saveSnapshot(); }
        });
        bar.addView(save, weighted());

        Button bottom = Ui.button(this, "到底部", false);
        bottom.setTextSize(12f);
        bottom.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                autoScroll = true;
                render(true);
                if (scroll != null) scroll.fullScroll(View.FOCUS_DOWN);
            }
        });
        bar.addView(bottom, weighted());

        root.addView(bar);

        // -------------------------------------------------------------- filter
        filterField = new EditText(this);
        filterField.setHint("过滤：tag、关键字或包名，用 | 分隔多个");
        filterField.setTextSize(12.5f);
        filterField.setTextColor(Ui.TEXT);
        filterField.setHintTextColor(0xFF6B7A8D);
        filterField.setSingleLine(true);
        filterField.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                filter = s.toString().trim();
                render(true);
            }
            public void afterTextChanged(Editable s) { }
        });
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.topMargin = Ui.dp(this, 6);
        root.addView(filterField, flp);

        // -------------------------------------------------------- level chips
        LinearLayout levels = Ui.row(this);
        levels.setPadding(0, Ui.dp(this, 6), 0, 0);
        for (final String lv : LEVELS) {
            Button lb = Ui.button(this, lv, false);
            lb.setTextSize(11f);
            lb.setPadding(Ui.dp(this, 10), Ui.dp(this, 2), Ui.dp(this, 10), Ui.dp(this, 2));
            final Button btn = lb;
            lb.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    levelFilter = lv;
                    render(true);
                    toast("最低级别: " + lv);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = Ui.dp(this, 4);
            levels.addView(lb, lp);
        }
        root.addView(levels);

        CheckBox hide = Ui.check(this, "隐藏本应用自身日志 (DSH-LC)", false);
        hide.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean v) {
                hideOurTag = v;
                render(true);
            }
        });
        root.addView(hide);

        // -------------------------------------------------------------- output
        stats = Ui.mono(this, "");
        stats.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 4));
        root.addView(stats);

        output = Ui.mono(this, "");
        output.setTextSize(10.5f);
        output.setBackgroundColor(0xFF0E1620);
        output.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6));
        output.setTextIsSelectable(true);

        scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF0E1620);
        scroll.addView(output, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        slp.topMargin = Ui.dp(this, 2);
        scroll.setLayoutParams(slp);
        root.addView(scroll);

        setContentView(root);

        startStream();
        ui.post(renderTick);
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = Ui.dp(this, 5);
        return lp;
    }

    @Override protected void onDestroy() {
        stopStream();
        ui.removeCallbacks(renderTick);
        super.onDestroy();
    }

    // ---------------------------------------------------------------- stream

    private void startStream() {
        running = true;

        // The buffer probe runs shell commands, so it must not happen in onCreate. Do it on a
        // worker thread and only then launch logcat, so the viewer uses this device's real
        // buffer set rather than the safe default.
        reader = new Thread(new Runnable() {
            public void run() {
                String[] buffers;
                try {
                    buffers = Logcat.probeBlocking();
                } catch (Throwable t) {
                    buffers = Logcat.DEFAULT_BUFFERS;
                }
                if (!running) return;

                // `-T <count>` (start with N lines of history) only exists from Android 6.0.
                // Try it first, and fall back to a plain streaming logcat when it is rejected.
                if (stream(buffers, true)) return;
                if (!running) return;
                stream(buffers, false);
            }

            /**
             * @param withHistory include the {@code -T} flag
             * @return true when the stream produced at least one line before ending
             */
            private boolean stream(String[] buffers, boolean withHistory) {
                List<String> argv = new ArrayList<String>();
                argv.add("logcat");
                for (String a : buffers) argv.add(a);
                argv.add("-v"); argv.add("threadtime");
                if (withHistory) {
                    argv.add("-T");
                    argv.add("200");
                }

                BufferedReader br = null;
                int produced = 0;
                try {
                    ProcessBuilder pb = new ProcessBuilder(argv);
                    pb.redirectErrorStream(true);
                    proc = pb.start();

                    br = new BufferedReader(new InputStreamReader(proc.getInputStream(), "UTF-8"), 1 << 15);
                    String line;
                    while (running && (line = br.readLine()) != null) {
                        produced++;
                        final String l = line;
                        synchronized (ringLock) {
                            ring.addLast(l);
                            while (ring.size() > RING) ring.removeFirst();
                        }
                        totalLines++;
                        dirty = true;
                    }
                } catch (Throwable t) {
                    if (produced == 0) {
                        synchronized (ringLock) {
                            ring.addLast("*** logcat " + (withHistory ? "-T " : "")
                                    + "stream failed: " + t + " ***");
                        }
                        dirty = true;
                    }
                } finally {
                    Util.close(br);
                    if (proc != null) { try { proc.destroy(); } catch (Throwable ignored) { } }
                    proc = null;
                }
                return produced > 0;
            }
        }, "logcat-viewer");
        reader.setDaemon(true);
        reader.start();
    }

    private void stopStream() {
        running = false;
        if (proc != null) { try { proc.destroy(); } catch (Throwable ignored) { } }
        if (reader != null) { try { reader.join(1200); } catch (Throwable ignored) { } }
    }

    // ---------------------------------------------------------------- render

    private final Runnable renderTick = new Runnable() {
        public void run() {
            if (dirty && !paused) {
                dirty = false;
                render(false);
            }
            ui.postDelayed(this, RENDER_INTERVAL);
        }
    };

    private void render(boolean force) {
        List<String> snapshot;
        synchronized (ringLock) {
            snapshot = new ArrayList<String>(ring);
        }

        int minLevel = levelIndex(levelFilter);

        // Collect the matching lines first, then render only the newest MAX_RENDER of them.
        List<String> matched = new ArrayList<String>();
        for (String line : snapshot) {
            if (hideOurTag && line.contains(Log.TAG)) continue;
            if (!passesLevel(line, minLevel)) continue;
            if (filter.length() > 0 && !matchesFilter(line, filter)) continue;
            matched.add(line);
        }

        int from = Math.max(0, matched.size() - MAX_RENDER);
        int rendered = matched.size() - from;

        // A SpannableStringBuilder with one colour span per line is far cheaper than running
        // Html.fromHtml over thousands of lines, which matters at a 10 Hz refresh rate.
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        for (int i = from; i < matched.size(); i++) {
            String line = matched.get(i);
            int start = sb.length();
            sb.append(line);
            sb.setSpan(new android.text.style.ForegroundColorSpan(levelColor(line)),
                    start, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append("\n");
        }

        shownLines = rendered;
        output.setText(sb);

        StringBuilder stat = new StringBuilder();
        stat.append("显示 ").append(rendered);
        if (matched.size() > rendered) stat.append("（较旧的 ").append(matched.size() - rendered).append(" 行已在缓冲中，调小过滤即可看到）");
        stat.append(" / 命中 ").append(matched.size())
            .append(" / 缓冲 ").append(snapshot.size())
            .append(" · 累计 ").append(totalLines)
            .append(" · 最低级别 ").append(levelFilter);
        if (filter.length() > 0) stat.append(" · 过滤 ").append(filter);
        stat.append(paused ? " · 已暂停" : " · 实时");
        stats.setText(stat.toString());

        if (autoScroll && scroll != null) {
            scroll.post(new Runnable() {
                public void run() { scroll.fullScroll(View.FOCUS_DOWN); }
            });
        }
    }

    private int levelColor(String line) {
        char lv = levelOf(line);
        switch (lv) {
            case 'F': return Color.parseColor("#FF7B72");
            case 'E': return Color.parseColor("#FF9E9E");
            case 'W': return Color.parseColor("#F59E0B");
            case 'I': return Color.parseColor("#8FD9A8");
            case 'D': return Color.parseColor("#7FB3D5");
            default:  return Color.parseColor("#8B98A8");
        }
    }

    /**
     * Extracts the level character from a threadtime line such as
     * {@code 01-01 12:00:00.000  1234  5678 I Tag     : message}.
     *
     * <p>The level is the first standalone single-letter token after the tid column. Scanning for
     * {@code " X "} rather than indexing a fixed offset keeps this correct when a build pads the
     * pid or tid columns differently.
     */
    static char levelOf(String line) {
        if (line == null || line.length() < 30) return 'V';
        int limit = Math.min(line.length() - 1, 48);
        for (int i = 19; i < limit; i++) {
            char c = line.charAt(i);
            if (!isLevelChar(c)) continue;
            if (line.charAt(i - 1) == ' ' && line.charAt(i + 1) == ' ') return c;
        }
        return 'V';
    }

    private static boolean isLevelChar(char c) {
        return c == 'V' || c == 'D' || c == 'I' || c == 'W'
                || c == 'E' || c == 'F' || c == 'S' || c == 'A';
    }

    private static int levelIndex(String lv) {
        for (int i = 0; i < LEVELS.length; i++) if (LEVELS[i].equals(lv)) return i;
        return 0;
    }

    private boolean passesLevel(String line, int minLevel) {
        char c = levelOf(line);
        int idx = 0;
        for (int i = 0; i < LEVELS.length; i++) if (LEVELS[i].charAt(0) == c) { idx = i; break; }
        return idx >= minLevel;
    }

    private static boolean matchesFilter(String line, String filter) {
        for (String p : filter.split("\\|")) {
            String t = p.trim();
            if (t.length() == 0) continue;
            if (line.toLowerCase().contains(t.toLowerCase())) return true;
        }
        return false;
    }

    // ------------------------------------------------------------- snapshot

    private void saveSnapshot() {
        List<String> snapshot;
        synchronized (ringLock) { snapshot = new ArrayList<String>(ring); }

        StringBuilder sb = new StringBuilder();
        sb.append("# logcat viewer snapshot\n");
        sb.append("# saved   : ").append(Util.human()).append("\n");
        sb.append("# filter  : ").append(filter.length() == 0 ? "(none)" : filter).append("\n");
        sb.append("# level   : ").append(levelFilter).append("\n");
        sb.append("# lines   : ").append(snapshot.size()).append("\n\n");
        int minLevel = levelIndex(levelFilter);
        for (String line : snapshot) {
            if (hideOurTag && line.contains(Log.TAG)) continue;
            if (!passesLevel(line, minLevel)) continue;
            if (filter.length() > 0 && !matchesFilter(line, filter)) continue;
            sb.append(line).append("\n");
        }

        java.io.File dir = getExternalFilesDir("shared");
        if (dir == null) dir = getFilesDir();
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        final java.io.File out = new java.io.File(dir, "logcat_snapshot_" + Util.stamp() + ".txt");
        if (Util.writeFile(out, sb.toString())) {
            toast("已保存: " + out.getName());
            String err = Packager.share(this, out, "text/plain", "分享日志快照");
            if (err != null) toast("已存到 " + out.getAbsolutePath());
        } else {
            toast("保存失败");
        }
    }

    private void toast(String m) {
        try { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) { }
    }
}
