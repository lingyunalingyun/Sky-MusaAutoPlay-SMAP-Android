# SMAP-Android 一键构建脚本
# 用法: powershell -File build.ps1   (或 ./build.ps1)
$ErrorActionPreference = "Stop"

$env:JAVA_HOME = "C:\Users\lingy\jdk21\jdk-21.0.11+10"
$env:GRADLE_USER_HOME = "D:\DSH_Works\.gradle"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

# 从工作区运行 + -p 指定项目：problems 报告等中间文件写到工作区，避开沙箱写限制
Set-Location "D:\DSH_Works"
Write-Output "=== 构建 Debug APK ==="
& "D:\DSH_Works\gradle-8.13\bin\gradle.bat" -p "F:\编程文件\编程计划\SMAP-Android" :app:assembleDebug 2>&1
if ($LASTEXITCODE -ne 0) { Write-Output "构建失败 (exit $LASTEXITCODE)"; exit $LASTEXITCODE }

$apk = "D:\DSH_Works\smap-android-build\app\outputs\apk\debug\app-debug.apk"
if (Test-Path -LiteralPath $apk) {
  $size = [math]::Round((Get-Item $apk).Length / 1MB, 1)
  Write-Output "构建成功: $apk ($size MB)"
} else {
  Write-Output "构建完成但未找到 APK，检查: $apk"
}
