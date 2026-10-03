# SMAP Android

**注意！本软件未开发完成！许多功能有待完善，如果你乐意也可以帮助我们一起开发！**

**SMAP（Sky-MusaAutoPlay，中文：光遇-Musa 自动演奏）** 是 SMAP 桌面版的 Android 延伸，用于管理、播放和编辑 15 键曲谱，并在用户主动开启游戏浮窗后辅助同步演奏。

> 当前版本：**0.1.0 Beta**
>
> Beta 版本仍在持续完善，建议在发布前备份本地曲谱和设置。

## 功能

- 播放 TXT、JSON、MIDI 格式的音乐文件
- 本地曲库：导入、搜索、排序、收藏、删除和封面显示
- 云端曲库：浏览、搜索、按难度/上传时间排序、查看做谱者与下载量、下载曲谱
- 播放列表：添加、移除、清空、收藏和顺序/循环/随机播放
- 播放控制：进度拖动、倍速、移调、音色和洞穴混响
- 练习模式：15 键曲谱练习、读谱模式、打点模式和 BPM 调整
- 曲谱编辑器：创建和编辑曲谱、钢琴卷帘、网格吸附、试听、撤销/重做和另存为
- 游戏浮窗：仅在用户主动开启后使用无障碍服务和悬浮窗辅助演奏
- 简体中文、繁體中文、English、日本語界面支持（持续完善中）

## 使用说明

普通曲库浏览、导入和本地播放不需要无障碍权限。只有主动开启“游戏浮窗/游戏模式”后，应用才会请求悬浮窗和无障碍权限，并向前台游戏发送模拟点击。

云端曲库属于可选功能。使用云端曲库或个人信息功能时，需要连接 SMAP 云端服务并登录账号；仅使用本地曲库时无需登录。

## 技术栈

- Kotlin
- Jetpack Compose / Material 3
- Android SDK 36，最低支持 Android 8.0（API 26）
- SoundPool 本地音色播放
- Android AccessibilityService 与前台悬浮窗服务
- Muse TreeHouse 云端曲库 API

## 构建

在 Windows PowerShell 中：

```powershell
$env:JAVA_HOME = "<你的 Android Studio JBR 路径>"
.\gradlew.bat :app:assembleDebug
```

Debug APK 输出到：`app/build/outputs/apk/debug/app-debug.apk`

正式签名构建请使用自己的 release keystore。不要提交 keystore、密码或 `keystore.properties`。配置模板见 [`keystore.properties.example`](keystore.properties.example)。

## 曲谱格式

SMAP 使用 JSON 曲谱格式，示例：

```json
[
  {
    "name": "歌曲名称",
    "author": "作者",
    "transcribedBy": "做谱者",
    "bpm": 120,
    "keyCount": 15,
    "songNotes": [
      { "time": 0, "key": "1Key0" },
      { "time": 500, "key": "1Key4" }
    ]
  }
]
```

`key` 末尾数字表示 0 至 14 的光遇琴键索引；同一时间附近的多个音符会作为和弦处理。

## 开源协议

本项目采用 [GNU General Public License v3.0](LICENSE)。隐私说明见 [`PRIVACY_POLICY.md`](PRIVACY_POLICY.md)，第三方组件说明见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。

项目作者：LingYunALingYun

欢迎提交 Issue 和 Pull Request。
