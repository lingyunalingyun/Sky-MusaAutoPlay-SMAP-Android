# SMAP-Android

SMAP（Sky-MusaAutoPlay）光遇自动弹琴助手的 **Android 手机版**。

## 定位
桌面版（C# WPF）的手机端延伸。光遇本身是手游，手机端直接弹琴是更主流场景。

## 技术栈
- Kotlin + Jetpack Compose
- Android 无障碍服务（`dispatchGesture` 模拟琴键点击）
- 复用缪斯树屋（musetreehouse.com）在线曲库 API

## 构建
```powershell
.\gradlew.bat assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`

## 相关
- 曲谱格式：Sky 曲谱（name/author/bpm/songNotes[{time,key:"1KeyN"}]/keyCount）
- 光遇 15 键 = C4~C6 白键（key 0~14）

## 开源协议

本项目采用 [GNU General Public License v3.0](LICENSE) 开源。

隐私政策见 [PRIVACY_POLICY.md](PRIVACY_POLICY.md)。

Copyright © 2026 LingYunALingYun
