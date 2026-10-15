# 为什么 `adb root` 了，应用还是显示没有 root

**设备**：moto g(100) / Android 12 (API 31) / `userdebug` / `test-keys`
**问题**：`adb root` 成功（adb shell 已是 uid=0），但 APK 的 `capability.txt` 报告 `root (su): NO`
**结论**：**这不是缺陷，是两个不同的东西共用了「root」这个词。** 但排查过程中发现并修复了 **3 个真实缺陷**。

---

## 一、直接答案

`adb root` 和「应用能拿到 root」是**两件互不相干的事**：

| | `adb root` 做了什么 | 应用要 root 需要什么 |
|---|---|---|
| 作用对象 | **adb shell 自己**（adbd 重启为 uid=0） | **应用进程**（uid 10347） |
| 机制 | adbd 以 root 身份运行，SELinux 域切到 `u:r:su:s0` | 通过 `su` 二进制提权 |
| 给应用的权限 | **零** | 需要 root 管理器（Magisk/SuperSU） |

关键在 `su` 二进制的**目录权限**：

```
/system/xbin   drwxr-x---  root shell    ← 应用的 other 权限是 ---，连目录都进不去
  └─ su        -rwsr-x---  root shell    ← 即使进去了，文件 other 也是 ---
```

应用进程的实际身份（从 `/proc/<pid>/status` 读取，权威证据）：

```
Uid:    10347 10347 10347 10347
Gid:    10347 10347 10347 10347
Groups: 1007 1077 3003 9997 20347 50347     ← 没有 2000
```

`shell` 组的 gid 是 **2000**，应用的组列表里**没有它**。所以 `execve("/system/xbin/su")` 在 DAC 层就被拒绝，返回 `EACCES` —— 这正是日志里的 `error=13`：

```
IOException: Cannot run program "su": error=13, Permission denied
```

**应用根本没机会执行到 `su` 的代码**，谈不到 SELinux、更谈不到提权。

> 想验证的话：`/system/xbin` 是 `drwxr-x---`，普通应用连 `ls` 都列不出来（返回空）。而 `ls -ld /system/xbin` 本身能成功，因为 stat 一个目录只需要**父目录** `/system` 的 `+x` —— 这就是下面缺陷 3 的由来。

---

## 二、排查中发现的 3 个真实缺陷

### 缺陷 A —— `su` 探测只认 Magisk 语法，AOSP 语法被判为「无 root」

原代码两次探测都是 `su -c`：

```java
Result r  = raw(new String[] { "su", "-c", "id" }, 6000);
Result r2 = raw(new String[] { "su", "-c", "echo ROOTOK" }, 6000);   // 还是 -c
```

但这台机器的 `su` 是 **AOSP 原版**，usage 明确写着：

```
usage: su [WHO [COMMAND...]]
Switch to WHO (default 'root') and run the given COMMAND (default sh).
```

实测对比：

| 调用形式 | 结果 |
|---|---|
| `su -c id` | `su: invalid uid/gid '-c'` ← **AOSP 不支持 `-c`** |
| `su 0 id` | `uid=0(root) ...` ✅ |
| `su root id` | `uid=0(root) ...` ✅ |
| `su 0 sh -c id` | `uid=0(root) ...` ✅ |

也就是说：**即使权限通了，我原来的 root 路径在这台机器上也是坏的。**

**修复**：探测改为依次尝试多种方言，并记住哪个成功，之后所有 root 命令都用它：

```java
private static final String[][] SU_FORMS = {
        { "su", "-c" },                // Magisk / SuperSU
        { "su", "0", "sh", "-c" },     // AOSP su：显式 uid，再起一个真 shell
        { "su", "root", "sh", "-c" },  // 需要写用户名的那种 su
};
```

每种形式都以 `-c` 结尾，命令作为单个参数追加 —— 这样探测和实际调用是同一个契约，探测通过的形式不会在实际执行管道／`&&` 时失败。

---

### 缺陷 B —— 诊断文案在每次采集时自我叠加，无限增长

`capability.txt` 里出现了这样的内容：

```
dmesg BLOCKED (cached verdict, not re-probed): dmesg BLOCKED (cached verdict, not re-probed):
dmesg BLOCKED (cached verdict, not re-probed): ...(共 9 层)... dmesg BLOCKED - 真正的原因
```

**根因**：`Capabilities` 把带标签的显示串存回闸门，下次读出来再套一层标签 —— 每跑一次采集就长一层。

**修复**：`markBlocked()` 存入前剥掉标签，且剥离必须是**幂等**的。

这里还踩了一个坑，被单测抓出来：剥离时如果**短前缀先匹配**，它会咬掉长标签的一部分，留下残缺串：

```
输入: "dmesg BLOCKED (cached verdict, not re-probed): kernel seccomp ..."
错误结果: "(cached verdict, not re-probed): dmesg BLOCKED ..."   ← 短前缀 "dmesg BLOCKED" 误匹配
```

**修正**：前缀按**从长到短**排列，命中即 break；短标签还要求后面是词边界。

单测结果（含 9 层嵌套的极端用例）：

```
[PASS] len 56  -> 56        裸原因，原样返回
[PASS] len 103 -> 56        单层标签
[PASS] len 88  -> 56
[PASS] len 72  -> 56
[PASS] len 479 -> 56        ← 9 层嵌套，完整剥净
[PASS] len 0   -> 0
[PASS] strip is idempotent  幂等
strip: PASS 7  FAIL 0
```

真机复验：连续 3 次采集后，`capability.txt` 中 `cached verdict` 出现次数 = **0**。

---

### 缺陷 C —— 诊断信息探测错了对象，导致说明永远是空的

我原本用 `ls -l /system/xbin/su` 拿 `su` 的模式位。**在应用身份下这条命令必然返回空**：

```
/system/xbin   drwxr-x---  root shell
```

应用连这个**目录**都进不去，`2>/dev/null` 又把 "Permission denied" 吞了 → 输出为空 → 说明文字整段消失。

**修复**：改为 stat **目录**而不是文件。stat 一个目录只需**父目录**的 `+x`，而 `/system` 是 `drwxr-xr-x`，所以应用**能**读到 `/system/xbin` 的模式位：

```
su location modes:
  drwxr-x--x 4 root shell 8192 /system/bin
  drwxr-x--- 2 root shell 4096 /system/xbin
Where su lives is mode drwxr-x--- root:shell, so the binary is
executable and even readable only by root and by members of the
`shell` group. ...
```

---

## 三、顺带确认的两件事

1. **`adb unroot` 后无法从 adb 启动本应用的服务** —— 这是**正确的安全行为**，不是缺陷：

   ```
   Permission Denial: Accessing service com.dsh.logcollector/.CollectorService
   from pid=1789, uid=2000 that is not exported from uid 10348
   ```

   `CollectorService` 声明为 `exported=false`，只有 root 身份的 adb 能拉起它。测试时若 `adb unroot` 过，记得 `adb root` 回来。

2. **`su` 是 setuid root**（`-rwsr-x---`）。所以一旦某个进程**能**执行它，它就会以 uid=0 运行 —— 权限的闸门完全在那个模式位上，而不是在 `su` 内部。

---

## 四、修复后 `capability.txt` 的实际输出

```
--- privileges -----------------------------------------------------
root (su)          : NO  - no working su - last error: IOException: Cannot run program "su": error=13, Permission denied
  su location modes:
    drwxr-x--x 4 root shell 8192 2009-01-01 08:00 /system/bin
    drwxr-x--- 2 root shell 4096 2009-01-01 08:00 /system/xbin
  Where su lives is mode drwxr-x--- root:shell, so the binary is
  executable and even readable only by root and by members of the
  `shell` group. A normal app uid belongs to neither: execve() fails
  with EACCES before su ever runs.

  `adb root` does NOT change this. It only restarts adbd so the *adb
  shell* runs as uid=0 in the su SELinux domain. It grants an
  application no additional rights whatsoever.

  For this app to run su, the device needs a root manager
  (Magisk / SuperSU / KernelSU), which installs a world-executable su
  and prompts the user to authorise each app.

  Without that, the parts of a collection that need root stay
  unavailable in-app - use tools/augment_from_pc.ps1 instead, which
  runs the same commands through adb where none of these limits apply.
```

界面上「设备能力」面板的 Root 行也改为直接给结论，而不是干巴巴一句 "no working su binary"：

> adb root 只让 adb shell 变成 root，不会给应用任何权限；应用要 root 需 Magisk/SuperSU 类 su

---

## 五、那要怎么才能拿到需要 root 的数据？

本机 `dmesg` 已经被 seccomp 拦死（见 `DEVICE_TEST_ANALYSIS.md`），而 root 又拿不到。有两条路：

**A. 用电脑侧脚本补齐（推荐，无需改设备）**

```powershell
adb root
pwsh -File tools\augment_from_pc.ps1
```

实测补入：`dmesg_from_pc.txt`（1.0 MB / 11,610 行）+ `dumpsys_from_pc.txt`（15.2 MB / 242 个服务）+ `crash_artefacts_from_pc.txt`。

**B. 装 root 管理器**（Magisk / KernelSU），之后应用即可自行 `su`。这条路会改动设备，且需要解锁 bootloader。

---

## 六、总结：3 个缺陷的共性

这三个缺陷有一个共同点：**它们都源于「我以为环境和我的假设一致」**。

| 假设 | 现实 |
|---|---|
| `su` 都支持 `-c` | AOSP 原版明确拒绝 `-c`，用的是 `su 0 sh -c` |
| 显示串可以原样存回 | 存回再读出会自我叠加，且短前缀会咬坏长标签 |
| `ls -l <文件>` 能读到模式位 | 父目录 `drwxr-x---`，应用连目录都进不去 |

而且**第三个缺陷是最隐蔽的**：`2>/dev/null` 把权限错误吞掉，命令"成功"返回空字符串，代码走了"没找到"分支，功能静默失效 —— 不报错、不崩溃，只是那段本该出现的说明永远不出现。

**教训**：诊断类代码必须验证「在目标身份下能否真的取到数据」，而不是「命令语法是否正确」。前者只有真机能证明。

---

## 七、验证汇总

| 测试 | 结果 |
|---|---|
| `stripLabel` 幂等性单测（含 9 层嵌套） | **7/7 通过** |
| seccomp 闸门单测 | **12/12 通过** |
| 逻辑单测（真实字节码） | **58/58 通过** |
| shell 命令语法 | **48/48 通过** |
| 真机连续 3 次采集后标签叠加 | **0 次** |
| 真机 tombstone | **0 个** |
| 真机 `capability.txt` root 段落 | 输出完整、含证据与结论 |
