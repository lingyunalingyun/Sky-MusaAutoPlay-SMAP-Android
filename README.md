# SMAP Android

**SMAP（Sky-MusaAutoPlay，中文：光遇-Musa 自动演奏）**是 SMAP 桌面版的 Android 端延伸，用于管理和播放《光·遇》15 键曲谱，并在用户主动开启游戏浮窗后，将曲谱演奏同步到游戏琴键。

项目仍在持续开发中。Android 端的界面、播放栏、播放列表、曲库和练习模式会尽量与桌面版保持一致。

## 当前功能

- 本地曲库：导入、搜索、排序、收藏、删除曲谱
- 云端曲库：浏览、搜索、按难度/上传时间排序、查看做谱者和下载量、下载曲谱及封面
- 播放系统：单曲播放、播放列表、顺序/循环/随机模式、播放进度拖动、倍速播放
- 播放列表：从右侧滑出，支持播放、移除、清空、收藏和当前播放状态
- 播放设置：音色、音高、洞穴音效、多语言和主题
- 练习模式：15 键跟弹、和弦整组推进、读谱模式分页谱面墙、打点模式和 BPM 调整
- 游戏浮窗：可选的 Android 无障碍服务和悬浮窗演奏
- GPL 3.0 开源协议和隐私政策说明

创建/编辑曲谱、试听编辑器等功能暂不属于当前 Android 端范围。

## 技术栈

- Kotlin
- Jetpack Compose / Material 3
- Android SDK 36，最低支持 Android 8.0（API 26）
- SoundPool 本地音色播放
- Android AccessibilityService 与前台悬浮窗服务（仅在主动开启游戏模式后使用）
- Muse Tree House 云端曲库 API

## 项目结构

```text
app/src/main/java/com/smap/android/
├─ MainActivity.kt                 主界面、曲库和播放器
├─ ui/PracticePanel.kt             练习模式界面
├─ engine/                         音色与曲谱播放引擎
├─ data/                           本地曲库、偏好设置和播放列表
├─ cloud/                          云端曲库与账号接口
├─ midi/                           MIDI 导入
└─ service/                        无障碍服务与游戏浮窗
```

## 本地构建

Windows PowerShell：

```powershell
$env:JAVA_HOME = "C:\Users\<你的用户名>\.jdks\openjdk-26.0.1"
.\gradlew.bat :app:assembleDebug
```

Debug APK 输出在 `app/build/outputs/apk/debug/app-debug.apk`。

建议使用 Android Studio 打开项目根目录，等待 Gradle 同步完成后再运行。`local.properties` 只用于本机 SDK 路径，不应提交到 Git。

## 签名发布

正式发布请使用自己的 release keystore，不要提交密钥、密码或 `keystore.properties`。项目提供了配置模板 `keystore.properties.example`。

Android Studio 菜单：`Build → Generate Signed App Bundle / APK`。

Google Play 推荐生成 Android App Bundle（`.aab`）；直接安装测试可生成 signed APK（`.apk`）。

## 权限说明

普通曲库浏览、导入和本地播放不需要无障碍权限。

只有在用户主动开启“游戏模式/游戏浮窗”后，应用才会引导开启无障碍服务和悬浮窗权限：

- 无障碍服务：按照已校准的琴键位置发送点击手势
- 在其他应用上层显示：显示和操作悬浮控制面板
- 前台服务：维持悬浮窗运行

应用不会通过无障碍服务读取屏幕内容，也不会在未开启游戏模式时模拟点击。详细说明见 [隐私政策](PRIVACY_POLICY.md)。

## 曲谱格式

SMAP 使用 JSON 曲谱格式，核心字段如下：

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

`key` 的末尾数字表示 0 到 14 的光遇琴键索引；同一时间附近的多个音符会作为和弦处理。

## 开源协议

本项目采用 [GNU General Public License v3.0](LICENSE)。

- 隐私政策：[PRIVACY_POLICY.md](PRIVACY_POLICY.md)
- 项目作者：LingYunALingYun
- 项目名称：Sky-MusaAutoPlay / 光遇-Musa 自动演奏

欢迎提交 Issue 和 Pull Request。提交代码时请保持现有桌面版视觉语言、Android 多语言支持和权限边界不变。
