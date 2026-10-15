<#
.SYNOPSIS
  Fill in the parts of a collection run that the on-device app cannot reach.

.DESCRIPTION
  The app runs inside an app's security domain, which means the kernel blocks `dmesg` with a
  seccomp SIGSYS and a handful of dumpsys services demand permissions that cannot be granted to
  a normal app. Those gaps are real and unfixable from inside the app - but adb has none of
  those limits.

  This script takes a run directory produced by the app and augments it from the PC side:

    * dmesg            (needs `adb root`, or a userdebug build)
    * the full `dumpsys` sweep as the shell uid
    * bugreport        (also needs root)
    * tombstones / ANR traces / pstore, when readable

  Everything it writes is clearly marked so nobody mistakes PC-side data for app-side data.

.PARAMETER Run
  The run directory to augment. Defaults to the newest one under /sdcard/log.

.PARAMETER Serial
  adb device serial, when more than one device is attached.

.PARAMETER SkipBugreport
  Skip the bugreport step. It is by far the slowest part (minutes).

.EXAMPLE
  pwsh -File tools\augment_from_pc.ps1
  pwsh -File tools\augment_from_pc.ps1 -Run /sdcard/log/20261014-175119
  pwsh -File tools\augment_from_pc.ps1 -SkipBugreport
#>
[CmdletBinding()]
param(
    [string]$Run,
    [string]$Serial,
    [switch]$SkipBugreport,
    [string]$Adb = "D:\Android\Sdk\platform-tools\adb.exe",
    [string]$BaseDir = "/sdcard/log"
)

$ErrorActionPreference = 'Stop'

function Step($m) { Write-Host "`n=== $m ===" -ForegroundColor Cyan }
function Ok($m)   { Write-Host "  [ok] $m" -ForegroundColor Green }
function Warn($m) { Write-Host "  [!!] $m" -ForegroundColor Yellow }

# ------------------------------------------------------------------ validate
if (-not (Test-Path $Adb)) {
    # Fall back to whatever adb is on PATH.
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { $Adb = $cmd.Source } else { throw "adb not found. Pass -Adb <path>." }
}
Ok "adb: $Adb"

# A serial argument has to be threaded through every call, so all adb use goes through one helper.
$script:AdbPath = $Adb

function Run-Adb {
    param([string[]]$Arguments, [switch]$AllowFail)
    $prefix = if ($Serial) { @('-s', $Serial) } else { @() }
    $out = & $script:AdbPath @prefix @Arguments 2>&1
    if ($LASTEXITCODE -ne 0 -and -not $AllowFail) {
        Warn ("adb " + ($Arguments -join ' ') + " exited " + $LASTEXITCODE)
    }
    return ($out | Out-String)
}

Step "Checking device"
$devices = Run-Adb @('devices')
Write-Host $devices
if ($devices -notmatch '\bdevice\b') { throw "No device in 'device' state. Check the USB connection and authorisation prompt." }

$model = (Run-Adb @('shell','getprop','ro.product.model')).Trim()
$rel   = (Run-Adb @('shell','getprop','ro.build.version.release')).Trim()
$isRoot = (Run-Adb @('shell','id')).Trim()
Ok "device: $model  Android $rel"
Write-Host "  identity: $isRoot"

$haveRoot = $isRoot -match 'uid=0'

# ------------------------------------------------------------ pick the run
Step "Locating the collection run"
if (-not $Run) {
    $latest = (Run-Adb @('shell',"ls -d $BaseDir/20* 2>/dev/null | tail -n 1")).Trim()
    if (-not $latest) { throw "No run directory found under $BaseDir. Run a collection in the app first." }
    $Run = $latest
}
$Run = $Run.Trim()
$exists = (Run-Adb @('shell',"test -d '$Run' && echo YES || echo NO")).Trim()
if ($exists -ne 'YES') { throw "Run directory does not exist on the device: $Run" }
Ok "run: $Run"

$stamp = (Run-Adb @('shell',"basename '$Run'")).Trim()
Write-Host "  run name: $stamp"

# Write a marker so the provenance of every PC-side file is unambiguous.
$header = @"
# Collected from the PC side by tools/augment_from_pc.ps1
# The on-device app cannot read these - see capability.txt / dmesg.txt for why.
# device  : $model (Android $rel)
# identity: $isRoot
# run     : $Run
#
"@
$header | Out-File -FilePath "$env:TEMP\lc_header.txt" -Encoding utf8

function Push-Text {
    param([string]$RemoteName, [string]$LocalFile, [string]$Description)
    $bytes = (Get-Item $LocalFile).Length
    & $script:AdbPath @(if ($Serial) { @('-s', $Serial) } else { @() }) @('push', $LocalFile, "$Run/$RemoteName") 2>&1 | Out-Null
    if ($LASTEXITCODE -eq 0) {
        Ok "$RemoteName  ($([math]::Round($bytes/1KB,1)) KB)  <- $Description"
    } else {
        Warn "$RemoteName push failed"
    }
}

# ------------------------------------------------------------------- dmesg
Step "dmesg (PC side)"
$dmesgOk = $false
if ($haveRoot) {
    $tmp = "$env:TEMP\lc_dmesg.txt"
    Run-Adb @('shell','dmesg') | Out-File -FilePath $tmp -Encoding utf8
    # Prepend the provenance header.
    $body = Get-Content $tmp -Raw
    ($header + $body) | Out-File -FilePath $tmp -Encoding utf8 -NoNewline
    if ((Get-Item $tmp).Length -gt 100) {
        Push-Text 'dmesg_from_pc.txt' $tmp 'full kernel ring buffer via adb root'
        $dmesgOk = $true
    }
} else {
    # A userdebug build often allows the shell uid to read dmesg without root.
    $probe = Run-Adb @('shell','dmesg 2>&1 | head -n 1') -AllowFail
    if ($probe -notmatch 'denied|not permitted|klogctl') {
        $tmp = "$env:TEMP\lc_dmesg.txt"
        Run-Adb @('shell','dmesg') | Out-File -FilePath $tmp -Encoding utf8
        $body = Get-Content $tmp -Raw
        ($header + $body) | Out-File -FilePath $tmp -Encoding utf8 -NoNewline
        Push-Text 'dmesg_from_pc.txt' $tmp 'kernel ring buffer via adb shell'
        $dmesgOk = $true
    }
}
if (-not $dmesgOk) {
    Warn "dmesg unavailable - try: adb root"
    Warn "if 'adb root' is refused, this build does not allow it."
}

# ---------------------------------------------------------------- dumpsys
Step "dumpsys sweep as the shell uid"
$svcTmp = "$env:TEMP\lc_services.txt"
Run-Adb @('shell','dumpsys -l') | Out-File -FilePath $svcTmp -Encoding utf8
$services = (Get-Content $svcTmp) |
    ForEach-Object { $_.Trim() } |
    Where-Object { $_ -and $_ -notmatch '^Currently running services' -and $_ -notmatch '^Service ' -and $_ -notmatch '^-' }
Write-Host "  $($services.Count) services listed"

# The shell uid can dump far more than an app, but the sweep is still slow: batch it into one
# shell invocation so we pay the adb round-trip once instead of once per service.
$script = ($services | ForEach-Object {
    "echo '########## $_'; dumpsys '$_' 2>&1"
}) -join "`n"

$sysTmp = "$env:TEMP\lc_sysdumpsys.txt"
$scriptFile = "$env:TEMP\lc_sweep.sh"
# Strip CR so the device's sh does not choke on CRLF.
($script -replace "`r", "") | Out-File -FilePath $scriptFile -Encoding ascii -NoNewline

    & $script:AdbPath @(if ($Serial) { @('-s', $Serial) } else { @() }) @('push', $scriptFile, "$Run/.pc_sweep.sh") 2>&1 | Out-Null
Run-Adb @('shell',"sh '$Run/.pc_sweep.sh'") | Out-File -FilePath $sysTmp -Encoding utf8
$body = Get-Content $sysTmp -Raw
($header + $body) | Out-File -FilePath $sysTmp -Encoding utf8 -NoNewline
Push-Text 'dumpsys_from_pc.txt' $sysTmp 'all services via adb shell (app was denied some)'
Run-Adb @('shell',"rm -f '$Run/.pc_sweep.sh'") -AllowFail | Out-Null

# --------------------------------------------------------------- bugreport
if (-not $SkipBugreport) {
    Step "bugreport (slow, minutes)"
    if ($haveRoot) {
        $brLocal = "$env:TEMP\lc_bugreport.zip"
        if (Test-Path $brLocal) { Remove-Item $brLocal -Force }
        Warn "running `adb bugreport` - this can take several minutes"
    & $script:AdbPath @(if ($Serial) { @('-s', $Serial) } else { @() }) @('bugreport', $brLocal) 2>&1 | Out-Null
        if ((Test-Path $brLocal) -and (Get-Item $brLocal).Length -gt 0) {
            Push-Text 'bugreport_from_pc.zip' $brLocal 'full bugreport via adb'
        } else {
            Warn "bugreport produced nothing"
        }
    } else {
        Warn "skipped: needs root"
    }
} else {
    Step "bugreport"
    Write-Host "  skipped (-SkipBugreport)"
}

# ------------------------------------------------------- crash artefacts
Step "Crash artefacts"
$crash = @"
$header
"@
foreach ($p in @('/data/tombstones', '/data/anr', '/sys/fs/pstore', '/data/system/dropbox')) {
    $listing = Run-Adb @('shell',"ls -la $p 2>&1 | head -n 40") -AllowFail
    $crash += "`n---------------- $p ----------------`n$listing"
}
$crashTmp = "$env:TEMP\lc_crash.txt"
$crash | Out-File -FilePath $crashTmp -Encoding utf8
Push-Text 'crash_artefacts_from_pc.txt' $crashTmp 'tombstones / ANR / pstore / dropbox listings'

# -------------------------------------------------- display / panel (root)
#
# These are the nodes the app can never read: they are labelled
# u:object_r:vendor_sysfs_graphics:s0, which root and the adb shell may read but the
# untrusted_app domain may not. The app records the denial and points here.
Step "Display / panel nodes (need root)"
$disp = @"
$header
These nodes carry the SELinux label vendor_sysfs_graphics, which an app cannot read.
They are the authoritative source for panel identity and the live brightness value.

"@
$dispProbes = @(
    '/proc/cmdline',
    '/sys/class/drm/card0-DSI-1/status',
    '/sys/class/drm/card0-DSI-1/modes',
    '/sys/class/drm/card0-DSI-1/enabled',
    '/sys/class/backlight/panel0-backlight/brightness',
    '/sys/class/backlight/panel0-backlight/actual_brightness',
    '/sys/class/backlight/panel0-backlight/max_brightness',
    '/sys/class/backlight/panel0-backlight/type',
    '/sys/class/backlight/panel0-backlight/bl_power'
)
foreach ($p in $dispProbes) {
    $v = Run-Adb @('shell',"cat $p 2>&1 | head -c 2000") -AllowFail
    $disp += "---------------- $p ----------------`n$($v.Trim())`n`n"
}
# Also enumerate whatever backlight and drm entries actually exist, since the names vary.
$disp += "---------------- discovered backlight nodes ----------------`n"
$disp += (Run-Adb @('shell','ls -laZ /sys/class/backlight/ /sys/class/drm/ 2>&1') -AllowFail)
$disp += "`n---------------- every backlight attribute ----------------`n"
$disp += (Run-Adb @('shell',
    'for d in /sys/class/backlight/*; do [ -d "$d" ] || continue; echo "### $d"; ' +
    'for a in "$d"/*; do [ -f "$a" ] || continue; echo "  $(basename $a) = $(cat $a 2>&1 | head -c 200)"; done; done') -AllowFail)

$dispTmp = "$env:TEMP\lc_disp.txt"
$disp | Out-File -FilePath $dispTmp -Encoding utf8
Push-Text 'display_from_pc.txt' $dispTmp 'cmdline panel args + DRM + backlight nodes (root only)'

# ------------------------------------------------------------------ finish
Step "Done"
Write-Host "  augmented run: $Run"
Write-Host ""
Write-Host "  Files added from the PC side (all suffixed _from_pc so their origin is obvious):"
    & $script:AdbPath @(if ($Serial) { @('-s', $Serial) } else { @() }) @('shell',"ls -la '$Run' | grep _from_pc") 2>&1 |
    ForEach-Object { Write-Host "    $_" }
Write-Host ""
Write-Host "  Pull the whole run to this machine with:"
Write-Host "    adb pull $Run ./$stamp"


