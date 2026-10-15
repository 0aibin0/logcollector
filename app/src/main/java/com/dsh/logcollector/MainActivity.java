package com.dsh.logcollector;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/**
 * The single control surface: capability panel, one-tap collection, the automation switches and
 * all settings.
 *
 * <p>Built entirely in code (no XML layouts, no AndroidX) so the APK stays small and the build
 * needs nothing but the platform SDK.
 */
public class MainActivity extends Activity implements CollectorService.Listener {

    private static final int REQ_PROJECTION = 1001;
    private static final int REQ_NOTIF      = 1002;
    private static final int REQ_PERMS      = 1003;

    private Prefs prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout root;
    private LinearLayout content;
    private TextView     statusLine;
    private TextView     progressLine;
    private Button       tabCollect, tabAuto, tabSettings;
    private int          currentTab = 0;

    private LinearLayout capabilityPanel;
    private TextView     resultBox;
    private Button       collectButton;

    private Capabilities caps;
    private volatile boolean probing;
    private Collector.Report lastReport;

    // ------------------------------------------------------------ lifecycle

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = Prefs.get(this);

        root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        int pad = Ui.dp(this, 14);
        root.setPadding(pad, pad, pad, pad);

        buildHeader();
        buildTabs();

        content = Ui.column(this);
        root.addView(content);

        ScrollView scroll = Ui.scroll(this, root);
        setContentView(scroll);

        showTab(0);
        CollectorService.start(this);
        refreshCapabilities();
    }

    @Override protected void onResume() {
        super.onResume();
        CollectorService.addListener(this);
        updateServiceStatus();
        // Coming back from the "all files access" settings page should refresh the panel.
        if (caps != null && caps.sdkInt >= 30 && !caps.allFilesGranted) refreshCapabilities();
    }

    @Override protected void onPause() {
        CollectorService.removeListener(this);
        super.onPause();
    }

    // --------------------------------------------------------------- header

    private void buildHeader() {
        LinearLayout head = Ui.row(this);
        head.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout col = Ui.column(this);
        TextView title = Ui.text(this, "DSH LogCollector", 19f, Ui.TEXT);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        TextView sub = Ui.text(this, "dmesg · logcat · dumpsys 一键采集", 12f, Ui.DIM);
        col.addView(title);
        col.addView(sub);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        head.addView(col, lp);

        Button viewer = Ui.button(this, "实时日志", false);
        viewer.setTextSize(12f);
        viewer.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, LogcatViewerActivity.class));
            }
        });
        head.addView(viewer);

        root.addView(head);

        statusLine = Ui.text(this, "", 12f, Ui.DIM);
        statusLine.setPadding(0, Ui.dp(this, 8), 0, 0);
        root.addView(statusLine);

        progressLine = Ui.text(this, "", 12f, Ui.ACCENT);
        progressLine.setPadding(0, Ui.dp(this, 4), 0, 0);
        progressLine.setVisibility(View.GONE);
        root.addView(progressLine);
    }

    private void buildTabs() {
        LinearLayout row = Ui.row(this);
        row.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 4));

        tabCollect  = tabButton("采集", 0, row);
        tabAuto     = tabButton("自动化", 1, row);
        tabSettings = tabButton("设置", 2, row);

        root.addView(row);
    }

    private Button tabButton(String label, final int index, LinearLayout parent) {
        Button b = Ui.button(this, label, false);
        b.setTextSize(13f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = Ui.dp(this, 6);
        parent.addView(b, lp);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showTab(index); }
        });
        return b;
    }

    private void showTab(int index) {
        currentTab = index;
        tint(tabCollect, index == 0);
        tint(tabAuto, index == 1);
        tint(tabSettings, index == 2);

        content.removeAllViews();
        if (index == 0) buildCollectTab();
        else if (index == 1) buildAutomationTab();
        else buildSettingsTab();
    }

    private void tint(Button b, boolean active) {
        if (b == null) return;
        b.setBackgroundColor(active ? Ui.ACCENT : 0xFF243040);
        b.setTextColor(active ? 0xFF06210F : Ui.TEXT);
    }

    // ---------------------------------------------------------- collect tab

    private void buildCollectTab() {
        // ------------------------------------------------------ capabilities
        LinearLayout capCard = Ui.card(this);
        LinearLayout capHead = Ui.row(this);
        TextView capTitle = Ui.title(this, "设备能力");
        capHead.addView(capTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button refresh = Ui.button(this, "重新检测", false);
        refresh.setTextSize(11f);
        refresh.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { refreshCapabilities(); }
        });
        capHead.addView(refresh);
        capCard.addView(capHead);

        capabilityPanel = Ui.column(this);
        capabilityPanel.setPadding(0, Ui.dp(this, 8), 0, 0);
        capCard.addView(capabilityPanel);
        capabilityPanel.addView(Ui.body(this, "检测中…"));
        content.addView(capCard);

        // -------------------------------------------------------- run button
        LinearLayout runCard = Ui.card(this);

        collectButton = Ui.button(this, "立即采集", true);
        collectButton.setTextSize(15f);
        collectButton.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));
        collectButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startCollection(); }
        });
        runCard.addView(collectButton);

        LinearLayout quick = Ui.row(this);
        quick.setPadding(0, Ui.dp(this, 8), 0, 0);
        Button stop = Ui.button(this, "停止自动化", false);
        stop.setTextSize(12f);
        stop.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                CollectorService.stopService(MainActivity.this);
                toast("已停止黑匣子 / 监听 / 定时");
                updateServiceStatus();
            }
        });
        Button projBtn = Ui.button(this, "授权截屏", false);
        projBtn.setTextSize(12f);
        projBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { requestProjection(); }
        });
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        hlp.rightMargin = Ui.dp(this, 6);
        quick.addView(stop, hlp);
        quick.addView(projBtn, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        runCard.addView(quick);

        TextView note = Ui.body(this,
                "非 root 设备上，dmesg 与大部分 dumpsys 需要提权。点「重新检测」可以看到"
                + "具体缺哪一项以及对应的 adb 命令。");
        note.setPadding(0, Ui.dp(this, 10), 0, 0);
        runCard.addView(note);

        content.addView(runCard);

        // ------------------------------------------------------- this run's options
        LinearLayout optCard = Ui.card(this);
        optCard.addView(Ui.title(this, "本次采集内容"));

        addOptionCheck(optCard, "logcat 各缓冲区", "main/system/crash/events/radio/kernel…",
                prefs.logBuffers(), new OnFlag() {
                    public void set(boolean v) { prefs.logBuffers(v); }
                });
        addOptionCheck(optCard, "dumpsys 全量服务", "逐个服务单独成文件 + 合并流",
                prefs.dumpsysFull(), new OnFlag() {
                    public void set(boolean v) { prefs.dumpsysFull(v); }
                });
        addOptionCheck(optCard, "显示 / 背光专项", "backlight 节点、display_id XML、SF/DRM",
                prefs.doDisplay(), new OnFlag() {
                    public void set(boolean v) { prefs.doDisplay(v); }
                });
        addOptionCheck(optCard, "内核专项", "/proc、thermal、regulator、debugfs",
                prefs.doKernel(), new OnFlag() {
                    public void set(boolean v) { prefs.doKernel(v); }
                });
        addOptionCheck(optCard, "系统属性", "getprop 全量 + 关键项",
                prefs.doProp(), new OnFlag() {
                    public void set(boolean v) { prefs.doProp(v); }
                });
        addOptionCheck(optCard, "内核启动参数 cmdline", "/proc/cmdline、bootconfig、ro.boot.*、面板参数",
                prefs.doCmdline(), new OnFlag() {
                    public void set(boolean v) { prefs.doCmdline(v); }
                });
        addOptionCheck(optCard, "截屏", "screencap 或 MediaProjection",
                prefs.doScreenshot(), new OnFlag() {
                    public void set(boolean v) { prefs.doScreenshot(v); }
                });
        addOptionCheck(optCard, "录屏", "screenrecord，失败时记录原因",
                prefs.doRecord(), new OnFlag() {
                    public void set(boolean v) { prefs.doRecord(v); }
                });
        addOptionCheck(optCard, "整机 bugreport", "很慢，可能数分钟",
                prefs.doBugreport(), new OnFlag() {
                    public void set(boolean v) { prefs.doBugreport(v); }
                });

        content.addView(optCard);

        // ------------------------------------------------------------- result
        LinearLayout resCard = Ui.card(this);
        resCard.addView(Ui.title(this, "上次结果"));
        resultBox = Ui.body(this, lastReport == null ? "还没有采集过。" : lastReport.shortSummary());
        resultBox.setPadding(0, Ui.dp(this, 6), 0, 0);
        resCard.addView(resultBox);

        LinearLayout actions = Ui.row(this);
        actions.setPadding(0, Ui.dp(this, 10), 0, 0);
        actions.addView(actionButton("打包 ZIP", new Runnable() {
            public void run() { doZip(); }
        }), weighted());
        actions.addView(actionButton("分享", new Runnable() {
            public void run() { doShare(); }
        }), weighted());
        actions.addView(actionButton("上传", new Runnable() {
            public void run() { doUpload(); }
        }), weighted());
        resCard.addView(actions);

        LinearLayout actions2 = Ui.row(this);
        actions2.setPadding(0, Ui.dp(this, 6), 0, 0);
        actions2.addView(actionButton("打开目录", new Runnable() {
            public void run() { openTargetDir(); }
        }), weighted());
        actions2.addView(actionButton("黑匣子导出", new Runnable() {
            public void run() { doExportBlackbox(); }
        }), weighted());
        resCard.addView(actions2);

        content.addView(resCard);
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = Ui.dp(this, 6);
        return lp;
    }

    private Button actionButton(String label, final Runnable action) {
        Button b = Ui.button(this, label, false);
        b.setTextSize(12f);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { action.run(); }
        });
        return b;
    }

    private interface OnFlag { void set(boolean v); }

    private void addOptionCheck(LinearLayout parent, String label, String hint,
                                boolean checked, final OnFlag sink) {
        CheckBox cb = Ui.check(this, label, checked);
        cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean v) { sink.set(v); }
        });
        LinearLayout wrap = Ui.column(this);
        wrap.setPadding(0, Ui.dp(this, 6), 0, 0);
        wrap.addView(cb);
        TextView h = Ui.text(this, "    " + hint, 11f, 0xFF6B7A8D);
        wrap.addView(h);
        parent.addView(wrap);
    }

    // ------------------------------------------------------ automation tab

    private void buildAutomationTab() {
        // --------------------------------------------------------- black box
        LinearLayout bbCard = Ui.card(this);
        bbCard.addView(Ui.title(this, "黑匣子滚动录制"));
        TextView bbHint = Ui.body(this,
                "后台持续录制全部日志缓冲区，崩溃或 ANR 时自动把最近这段时间的日志"
                + "一并存进采集目录。使用 logcat 自身的轮转，几乎不耗电。");
        bbHint.setPadding(0, Ui.dp(this, 6), 0, 0);
        bbCard.addView(bbHint);

        final TextView bbStatus = Ui.mono(this, Blackbox.status(this));
        bbStatus.setPadding(0, Ui.dp(this, 10), 0, 0);
        bbCard.addView(bbStatus);

        CheckBox bbOn = Ui.check(this, "启用黑匣子", prefs.blackboxOn());
        bbOn.setPadding(0, Ui.dp(this, 8), 0, 0);
        bbOn.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean v) {
                prefs.blackboxOn(v);
                CollectorService.reload(MainActivity.this);
                ui.postDelayed(new Runnable() {
                    public void run() { bbStatus.setText(Blackbox.status(MainActivity.this)); }
                }, 1200);
            }
        });
        bbCard.addView(bbOn);

        final LinearLayout bufSpinner = Ui.spinner(this, "缓冲区总大小",
                new String[] { "2 MB", "4 MB", "8 MB", "16 MB", "32 MB" },
                kbIndex(prefs.blackboxBufferKb(), new int[] { 2048, 4096, 8192, 16384, 32768 }));
        bbCard.addView(bufSpinner);

        final LinearLayout winSpinner = Ui.spinner(this, "导出时间窗口",
                new String[] { "最近 2 分钟", "最近 5 分钟", "最近 10 分钟", "最近 30 分钟", "全部" },
                minutesIndex(prefs.blackboxMinutes()));
        bbCard.addView(winSpinner);

        onSpinnerSelected(Ui.spinnerOf(bufSpinner), new OnInt() {
            public void run(int i) {
                int[] vals = { 2048, 4096, 8192, 16384, 32768 };
                prefs.blackboxBufferKb(vals[i]);
                CollectorService.reload(MainActivity.this);
            }
        });
        onSpinnerSelected(Ui.spinnerOf(winSpinner), new OnInt() {
            public void run(int i) { prefs.blackboxMinutes(minutesValue(i)); }
        });

        LinearLayout bbBtns = Ui.row(this);
        bbBtns.setPadding(0, Ui.dp(this, 8), 0, 0);
        bbBtns.addView(actionButton("导出黑匣子", new Runnable() {
            public void run() { doExportBlackbox(); }
        }), weighted());
        bbBtns.addView(actionButton("清空", new Runnable() {
            public void run() {
                Blackbox.stop();
                Util.deleteTree(Blackbox.dir(MainActivity.this));
                toast("黑匣子数据已清空");
                bbStatus.setText(Blackbox.status(MainActivity.this));
            }
        }), weighted());
        bbCard.addView(bbBtns);

        content.addView(bbCard);

        // ------------------------------------------------------ crash watcher
        LinearLayout cwCard = Ui.card(this);
        cwCard.addView(Ui.title(this, "崩溃 / ANR 自动采集"));

        CheckBox cwCrash = Ui.check(this, "应用崩溃时自动采集", prefs.autoCrash());
        cwCrash.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean v) {
                prefs.autoCrash(v);
                CollectorService.reload(MainActivity.this);
            }
        });
        cwCard.addView(cwCrash);

        CheckBox cwAnr = Ui.check(this, "ANR 时自动采集", prefs.autoAnr());
        cwAnr.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean v) {
                prefs.autoAnr(v);
                CollectorService.reload(MainActivity.this);
            }
        });
        cwCard.addView(cwAnr);

        TextView cwHint = Ui.body(this,
                "监听 events / crash 缓冲区里的 am_crash、am_anr、am_native_crash 记录。"
                + "自动采集会跳过 bugreport 和录屏以便快速完成，并且每小时最多触发 10 次。");
        cwHint.setPadding(0, Ui.dp(this, 8), 0, 0);
        cwCard.addView(cwHint);

        final TextView cwStatus = Ui.mono(this, CrashWatcher.statusLine());
        cwStatus.setPadding(0, Ui.dp(this, 8), 0, 0);
        cwCard.addView(cwStatus);

        content.addView(cwCard);

        // ------------------------------------------------------------- timer
        LinearLayout tmCard = Ui.card(this);
        tmCard.addView(Ui.title(this, "定时循环采集"));

        CheckBox tmOn = Ui.check(this, "启用定时采集", prefs.timerOn());
        tmOn.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean v) {
                prefs.timerOn(v);
                CollectorService.reload(MainActivity.this);
            }
        });
        tmCard.addView(tmOn);

        final LinearLayout intervalSpinner = Ui.spinner(this, "间隔",
                new String[] { "5 分钟", "15 分钟", "30 分钟", "1 小时", "2 小时", "6 小时" },
                timerIndex(prefs.timerMinutes()));
        tmCard.addView(intervalSpinner);

        final LinearLayout timerKeepSpinner = Ui.spinner(this, "保留最近几次",
                new String[] { "3 次", "5 次", "10 次", "20 次", "50 次" },
                keepIndex(prefs.timerKeep()));
        tmCard.addView(timerKeepSpinner);

        onSpinnerSelected(Ui.spinnerOf(intervalSpinner), new OnInt() {
            public void run(int i) {
                int[] vals = { 5, 15, 30, 60, 120, 360 };
                prefs.timerMinutes(vals[i]);
                CollectorService.reload(MainActivity.this);
            }
        });
        onSpinnerSelected(Ui.spinnerOf(timerKeepSpinner), new OnInt() {
            public void run(int i) {
                int[] vals = { 3, 5, 10, 20, 50 };
                prefs.timerKeep(vals[i]);
            }
        });

        TextView tmHint = Ui.body(this,
                "定时采集会按「保留最近几次」自动删除更早的目录，避免把存储写满。");
        tmHint.setPadding(0, Ui.dp(this, 8), 0, 0);
        tmCard.addView(tmHint);

        content.addView(tmCard);

        // -------------------------------------------------------------- boot
        LinearLayout bootCard = Ui.card(this);
        bootCard.addView(Ui.title(this, "开机自动采集"));
        CheckBox boot = Ui.check(this, "开机 45 秒后自动采集一次", prefs.autoBoot());
        boot.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean v) { prefs.autoBoot(v); }
        });
        bootCard.addView(boot);
        TextView bootHint = Ui.body(this,
                "延迟 45 秒是为了等存储挂载完成、日志缓冲区开始有内容。");
        bootHint.setPadding(0, Ui.dp(this, 6), 0, 0);
        bootCard.addView(bootHint);
        content.addView(bootCard);

        // Keep the live statuses ticking while this tab is visible.
        ui.post(new Runnable() {
            public void run() {
                if (currentTab != 1) return;
                bbStatus.setText(Blackbox.status(MainActivity.this));
                cwStatus.setText(CrashWatcher.statusLine());
                ui.postDelayed(this, 2000);
            }
        });
    }

    private interface OnInt { void run(int index); }

    /**
     * Attaches a selection listener to a spinner, skipping the callback Android fires when the
     * listener is first attached. Without that guard, merely opening this tab would write the
     * spinner's initial row back into settings and silently overwrite the saved value.
     */
    private void onSpinnerSelected(Spinner s, final OnInt sink) {
        if (s == null) return;
        s.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            private boolean first = true;
            public void onItemSelected(android.widget.AdapterView<?> p, View view, int pos, long id) {
                if (first) { first = false; return; }
                sink.run(pos);
            }
            public void onNothingSelected(android.widget.AdapterView<?> p) { }
        });
    }

    private static int kbIndex(int kb, int[] table) {
        for (int i = 0; i < table.length; i++) if (table[i] == kb) return i;
        return 2;
    }

    private static int minutesIndex(int m) {
        int[] t = { 2, 5, 10, 30, 0 };
        for (int i = 0; i < t.length; i++) if (t[i] == m) return i;
        return 2;
    }

    private static int minutesValue(int i) {
        int[] t = { 2, 5, 10, 30, 0 };
        return (i >= 0 && i < t.length) ? t[i] : 10;
    }

    private static int timerIndex(int m) {
        int[] t = { 5, 15, 30, 60, 120, 360 };
        for (int i = 0; i < t.length; i++) if (t[i] == m) return i;
        return 2;
    }

    private static int keepIndex(int k) {
        int[] t = { 3, 5, 10, 20, 50 };
        for (int i = 0; i < t.length; i++) if (t[i] == k) return i;
        return 2;
    }

    // -------------------------------------------------------- settings tab

    private void buildSettingsTab() {
        LinearLayout dirCard = Ui.card(this);
        dirCard.addView(Ui.title(this, "输出目录"));

        final LinearLayout dirField = Ui.field(this, "采集根目录", prefs.baseDir(),
                InputType.TYPE_CLASS_TEXT);
        dirCard.addView(dirField);
        dirCard.addView(actionButton("保存目录", new Runnable() {
            public void run() {
                String v = Ui.editTextOf(dirField).getText().toString().trim();
                if (v.length() == 0) { toast("目录不能为空"); return; }
                prefs.baseDir(v);
                toast("已保存：" + v);
                refreshCapabilities();
            }
        }));

        TextView dirHint = Ui.body(this,
                "每次采集会在该目录下新建一个 <时间戳> 子目录，并更新 latest 指向最新一次。"
                + "Android 11+ 需要「所有文件访问权限」才能写入 /sdcard/log。");
        dirHint.setPadding(0, Ui.dp(this, 8), 0, 0);
        dirCard.addView(dirHint);
        content.addView(dirCard);

        // -------------------------------------------------------- permissions
        LinearLayout permCard = Ui.card(this);
        permCard.addView(Ui.title(this, "权限与系统设置"));

        permCard.addView(actionButton("授予「所有文件访问权限」", new Runnable() {
            public void run() { requestAllFiles(); }
        }));
        Ui.space(this, permCard, 6);
        permCard.addView(actionButton("授予通知权限", new Runnable() {
            public void run() { requestNotifications(); }
        }));
        Ui.space(this, permCard, 6);
        permCard.addView(actionButton("忽略电池优化", new Runnable() {
            public void run() { Packager.requestIgnoreBatteryOptimizations(MainActivity.this); }
        }));
        Ui.space(this, permCard, 6);
        permCard.addView(actionButton("查看 adb 授权命令", new Runnable() {
            public void run() { showAdbHelp(); }
        }));

        content.addView(permCard);

        // ------------------------------------------------------------ limits
        LinearLayout limCard = Ui.card(this);
        limCard.addView(Ui.title(this, "采集量上限"));

        final LinearLayout logField = Ui.field(this, "logcat 每个缓冲区行数上限（KB 估算）",
                String.valueOf(prefs.logTailKb()), InputType.TYPE_CLASS_NUMBER);
        limCard.addView(logField);

        final LinearLayout dmesgField = Ui.field(this, "dmesg 保留大小（KB）",
                String.valueOf(prefs.dmesgTailKb()), InputType.TYPE_CLASS_NUMBER);
        limCard.addView(dmesgField);

        final LinearLayout keepField = Ui.field(this, "普通采集保留最近几次",
                String.valueOf(prefs.keepRuns()), InputType.TYPE_CLASS_NUMBER);
        limCard.addView(keepField);

        limCard.addView(actionButton("保存上限", new Runnable() {
            public void run() {
                prefs.logTailKb(parseInt(Ui.editTextOf(logField).getText().toString(), prefs.logTailKb()));
                prefs.dmesgTailKb(parseInt(Ui.editTextOf(dmesgField).getText().toString(), prefs.dmesgTailKb()));
                prefs.keepRuns(parseInt(Ui.editTextOf(keepField).getText().toString(), prefs.keepRuns()));
                toast("已保存");
            }
        }));

        CheckBox shell = Ui.check(this, "允许用 shell 重定向写入目标目录", prefs.shellFallback());
        shell.setPadding(0, Ui.dp(this, 10), 0, 0);
        shell.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean v) {
                prefs.shellFallback(v);
            }
        });
        limCard.addView(shell);
        TextView shellHint = Ui.text(this,
                "    关掉后，若应用无权直接写目标目录，就会回退到应用私有目录，"
                + "而不会通过 sh -c 管道写入。", 11f, 0xFF6B7A8D);
        limCard.addView(shellHint);

        content.addView(limCard);

        // ------------------------------------------------------------ upload
        LinearLayout upCard = Ui.card(this);
        upCard.addView(Ui.title(this, "上传"));

        final LinearLayout urlField = Ui.field(this, "HTTP 上传地址（POST multipart）",
                prefs.uploadUrl(), InputType.TYPE_TEXT_VARIATION_URI);
        upCard.addView(urlField);

        final LinearLayout tokField = Ui.field(this, "Token（可选）",
                prefs.uploadToken(), InputType.TYPE_CLASS_TEXT);
        upCard.addView(tokField);

        CheckBox autoUp = Ui.check(this, "采集完成后自动上传", prefs.autoUpload());
        autoUp.setPadding(0, Ui.dp(this, 8), 0, 0);
        autoUp.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean v) { prefs.autoUpload(v); }
        });
        upCard.addView(autoUp);

        upCard.addView(actionButton("保存上传设置", new Runnable() {
            public void run() {
                prefs.uploadUrl(Ui.editTextOf(urlField).getText().toString());
                prefs.uploadToken(Ui.editTextOf(tokField).getText().toString());
                toast("已保存");
            }
        }));

        TextView upHint = Ui.body(this,
                "上传时文件以 multipart/form-data 的 file 字段发送，同时附带 device、android、"
                + "filename 字段；Token 会同时作为表单字段和 X-Auth-Token 头发送。");
        upHint.setPadding(0, Ui.dp(this, 8), 0, 0);
        upCard.addView(upHint);
        content.addView(upCard);

        // ------------------------------------------------------------- danger
        LinearLayout dgCard = Ui.card(this);
        dgCard.addView(Ui.title(this, "维护"));
        dgCard.addView(actionButton("清理黑匣子数据", new Runnable() {
            public void run() {
                Blackbox.stop();
                Util.deleteTree(Blackbox.dir(MainActivity.this));
                toast("已清理");
            }
        }));
        Ui.space(this, dgCard, 6);
        dgCard.addView(actionButton("恢复默认设置", new Runnable() {
            public void run() { confirmReset(); }
        }));
        content.addView(dgCard);

        LinearLayout about = Ui.card(this);
        about.addView(Ui.title(this, "关于"));
        about.addView(Ui.mono(this, buildAboutText()));
        content.addView(about);
    }

    private String buildAboutText() {
        StringBuilder sb = new StringBuilder();
        sb.append("版本      : 1.0\n");
        sb.append("构建      : 免 Gradle（aapt2 / javac / d8）\n");
        sb.append("调试标签  : adb logcat -s DSH-LC:V\n");
        if (caps != null) {
            sb.append("设备      : ").append(caps.deviceModel).append("\n");
            sb.append("Android   : ").append(caps.androidRelease)
              .append(" (API ").append(caps.sdkInt).append(")\n");
            sb.append("build.type: ").append(caps.buildType).append("\n");
            sb.append("root      : ").append(caps.root ? "有" : "无").append("\n");
        }
        return sb.toString();
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Throwable t) { return def; }
    }

    // -------------------------------------------------------------- actions

    private void refreshCapabilities() {
        if (probing) return;
        probing = true;
        capabilityPanel.removeAllViews();
        capabilityPanel.addView(Ui.body(this, "检测中…"));

        new Thread(new Runnable() {
            public void run() {
                final Capabilities c = Capabilities.probe(MainActivity.this, prefs.baseDir());
                ui.post(new Runnable() {
                    public void run() {
                        caps = c;
                        probing = false;
                        renderCapabilities(c);
                    }
                });
            }
        }, "capability-probe").start();
    }

    private void renderCapabilities(Capabilities c) {
        if (capabilityPanel == null) return;
        capabilityPanel.removeAllViews();

        java.util.List<String[]> rows = c.summaryRows(this, prefs.baseDir());
        for (String[] r : rows) {
            LinearLayout row = Ui.row(this);
            row.setPadding(0, Ui.dp(this, 4), 0, 0);

            TextView name = Ui.text(this, r[0], 12.5f, Ui.DIM);
            row.addView(name, new LinearLayout.LayoutParams(
                    Ui.dp(this, 96), ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView value = Ui.text(this, r[1], 12.5f, Ui.statusColor(r[1]));
            value.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            row.addView(value, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            capabilityPanel.addView(row);

            if (r[2] != null && r[2].length() > 0) {
                TextView how = Ui.mono(this, r[2]);
                how.setTextColor(0xFF6B7A8D);
                how.setPadding(Ui.dp(this, 96), 0, 0, 0);
                capabilityPanel.addView(how);
            }
        }

        if (c.needsAllFilesRequest()) {
            Button grant = Ui.button(this, "点此授予「所有文件访问权限」", true);
            grant.setTextSize(12f);
            grant.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { requestAllFiles(); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(this, 10);
            capabilityPanel.addView(grant, lp);
        }

        updateServiceStatus();
    }

    private void startCollection() {
        if (CollectorService.isBusy()) { toast("已有采集任务在运行"); return; }
        CollectorService.requestCollect(this, "手动");
        toast("开始采集…");
    }

    private void doZip() {
        final Collector.Report r = lastReport;
        if (r == null || !r.ok) { toast("还没有可打包的采集结果"); return; }
        toast("打包中…");
        new Thread(new Runnable() {
            public void run() {
                final Storage.Session s = new Storage.Session(r.requestedDir, r.dir, new File(r.dir),
                        r.tier, r.tierDetail, new File(r.dir).getName());
                final Packager.Result zr = Packager.zip(MainActivity.this, s, null);
                ui.post(new Runnable() {
                    public void run() {
                        if (zr.ok) {
                            lastReport.zipPath = zr.path;
                            toast("已打包 " + zr.entries + " 个文件, " + Util.bytes(zr.bytes));
                            if (resultBox != null) {
                                resultBox.setText(r.shortSummary()
                                        + "\nZIP: " + zr.path
                                        + (zr.skipped.isEmpty() ? ""
                                           : "\n跳过 " + zr.skipped.size() + " 个不可读文件"));
                            }
                        } else {
                            toast("打包失败: " + zr.detail);
                        }
                    }
                });
            }
        }, "zip").start();
    }

    private void doShare() {
        Collector.Report r = lastReport;
        if (r == null || !r.ok) { toast("还没有采集结果"); return; }

        File target = null;
        String mime = "application/zip";
        if (r.zipPath != null && r.zipPath.length() > 0 && new File(r.zipPath).exists()) {
            target = new File(r.zipPath);
        } else {
            // No zip yet - share the most useful single file instead, and say so.
            File dmesg = new File(r.dir, "dmesg.txt");
            File idx = new File(r.dir, "index.txt");
            if (dmesg.exists()) { target = dmesg; mime = "text/plain"; }
            else if (idx.exists()) { target = idx; mime = "text/plain"; }
        }
        if (target == null) { toast("没有可分享的文件，请先打包 ZIP"); return; }

        // Copy into the app's own external dir first: that path is always readable by the
        // receiving app, whatever the target directory's ownership ended up being.
        File shareable = Packager.exportToAppDir(this, target);
        String err = Packager.share(this, shareable, mime, "分享日志");
        if (err != null) toast("分享失败: " + err);
    }

    private void doUpload() {
        final Collector.Report r = lastReport;
        if (r == null || !r.ok) { toast("还没有采集结果"); return; }
        if (prefs.uploadUrl().length() == 0) {
            toast("请先在「设置」里填写上传地址");
            showTab(2);
            return;
        }

        File zip = (r.zipPath != null && r.zipPath.length() > 0) ? new File(r.zipPath) : null;
        if (zip == null || !zip.exists()) {
            toast("请先「打包 ZIP」");
            return;
        }

        toast("上传中…");
        new Thread(new Runnable() {
            public void run() {
                final Packager.UploadResult ur = Packager.upload(
                        MainActivity.this, new File(r.zipPath), prefs.uploadUrl(), prefs.uploadToken());
                ui.post(new Runnable() {
                    public void run() {
                        if (ur.ok) toast("上传成功 (" + ur.ms + " ms)");
                        else toast("上传失败: " + ur.detail);
                        if (resultBox != null && ur.response.length() > 0) {
                            resultBox.setText(r.shortSummary() + "\n上传: " + ur.detail
                                    + "\n" + Util.trimTo(ur.response, 400));
                        }
                    }
                });
            }
        }, "upload").start();
    }

    private void doExportBlackbox() {
        if (Blackbox.sizeOnDisk(this) == 0) {
            toast("黑匣子没有数据（未启用或刚启动）");
            return;
        }
        toast("导出中…");
        new Thread(new Runnable() {
            public void run() {
                final Blackbox.Extract ex = Blackbox.extract(MainActivity.this, prefs.blackboxMinutes());
                final File out = new File(getExternalFilesDir("shared"),
                        "blackbox_" + Util.stamp() + ".txt");
                final boolean wrote = Util.writeFile(out, ex.text);
                ui.post(new Runnable() {
                    public void run() {
                        if (!wrote) { toast("导出失败"); return; }
                        toast("已导出 " + ex.lines + " 行");
                        String err = Packager.share(MainActivity.this, out, "text/plain", "分享黑匣子日志");
                        if (err != null) toast("分享失败: " + err + "（文件在 " + out.getAbsolutePath() + "）");
                    }
                });
            }
        }, "blackbox-export").start();
    }

    private void openTargetDir() {
        String dir = (lastReport != null && lastReport.dir != null) ? lastReport.dir : prefs.baseDir();
        File f = new File(dir);

        // No universal "open this directory" intent exists; try the ones that usually work,
        // then fall back to showing the path so it can be pasted anywhere.
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.fromFile(f), "resource/folder");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return;
        } catch (Throwable ignored) { }

        try {
            Intent i = new Intent("android.intent.action.VIEW");
            i.setDataAndType(Uri.parse("content://" + getPackageName() + ".share" + dir), "*/*");
            startActivity(i);
            return;
        } catch (Throwable ignored) { }

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("采集目录");
        b.setMessage(dir + "\n\n（本机没有能打开该目录的文件管理器，已复制路径到剪贴板）");
        copyToClipboard("logcollector.path", dir);
        b.setPositiveButton("好", null);
        b.show();
    }

    private void copyToClipboard(String label, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText(label, text));
        } catch (Throwable ignored) { }
    }

    private void showAdbHelp() {
        final String pkg = getPackageName();
        final String msg =
                "非 root 的 userdebug / eng 版本上，用下面两条命令给本应用授权，"
                + "之后 dmesg 与全量 dumpsys 都能直接采集：\n\n"
                + "adb shell pm grant " + pkg + " android.permission.READ_LOGS\n"
                + "adb shell pm grant " + pkg + " android.permission.DUMP\n\n"
                + "Android 11+ 写 /sdcard/log 还需要：\n\n"
                + "adb shell appops set --uid " + pkg + " MANAGE_EXTERNAL_STORAGE allow\n\n"
                + "dmesg 若仍被拒绝，只能：\n\n"
                + "adb root\n"
                + "adb shell dmesg > dmesg.txt\n\n"
                + "查看本应用自己的运行日志：\n\n"
                + "adb logcat -s DSH-LC:V";
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("adb 授权命令");
        b.setMessage(msg);
        b.setPositiveButton("复制", new android.content.DialogInterface.OnClickListener() {
            public void onClick(android.content.DialogInterface d, int w) {
                copyToClipboard("logcollector.adb", msg);
                toast("已复制到剪贴板");
            }
        });
        b.setNeutralButton("好", null);
        b.show();
    }

    private void confirmReset() {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("恢复默认设置");
        b.setMessage("所有开关和目录设置都会回到初始值，已采集的日志不会被删除。");
        b.setPositiveButton("恢复", new android.content.DialogInterface.OnClickListener() {
            public void onClick(android.content.DialogInterface d, int w) {
                prefs.raw().edit().clear().apply();
                CollectorService.reload(MainActivity.this);
                showTab(currentTab);
                refreshCapabilities();
                toast("已恢复默认设置");
            }
        });
        b.setNegativeButton("取消", null);
        b.show();
    }

    // ---------------------------------------------------------- permissions

    private void requestAllFiles() {
        if (Build.VERSION.SDK_INT < 30) {
            if (Build.VERSION.SDK_INT >= 23) {
                requestPermissions(new String[] {
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        Manifest.permission.READ_EXTERNAL_STORAGE }, REQ_PERMS);
            }
            return;
        }
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Throwable t2) {
                toast("无法打开设置页，请手动到「应用 → 所有文件访问权限」");
            }
        }
    }

    private void requestNotifications() {
        if (Build.VERSION.SDK_INT < 33) { toast("此版本无需通知权限"); return; }
        requestPermissions(new String[] { "android.permission.POST_NOTIFICATIONS" }, REQ_NOTIF);
    }

    private void requestProjection() {
        if (Build.VERSION.SDK_INT < 21) { toast("系统版本过低，不支持"); return; }
        try {
            MediaProjectionManager mpm =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (mpm == null) { toast("系统不支持录屏授权"); return; }
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION);
        } catch (Throwable t) {
            toast("无法发起授权: " + t.getMessage());
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PROJECTION) {
            if (res == RESULT_OK && data != null) {
                MediaCollector.ProjectionGrant.INSTANCE =
                        new MediaCollector.ProjectionGrant(res, data);
                toast("已获得截屏授权，下次采集会自动使用");
            } else {
                toast("已取消截屏授权");
            }
        }
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        refreshCapabilities();
    }

    // ------------------------------------------------------ service listener

    @Override public void onStateChanged() {
        ui.post(new Runnable() { public void run() { updateServiceStatus(); } });
    }

    @Override public void onProgress(final String phase, final String detail) {
        ui.post(new Runnable() {
            public void run() {
                if (progressLine == null) return;
                progressLine.setVisibility(View.VISIBLE);
                progressLine.setText("● " + phase + " · " + detail);
            }
        });
    }

    @Override public void onRunFinished(final Collector.Report report) {
        ui.post(new Runnable() {
            public void run() {
                lastReport = report;
                if (progressLine != null) progressLine.setVisibility(View.GONE);
                updateServiceStatus();

                if (resultBox != null) {
                    StringBuilder sb = new StringBuilder();
                    sb.append(report.shortSummary());
                    if (report.latestResult != null && report.latestResult.length() > 0) {
                        sb.append("\nlatest: ").append(report.latestResult);
                    }
                    if (report.pruned > 0) sb.append("\n已清理旧目录: ").append(report.pruned);
                    if (!report.warnings.isEmpty()) {
                        sb.append("\n");
                        for (String w : report.warnings) sb.append("\n⚠ ").append(w);
                    }
                    resultBox.setText(sb.toString());
                }
                toast(report.ok ? ("采集完成: " + report.files + " 个文件") : ("采集失败: " + report.error));
            }
        });
    }

    private void updateServiceStatus() {
        if (statusLine == null) return;
        StringBuilder sb = new StringBuilder();
        if (CollectorService.isBusy()) {
            sb.append("● 采集中");
            String d = CollectorService.detail();
            if (d != null && d.length() > 0) sb.append(" · ").append(d);
        } else {
            sb.append("空闲");
            if (Blackbox.isRunning()) sb.append(" · 黑匣子开");
            if (CrashWatcher.isRunning()) sb.append(" · 监听开");
            if (prefs.timerOn()) sb.append(" · 定时 ").append(prefs.timerMinutes()).append(" 分");
        }
        String dir = (lastReport != null && lastReport.ok) ? lastReport.dir : prefs.baseDir();
        sb.append("  →  ").append(dir);
        statusLine.setText(sb.toString());
    }

    private void toast(String msg) {
        try { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) { }
    }
}
