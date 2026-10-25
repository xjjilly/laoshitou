# LaoShiTou (老石头) v1.1 - 曲库扫描增强与音乐目录迁移

本地优先、音质优先的 Android 播放器。无广告、无在线曲库、无账号、无遥测，
**未申请 INTERNET 权限**，音频与配置全部在本机处理。

作者：[@xjjilly](https://github.com/xjjilly)

## 本版更新

- **曲库扫描增强**：启动自动扫描、回到前台自动重扫（首次启动亦由生命周期触发）、
  扫描状态提示（"正在扫描音乐…" → "已扫描 N 张专辑，M 首音乐"）、防重入保护。
- **空曲库指引**：三套界面（极简/沉浸/高保真）曲库为空时显示音乐复制目录提示。
- **音乐目录迁移**：默认音乐目录由 `Android/data/com.gu.laoshitou/files/Music/`
  改为手机根目录的 **`laoshitou/`**（`/storage/emulated/0/laoshitou/`），
  避免系统对 `Android/data` 的访问限制。
- **「所有文件访问」权限**：Android 11+ 读取公共目录中的 DSD/APE 等非标准媒体
  扩展名需要该权限；曲库卡提供「授权访问」入口（跳系统设置），Android 8-9
  使用传统 `READ_EXTERNAL_STORAGE` 运行时权限。
- **旧目录一键迁移**：检测到旧目录仍有音乐且新目录为空时，曲库卡显示
  「迁移旧目录音乐」按钮，一键复制到 `laoshitou/`（保留子目录结构，
  校验大小后删除源文件），带迁移进度提示。
- **文件夹导入**：三套界面新增「选择音频文件夹」，用系统目录选择器选中一个
  文件夹后，递归导入其中支持的音频到 `laoshitou/<文件夹名>/`（重名自动加
  序号，保留子目录结构），完成后自动刷新曲库成为专辑。

## 隐私

- `AndroidManifest.xml` 中没有 `INTERNET` 权限。
- 无广告 SDK、无在线曲库、无遥测、无账号。
- `android:allowBackup="false"`，关闭系统云备份。
- 除播放所需的通知、前台服务与 WakeLock 权限外，新增存储权限仅用于读取
  `laoshitou/` 音乐目录：API 30+ 为 `MANAGE_EXTERNAL_STORAGE`
  （「所有文件访问」），API 26-29 为 `READ_EXTERNAL_STORAGE`。

## 安装

```sh
adb install -r app-release-signed.apk
```

- 包名：`com.gu.laoshitou`
- 版本：`1.1`（versionCode 3）
- 最低 Android：8.0（API 26），arm64-v8a
- 签名：APK Signature Scheme v2；证书 SHA-256
  `eb14ff089e17f6790bbe01cf67669152d86a5921e94d1a108dcb8d51945c7fe8`
- APK SHA-256：`969340714764127614d85f8cd9f6ed6598d5efd480ab36ae5ca5629c227f2159`

## 源码与许可

- License: **GNU GPLv3**（见 `LICENSE`）。
- 完整对应源码：`laoshitou-1.1-source.tar.gz`（含应用源码、第三方源码、许可文本
  与 FFmpeg 7.1 源包），SHA-256
  `509a239687305d458e0cdde510ad5e9ebb82c503020f0086428f95498354ce0a`。
- 第三方许可见 `THIRD_PARTY_NOTICES.md`。
