# DSH LogCollector — 安卓调试信息采集 APK

一键把 **dmesg + logcat + 各类 dumpsys** 采集到 `/sdcard/log`，并附带黑匣子录制、崩溃自动采集、显示背光专项、整机 bugreport 等调试功能。

**成品**：[`dist/LogCollector.apk`](dist/LogCollector.apk)（124 KB，已签名，v1+v2+v3，Android 5.0 ~ 14 可装）

---

## 一、装到设备上

```powershell
adb install -r dist\LogCollector.apk
```

或用工作区里现成的构建脚本一步到位（编译 + 签名 + 安装）：

```powershell
pwsh -File tools\build.ps1 -Install
```

装完在桌面上叫 **「日志采集」**。

---

## 二、能采集什么

点一下「立即采集」，会在 `/sdcard/log/<时间戳>/` 下生成：

```
/sdcard/log/
├── 20260101-120000/
│   ├── index.txt                  本次每个产物怎么跑的、成没成、多大
│   ├── verify.txt                 ★ 自检：这次抓全了吗？缺什么、怎么补
│   ├── capability.txt             设备与权限报告（缺什么、怎么补）
│   ├── cmdline.txt                ★ 内核启动参数 / boot args / 面板参数
│   ├── dmesg.txt                  内核环形缓冲区（**应用侧通常拿不到，见下**）
│   ├── dmesg_human_time.txt       dmesg -T 墙钟时间版
│   ├── dmesg_levels.txt           按内核日志级别统计行数
│   ├── kernel_log.txt             ★ 内核日志的实际来源（logcat -b kernel）
│   ├── kernel_log_errors.txt      内核 E 级提取
│   ├── kernel_log_warnings.txt    内核 W 级提取
│   ├── logcat_kernel.txt          kernel 缓冲区原文
│   ├── logcat_main.txt            各缓冲区单独成文件
│   ├── logcat_system.txt / _crash.txt / _events.txt / _radio.txt …
│   ├── logcat_all.txt             全部缓冲区合并（最常看的那个）
│   ├── logcat_fatal.txt           崩溃与致命信号提取
│   ├── logcat_anr.txt             ANR 报告提取
│   ├── logcat_native_crash.txt    native crash / tombstone 线索
│   ├── logcat_watchdog.txt        watchdog 击杀
│   ├── logcat_lowmemory.txt       lmkd 内存击杀
│   ├── logcat_display.txt         显示 / 面板 / 背光相关行
│   ├── logcat_buffer_sizes.txt    日志缓冲区实际大小
│   ├── dumpsys/                   每个服务一个文件（全量）
│   │   ├── _service_list.txt      dumpsys -l 的原始输出
│   │   ├── activity.txt / window.txt / power.txt / meminfo.txt …
│   ├── dumpsys_all.txt            不带动参的全量 dump 合并流
│   ├── prop.txt / prop_sorted.txt / prop_selected.txt   系统属性
│   ├── kernel/                    /proc 与 /sys 快照
│   │   ├── proc_meminfo / proc_interrupts / proc_vmstat / proc_slabinfo …
│   │   ├── thermal.txt            温度区 + cooling device（看是否在降频）
│   │   ├── regulators.txt         各路供电（面板供电没起来一眼能看出来）
│   │   └── debugfs_listing.txt
│   ├── display/                   ★ 面板调试专项
│   │   ├── backlight_nodes.txt    所有 backlight/LED 节点全属性
│   │   ├── displayconfig.xml      /vendor/etc/displayconfig 全部 xml 原文
│   │   ├── brightness_map_extract.txt  screenBrightnessMap / HBM 提取
│   │   ├── panel_info.txt         DRM / DSI / fb / device-tree 面板信息
│   │   ├── brightness_timeline.txt 亮度节点 5 秒 × 11 次采样
│   │   ├── svc_SurfaceFlinger.txt / svc_display.txt / svc_power.txt …
│   │   └── summary.txt
│   ├── media/
│   │   ├── screenshot.png         截屏
│   │   └── screenrecord.mp4       录屏（可选）
│   ├── bugreport/
│   │   └── bugreport.zip          整机 bugreport（可选，慢）
│   ├── blackbox_window.txt        崩溃时自动附带的最近日志窗口
│   ├── dmesg_from_pc.txt          ← 电脑侧补齐（应用侧被 seccomp 拦截）
│   ├── dumpsys_from_pc.txt        ← 电脑侧补齐（shell 身份的全量 dump）
│   └── crash_artefacts_from_pc.txt ← 电脑侧补齐（tombstone / ANR / pstore 清单）
├── latest                         指向最新一次（软链接或 WHERE.txt）
└── auto-crash-20260101-120500/    崩溃自动采集的目录（前缀标明触发原因）
```

`index.txt` 是理解一次采集的关键：逐行记录每个产物用的是 **root 还是 app 身份**、退出码多少、产出多大，失败的直接写明原因和补救命令。

---

## 三、权限：分层自动兼容

这是本项目最花心思的部分。普通应用**默认读不到 dmesg，也没有 `android.permission.DUMP`**，所以采集能力完全取决于设备环境。APK 会自动探测并逐级降级，界面上明确告诉你缺哪一项。

### 采集通道（自动选择，无需配置）

| 环境 | 能力 |
|---|---|
| **有 root（`su` 可用）** | dmesg、全量 dumpsys、debugfs、regulator、thermal、bugreport 全部可采 |
| **userdebug/eng，无 root** | 授权后 logcat 与 dumpsys 基本全量可用。**`dmesg` 除外 —— 见下方说明** |
| **普通 user 版本，无 root** | logcat 仅本应用可见；dumpsys 大部分被拒 —— 界面会说明 |
| **兜底** | 无论如何都会采集 `/proc`、`/sys` 里公开的部分，并把不可读项的原因写进 index.txt |

### ⚠️ `dmesg` 在应用侧拿不到 —— 但内核日志没丢

这是真机验证得到的明确结论。应用调用 `dmesg` 会被内核 **seccomp 过滤器**以 SIGSYS 杀死（exit 159）：

```
signal 31 (SIGSYS), code 1 (SYS_SECCOMP)
Cause: seccomp prevented call to disallowed arm64 system call 116   ← syslog
```

`syslog` 系统调用从应用的安全域里被移除了，**`pm grant` / appops / 应用内启动的 root helper 都绕不过**。判定依据（无需真的调用 `dmesg`）：

```
/proc/self/status → Seccomp: 2     (应用域，filter 模式 → syslog 被禁)
/proc/self/status → Seccomp: 0     (adb shell 域，无过滤器 → 可用)
```

**为什么本应用不再重试 `dmesg`**：每次被 SIGSYS 杀死，内核都会写一个 tombstone 到 `/data/tombstones/`，而那是 **32 个的环形缓冲**，存的正是 native crash 现场。真机实测 32/32 个 tombstone 全部来自本应用的 `dmesg` 调用 —— 约 5 轮采集就足以覆盖设备上所有真实崩溃证据。所以本应用**探测一次后即永久停止重试**，并把结论持久化。

#### ★ 内核日志实际在 `kernel_log.txt`

**`logcat -b kernel` 通过 logd 暴露同一个内核环形缓冲区，非 root 即可读**（真机实测 6944～7059 行）。本应用会：

1. 写 `kernel_log.txt`，并在文件头**明确说明这不是 dmesg**、与真 dmesg 的差异（时间戳格式、列格式、缓冲区容量）
2. 额外提取 `kernel_log_errors.txt`（E 级）与 `kernel_log_warnings.txt`（W 级）
3. 若该缓冲区在本机也为空，**仍写占位文件说明原因**，绝不留空文件

需要**真正的** dmesg 时（含内核 uptime 时间戳），用附带的电脑侧脚本：

```powershell
pwsh -File tools\augment_from_pc.ps1
# 产出 dmesg_from_pc.txt（实测 1 MB / 11,610 行，含 DSI/面板/背光日志）
#    + dumpsys_from_pc.txt（实测 15.2 MB / 242 个服务）
```

### ★ `cmdline.txt` —— 内核启动参数

三条来源自动回退，保证任何机器都有数据：

| 来源 | 非 root 可读 | 说明 |
|---|---|---|
| `/proc/cmdline` | **否** | SELinux 标为 `proc_cmdline`，连 shell 都被拒，只有 root 能读 |
| `/proc/bootconfig` | Android 12+ | GKI 才有 |
| **`ro.boot.*` 属性** | **是** | cmdline 里 `androidboot.*` 的镜像，**任何机器都可读**（实测 43 个） |

`cmdline.txt` 包含原始 cmdline、逐参数 `key = value` 表格、全部 `ro.boot.*`、启动要点表（SoC / SKU / 硬件版本 / 序列号 / bootloader / 基带 / A/B 槽位 / verified boot / 解锁状态 / dm-verity / **重启原因** / 写保护 等 23 项），以及**面板参数提取**。

面板调试重点看这一行 —— 它决定内核绑定哪个 panel 驱动：

```
msm_drm.dsi_display0=qcom,mdss_dsi_vtdisp_...        ← 从 cmdline / ro.boot 提取
```

### ★ `verify.txt` —— 每次采集的自检

采完自动审计整个目录，把每个产物归为 **OK / PLACEHOLDER / THIN / EMPTY / MISSING**，给出结论（`GOOD` / `USABLE WITH GAPS` / `INCOMPLETE`）、需关注清单（含原因与原文摘录）、**可读性检查**（逐个打开读前 4 KB，检查是否可读、是否含 NUL），以及针对每类缺口的补齐命令。

**"这次抓全了没有"是一个文件的答案，不用翻目录猜。**

### 提权命令（界面上也有「查看 adb 授权命令」按钮，可直接复制）

```powershell
# 1. 让本应用能读日志、能跑 dumpsys（userdebug / eng 版本有效）
adb shell pm grant com.dsh.logcollector android.permission.READ_LOGS
adb shell pm grant com.dsh.logcollector android.permission.DUMP

# 2. Android 11+ 写 /sdcard/log 需要「所有文件访问权限」
adb shell appops set --uid com.dsh.logcollector MANAGE_EXTERNAL_STORAGE allow

# 3. 解锁另外 ~18 个 dumpsys 服务
#    这些服务即使 DUMP 已授予也会拒绝，理由是缺 PACKAGE_USAGE_STATS。
#    appops 这行最稳；下面两行能生效是因为本应用已在其 manifest 里声明了这些权限。
adb shell appops set --uid com.dsh.logcollector GET_USAGE_STATS allow
adb shell pm grant com.dsh.logcollector android.permission.PACKAGE_USAGE_STATS
adb shell pm grant com.dsh.logcollector android.permission.BATTERY_STATS
```

实测效果：被拒服务 **18 → 1**，采集总量 **3.2 MB → 10.3 MB**，`dumpsys/activity.txt` 由被拒变为 1,159,004 字节完整 dump。（仅剩 `location` 需要 `INTERACT_ACROSS_USERS`，非 root 确实拿不到。）

### 写 `/sdcard/log` 的四级降级

Android 10 起的分区存储会让 `/sdcard/log` 直接不可写。APK 按下面顺序自动尝试：

1. **直接写** — 有旧版存储权限或「所有文件访问权限」时，普通文件 IO 即可
2. **root 搬运** — 内容先在应用私有目录落盘，再用 `su cp` 搬进目标目录（并 `chmod 666`）
3. **shell 重定向** — 无 root 时用 `sh -c 'cat > 目标'` 管道写入
4. **回退到应用私有目录** — 以上都不行时落到应用自己的外部目录，并在界面和 `capability.txt` 里**明确说明真实路径未能使用**，而不是假装成功

`latest` 快捷目录同理：优先建真软链接，FUSE 拒绝软链接时退化为 `latest/WHERE.txt` 指针文件。

---

## 四、附加调试功能

### 黑匣子滚动录制
后台常驻一个 `logcat` 子进程，利用 logcat **自身的 `-r`/`-n` 轮转**写文件 —— 不轮询、不解析、不涨堆，几乎不耗电。缓冲区大小和窗口可调（2–32 MB）。崩溃时可把最近 2–30 分钟的日志一并导出。

### 崩溃 / ANR 自动采集
监听 `events` 与 `crash` 缓冲区里的 `am_crash`、`am_anr`、`am_native_crash`、watchdog 记录，以及**明确标注 low memory** 的 `am_kill`。触发后自动跑一轮采集（**自动跳过 bugreport 和录屏**以保证快速完成），并把黑匣子窗口附进同一目录。带 20 秒去抖和**每小时最多 10 次**的上限。

> **不把 `am_proc_died` 当崩溃信号**。真机实测它对每次普通进程回收都会产生（该机每小时数百次），用它触发等于把正常回收误判为崩溃 —— 实测造成 2 次无意义采集。另外 `logcat -b events` 打开时会回放整个缓冲区（实测 620 条历史事件），所以启动后有 8 秒预热期并校验行内时间戳，避免被数小时前的事件触发。

### 显示 / 背光专项快照
针对面板调试场景：把所有 backlight/LED 节点连同属性全量 dump、抓取 `/vendor/etc/displayconfig/*.xml` 原文并**单独提取 `screenBrightnessMap` / HBM / transitionPoint**、采样亮度节点时间线（能区分「节点卡死」和「只是没在对的时机读到」）、以及 SurfaceFlinger/display/power 等服务。

> 这块特意做成每次采集都留一份，因为 `screenBrightnessMap` 的 `<value>`/`<nits>` 必须非递减，否则整表被丢弃 → `getBrightnessFromBacklight` NPE → `system_server` 崩溃重启。事后复盘时有原始 XML 才说得清。

### 整机 bugreport
优先调 `bugreport -p -o <file>`（新版本产出 zip），失败再试旧签名 `bugreport <file>`。**两者都不可用时不会空手而归**：自动生成脚本化近似版本，覆盖 dropbox、tombstone、ANR traces、pstore/last_kmsg、reboot reason、SELinux、进程表等 18 个板块，并注明哪几段真的取到了。

### 内核专项
`/proc` 与 `/sys` 三十多项快照，加上 thermal zones、cooling devices、regulator 状态（面板供电是否真的起来了）、debugfs 列表。

### App 内实时日志查看器
实时滚动 logcat，按 tag / 关键字（`|` 分隔多条件）/ 最低级别过滤，按级别着色。用 `SpannableStringBuilder` 逐行着色而非逐行 `Html.fromHtml`，3000 行环形缓冲 + 最高 10 Hz 重绘，日志再吵也不卡。支持一键存快照并分享。

### 截屏 / 录屏归档
三层降级：root 直写 → shell 落到暂存区再搬运 → **MediaProjection**（界面上点「授权截屏」）。录屏用 `screenrecord --time-limit`，靠自身时限结束而**不 kill 进程**（kill 会写坏 mp4）。

### 一键打包 zip + 分享 / 上传
打包成 `log_<时间戳>.zip` 放在采集目录内。读不到的文件不会静默消失，会列进 `zip_skipped.txt`。分享走系统分享面板（自带只读 `content://` provider，并限制只能分享日志树内的文件）。上传用 `multipart/form-data`，Token 同时作为表单字段和 `X-Auth-Token` 头发送。

### 定时循环采集
按 5 分钟 ~ 6 小时间隔自动采集，并按「保留最近几次」自动清理旧目录，避免写满存储。

### 开机自动采集
开机后 **延迟 45 秒**再采 —— 太早的话存储还没挂完、日志缓冲区也没内容。

### 一个手动触发入口
通知栏常驻通知带「立即采集」动作，不用进 App。

---

## 五、重新编译

```powershell
pwsh -File tools\build.ps1            # 编译 + 对齐 + 签名
pwsh -File tools\build.ps1 -Clean     # 先清 build 目录
pwsh -File tools\build.ps1 -Install   # 顺带 adb install -r
```

流程是 `aapt2 compile → aapt2 link → javac → d8 → zipalign → apksigner`，**不依赖 Gradle，不需要联网**拉依赖，几秒钟出包。

工具链（已在本机验证）：

| 组件 | 路径 |
|---|---|
| JDK | `C:\Program Files\JetBrains\PyCharm Community Edition 2023.3.2\jbr` |
| SDK | `D:\Android\Sdk`（build-tools 34.0.0，platforms android-34） |
| 签名 | `keystore\logcollector.jks`，口令 `logcollect2026`，别名 `logcollect` |

---

## 六、目录结构

```
logcollector/
├── dist/LogCollector.apk          成品（已签名）
├── keystore/logcollector.jks      签名密钥
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/dsh/logcollector/
│   │   ├── MainActivity.java          主界面（能力面板 / 采集 / 自动化 / 设置）
│   │   ├── LogcatViewerActivity.java  实时日志查看器
│   │   ├── CollectorService.java      前台服务：黑匣子 / 监听 / 定时 / 执行采集
│   │   ├── BootReceiver.java          开机与升级后自启
│   │   ├── Collector.java             采集引擎（dmesg/logcat/dumpsys/prop/kernel/bugreport）
│   │   ├── DisplayCollector.java      显示背光专项
│   │   ├── MediaCollector.java        截屏录屏三层降级
│   │   ├── Blackbox.java              黑匣子滚动录制与窗口导出
│   │   ├── CrashWatcher.java          崩溃 / ANR 监视
│   │   ├── Storage.java               输出布局 + 四级写入降级 + latest + 保留策略
│   │   ├── Shell.java                 分层执行（root / app）+ 超时 + 进程回收
│   │   ├── Capabilities.java          能力探测与报告
│   │   ├── Logcat.java                日志缓冲区探测（-b all 并非处处支持）
│   │   ├── Packager.java              zip / 分享 / HTTP 上传
│   │   ├── SilentFileProvider.java    只读 content:// provider
│   │   ├── Prefs.java / Util.java / Ui.java / Log.java
│   └── res/                       图标、主题、颜色、字符串
└── tools/
    ├── build.ps1                  一键编译
    ├── augment_from_pc.ps1        电脑侧补齐（dmesg / 全量 dumpsys / bugreport）
    ├── make_icons.py              生成图标
    ├── LogicTest.java             针对已编译字节码的逻辑与转义测试
    └── check_shell_commands.sh    校验采集器全部 shell 命令的语法
```

---

## 七、已验证 / 未验证（说实话的部分）

### 已实测通过

**已在真机端到端验证**：moto g(100)，Android 12（API 31），`userdebug`。三种权限场景全部跑通，完整报告见 [DMESG_CMDLINE_ANALYSIS.md](DMESG_CMDLINE_ANALYSIS.md)。

| 场景 | 实测结果 |
|---|---|
| **全权限**（READ_LOGS + DUMP + PACKAGE_USAGE_STATS + appops） | **209 个产物 / 49.6 MB**；自检 `OK 182 / PLACEHOLDER 9 / EMPTY 0`，结论 `USABLE WITH GAPS`，需关注清单仅 1 条 |
| **无任何授权**（模拟普通 user 版） | **205 个产物**；正确回退到应用私有目录并**明确说明**未使用 `/sdcard/log` |
| **root 侧补齐** | 补入 1.0 MB dmesg + 14.9 MB dumpsys + 面板节点 |

| 验证项 | 结果 |
|---|---|
| **`kernel_log.txt`**（dmesg 替代通道） | **172,886 字节**真实内核日志 —— 非 root 可用 |
| **`cmdline.txt`**（新增） | **5,900 字节**，**63 个 `ro.boot.*`** + 23 项启动要点 |
| `display/brightness_from_services.txt`（新增） | **11,222 字节**，取到框架**实际加载的完整亮度/nit 表**（非 root） |
| 产物可读性 | **204/204** 文本产物可读，0 二进制异常 |
| EMPTY 产物 | **0**（三个场景均是） |
| tombstone | **0**（当天全部真机采集累计） |
| 编译与签名 | v1+v2+v3 全部 verified；zipalign `-c -p 4` 通过 |
| Manifest / 资源 / DEX | 权限、组件注册、32 个资源引用、136 个类均正确 |
| 纯逻辑单测 | **58/58** |
| seccomp 闸门单测 | **12/12** |
| dumpsys 服务名匹配 | **12/12** |
| 去标签幂等单测 | **7/7** |
| 时间线解析 + 塌行守护 | **5/5 + 5/5** |
| 自检头注释剥离 | **5/5** |
| shell 命令语法 | **59/59** |
| shell 转义安全性 | **10/10** 真实 bash 往返，含注入载荷 |

**合计 163 条断言通过**（104 单测 + 59 shell）。

### 真机暴露并修复的缺陷

静态检查与桌面测试**全部漏掉了**这些问题，只有真机才能发现。按严重程度：

0. **应用反复调用被 seccomp 拦截的 `dmesg`，覆盖了设备全部 native crash 现场**【破坏性】。内核每次都会写 tombstone 到 `/data/tombstones/`，而该目录是 32 个的环形缓冲。实测 32/32 个 tombstone 全部来自本应用 —— 做 5 轮采集就能把真实崩溃证据全部覆盖。修复：读 `/proc/self/status` 的 `Seccomp:` 字段做**零代价预判**（不产生 tombstone）＋ 结论持久化 ＋ `Shell` 层统一防线（`su` 路径豁免，因为 root 域不继承应用的 seccomp 过滤器）。
1. **能力探测谎报「dmesg 可读」**。探测命令是 `dmesg -t 2>&1 | head -n 1`，管道让退出码来自 `head` 而非 `dmesg`，于是 SIGSYS 被杀（无输出 + exit 0）被误判为可读。
2. **同一份报告自相矛盾**。`probeDmesg()` 结果漏赋两个字段，导致建议段拿到默认值。
3. **dumpsys 权限建议是错的**。一律建议 `pm grant DUMP`，但 DUMP 早已授予；真实缺的是 `PACKAGE_USAGE_STATS`。现从错误文本解析真实权限名。
4. **`Can't find service` 被误报为成功**（28 字节的"有效输出"）。现单列为 `NO SUCH SERVICE on this build`。
5. **启动即误触发采集**。`logcat -b events` 打开会回放整个缓冲区，实测回放 **620 条**历史 `am_proc_died`，第一条就触发采集。
6. **修复 5 时引入日志洪泛**。逐条打印被忽略事件 → 620 行日志，反而污染日志（本工具要解决的问题）。改为聚合为一行。
7. **权限建议给出的命令跑不通**。`pm grant PACKAGE_USAGE_STATS` 抛 `SecurityException: has not requested permission` —— 权限必须在 manifest 声明才可授予。已补声明，并改为优先推荐已验证可用的 appops 路径。
8. **把「进程退出」当成「崩溃」**。`am_proc_died` 对每次普通进程回收都产生（该机每小时数百次），用它触发采集等于把正常回收当崩溃。已移除该触发源。

### 未验证

以下是**尚未**在真机上跑通的项目：

- **录屏 / 截屏**：需要 MediaProjection 人工点确认，未做无人值守测试。
- **整机 bugreport**：耗时数分钟，本轮用 `-SkipBugreport` 跳过（脚本模式已实现并有回退版）。
- **黑匣子长时间录制**：内存与耗电需数小时观察。
- **定时循环 / 开机采集**：需等待定时周期或重启设备。
- **崩溃自动采集的真实触发**：设备稳定，未制造真实崩溃（*误*触发已修复并验证）。
- **上传功能**：没有可用的服务端。
- **App 内实时日志查看器 UI**：`adb` 无法稳定断言其内部状态。

这些都不影响"换一台机器能否正常抓日志"：三种权限场景都已在真机跑通，`EMPTY` 产物为 0、tombstone 为 0。

### 已知限制（平台事实，非缺陷）

- **`dmesg` 应用侧永远拿不到**（seccomp + SIGSYS）。内核日志改由 `kernel_log.txt`（`logcat -b kernel`）提供，**非 root 可用**，实测 172 KB。
- **显示类 sysfs 节点（`vendor_sysfs_graphics` 标签）应用不可读**：`/sys/class/backlight/*`、`/sys/class/drm/card*-DSI-*/*` 对 `untrusted_app` 域拒绝，root 与 adb shell 可读。替代：`display/brightness_from_services.txt`（非 root 拿到完整亮度/nit 表）+ `tools/augment_from_pc.ps1`（root 读原始节点）。
- **内核专有启动参数（如 `msm_drm.dsi_display0=<panel>`）在非 root 下不可得** —— 实测它们**只存在于 `/proc/cmdline`，不镜像进 `ro.boot.*`**。应用会如实说明并指向替代来源。

### 已知限制

1. **非 root 且未 `pm grant` 时，dmesg 与大部分 dumpsys 取不到** —— 这是 Android 的安全模型，不是 App 的问题。界面会明确告诉你去跑哪条命令。
2. **`bugreport` 在非 root 下基本跑不动**，会自动降级成脚本近似版。
3. **录屏在部分设备上受 SELinux 限制**，失败时会把 stderr 完整写进 `media/screenrecord_error.txt` 而不是静默跳过。
4. **软件包名 `com.dsh.logcollector`**，与设备上其他应用无关；换签名密钥会导致无法覆盖安装。
5. 采集 `dumpsys` 全量服务**较慢**（几十个服务，每个最多 40 秒超时）。默认给了 20 分钟总预算，超预算的项会在 index.txt 里标记 `SKIPPED (budget exhausted)` 而不是卡死。
