# Build, align and sign the DSH LogCollector APK.
#
# Pipeline: aapt2 compile -> aapt2 link -> javac -> d8 -> zipalign -> apksigner
# No Gradle, no network access required.
#
#   pwsh -File tools/build.ps1
#   pwsh -File tools/build.ps1 -Clean
#   pwsh -File tools/build.ps1 -Install        # adb install -r onto the connected device
#
[CmdletBinding()]
param(
    [switch]$Clean,
    [switch]$Install
)

$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------- toolchain
$JBR     = "C:\Program Files\JetBrains\PyCharm Community Edition 2023.3.2\jbr"
$JAVAC   = Join-Path $JBR "bin\javac.exe"
$JAVA    = Join-Path $JBR "bin\java.exe"
$KEYTOOL = Join-Path $JBR "bin\keytool.exe"

# d8.bat and apksigner need a discoverable JDK.
$env:JAVA_HOME = $JBR
$env:PATH = (Join-Path $JBR "bin") + ";" + $env:PATH

$SDK           = "D:\Android\Sdk"
$BT            = Join-Path $SDK "build-tools\34.0.0"
$ANDROID_JAR   = Join-Path $SDK "platforms\android-34\android.jar"
$AAPT2         = Join-Path $BT "aapt2.exe"
$D8            = Join-Path $BT "d8.bat"
$ZIPALIGN      = Join-Path $BT "zipalign.exe"
$APKSIGNER_JAR = Join-Path $BT "lib\apksigner.jar"
$ADB           = Join-Path $SDK "platform-tools\adb.exe"

# ------------------------------------------------------------------ project
$PROJ  = Split-Path $PSScriptRoot -Parent
$SRC   = Join-Path $PROJ "app\src\main"
$BUILD = Join-Path $PROJ "build"
$DIST  = Join-Path $PROJ "dist"
$KS    = Join-Path $PROJ "keystore\logcollector.jks"

$KS_PASS  = "logcollect2026"
$KS_ALIAS = "logcollect"
$KS_DNAME = "CN=DSH LogCollector, OU=Debug, O=Personal, L=Shenzhen, ST=Guangdong, C=CN"

$MIN_SDK      = 21
$TARGET_SDK   = 34
$VERSION_CODE = 1
$VERSION_NAME = "1.0"
$APK_NAME     = "LogCollector.apk"

function Step($m) { Write-Host "`n=== $m ===" -ForegroundColor Cyan }
function Ok($m)   { Write-Host "  [ok] $m" -ForegroundColor Green }
function Warn($m) { Write-Host "  [!!] $m" -ForegroundColor Yellow }

# ---------------------------------------------------------------- preflight
Step "Checking toolchain"
foreach ($t in @($JAVAC, $JAVA, $KEYTOOL, $AAPT2, $D8, $ZIPALIGN, $APKSIGNER_JAR, $ANDROID_JAR)) {
    if (-not (Test-Path $t)) { throw "Missing required tool: $t" }
    Ok (Split-Path $t -Leaf)
}
if (-not (Test-Path "$SRC\AndroidManifest.xml")) { throw "Missing manifest: $SRC\AndroidManifest.xml" }

if ($Clean -and (Test-Path $BUILD)) {
    Step "Cleaning build directory"
    Remove-Item $BUILD -Recurse -Force
    Ok "removed $BUILD"
}

# --------------------------------------------------------------- directories
Step "Preparing build directories"
foreach ($d in @("$BUILD\res", "$BUILD\gen", "$BUILD\classes", "$BUILD\dex", $DIST, (Split-Path $KS))) {
    New-Item -ItemType Directory -Force -Path $d | Out-Null
}
foreach ($d in @("$BUILD\res", "$BUILD\gen", "$BUILD\classes", "$BUILD\dex")) {
    Remove-Item "$d\*" -Recurse -Force -ErrorAction SilentlyContinue
}
Ok "clean"

# ------------------------------------------------------------------ keystore
Step "Signing key"
if (-not (Test-Path $KS)) {
    & $KEYTOOL -genkeypair -v `
        -keystore $KS -alias $KS_ALIAS `
        -keyalg RSA -keysize 2048 -validity 10950 `
        -storepass $KS_PASS -keypass $KS_PASS `
        -dname $KS_DNAME 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
    Ok "created $KS"
} else {
    Ok "reusing $KS"
}

# ---------------------------------------------------------------- resources
Step "Compiling resources (aapt2 compile)"
$resOut = "$BUILD\res.zip"
& $AAPT2 compile --dir "$SRC\res" -o $resOut 2>&1 | ForEach-Object { Write-Host "    $_" }
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }
Ok "compiled -> $((Get-Item $resOut).Length) bytes"

Step "Linking resources (aapt2 link)"
$unsigned = "$BUILD\unsigned.apk"
& $AAPT2 link `
    -o $unsigned `
    -I $ANDROID_JAR `
    --manifest "$SRC\AndroidManifest.xml" `
    --java "$BUILD\gen" `
    --min-sdk-version $MIN_SDK `
    --target-sdk-version $TARGET_SDK `
    --version-code $VERSION_CODE --version-name $VERSION_NAME `
    $resOut 2>&1 | ForEach-Object { Write-Host "    $_" }
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }
Ok "linked -> $((Get-Item $unsigned).Length) bytes"

# --------------------------------------------------------------------- javac
Step "Compiling Java"
$javaFiles = Get-ChildItem -Path "$SRC\java", "$BUILD\gen" -Filter *.java -Recurse -ErrorAction SilentlyContinue |
             Select-Object -ExpandProperty FullName
if (-not $javaFiles) { throw "no .java sources found under $SRC\java" }
Write-Host "    $($javaFiles.Count) source files"
& $JAVAC -nowarn -encoding UTF-8 --release 8 `
    -classpath $ANDROID_JAR `
    -d "$BUILD\classes" `
    $javaFiles 2>&1 | ForEach-Object { Write-Host "    $_" }
if ($LASTEXITCODE -ne 0) { throw "javac failed" }
Ok "classes compiled"

# ------------------------------------------------------------------------ d8
Step "Dexing (d8)"
# d8 accepts jars/zips but not a bare directory, and passing every .class path overflows the
# Windows command-line limit with this many sources. So: pack the classes into a jar first.
$classDir = "$BUILD\classes"
$classCount = (Get-ChildItem -Path $classDir -Filter *.class -Recurse | Measure-Object).Count
if ($classCount -eq 0) { throw "no .class files under $classDir" }
Write-Host "    $classCount class files"

$classesJar = "$BUILD\classes.jar"
if (Test-Path $classesJar) { Remove-Item $classesJar -Force }
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::CreateFromDirectory(
    $classDir, $classesJar, [System.IO.Compression.CompressionLevel]::Optimal, $false)
Ok "packed -> $((Get-Item $classesJar).Length) bytes"

& $D8 --min-api $MIN_SDK --lib $ANDROID_JAR --output "$BUILD\dex" $classesJar 2>&1 |
    ForEach-Object { Write-Host "    $_" }
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }
$dex = "$BUILD\dex\classes.dex"
if (-not (Test-Path $dex)) { throw "classes.dex not produced" }
Ok "classes.dex -> $((Get-Item $dex).Length) bytes"

# ------------------------------------------------------------ assemble APK
Step "Assembling APK"
Copy-Item $unsigned "$BUILD\assembled.apk" -Force
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$zip = [System.IO.Compression.ZipFile]::Open("$BUILD\assembled.apk", 'Update')
try {
    foreach ($f in Get-ChildItem "$BUILD\dex" -Filter *.dex) {
        $entry = $zip.GetEntry($f.Name)
        if ($entry) { $entry.Delete() }
        [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
            $zip, $f.FullName, $f.Name, [System.IO.Compression.CompressionLevel]::Optimal)
        Ok "added $($f.Name)"
    }
} finally { $zip.Dispose() }
Ok "assembled -> $((Get-Item "$BUILD\assembled.apk").Length) bytes"

# -------------------------------------------------------------------- align
Step "Aligning (zipalign)"
$aligned = "$BUILD\aligned.apk"
if (Test-Path $aligned) { Remove-Item $aligned -Force }
& $ZIPALIGN -f -p 4 "$BUILD\assembled.apk" $aligned 2>&1 | ForEach-Object { Write-Host "    $_" }
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }
Ok "aligned"

# --------------------------------------------------------------------- sign
Step "Signing (apksigner)"
$final = Join-Path $DIST $APK_NAME
if (Test-Path $final) { Remove-Item $final -Force }
& $JAVA -jar $APKSIGNER_JAR sign `
    --ks $KS --ks-key-alias $KS_ALIAS `
    --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" `
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true `
    --out $final $aligned 2>&1 | ForEach-Object { Write-Host "    $_" }
if ($LASTEXITCODE -ne 0) { throw "apksigner sign failed" }

Step "Verifying signature"
& $JAVA -jar $APKSIGNER_JAR verify --verbose --print-certs $final 2>&1 |
    ForEach-Object { Write-Host "    $_" }
if ($LASTEXITCODE -ne 0) { throw "apksigner verify failed" }

# ------------------------------------------------------------------ summary
Step "Result"
$fi = Get-Item $final
Write-Host ("  APK     : " + $fi.FullName)
Write-Host ("  Size    : {0:N0} bytes ({1:N2} MB)" -f $fi.Length, ($fi.Length / 1MB))
Write-Host ("  SHA256  : " + (Get-FileHash $final -Algorithm SHA256).Hash)
Write-Host ("  minSdk  : $MIN_SDK   targetSdk: $TARGET_SDK   version: $VERSION_NAME ($VERSION_CODE)")

if ($Install) {
    Step "Installing via adb"
    if (-not (Test-Path $ADB)) { throw "adb not found at $ADB" }
    & $ADB install -r $final 2>&1 | ForEach-Object { Write-Host "    $_" }
    if ($LASTEXITCODE -ne 0) { Warn "adb install failed (exit $LASTEXITCODE)" }
    else { Ok "installed" }
}

Write-Host "`nDONE" -ForegroundColor Green
