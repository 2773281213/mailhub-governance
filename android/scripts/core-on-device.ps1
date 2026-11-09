<#
.SYNOPSIS
    在已连接的 Android 设备上跑 :core 的全部测试。

.DESCRIPTION
    :core 的测试源码和测试资源由 :app 的 androidTest 源码集直接复用（见 android/app/build.gradle.kts），
    所以 :app:connectedDebugAndroidTest 就是「:core 的测试在真正的 Android 运行时（ART）上回放」的入口。
    本脚本只做四件事：
      1. 只在当前进程设置 JAVA_HOME 和 ANDROID_HOME（不改系统环境变量），与 check.ps1 相同；
      2. 用 adb get-state 确认设备在线：连不上就输出「blocked：没有可用设备」并以退出码 2 结束；
      3. 用 ANDROID_SERIAL 锁定这一台设备，运行 :app:connectedDebugAndroidTest；
      4. 打印测试报告路径和通过 / 失败数。

    设备是否开机由用户负责：本脚本不启动、不关闭、不安装、不卸载任何软件（包括 MuMu 模拟器本身）。

    @Category(JvmOnly::class) 标记的测试只能在桌面 JVM 上跑，这里用 runner 参数 notAnnotation 排除，
    约定见 android/core/src/test/kotlin/com/mailhub/core/JvmOnly.kt。

.PARAMETER Serial
    设备序列号，默认是 MuMu 模拟器的 127.0.0.1:16384。

.PARAMETER ExtraArgs
    额外的 gradle 参数，例如重试时用的
    -ExtraArgs '--max-workers=2','-Dorg.gradle.internal.http.connectionTimeout=120000'

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File android\scripts\core-on-device.ps1

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File android\scripts\core-on-device.ps1 -ExtraArgs '--max-workers=2'
#>
[CmdletBinding()]
param(
    [string]$Serial = '127.0.0.1:16384',
    [Parameter(ValueFromRemainingArguments = $false)]
    [string[]]$ExtraArgs = @()
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$androidDir = Join-Path $repoRoot 'android'

# 只在当前进程里设置，不污染系统环境（与 check.ps1 一致）。
$env:JAVA_HOME = Join-Path $env:USERPROFILE '.jdks\temurin-21'
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'

if (-not (Test-Path $env:JAVA_HOME)) {
    Write-Error "找不到 JDK：$env:JAVA_HOME"
    exit 1
}
if (-not (Test-Path $env:ANDROID_HOME)) {
    Write-Error "找不到 Android SDK：$env:ANDROID_HOME"
    exit 1
}

$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
if (-not (Test-Path $adb)) {
    Write-Error "找不到 adb：$adb"
    exit 1
}

# 查设备状态。adb 把失败原因写到 stderr，这里两路都收下来原样打印；
# 期间临时把 ErrorActionPreference 放回 Continue，避免 stderr 被当成终止性错误。
$previousPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$stateOutput = & $adb -s $Serial get-state 2>&1
$adbExitCode = $LASTEXITCODE
$ErrorActionPreference = $previousPreference

$stateText = (($stateOutput | ForEach-Object { "$_" }) -join "`n").Trim()
if ($adbExitCode -ne 0 -or $stateText -ne 'device') {
    Write-Host 'blocked：没有可用设备'
    Write-Host "adb -s $Serial get-state 退出码：$adbExitCode"
    Write-Host "adb 输出：$stateText"
    Write-Host '设备是否开机由用户负责：本脚本不启动、不关闭、不安装、不卸载任何软件。'
    exit 2
}

# 只对这一台设备跑：adb 和 AGP 都认 ANDROID_SERIAL。
$env:ANDROID_SERIAL = $Serial

# 统一成字符串数组，允许 -ExtraArgs '--max-workers=2','-Dfoo=bar' 这种写法。
$extra = @()
foreach ($item in $ExtraArgs) {
    if ($null -ne $item -and "$item".Trim().Length -gt 0) {
        foreach ($part in "$item".Split(' ', [System.StringSplitOptions]::RemoveEmptyEntries)) {
            $extra += $part
        }
    }
}

# JvmOnly 是 :core 测试源码里的标记接口，只能在桌面 JVM 上跑的测试用 @Category(JvmOnly::class) 标出来。
$jvmOnlyCategory = 'com.mailhub.core.JvmOnly'

$gradleArgs = @(
    '--no-daemon',
    ':app:connectedDebugAndroidTest',
    "-Pandroid.testInstrumentationRunnerArguments.notAnnotation=$jvmOnlyCategory"
) + $extra

$gradlew = Join-Path $androidDir 'gradlew.bat'

Write-Host "==> 设备：$Serial"
Write-Host "==> $gradlew $($gradleArgs -join ' ')"
Push-Location $androidDir
try {
    & $gradlew @gradleArgs
    $gradleExitCode = $LASTEXITCODE
}
finally {
    Pop-Location
}
Write-Host "==> gradlew 退出码：$gradleExitCode"

# 报告位置（AGP 的固定路径）：HTML 汇总页 + JUnit XML 原始结果。
$reportIndex = Join-Path $androidDir 'app\build\reports\androidTests\connected\debug\index.html'
$resultsRoot = Join-Path $androidDir 'app\build\outputs\androidTest-results\connected'

Write-Host ''
Write-Host '==> 测试报告'
if (Test-Path $reportIndex) {
    Write-Host "    HTML 汇总：$reportIndex"
}
else {
    Write-Host "    HTML 汇总：没有生成（$reportIndex）——构建可能在编译、安装或连接设备阶段就失败了"
}
if (Test-Path $resultsRoot) {
    Write-Host "    XML 原始结果：$resultsRoot"
}

$total = 0
$failed = 0
$skipped = 0
$rows = @()

if (Test-Path $resultsRoot) {
    foreach ($file in (Get-ChildItem -Path $resultsRoot -Recurse -Filter '*.xml' -ErrorAction SilentlyContinue)) {
        $doc = $null
        try {
            [xml]$doc = Get-Content -Path $file.FullName -Raw
        }
        catch {
            Write-Warning "读不懂结果文件 $($file.FullName)：$($_.Exception.Message)"
            continue
        }
        if ($null -eq $doc) { continue }
        foreach ($suite in $doc.SelectNodes('//testsuite')) {
            # 属性要用 GetAttribute 取：XmlElement 自带的 Name 属性会盖掉 name 特性。
            $className = $suite.GetAttribute('name')
            $tests = [int]$suite.GetAttribute('tests')
            $failures = [int]$suite.GetAttribute('failures') + [int]$suite.GetAttribute('errors')
            $skips = [int]$suite.GetAttribute('skipped')
            $total += $tests
            $failed += $failures
            $skipped += $skips
            $rows += [pscustomobject]@{
                测试类 = $className
                条数   = $tests
                失败   = $failures
                跳过   = $skips
            }
        }
    }
}

if ($rows.Count -gt 0) {
    Write-Host ''
    Write-Host '==> 每个测试类的结果'
    Write-Host (($rows | Sort-Object 测试类 | Format-Table -AutoSize | Out-String -Width 200).TrimEnd())
}

if ($total -eq 0) {
    Write-Host '==> 没有读到任何测试结果，无法统计通过 / 失败数'
}
else {
    $passed = $total - $failed - $skipped
    Write-Host "==> 通过 $passed，失败 $failed，跳过 $skipped，共 $total 条"
}

exit $gradleExitCode
