# LaoShiTou (老石头) v0.1.0-alpha - 硬核 DSP 音频引擎首发

本地优先、音质优先的 Android 播放器。无广告、无在线曲库、无账号、无遥测，
**未申请 INTERNET 权限**，音频与配置全部在本机处理。

作者：[@xjjilly](https://github.com/xjjilly)

## 亮点

- **Oboe 低延迟输出**：请求 Exclusive 独占，设备不支持时自动回退 Shared。
- **16 段可视化 PEQ**：PK/LSC/HSC/HPF/LPF、2/4 阶级联、AutoEQ txt/CSV/GraphicEQ 导入。
- **BRIR 卷积混响**：分区 FFT、立体声 BRIR WAV、湿/干比例可调。
- **声场控制**：libbs2b Crossfeed、M/S 声场拓宽 0-150%、响度补偿、可调限制器。
- **DSD64 与高解析**：DSF/DFF 本地解码；FLAC/WAV/ALAC/AAC/MP3/APE/WavPack/TTA。
- **USB DAC 自动 192k**：48k 族 PCM 在检测到 USB 输出时优先 192k。
- **设备切换自动恢复**：蓝牙/有线/USB 路由变化导致断流时自动重开输出。
- **三种界面**：极简 / 沉浸 / 高保真，共享播放与曲库状态。
- **纯本地曲库**：文件夹即专辑，顺序/循环/随机，媒体按键与前台服务。

## 隐私

- `AndroidManifest.xml` 中没有 `INTERNET` 权限。
- 无广告 SDK、无在线曲库、无遥测、无账号。
- `android:allowBackup="false"`，关闭系统云备份。
- 仅保留播放所需的通知、前台服务与 WakeLock 权限；不申请存储权限，文件通过
  系统选择器或应用私有目录访问。

## 安装

```sh
adb install -r app-release-signed.apk
```

- 包名：`com.gu.laoshitou`
- 版本：`0.1.0`（versionCode 1）
- 最低 Android：8.0（API 26），arm64-v8a
- 签名：APK Signature Scheme v2；证书 SHA-256
  `eb14ff089e17f6790bbe01cf67669152d86a5921e94d1a108dcb8d51945c7fe8`
- APK SHA-256：`01810fe444c2b056517181798d32a19f58af4da63fab1917d8fec9ce994c327d`

## 源码与许可

- License: **GNU GPLv3**（见 `LICENSE`）。
- 完整对应源码：`laoshitou-0.1.0-source.tar.gz`，包含应用源码、第三方源码、
  许可文本与 FFmpeg 7.1 源包。
- 第三方许可见 `THIRD_PARTY_NOTICES.md`。
- 测试 IR 仅保留三份许可明确的文件（UniS MIT、SADIE II Apache-2.0、
  MIT IR Survey CC BY 4.0），不含任何商业音乐。

## 已知限制

- USB DAC 自动 192k 的最终实机确认仍需插入 USB 小尾巴后完成。
- Exclusive 是否授予取决于设备/系统；未授予时自动使用 Shared。
- “采样率变化后重建 DSP 管线”分支已实现，但本轮测试设备各路由均接受请求
  采样率，未能在实机上触发该分支。
