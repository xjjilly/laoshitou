# LaoShiTou (老石头) v1.5.1 — 关于页 PC 生态入口与下载

本地优先、音质优先的 Android 播放器。无广告、无在线曲库、无账号、无遥测；
默认离线，DLNA 为可选功能，开启后只在本机 Wi-Fi/以太网内通信，不连接互联网。

作者：[@xjjilly](https://github.com/xjjilly)

> **本软件所有代码开源，免费下载。如果你是花钱买的，你被骗了，请立刻退款并举报。**

## 本版更新

- **关于页新增 PC 生态卡片**：介绍老石头 PC 端 v1.0.0（WASAPI 独占 /
  NAS 断网续传 / 专属高定皮肤，Windows 10/11 x64），提供「下载 PC 版」
  与「了解详情」两个入口。
- **PC 下载弹窗**：提供安装版 `LaoShiTou-PC-v1.0-Setup.exe`（推荐）与
  便携版 `LaoShiTou-PC-v1.0-portable.zip`（解压即用）；蓝奏云链接，
  提取码可一键复制。
- **PC 完整版入口调整**：由爱发电改为闲鱼「老石头 PC 完整版（¥299）」
  商品页；本版暂时移除爱发电入口。
- **关于页作者信息**：底部新增「联系作者：gsl_snake」。
- 播放、DSP、NAS/WebDAV、DLNA 功能与 v1.5 完全一致；本版为关于页与
  分发入口更新，无播放路径变更。
- 工程侧：`.gitignore` 忽略 `.vscode/` 与 PDB/ILK/EXP/OBJ/LIB 等 Windows
  符号和编译产物（不影响 APK 功能）。

## 升级说明

- 版本：`1.5.1`（versionCode 8），包名 `com.gu.laoshitou`。
- 最低 Android 8.0（API 26），targetSdk 35，arm64-v8a。
- 使用与 v1.5 相同的 release 签名，可直接覆盖安装并保留应用数据。
- 若手机上安装的是其他签名的构建（例如 debug 包），需要先卸载再安装；
  卸载会清除应用数据。

## 下载与校验

- GitHub Release：
  <https://github.com/xjjilly/laoshitou/releases/tag/v1.5.1>
- 安装包：`LaoShiTou-Android-v1.5.1-signed.apk`
- 大小：17,620,131 字节
- SHA-256：
  `0378f2249debc4ed0283a9e15760d9af26ab2e3b68d69c31b0c1464647e8136f`
- 校验和文件：`SHA256SUMS-v1.5.1.txt`
- 签名：APK Signature Scheme v2；证书 DN
  `CN=LaoShiTou, OU=Mobile, O=LaoShiTou, L=Local, ST=Local, C=CN`，
  证书 SHA-256
  `eb14ff089e17f6790bbe01cf67669152d86a5921e94d1a108dcb8d51945c7fe8`
  （与 v0.1.0-alpha 以来的 release 包一致）。

## 安装

```sh
adb install -r LaoShiTou-Android-v1.5.1-signed.apk
```

小白用户请看 `docs/APK_USER_GUIDE.md`（安装、放歌、DLNA、小米省电设置、
常见问题）。

## 界面截图

- `screenshots/about-pc-ecosystem-card.png`
- `screenshots/about-pc-download-dialog.png`

## 已知边界

- NAS 曲库为只读；DFF 仅缓存/本地播放，不支持 DMR 投送；
- Android DSD 为解码 + DSP + Oboe 共享输出，不是原生 DSD 直通；
- 同一 DLNA 连接只启用一个角色（发送者/接收者/离线）；
- 锁屏播放依赖系统省电策略，MIUI/HyperOS 需将应用设为“无限制”。

## 源码与许可

- 完整源代码（tag `v1.5.1`）：
  <https://github.com/xjjilly/laoshitou/tree/v1.5.1>
- 源码归档：
  <https://github.com/xjjilly/laoshitou/archive/refs/tags/v1.5.1.tar.gz>
- 源码包：`laoshitou-1.5.1-source.tar.gz`（SHA-256
  `fb270c3d57b62f459752d7e952097e4722440d6c9b59540d44eac23ef9031513`，
  随本 Release 上传）
- 许可证：GNU GPLv3（见 `LICENSE`）。
- 第三方组件：见 `THIRD_PARTY_NOTICES.md` 与 `third_party/licenses/`。
- FFmpeg 7.1 以 arm64-v8a 静态库链接，对应源码包随仓库提供
  （`third_party/ffmpeg/ffmpeg-7.1.tar.xz`）。
