# dmesg 缺失、cmdline 新增、跨机型兼容 — 分析文档

**本轮目标**（用户提出）：
1. 全功能验证，保证有效可用
2. 保证抓到的日志都能查看
3. **dmesg 的 log 没有打印出来** → 修
4. **新增读取 cmdline**
5. 保证换到其他机器也能正常抓取

**设备**：moto g(100) / Android 12 (API 31) / userdebug（第一轮中途被拔线，**第二轮已完整验证**，见第六节）

---

## 一、dmesg 为什么没打印 —— 找到真正原因
### 结论：**dmesg 在应用侧不可能拿到，但内核日志本来有另一条通道，而我的代码主动把它关掉了。**

`dmesg` 被内核 seccomp 用 SIGSYS 拦死（exit 159），这是平台限制，任何授权都绕不过（前一份 `ROOT_DIAGNOSIS_ANALYSIS.md` 已定性）。

**但内核日志并没有丢**：真机实测 `logcat -b kernel` 有 **6944～7059 行**内核日志，而且**非 root 可读**：

```
10-14 19:16:37.094     0     0 E msm_dwc3_perf_vote_work: in_perf_mode:0, interrupts in last sample:24
10-14 19:16:37.218     0     0 I binder  : 2625:2625 ioctl 40046210 7fd21040a4 returned -22
10-14 19:16:38.742     0     0 W healthd : battery l=100 v=4370 t=29.0 h=2 st=5 c=-51000 fc=4700000 cc=1 chg=u
```

**关键缺陷**：我的 `Logcat.reportableBuffers()` 把 `kernel` 缓冲区**主动排除了**，注释还写着"与 dmesg.txt 重复"：

```java
// "kernel" duplicates dmesg.txt; "stats" is noise for most debugging.
if ("kernel".equals(b) || "stats".equals(b)) continue;
```

这个假设在**这台机器上完全不成立** —— `dmesg.txt` 是失败占位文件，根本不重复。等于我把唯一可用的内核日志通道关掉了，然后报告"dmesg 没抓到"。

### 修复

1. **`Logcat.reportableBuffers()` 不再排除 kernel**（只保留排除 `stats`）
2. **新增 `collectKernelLogcat()`**：写 `kernel_log.txt`，并在文件头**明确说明这不是 dmesg**：

```
# KERNEL LOG - captured via `logcat -b kernel`, not via `dmesg`.
#
# Why not dmesg: this app is denied the syslog syscall by the kernel's seccomp
# filter (and every attempt writes a tombstone into /data/tombstones, so it is
# not retried). logd republishes the same kernel ring buffer, which an app may
# read once READ_LOGS is granted - that is what you are looking at.
#
# Differences from a real dmesg capture:
#   * timestamps are wall-clock (MM-DD HH:MM:SS.mmm) from logd, not kernel uptime
#   * columns are 'pid tid priority tag: message' rather than '<level>[uptime]'
#   * capacity is logd's kernel buffer, not the kernel's own log_buf_len
```

3. 附带两个提取：`kernel_log_errors.txt`（E 级）、`kernel_log_warnings.txt`（W 级）
4. **`dmesg` 一旦被判定不可用，就走 logcat kernel 通道**，而不是直接放弃
5. `logcat -b kernel` 也为空时，**仍写占位文件说明原因**（绝不静默留空）

---

## 二、新增 cmdline 采集

### 真机实测的三条来源及其可用性

| 来源 | 非 root 可读？ | 说明 |
|---|---|---|
| `/proc/cmdline` | **否** | SELinux 标为 `proc_cmdline`，**连 shell 都被拒**（`Permission denied`），只有 root 能读 |
| `/proc/bootconfig` | 本机不存在 | Android 12+ GKI 才有 |
| `ro.boot.*` 属性 | **是（43 个）** | cmdline 里 `androidboot.*` 的镜像，**任何机器都可读** |

实测对应关系（cmdline ↔ 属性一一对应）：

```
cmdline: androidboot.serialno=NHDE130317   →   getprop ro.boot.serialno=NHDE130317   ✅
cmdline: androidboot.hab.product=hawao     →   getprop ro.boot.hab.product=hawao     ✅
```

### 实现：三层回退，最后保证有数据

`collectCmdline()` 依次尝试 `/proc/cmdline` → `/proc/bootconfig` → `ro.boot.*`，写入 `cmdline.txt`，包含：

- `/proc/cmdline` 原文（可读时）+ **逐参数拆分成 `key = value` 表格**
- `/proc/bootconfig` 内容（存在时）
- **全部 `ro.boot.*` 属性**（本机 43 个，这是兜底，永远有数据）
- device-tree `chosen/bootargs`（可读时）
- **启动要点表**：SoC、SKU、硬件版本、序列号、bootloader、基带、A/B 槽位、**verified boot 状态**、**bootloader 解锁状态**、dm-verity、**重启原因**、写保护、coredump 等 23 项
- **面板参数提取**：从启动参数里搜 `msm_drm.dsi_display0` 等键

本机实测 cmdline 里直接有面板型号：

```
msm_drm.dsi_display0=qcom,mdss_dsi_vtdr6110c_ft3519_643_visionox_video:
```

这一项对面板调试很关键 —— 它决定内核绑定哪个 panel 驱动。

---

## 三、新增自检（回答"日志是否都可用"）

`Verifier` 在每次采集收尾时审计整个目录，写 `verify.txt`，把每个产物归到五类之一：

| 状态 | 含义 |
|---|---|
| **OK** | 有真实内容 |
| **PLACEHOLDER** | 明确说明某能力不可用（如 seccomp 拦截 dmesg）—— 不是缺陷 |
| **THIN** | 存在但小于 128 字节，疑似 stub |
| **EMPTY** | 0 字节 —— 缺陷 |
| **MISSING** | 预期应有但未生成 |

并给出：

- **结论**：`GOOD` / `USABLE WITH GAPS` / `INCOMPLETE`
- **需关注清单**：逐项列出状态、字节数、原因（含原文摘录）
- **可读性检查**：逐个打开文本产物读前 4 KB，检查是否可读、是否含 NUL（二进制）
- **补齐指引**：针对每类缺口给出具体命令

这样"这次采集到底抓全了没有"是**一个文件**的答案，而不是翻目录猜。

---

## 四、自查中发现的另外两个真实缺陷

### 缺陷 A —— 深扫 sysfs 的深度不够，从来没读到过亮度文件

原代码用 `find /sys/devices -maxdepth 6` 找亮度节点。两个问题：

1. `find -maxdepth` **不可移植**：toybox 到 Android 8 才支持，老 ROM 的 busybox 直接报错 → 静默返回空
2. **深度根本不够**：`/sys/class/backlight` 是软链接，真实路径是
   `/sys/devices/platform/soc/<addr>.<bus>/<addr>.<dev>/backlight/<name>/brightness` —— 实测 **7–8 层**

我用复刻目录树验证，depth 6 与 depth 9 的差别：

```
--- depth 6 (旧) ---
.../backlight/ (dir)                                    ← 只打印目录，读不到值
--- depth 9 (新) ---
.../backlight/ (dir)
.../backlight/panel0/brightness = 818                   ← 读到真正的节点值
.../disp_foo/ (dir)
```

**也就是说：这个"非标准亮度路径深扫"功能从上线起就没真正工作过**，只是没人验证。

修复：换成纯 shell 递归（不依赖 `find -maxdepth`），深度放到 12，并保留 `head -n 200` 早停。

同类修复：`readlink -f` → `ls -l`（前者在部分 toybox 上缺失）、`sort -h` → `sort -n`。

### 缺陷 B —— 亮度时间线把节点名当成了亮度值

我为提速把"每个节点一次 shell"改成"一次 shell 读全部节点"，命令用 `printf '%s\t'` 打标签。但**解析写错了**：

- 实际输出是 **N 行，每行 `路径<TAB>值`**（因为 `cat` 自己会补换行）
- 我的 Java 把**整个输出按 `\t` 分割** → 得到扁平的 `[路径, 值, 路径, 值, ...]`，下标完全错位

单测记录了这个差异：

```
[PASS] real output parsed positionally: [818, 1234, 4095]
[INFO] old whole-blob split would give: [/sys/class/backlight/panel0/brightness, 818
/sys/class/backlight/panel0/actual_brightness, 1234 ...]
```

**后果**：表格里会打印**路径**而不是亮度值 —— 数据看着有，但全是错的。

修复：新增 `parseNodeValues()`，**按行取第一个 tab 之后的内容**，并按位置对齐。单测 5/5：

```
[PASS] real output parsed positionally: [818, 1234, 4095]
[PASS] no value is a path
[PASS] missing node stays '-' without shifting
[PASS] empty/null output -> all '-'
[PASS] extra lines ignored
```

顺带把 `max_brightness` 也纳入采样，这样原始节点值可以换算成满量程比例。

### 缺陷 C —— dmesg 因非 seccomp 原因失败时，仍不走内核日志通道

`dmesg` 失败有两种原因：seccomp 拦截（走新加的 kernel 通道）和其它原因（DAC/SELinux）。但**只有 seccomp 那条分支加了 `collectKernelLogcat()`**，另一种原因直接 `return` 了 —— 逻辑不一致，等于在别的机型上又丢一次内核日志。

修复：无论哪种失败原因，都继续尝试 logcat kernel 通道（它是**独立通道**，完全可能可用）。同时把建议里的 `su -c 'dmesg ...'` 去掉 —— 那是 Magisk 语法，AOSP 的 `su` 不支持 `-c`，会误导用户。

### 缺陷 D —— 硬编码的 dumpsys 服务名在别的机型上产生噪音

`CORE_DUMPSYS` / `DISPLAY_DUMPSYS` 是在**一台机器**上调出来的名字，其中 `cpuinfo`、`netstats`、`thermalservice`、`color_display`、`displayfeature` 等在很多 build 上不存在（`cpuinfo` 在 AOSP 8+ 已移除，`thermalservice`/`color_display` 是 Qualcomm/厂商专有）。对不存在的服务调用 `dumpsys` 会得到 "Can't find service" stub —— 在别的机型上看起来像"采集失败"，其实这台机器没任何问题。

修复：**用设备实际上报的服务列表过滤硬编码清单**，跳过的名字记进 `index.txt`。两个例外：`SurfaceFlinger` 必须豁免（大小写敏感且多数 build 的 `dumpsys -l` 不含它）；服务列表读不到时不做过**滤（宁可白试，不能静默丢服务）。另加 `thermal` 作为 `thermalservice` 的别名。匹配单测 12/12。

### 缺陷 E —— **失败的服务完全不产生文件**（与你的 dmesg 抱怨同一形态）

这是最难堪的一个：`dumpsys` 有三个分支，其中**两个 `continue` 之前根本没写文件**：

```java
if (body.length() == 0) {
    empty++;
    index.append("dumpsys/… :: EMPTY (…)");
    continue;                    // ← 没有任何文件产生
}
if (noSuchService && body.length() < 2000) {
    missing++;
    index.append("dumpsys/… :: NO SUCH SERVICE on this build\n");
    continue;                    // ← 同样没有文件
}
```

**后果**：在一台未 root、未 `pm grant` 的机器上，几乎每个服务都走这两个分支 → `dumpsys/` 目录只有寥寥几个文件，而**没有任何记录说明另外 ~150 个被尝试过**。打开目录的人看到的是"文件不见了"，和你说 dmesg 没打印是**完全一样的症状**。

修复：两个分支都写出说明性文件（含 read mode、退出码、stderr、以及针对性的授权建议）。

### 缺陷 F —— `index.txt` 报告了两个**根本不存在的文件**

`index.append` 里出现了 `display/led_nodes.txt` 和 `display/backlight_deep_scan.txt`，但**全代码库没有任何地方写过这两个路径** —— 它们的内容其实被追加进了 `display/backlight_nodes.txt`。

**后果**：照 `index.txt` 去开文件的人会撞上两个 missing file。而 `Verifier` 抓不到 —— 它审计**磁盘上有什么**，而不是**清单声称有什么**。

修复：合并为一个文件，index 只报那一个，并在行内注明三段的各自结果。

### 缺陷 G —— 亮度时间线在值不带换行时**整行塌成一行**

这个比原先以为的更严重。sysfs 属性**不保证以换行结尾**，而 `cat` 在这种情况下不补换行，于是下一个节点的**标签被粘到上一个节点的值后面**。复刻树实测：

```
输入：n0 无换行("818")、n1 无换行("1234")、n2 有换行("4095")
输出：/tmp/nl/n0\t818/tmp/nl/n1\t1234/tmp/nl/n2\t4095\n     ← 整个采样塌成一行
```

解析后 node0 会读到 `818/tmp/nl/n1`（垃圾），node1/node2 全部变成 `-`，而表格**看起来仍有正常列数** —— 静默错误。

修复（两层）：
1. **命令侧**：每个节点后加一个 `echo`，保证每个节点独占一行
2. **解析侧**：值里出现 `/` 就判定为标签泄漏，输出 `?` 而不是把文本当亮度值

单测 5/5：

```
[PASS] collapsed line -> '?' not garbage: [?, -, -]
[PASS] fixed output parses cleanly: [818, 1234, 4095]
[PASS] unreadable node stays '-' without shifting
[PASS] short output pads with '-'
[PASS] empty/null -> all '-'
```

### 缺陷 H —— 三处仍只写 index 不写文件

`display/brightness_map_extract.txt`、`display/panel_ids.txt`、`prop_sorted.txt`、`kernel/thermal.txt` 在数据源不可用时**只往 index.txt 写一行，不产生文件**。与 `dmesg.txt` / `kernel_log.txt` / `cmdline.txt`（它们总是写说明）不一致。

修复：全部改为写说明性占位文件，说明搜过哪些路径、为什么没有、以及替代来源。

### 缺陷 I —— dmesg 成功时 `kernel_log.txt` 缺失

三个调用点都在**失败路径**上，所以在一台 root 设备上 `dmesg` 成功时，`kernel_log.txt` 不会被创建 —— 而运行头部的导读和 `verify.txt` 都引用了它，缺文件会被读成"缺口"。

修复：dmesg 成功时也写一份，说明"本机不需要这个替代通道，真 dmesg 在 dmesg.txt"。

### 缺陷 J —— 自检工具对"没写出来的文件"是盲的

`Verifier` 只能枚举**已存在**的文件，所以上面那些没被创建的产物它一个都看不见 —— `verify.txt` 可能在半个采集缺失的情况下报 **GOOD**。这是结构性缺陷，也是缺陷 E/F/H 之所以危险的原因。

修复：新增 `Report.gaps` 登记表，采集器把"尝试过但没产出"的项连同原因登记进去，`Verifier` 合并进 `MISSING`。

---

## 五、跨机型兼容性修复

| 问题 | 原写法 | 改法 | 影响机型 |
|---|---|---|---|
| `find -maxdepth` | `find /sys/devices -maxdepth 6` | 纯 shell 递归 `scan()` | Android <8 toybox、老 busybox |
| `readlink -f` | `readlink -f $d` | `ls -l` | 部分 toybox 精简版 |
| `sort -h` | `du -sh \| sort -h` | `du -sk \| sort -n` | 老 toybox / busybox |
| kernel 缓冲区被排除 | `if ("kernel".equals(b)) continue` | 保留 | **所有 Android 11+** |
| cmdline 只读一个来源 | 只读 `/proc/cmdline` | 三层回退到 `ro.boot.*` | **除 root 外全部** |
| 缺口静默无文件 | 不写文件 | 写说明性占位 | 全部 |
| 非 seccomp 失败丢内核日志 | 直接 return | 也走 kernel 通道 | 非 seccomp 拒绝的机型 |
| 硬编码 dumpsys 服务名 | 全部盲试 | 按设备实际上报过滤 | **所有机型** |
| 建议里的 `su -c` | Magisk 语法 | 去掉，改用 adb / 脚本 | **AOSP su 机型** |

已用真实 bash 校验新命令语法与行为：

```
[OK]   brightness deep scan
[OK]   debugfs dsi/panel scan
[OK]   du sorted by size (no sort -h)
[OK]   ls -l resolve (no readlink -f)
[OK]   form 1 (-t) / form 2 (tail fallback) / merged: -b all / per-buffer fallback
```

---

## 六、验证结果

### 真机端到端验证（第二轮，设备已接回）

命令：`adb install` → 授予全套权限 → 触发采集 → 读产出。**三个场景全部验证**：

| 场景 | 结果 |
|---|---|
| **A. 全权限**（READ_LOGS + DUMP + PACKAGE_USAGE_STATS + appops） | **209 个产物 / 49.6 MB**，`OK 182 / PLACEHOLDER 9 / EMPTY 0`，自检结论 `USABLE WITH GAPS`，需关注清单**只剩 1 条** |
| **B. 全撤销**（模拟普通 user 版，无任何授权） | **205 个产物**，`OK 31 / PLACEHOLDER 157 / EMPTY 0`；正确回退到 `/sdcard/Android/data/.../files/log/`（tier 3）并**明确说明未使用真实路径** |
| **C. root 侧补齐** | `augment_from_pc.ps1` 补入 1.0 MB dmesg + 14.9 MB dumpsys + 面板节点 |

### 两个核心新功能：真有数据

| 文件 | 实测 | 说明 |
|---|---|---|
| **`kernel_log.txt`** | **172,886 字节**（A 场景），110~147 行内核日志 | **这就是 dmesg 的替代通道，非 root 可用** —— 你抱怨的"dmesg 没打印"已解决 |
| **`cmdline.txt`** | **5,900 字节**，**63 个 `ro.boot.*` 属性** + 23 项启动要点 | 新增功能有效 |
| `display/brightness_from_services.txt` | **11,222 字节** | 新增：**非 root 拿到完整亮度表**（见下） |

### 意外收获：非 root 拿到了完整的亮度/nit 表

新加的 `brightness_from_services.txt` 从 `dumpsys display` 里取到了**框架实际加载的整张表**：

```
mDisplayDeviceConfig=DisplayDeviceConfig{mLoadedFrom=<config.xml>,
  mBacklight=[0.007874016, 0.031496063, 0.08267716, 0.17322835, 0.2007874, 0.27165353,
              0.36220473, 0.47244096, 0.6574803, 0.7677165, 1.0],
  mNits=[4.857143, 16.0, 40.0, 79.0, 92.0, 124.0, 165.0, 214.0, 296.0, 346.0, 450.0],
  mBrightnessToBacklightSpline=MonotoneCubicSpline{...},
  isHbmEnabled=false, mHbmData=null, mBacklightMinimum=0.007874016, ...}
```

这**正是**导致 `getBrightnessFromBacklight` NPE 崩溃的那张表，而且 `mLoadedFrom=<config.xml>` 直接指明来源。**非 root 就能拿到** —— 对面板调试直接有用。

### 真机暴露的 4 个新缺陷（自检工具自身）

自检功能**第一次上真机就暴露了自己的一批 bug**，都已修复并复验：

| # | 缺陷 | 后果 | 修复 |
|---|---|---|---|
| 1 | `index.txt` / `verify.txt` 被报 **MISSING**，但它们确实存在 | 每次运行都被误判 `INCOMPLETE` | 二者是**审计之后**才写的（index 要报告自检结果、verify 是自检自身输出），加入 `WRITTEN_AFTER_AUDIT` 白名单 |
| 2 | `logcat_all.txt`（**4.7 MB 真实日志**）被判为 `PLACEHOLDER` | 真实日志被标注为"不是数据"，等于叫用户别信它 | 标记词只在**文件开头 600 字符**且**文件 < 64KB** 时才采信 |
| 3 | `logcat_events.txt`（11 KB 真实日志）、`capability.txt` 同样被误判 | 同上 | 增加 `stripHeader()`：**剥掉应用自己写的 `#` 头注释**再判定，因为头注释里本来就会讨论权限 |
| 4 | "如何补齐"段落对每个文件重复同一段话，几十行 | 真正有用的两三条建议被淹没 | 按建议**分组去重**，每组列出受影响文件 |

**修复效果（同一台设备、同一次采集流程）**：

| 指标 | 修复前 | 修复后 |
|---|---|---|
| `OK` | 126 | **182** |
| `PLACEHOLDER` | 64 | **9** |
| 需关注清单条目 | 60+ | **1** |

`stripHeader` 单测 5/5（真实 capture 保留数据、纯占位仍可识别、只有头注释则视为空、无头注释原样返回、null 不炸）。

### 真机确认的一个平台事实：`vendor_sysfs_graphics` 拦截

所有读不到的显示节点带**同一个 SELinux 标签**：

```
/sys/class/backlight/panel0-backlight   u:object_r:vendor_sysfs_graphics:s0
/sys/class/drm/card0-DSI-1/modes        u:object_r:vendor_sysfs_graphics:s0
/sys/class/drm/card0-DSI-1/status       u:object_r:vendor_sysfs_graphics:s0
```

该标签 **root 和 adb shell 可读，`untrusted_app` 不可读** —— 一条事实解释了全部显示类缺口。应用现在**如实说明这一点**，并指向可行替代（`dumpsys display` / `brightness_from_services.txt` / PC 侧脚本）。

### 修正了一处我自己的错误说法

`cmdline.txt` 原先写 `/proc/cmdline` 读不到时"**nothing is lost**"（因为 `ro.boot.*` 有同样的数据）。真机证明**这句话是错的**：

```
/proc/cmdline: msm_drm.dsi_display0=qcom,mdss_dsi_vtdr6110c_ft3519_643_visionox_video
ro.boot.*    : 无此键（实测 63 个 ro.boot 属性里没有任何 panel/dsi 项）
getprop      : 无
kernel_log   : 无
```

**内核专有参数只存在于 cmdline，不会镜像进属性** —— 面板驱动名在非 root 设备上**确实拿不到**。已改为如实说明，并给出可行替代：

- `/sys/class/drm/card*-DSI-*/modes` → `1080x2400x60x169850vid`（**shell 可读，app 不可读**，原因是上面的标签）
- `dumpsys display` → 完整的亮度/nit 表
- `tools/augment_from_pc.ps1` → 以 root 读 cmdline

`augment_from_pc.ps1` 已新增 `display_from_pc.txt`，实测取到面板驱动名 + DRM mode + 背光节点（`brightness = 320`、`max_brightness = 2047`）。

### 单测 / 静态验证

| 项目 | 结果 |
|---|---|
| 逻辑单测 | **58/58** |
| seccomp 闸门单测 | **12/12** |
| 去标签幂等单测 | **7/7** |
| 时间线解析单测 | **5/5** |
| 时间线塌行守护栏 | **5/5** |
| dumpsys 服务名匹配 | **12/12** |
| 自检头注释剥离 | **5/5** |
| shell 命令语法 | **59/59** |
| 编译 / 签名 | v1+v2+v3 verified |
| **tombstone** | **0**（今天全部真机采集轮次累计） |
| **EMPTY 产物** | **0**（三个场景均是） |
| 可读性检查 | 204/204 文本产物可读，0 二进制异常 |

**合计 163 条断言通过**（104 单测 + 59 shell）。

---

## 七、仍未验证的部分（说实话）

以下**没有**在本轮真机验证中覆盖：

- **截屏 / 录屏** —— 需要 MediaProjection 人工点一次授权，无人值守测不了
- **bugreport** —— 本轮用 `-SkipBugreport` 跳过（耗时数分钟）
- **黑匣子长时间录制** —— 需要数小时观察内存与耗电
- **定时循环 / 开机采集** —— 需要等待周期或重启设备
- **崩溃自动采集的真实触发** —— 设备很稳定，没有制造真实崩溃（*误*触发已在前几轮修掉并验证）
- **上传功能** —— 没有可用服务端
- **App 内实时日志查看器 UI** —— `adb` 无法稳定断言其内部状态
- **开机采集** —— 需重启

这些都不影响"换一台机器能否正常抓日志"这个核心诉求：三个场景（全权限 / 无权限 / root 补齐）都已在真机跑通，且 `EMPTY` 产物为 0、tombstone 为 0。

---

## 八、给使用者的话

### dmesg 的替代关系

```
dmesg.txt         <-- 应用侧永远拿不到（seccomp）。写占位文件说明原因
kernel_log.txt    <-- ★ 内核日志实际在这里（logcat -b kernel），非 root 可读
kernel_log_errors.txt / _warnings.txt   <-- 按级别提取
dmesg_from_pc.txt <-- 需要真 dmesg 时，用 tools/augment_from_pc.ps1（root 或 adb）
```

### cmdline 的读法

`cmdline.txt` 里优先看这几行：

- `msm_drm.dsi_display0` —— 内核绑定的面板驱动
- `ro.boot.hardware` / `ro.boot.hab.product` —— 平台与产品代号
- `ro.boot.bootreason` —— 本次为什么开机
- `ro.boot.verifiedbootstate` / `flash.locked` —— 安全启动与解锁状态

### 每次采集完先看 `verify.txt`

它直接告诉你这次抓全了没有、缺什么、怎么补。`GOOD` 就可以直接用；`USABLE WITH GAPS` 按里面的指引补。
