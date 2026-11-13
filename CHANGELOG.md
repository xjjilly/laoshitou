# 更新日志

老石头（LaoShiTou）Android 播放器的版本变更记录。每个版本的完整说明见仓库根目录的
`RELEASE_NOTES_*.md`；安装包、校验和与源码归档见
[GitHub Releases](https://github.com/xjjilly/laoshitou/releases)。

版本号遵循语义化版本；Android `versionCode` 单调递增，用于覆盖安装。
格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [1.5.1] - 2026-11-10

### 新增
- 关于页新增「老石头 PC 端」生态卡片：PC v1.0.0 简介（WASAPI 独占 /
  NAS 断网续传 / 专属高定皮肤，Windows 10/11 x64）与下载入口。
- PC 下载弹窗：安装版 `LaoShiTou-PC-v1.0-Setup.exe` 与便携版
  `LaoShiTou-PC-v1.0-portable.zip`，蓝奏云链接，提取码一键复制。
- 关于页底部新增「联系作者：gsl_snake」。

### 变更
- PC 完整版入口由爱发电改为闲鱼「老石头 PC 完整版（¥299）」商品页，
  暂时移除爱发电入口。
- `versionCode` 8 / `versionName` 1.5.1；播放、DSP、NAS/WebDAV、DLNA
  功能与 1.5 一致。

### 工程
- `.gitignore` 忽略 `.vscode/` 与 PDB/ILK/EXP/OBJ/LIB 等 Windows 符号和
  编译产物。

完整说明：[RELEASE_NOTES_v1.5.1.md](RELEASE_NOTES_v1.5.1.md) ·
[Release](https://github.com/xjjilly/laoshitou/releases/tag/v1.5.1) ·
[对比 1.5...1.5.1](https://github.com/xjjilly/laoshitou/compare/v1.5...v1.5.1)

## [1.5] - 2026-11-06

### 新增
- NAS / SQL 曲库：接入 NAS SQL 索引，真实封面、专辑详情与曲目列表，
  支持保存 SQL API 地址与 Token。
- WebDAV 凭据配置：在「曲库 → NAS → 配置」中填写地址、用户名、密码与
  认证方式，凭据加密存储。
- DLNA 双向：发送者（DMS + DMC）与接收者（DMR）；手机可投送 PC/其他
  渲染器，也可作为 DLNA 音箱接收投送。
- 锁屏保活：NAS 曲目播放后启动前台 `PlaybackService` 并持有
  `PARTIAL_WAKE_LOCK`。
- 曲库/投送原生 Compose 界面；曲库与投送 UI 文案统一使用密码/令牌。

### 修复
- 恢复 NAS WebDAV 配置入口。
- 播放成功后持有唤醒锁，改善锁屏连续播放。

完整说明：[RELEASE_NOTES_v1.5.md](RELEASE_NOTES_v1.5.md) ·
[Release](https://github.com/xjjilly/laoshitou/releases/tag/v1.5) ·
[对比 1.3.1...1.5](https://github.com/xjjilly/laoshitou/compare/v1.3.1...v1.5)

## [1.3.1] - 2026-11-02

### 新增
- DMC 第三方渲染器互操作（Kodi 21.3）：发现、描述解析、
  `GetProtocolInfo`、`SetAVTransportURI`、`Play`、暂停/继续、Seek、
  音量、停止均通过；真实 FLAC 投送正常。
- DIDL-Lite 元数据：`SetAVTransportURI` 的 `CurrentURIMetaData` 升级为
  标准 DIDL-Lite，提升电视/AVR/BubbleUPnP/Windows Media Player 兼容性。
- MIUI/HyperOS 同机渲染器省电提示。

### 修复
- 渲染器离线检测与恢复：连续 3 次轮询失败提示「渲染器已离线…」，
  重新上线后自动恢复轮询（不自动重投）。

完整说明：[RELEASE_NOTES_v1.3.1.md](RELEASE_NOTES_v1.3.1.md) ·
[Release](https://github.com/xjjilly/laoshitou/releases/tag/v1.3.1) ·
[对比 1.3.0...1.3.1](https://github.com/xjjilly/laoshitou/compare/v1.3.0...v1.3.1)

## [1.3.0] - 2026-11-02

### 新增
- DLNA / UPnP 正式落地：DMS 曲库共享（token 媒体 URL、Range/206、HEAD）、
  DMC 设备发现/投送/控制、DMR 原生接收（FFmpeg → DSP → Oboe，不支持格式
  回退系统 MediaPlayer）。
- 每次接入新局域网选择角色（发送者/接收者/离线），同一连接只启用一个角色。
- DMR 支持 DIDL 标题、系统播控键、`SetNextAVTransportURI` 自动切歌；
  Visual EQ 在启动/Seek/切歌后保持。
- DMS 界面新增 MIUI/HyperOS 息屏保活说明卡片。

### 修复
- CyberLink JAXP 解析兼容、GENA `LastChange`、`DmsDevice.start()` 遮蔽、
  Range 解析、DMR 停止后前台服务被冻结等问题。
- Oboe 改为共享模式，避免与系统/其他应用抢占音频设备。

完整说明：[RELEASE_NOTES_v1.3.md](RELEASE_NOTES_v1.3.md) ·
[Release](https://github.com/xjjilly/laoshitou/releases/tag/v1.3.0) ·
[对比 1.2...v1.3.0](https://github.com/xjjilly/laoshitou/compare/v1.2...v1.3.0)

## [1.2] - 2026-10-29

### 新增
- 线性相位 FIR EQ：2048 抽头分块卷积，采样率高于 96kHz 时自动回退到
  最小相位 IIR。
- 三段多段压缩；场景预设增至 12 个；用户预设支持保存、应用与删除。
- EQ 播放中实时生效；16 段编辑器增加数值气泡与 ±0.5dB 微调。
- 内置 9 个 BRIR/HRTF 测试冲激响应；关于页新增支持开发者赞赏码与开源声明。

### 修复
- 线性相位 FIR 在真实解码块尺寸下的输出环溢出（片段重复、乱序或静音）。

完整说明：[RELEASE_NOTES_v1.2.md](RELEASE_NOTES_v1.2.md) ·
[Release](https://github.com/xjjilly/laoshitou/releases/tag/v1.2) ·
[对比 v0.1.1...v1.2](https://github.com/xjjilly/laoshitou/compare/v0.1.1...v1.2)

## [1.1] - 2026-10-25

> 发布 tag 为 `v0.1.1`，APK 内版本号为 `1.1`（versionCode 3）。

### 新增
- 启动自动扫描、回到前台自动重扫、扫描状态提示与防重入保护。
- 空曲库指引；音乐目录迁移到 `/storage/emulated/0/laoshitou/`。
- Android 11+「所有文件访问」授权入口；Android 8-9 使用
  `READ_EXTERNAL_STORAGE`。
- 旧目录一键迁移；文件夹导入。

完整说明：[RELEASE_NOTES_v1.1.md](RELEASE_NOTES_v1.1.md) ·
[Release](https://github.com/xjjilly/laoshitou/releases/tag/v0.1.1) ·
[对比 v0.1.0-alpha...v0.1.1](https://github.com/xjjilly/laoshitou/compare/v0.1.0-alpha...v0.1.1)

## [0.1.0-alpha] - 2026-10-25

### 新增
- 初始版本：Oboe 低延迟输出、16 段可视化 PEQ、BRIR 卷积、Crossfeed /
  声场 / 响度 / 限制器。
- DSF/DFF 本地解码与高解析格式支持；USB DAC 自动 192k、音频路由切换恢复。
- 三种界面（极简/沉浸/高保真）、本地曲库与前台服务。

完整说明：[RELEASE_NOTES_v0.1.0-alpha.md](RELEASE_NOTES_v0.1.0-alpha.md) ·
[Release](https://github.com/xjjilly/laoshitou/releases/tag/v0.1.0-alpha)
