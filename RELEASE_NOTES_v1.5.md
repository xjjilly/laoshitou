# 老石头 Android v1.5 发布说明

> 版本：1.5（versionCode 7）
> 日期：2026-11-06
> 安装包：`artifacts/LaoShiTou-Android-v1.5-signed.apk`
> 说明书：`docs/APK_USER_GUIDE.md`

## 本版重点

- **NAS / SQL 曲库**：接入 NAS SQL 索引，135 专辑/861 曲目，真实封面、专辑详情、
  曲目列表；支持保存 SQL API 地址与 Token。
- **WebDAV 凭据配置**：在「曲库 → NAS → 配置」中新增 WebDAV 地址、用户名、
  密码与认证方式，保存后用于 NAS 曲目播放/投送；凭据加密存储。
- **DLNA 双向**：发送者（DMS + DMC）与接收者（DMR）角色；手机可投送 PC/其他
  渲染器，也可作为 DLNA 音箱接收投送。
- **锁屏保活**：NAS 曲目播放成功后启动前台 `PlaybackService`，持有
  `PARTIAL_WAKE_LOCK`；60 秒锁屏实测播放连续。
- **本地播放 / DSP**：原生 FFmpeg + 16 段 PEQ、Crossfeed、BRIR、Loudness、
  Multiband、线性相位；DSF/DFF 本地 DSD64 解码（DFF 不支持 DMR 投送）。
- **界面**：曲库/投送原生 Compose 界面，NAS 封面网格与专辑详情。

## 已知边界

- NAS 曲库为只读；DFF 仅缓存/本地播放，不支持 DMR 投送；
- Android DSD 为解码 + DSP + Oboe 共享输出，不是原生 DSD 直通；
- 同一 DLNA 连接只启用一个角色（发送者/接收者/离线）；
- 锁屏播放依赖系统省电策略，MIUI/HyperOS 需将应用设为“无限制”。

## 安装

```sh
adb install -r artifacts/LaoShiTou-Android-v1.5-signed.apk
```

签名与校验和见 `artifacts/SHA256SUMS-v1.5.txt` 与用户说明书第 10 节。
