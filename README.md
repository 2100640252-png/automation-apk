# 自动化工作 APK

视频定时发布 + 评论任务的安卓自动化工作台。

## 两个选项

### 1. 视频版
- 把要发的视频存进视频库（选择本地视频文件，可预览文件名、大小）
- 为每个视频勾选要发的平台：抖音、快手、小红书、视频号、哔哩哔哩、TikTok、YouTube、微博
- 定好发布时间，排入发布队列，到时间自动提醒 / 执行
- 文案识别：粘贴整段文案，自动拆成正文、`#话题`、声明，可保存并绑定到视频

### 2. 评论版
- 读取视频链接，一次最多 10 个链接
- 界面里有 10 个编号的空白链接输入框（链接 1 ~ 链接 10）
- 一个可自定义内容的空白评论文本框
- 设置发送间隔（秒），点「创建并开始任务」后自动按顺序逐条发布，实时显示每条状态，可取消任务

## 权限

应用在首次使用时会逐项申请权限，全部由你在手机上自愿同意：

- 存储 / 媒体：读取视频文件 `READ_MEDIA_VIDEO`、`READ_MEDIA_IMAGES`、`READ_EXTERNAL_STORAGE`
- 相机 / 录音：`CAMERA`、`RECORD_AUDIO`（仅在需要拍摄时使用）
- 通知：`POST_NOTIFICATIONS`
- 定时：`SCHEDULE_EXACT_ALARM`、`RECEIVE_BOOT_COMPLETED`、`FOREGROUND_SERVICE`
- 网络：`INTERNET`、`ACCESS_NETWORK_STATE`、`WAKE_LOCK`
- 悬浮窗：`SYSTEM_ALERT_WINDOW`，需要在设置里手动开启
- 无障碍屏幕控制：`AutomationService`（AccessibilityService），需要在设置 > 无障碍里手动开启，才能由应用辅助操作屏幕

**重要说明：**
- 安卓不允许应用“直接调用手机所有权限”，每个敏感权限都必须由用户在运行时逐项同意，无障碍和悬浮窗还要去系统设置里手动开。
- 跨平台自动发视频 / 评论要走各平台自己的登录和发布规则。应用本身不保存你的平台账号密码，发布时是打开对应平台应用、由你在自己已登录的账号下确认；频繁、重复发评论可能被平台限流或封号，请遵守各平台规则，只发你自己的内容。
- 本项目只做你自己的任务管理，不做任何后台上传、窃取数据的事。

## 如何编译出 APK

### 方法 A：Android Studio
1. 用 Android Studio 打开本仓库
2. 等 Gradle 同步完成
3. 点 Build > Build App Bundle(s) / APK(s) > Build APK(s)
4. 生成的 APK 在 `app/build/outputs/apk/debug/app-debug.apk`

### 方法 B：GitHub Actions（推荐）
1. 把本仓库推到 GitHub
2. 打开仓库的 Actions 页，会自动运行 `build-apk`
3. 运行完成后在 Artifacts 里下载 `app-debug`，里面就是 APK

## 项目结构

```
app/src/main/
  AndroidManifest.xml
  java/com/automation/work/
    MainActivity.kt        主界面：视频版 / 评论版两个 Tab
    CopyParser.kt          文案识别：正文 / 话题 / 声明
    PermissionHelper.kt    权限申请清单
    VideoScheduler.kt      视频定时任务（WorkManager）
    CommentTask.kt         评论任务：10 条链接队列
    AutomationService.kt   无障碍服务：屏幕辅助操作框架
  res/xml/accessibility_service_config.xml
.github/workflows/build-apk.yml
```

## v1.1.0 改了什么
- 打开就索取权限：启动时主动申请存储 / 媒体、相机、录音、通知等运行时权限，权限面板里缺哪项直接显示，点一下就去系统设置开无障碍、悬浮窗、安装未知应用、精确闹钟。
- 视频真的能存：视频版有“选择视频”按钮，选中的视频复制到应用内 files/videos，存进视频库列表，显示名字、大小、状态；勾选平台、填几分钟后发布，定时到点发通知提醒。
- 评论真的能发：评论版点“创建并开始任务”后，无障碍服务会逐条打开链接、找评论入口、填自定义评论、点发送；找不到评论框会重试 3 次、换关键词再找，实在发不出去就记日志、跳过下一条，不会卡死；运行日志在界面上直接能看到。
- 软件内更新：新增“更新”页，点“检查更新”查 GitHub 最新 Release，有新版直接在软件内下载 app-debug.apk 并调系统安装器安装，不用去网页下载、不用解压 zip。仓库是私有的，第一次需在更新页填一次 GitHub 只读 Token（只勾 automation-apk 的 Contents 只读），只存本机。
- 遇到错误自己修：评论每条失败自动重试、换选择器再找；定时任务失败 WorkManager 自动重试；所有步骤都写进运行日志。

## 版本
- 当前 1.1.0（versionCode 2）
- minSdk 26（Android 8.0），targetSdk 34
- Kotlin + Jetpack Compose + WorkManager
