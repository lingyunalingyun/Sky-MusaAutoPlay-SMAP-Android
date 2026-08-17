# SMAP-Android

SMAP（Sky-MusaAutoPlay）光遇自动弹琴助手的 **Android 手机版**。

## 定位
桌面版（C# WPF）的手机端延伸。光遇本身是手游，手机端直接弹琴是更主流场景。

## 技术栈
- Kotlin + Jetpack Compose
- Android 无障碍服务（`dispatchGesture` 模拟琴键点击）
- 复用缪斯树屋（musetreehouse.com）在线曲库 API

## 里程碑
- [x] M1 项目骨架（Gradle + Kotlin + Compose）
- [ ] M2 曲谱解析 + 本地曲库 + 基础播放
- [ ] M3 在线曲库（登录/列表/下载）
- [ ] M4 自动弹琴核心（无障碍服务 + 琴键校准）
- [ ] M5 练习/读谱模式
- [ ] M6 设置 + APK 打包发布

## 构建
```
gradle :app:assembleDebug
```
APK 输出：`app/build/outputs/apk/debug/app-debug.apk`

## 相关
- 桌面版：`F:\编程文件\编程计划\SMAP-WPF`
- 曲谱格式：Sky 曲谱（name/author/bpm/songNotes[{time,key:"1KeyN"}]/keyCount）
- 光遇 15 键 = C4~C6 白键（key 0~14）
