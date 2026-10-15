# DSH LogCollector 真机验证分析

**设备**：moto g(100) / `hawao_g` / bengal 平台
**系统**：Android 12（API 31），`userdebug`，`intcfg,test-keys`
**连接**：adb，`adb root` 可用（adbd 重启为 uid=0）
**APK**：`com.dsh.logcollector`，最终 132,713 bytes
**验证日期**：2026-10-14

---

## 一、结论速览

端到端功能**全部跑通**：一次采集产出 155 个文件 / 21 MB，目录结构与设计文档完全一致。

真机暴露了 **8 个静态检查抓不到的缺陷**，全部已修复并复验。其中最严重的一个是**破坏性**的：应用反复调用被 seccomp 拦截的 `dmesg`，导致内核不断写入 tombstone，**5 轮采集就足以覆盖设备上全部 native crash 现场**（实测 32/32 个 tombstone 均来自本应用）。修复后，全新安装 + 启动 + 2 次完整采集的 tombstone 增量为 **0**。

其余缺陷分布：3 个"会误导用户"的错误信息、2 个"功能实际不生效"、1 个"启动即误触发"、1 个"权限建议跑不通"。

未在真机上跑通的部分已定位根因（dmesg 属平台级 seccomp 限制），并提供了实测可用的电脑侧补齐脚本（补入 1 MB 内核日志 + 15.2 MB dumpsys）。

---

## 二、设备能力实测（采集前）

| 项目 | 实测结果 |
|---|---|
| `dumpsys -l` 服务数 | **236**（应用视角列出 156） |
| root（`su`） | **NO** —— `su` 二进制存在于 `/system/xbin/su`，但应用 uid 调用被拒（`error=13, Permission denied`） |
| `adb root` | **可用**，重启后 uid=0 |
| `dmesg`（shell） | 可用（`adb root` 后 10,139 行） |
| `dmesg`（应用） | **被 seccomp 拦截**（SIGSYS，exit=159） |
| `screenrecord` | 存在 |
| READ_LOGS / DUMP | `pm grant` 后 `granted=true` |
| 所有文件访问 | `appops set MANAGE_EXTERNAL_STORAGE allow` 后生效，可直接写 `/sdcard/log` |

---

## 三、真机发现的缺陷与修复

### 缺陷 0 —— 应用反复调用 dmesg，覆盖了设备的 native crash 现场【最严重，破坏性】

**这是本次测试最重要的发现。**

`dmesg` 被 seccomp 以 SIGSYS 杀死时，**内核会写一个 tombstone 到 `/data/tombstones/`**。该目录是 **32 个的环形缓冲**，满了就覆盖最旧的 —— 而这里存的正是 native crash 的现场证据。

实测统计（清理前）：

| 项 | 数量 |
|---|---|
| tombstone 总数 | 32（环形上限） |
| `Cmdline: dmesg` | 28 |
| `Cmdline: dmesg -t` | 4 |
| `uid: 10343`（本应用） | **32 / 32** |

一份 tombstone 的内容：

```
Cmdline: dmesg -t
uid: 10343
signal 31 (SIGSYS), code 1 (SYS_SECCOMP)
Cause: seccomp prevented call to disallowed arm64 system call 116   ← syslog
```

**这意味着：本应用做 5 轮采集，就足以把设备上所有真实的 native crash 记录全部覆盖掉。** 一个用来排查崩溃的工具，把崩溃证据毁了 —— 比没有这个工具更糟。

**修复（三层防护）**：

1. **零代价预判**：读 `/proc/self/status` 的 `Seccomp:` 字段。应用域为 `2`（filter 模式），而 shell 域为 `0` —— 这正是 `adb shell dmesg` 能work而应用不能的原因。纯文件读取，**不产生 tombstone**。
2. **结论持久化**：判定为 blocked 后写入 SharedPreferences，后续启动与采集**不再调用 dmesg**（7 天后允许一次复核）。
3. **统一防线**：拦截点放在 `Shell` 层，任何未来新增的代码路径都无法绕过。

**root 例外**：`su` 由 root 守护进程 fork 到自己的域，**不继承应用的 seccomp 过滤器**，所以有 root 时 `su -c dmesg` 是真能工作的 —— 预判在此情况下不做拦截。

**闭环验证（全新安装，无任何缓存）**：

| 阶段 | 新增 tombstone |
|---|---|
| 安装 + 启动 | **0** |
| 2 次完整采集 | **0** |
| **合计** | **0** |

`index.txt` 现在如实标注：`dmesg.txt :: NOT AVAILABLE (seccomp SIGSYS (cached verdict)) :: not retried, to protect /data/tombstones`。

同时新增 `tools/augment_from_pc.ps1`，从电脑侧把应用拿不到的部分补进同一目录（实测可用，见第七节）。

---

### 缺陷 1 —— 能力探测谎报「dmesg 可读」【严重】

`capability.txt` 声称 `dmesg : READABLE`，而同一目录的 `dmesg.txt` 是失败占位文件。

**根因**：探测命令是 `dmesg -t 2>&1 | head -n 1`。管道让**退出码来自 `head` 而非 `dmesg`**。应用被 seccomp 用 SIGSYS 杀掉时不产生任何输出，于是 `exit=0` + 空输出，被判定为"可读但无内容"。

**修复**：改为先单独取 `dmesg` 自身的退出码（`dmesg >/dev/null 2>&1; echo "__DSHEXIT=$?"`），再据此分类：

- `exit=159`（128+31）→ **seccomp 拦截**，明确告知任何授权都无效
- `exit=1` + "Operation not permitted" → DAC/SELinux，提示用 adb
- `exit=0` → 再确认真的产出了行数

**复验**：`dmesg : BLOCKED`，且文案正确说明 seccomp。

---

### 缺陷 2 —— 同一份报告自相矛盾

`capability.txt` 能力段写 seccomp，建议段却写 `exit=-1 / DAC or SELinux`。

**根因**：`probeDmesg()` 的结果只赋给了 `readable` 和 `detail` 两个字段，漏了 `seccompBlocked` 与 `exitCode`，导致建议段拿到默认值。

**修复**：补齐字段赋值。

---

### 缺陷 3 —— dumpsys 权限建议是错的【严重，会误导】

被拒服务一律提示 `adb shell pm grant ... android.permission.DUMP`，但 **DUMP 明明已授予**。真实原因写在错误信息里：

```
Permission Denial: can't dump ActivityManager from pid=12386, uid=10343
  due to missing android.permission.PACKAGE_USAGE_STATS permission
```

**修复**：新增 `extractNeededPermission()`，从错误文本里提取**真正缺失的权限名**并据此给出建议。

**复验**：`activity.txt` / `package.txt` 顶部现在写 `This service needs: android.permission.PACKAGE_USAGE_STATS`；`location` 正确识别为 `INTERACT_ACROSS_USERS`。

---

### 缺陷 4 —— 「Can't find service」被误报为成功

`battery`、`window`、`meminfo` 等返回 28 字节的 `Can't find service: battery`，被归类为 `ok but tiny`。

**修复**：识别 `Can't find service` / `Unknown service` / `No service published`，改判为 `NO SUCH SERVICE on this build`，并单独计数。

**复验**：8 个服务正确归类，汇总行变成 `18 denied / 8 not-on-this-build / 58 empty`。

---

### 缺陷 5 —— 启动即误触发采集【严重】

首次启动就自动跑了一次 `auto-crash-*` 采集。日志显示触发源是**前一天的历史事件**：

```
auto capture triggered by kill: process died: 10-13 20:28:37 ... am_proc_died
```

**根因**：`logcat -b events` 打开时会**回放整个既有缓冲区**。这台设备回放了 **620 条**历史 `am_proc_died` 记录，第一条就触发了自动采集。

**修复**（双保险）：

1. 启动后 **8 秒预热期**内忽略所有事件
2. 解析日志行自带的时间戳，早于启动时刻的一律视为回放

**复验**：`ignored 620 pre-existing event(s) from the logcat backlog`，不再误触发。

---

### 缺陷 6 —— 修复 5 时引入日志洪泛

第一版修复对**每条**被忽略的事件都打一行日志 → 620 行，把 logcat 刷爆。而这个 App 本身是用来收集日志的，属于自我污染。

**修复**：改为原子计数器聚合，预热结束后只输出**一行汇总**。

**复验**：DSH-LC 标签下总日志从数百行降到 **2 行**。

---

### 缺陷 7 —— 权限建议给出的命令跑不通【严重】

应用自己在 `capability.txt` 里建议：

```
adb shell pm grant com.dsh.logcollector android.permission.PACKAGE_USAGE_STATS
```

实测**执行失败**：

```
SecurityException: Package com.dsh.logcollector has not requested permission
  android.permission.PACKAGE_USAGE_STATS
```

**根因**：Android 规定 `pm grant` 只能授予**manifest 中已声明**的权限。该权限未声明，所以这条建议是空头支票。

**修复**：

1. manifest 补声明 `PACKAGE_USAGE_STATS`、`BATTERY_STATS`、`INTERACT_ACROSS_USERS`、`INTERACT_ACROSS_USERS_FULL`（仅为让 adb 命令可用，不会在运行时申请）
2. 建议文案改为**优先推荐已验证可用的 appops 路径**

**闭环复验**：

| 指标 | 授权前 | 授权后 |
|---|---|---|
| 被拒服务 | **18** | **1** |
| 采集总量 | 3.2 MB | **10.3 MB** |
| `dumpsys/activity.txt` | 被拒 | **1,159,004 字节**完整 dump |

仅剩 `location` 需要 `INTERACT_ACROSS_USERS`（跨用户权限，非 root 无法获得）。

---

### 缺陷 8 —— 把「进程退出」当成「崩溃」触发采集

修复缺陷 5（历史事件误触发）后，仍观察到 `auto-crash-*` 采集在正常运行期间产生，触发源是**当下**的 `am_proc_died`：

```
auto capture triggered by kill: process died: 18:21:57 ... am_proc_died: [..., com.google.android.apps.turbo:aab, ...]
```

**根因**：`am_proc_died` 对**每一次普通进程退出**都会产生 —— 后台应用回收、任务清理、低内存击杀、用户划掉应用。这台设备上它**每小时出现数百次**。把它当作崩溃信号，等于把正常回收误判为崩溃。

**修复**：

- **移除** `am_proc_died` 触发（它根本不是崩溃信号）
- 保留 `am_crash` / `am_anr` / `am_native_crash` —— 这三个是精确且可靠的
- `am_kill` 仅在**明确标注 low memory** 时才作为提示触发

**复验**：同一测试流程下，误触发从 2 次降到 **0 次**。

## 四、端到端验证结果

采集产出（`/sdcard/log/20261014-175119/`）：

| 项目 | 结果 |
|---|---|
| 文件数 / 总量 | 155 个 / 21 MB |
| 写入模式 | `direct file write`（appops 授权后直接写） |
| `latest` 指针 | `latest/WHERE.txt`（FUSE 拒绝软链接，降级路径正确工作） |
| logcat 各缓冲区 | main 463 KB、system 89 KB、crash 26 KB、events 3.8 MB、radio 5.1 MB |
| logcat 合并流 | 4.8 MB |
| 专项提取 | fatal 9 行、ANR 8 行、native crash 325 行 |
| dumpsys | 163 个服务，10.3 MB |
| 显示/背光专项 | SurfaceFlinger 125 KB、display 25 KB、power 183 KB 等 17 项 |
| capability.txt | 准确，无谎报 |
| index.txt | 逐项记录 root/app 模式、退出码、字节数、失败原因 |

---

## 五、真机上未能跑通的部分

### dmesg：应用侧无法获取（已定性为平台限制）

`dmesg` 在应用 uid 下被 **seccomp 过滤器**用 SIGSYS 杀死（exit=159）。这**不是权限问题**：`syslog` 系统调用从应用的安全域里被移除了，`pm grant`、appops、甚至应用内启动的 root helper 都绕不过。

应用侧判定依据（无需真的调用 dmesg）：

```
/proc/self/status → Seccomp: 2     (应用域，filter 模式)
/proc/self/status → Seccomp: 0     (adb shell 域，无过滤器)
```

**替代方案**：`tools/augment_from_pc.ps1` 从电脑侧补齐，实测产出 `dmesg_from_pc.txt`（1.0 MB，11,610 行，含 50 行 DSI/面板/背光日志）。

---

## 六、PC 侧补齐脚本实测

`pwsh -File tools/augment_from_pc.ps1` 在真机上运行结果：

| 产出文件 | 大小 | 内容 |
|---|---|---|
| `dmesg_from_pc.txt` | **1.0 MB** | 11,610 行完整内核日志，含 `dsi_display_set_mode`、`backlight panel0-backlight` 等面板调试关键行 |
| `dumpsys_from_pc.txt` | **15.2 MB** | 242 个服务（应用侧因权限只有 163 个） |
| `crash_artefacts_from_pc.txt` | 7.2 KB | tombstone / ANR / pstore / dropbox 清单 |

所有 PC 侧文件都带明确来源头（设备、身份、采集方式），不会与应用侧产物混淆。

### 授权前后的对比（应用侧）

| 指标 | 授权前 | 授权后 |
|---|---|---|
| 被拒服务 | 18 | **1** |
| 采集总量 | 3.2 MB | **10.3 MB** |
| `dumpsys/activity.txt` | 被拒 | **1,159,004 字节** |

仅剩 `location` 需要 `INTERACT_ACROSS_USERS`（跨用户权限，非 root 无法获得）。

---

## 七、未在本轮覆盖

- **录屏 / 截屏**：需要 MediaProjection 授权（需人工点确认），未在无人值守下测试
- **黑匣子长时间录制**：内存与耗电表现需数小时观察
- **定时循环 / 开机采集**：需要重启设备或等待定时周期
- **上传功能**：没有可用的服务端
- **崩溃自动采集的真实触发**：设备很稳定，未制造真实崩溃（但自动采集的*误*触发已修，见缺陷 5）
- **in-app 实时日志查看器 UI**：`adb` 无法稳定断言其内部状态

---

## 八、经验总结

1. **管道会吞掉退出码**。`cmd | head` 的退出码来自 `head`。要判断 `cmd` 是否被信号杀死，必须先单独取它的退出码。这一个疏忽导致整个能力探测系统性地说谎。
2. **`logcat -b events` 打开即回放历史**。任何基于它的触发器都必须做预热或用行内时间戳过滤，否则会在启动时被数小时前的事件触发。
3. **`pm grant` 只对 manifest 中声明过的权限有效**。建议用户执行的命令，自己必须先实测一遍 —— 否则建议本身就是缺陷。
4. **Android 的服务权限是"按服务"的**。`DUMP` 只是一张门票，`dumpsys activity` 还要 `PACKAGE_USAGE_STATS`。错误信息里已经写明了缺什么，应该解析它而不是猜测。
5. **修复噪音可能制造新噪音**。防误触发的第一版把 620 条事件逐条打了日志，反而污染了日志 —— 而这正是本工具要解决的问题。聚合是必须的。

