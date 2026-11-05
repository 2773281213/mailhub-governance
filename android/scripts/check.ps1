<#
.SYNOPSIS
    本地跑一遍和 CI 完全相同的安卓验收任务。

.DESCRIPTION
    只在当前进程设置 JAVA_HOME 和 ANDROID_HOME（不改系统环境变量），
    然后在 android/ 目录里运行：
      :app:swissGuard :app:swissGuardSelfTest :app:testDebugUnitTest
      :app:verifyRoborazziDebug :app:lintDebug :app:assembleDebug
    gradlew 的退出码原样作为本脚本的退出码。

.PARAMETER ExtraArgs
    额外的 gradle 参数，例如重试时用的
    -ExtraArgs '--max-workers=2','-Dorg.gradle.internal.http.connectionTimeout=120000'

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File android\scripts\check.ps1

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File android\scripts\check.ps1 -ExtraArgs '--max-workers=2'
#>
[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $false)]
    [string[]]$ExtraArgs = @()
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$androidDir = Join-Path $repoRoot 'android'

# 只在当前进程里设置，不污染系统环境。
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

$tasks = @(
    ':app:swissGuard',
    ':app:swissGuardSelfTest',
    ':app:testDebugUnitTest',
    ':app:verifyRoborazziDebug',
    ':app:lintDebug',
    ':app:assembleDebug'
)

# 统一成字符串数组，允许 -ExtraArgs '--max-workers=2','-Dfoo=bar' 这种写法
$extra = @()
foreach ($item in $ExtraArgs) {
    if ($null -ne $item -and "$item".Trim().Length -gt 0) {
        foreach ($part in "$item".Split(' ', [System.StringSplitOptions]::RemoveEmptyEntries)) {
            $extra += $part
        }
    }
}

$gradleArgs = @('--no-daemon') + $tasks + $extra
$gradlew = Join-Path $androidDir 'gradlew.bat'

Write-Host "==> $gradlew $($gradleArgs -join ' ')"
Push-Location $androidDir
try {
    & $gradlew @gradleArgs
    $exitCode = $LASTEXITCODE
}
finally {
    Pop-Location
}

Write-Host "==> gradlew 退出码：$exitCode"
exit $exitCode
