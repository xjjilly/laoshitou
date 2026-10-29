# LaoShiTou (老石头) v1.2 — 线性相位 FIR、多段压缩与预设系统

本地优先、音质优先的 Android 播放器。无广告、无在线曲库、无账号、无遥测，
**未申请 INTERNET 权限**，音频与配置全部在本机处理。

作者：[@xjjilly](https://github.com/xjjilly)

> **本软件所有代码开源，免费下载。如果你是花钱买的，你被骗了，请立刻退款并举报。**

## 本版更新

- **线性相位 FIR EQ**：2048 抽头分块卷积，零相位失真；固有延迟约 35ms，
  采样率高于 96kHz 时自动回退到最小相位 IIR。
- **三段多段压缩**：低/中/高分段压缩，开启后取代单段限制器，用于压住爆炸、
  鼓点等瞬态并防止削波。
- **场景预设增至 12 个**：新增 FPS 听声辩位、语音/播客清晰、流行、摇滚、
  古典/原声、低音增强、高音/空气感、电影；连同原有录音棚、深夜动铁、
  近场书架、Live 共 12 个。低音增强带 −3dB Preamp，避免触发削波保护。
- **用户预设**：把当前 16 段 EQ、Preamp、Crossfeed、声场、响度、多段压缩与
  线性相位开关存成命名预设，支持应用与删除，重启后保留。
- **EQ 播放中实时生效**：调整后点「应用（实时生效）」立即生效，无需重新播放
  （直通模式下自动回退为下次播放生效）。
- **16 段编辑器增强**：选中频点浮出精确数值气泡；`−` / `+` 按钮按 0.5dB 微调；
  新增显式「保存」按钮。
- **内置 BRIR/HRTF 素材**：9 个测试冲激响应随 APK 分发，首次启动自动复制到
  应用 IR 目录。
- **关于页**：新增「支持开发者」赞赏码与免费开源声明水印。

## 修复

- 线性相位 FIR 在真实解码块尺寸（MP3 1152、FLAC/WAV 4096 帧）下的输出环溢出，
  表现为片段重复、乱序或静音；新增覆盖 8 种帧块的连续性回归测试。
- EQ 配置文件不存在时不再尝试加载并写警告。
- 高保真界面此前无法访问预设；预设区已抽成公共组件，三种界面均可使用。

## 隐私

- `AndroidManifest.xml` 中没有 `INTERNET` 权限。
- 无广告 SDK、无在线曲库、无遥测、无账号。
- `android:allowBackup="false"`，关闭系统云备份。
- 存储权限仅用于读取 `laoshitou/` 音乐目录：API 30+ 为
  `MANAGE_EXTERNAL_STORAGE`（「所有文件访问」），API 26-29 为
  `READ_EXTERNAL_STORAGE`。

## 安装

```sh
adb install -r LaoShiTou-Android-v1.2-signed.apk
```

- 包名：`com.gu.laoshitou`
- 版本：`1.2`（versionCode 4）
- 最低 Android：8.0（API 26），arm64-v8a
- 签名：APK Signature Scheme v2；证书 SHA-256
  `eb14ff089e17f6790bbe01cf67669152d86a5921e94d1a108dcb8d51945c7fe8`

## 源码与许可

- 完整源代码：<https://github.com/xjjilly/laoshitou>
- 许可证：GNU GPLv3（见 `LICENSE`）
- 第三方组件：见 `THIRD_PARTY_NOTICES.md` 与 `third_party/licenses/`
- FFmpeg 7.1 以 arm64-v8a 静态库链接（`--disable-gpl --disable-nonfree
  --disable-version3`，无 libfdk_aac），构建脚本
  `tools/build_ffmpeg_arm64.sh`，对应源码压缩包随仓库提供
  （`third_party/ffmpeg/ffmpeg-7.1.tar.xz`）。
