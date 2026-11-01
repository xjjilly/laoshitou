# LaoShiTou (老石头) v1.3 — DLNA 局域网音频与 M3/M4 收尾

本地优先、音质优先的 Android 播放器。无广告、无在线曲库、无账号、无遥测；
**默认离线**，DLNA 为可选功能，开启后只在本机 Wi-Fi/以太网内通信，不连接互联网。

作者：[@xjjilly](https://github.com/xjjilly)

> **本软件所有代码开源，免费下载。如果你是花钱买的，你被骗了，请立刻退款并举报。**

## 本版更新

- **DLNA / UPnP 正式落地（M3/M4）**：
  - **发送者**：DMS 曲库共享（token 媒体 URL、Range/206、HEAD、错误 token 400）；
    DMC 设备发现、投送、暂停/继续、停止、进度、Seek、音量。
  - **接收者**：DMR（原生 FFmpeg → 老石头 DSP 管线 → Oboe；EQ/Crossfeed/BRIR/
    限制器生效；不支持的格式自动回退系统 MediaPlayer），支持 DIDL 标题、
    系统播控键、`SetNextAVTransportURI` 自然播完自动切歌。新增原生流式输入
    （自定义 AVIO，FFmpeg 仍不开启网络）、原生 seek/position 与失败回退。
  - 每次接入新局域网选择角色（发送者 / 接收者 / 保持离线），同一连接只启用
    一个角色，从结构上避免 DMC/DMR 冲突。
  - 渲染器断电恢复：连续 3 次轮询失败显示「渲染器已离线，正在重试…」，
    渲染器恢复后自动清除。
- **真实格式与 DSP 验证**：真实 FLAC（44.1k）与 DSF（DSD64）原生 DMR 投送
  通过；离线 DSP 仪器验证（EQ 频响/Crossfeed/Limiter）与 host 18/18 单测
  通过；DFF 在 DMR 上仍不支持（本地播放器支持 DFF）。
- **Visual EQ 同步**：DMR 启动/Seek/切歌后保持 16 段 Visual EQ；运行中在
  播放器页点“应用（实时生效）”会实时更新 DMR。
- **小米/HyperOS 息屏保活提示**：DMS 界面新增「息屏保活（DMS 长驻）」说明卡片，
  给出“设置 → 应用信息 → 老石头 → 电量详情 → 省电策略 → 无限制”的手动路径
  与原因。**本应用只做文字提示，不会自动修改系统设置或跳转页面。**
- **稳定性**：30 分钟 DMR 长跑（90/90 采样、45 次自动重播、0 异常、
  Range 全部 206）；socket 表取证 13 次采样无任何非私网远端。原生 DMR
  路径的 30 分钟混合长跑（90 采样、Seek 30/30、切歌 15/15）同样通过：
  PSS 平稳约 177 MB、Oboe 延迟约 30 ms、稳态 underrun/XRuns 0。
- **音频**：Oboe 改为共享模式，移除独占直连降档重试，避免与系统/其他应用
  抢占音频设备。

## 修复

- CyberLink 在 Android 上的 JAXP 解析兼容问题（新增 `AndroidXmlParser`）。
- GENA `LastChange` 不发送（显式 `setSendEvents(true)`）。
- `DmsDevice.start()` 被父类方法遮蔽（改名 `startDevice()`）。
- CyberLink `getContentRange()` 只认 `bytes start-end/total`，标准 Range
  请求头需自行解析并规范化。
- DMR 停止后 MediaSession 将 FGS 置为 inactive 导致后台被冻结；设备运行期间
  FGS 保持 active。
- 新增 WifiLock/MulticastLock，避免息屏省电导致局域网断连（小米/HyperOS 仍需
  按上面的路径手动把系统“省电策略”设为“无限制”）。

## 格式与性能说明

- **DSD（DSF）**：DSD64 的原始数据率约 2.8 MHz/声道。播放时需要转成 PCM、
  重采样到输出采样率（测试机 48 kHz）再经过 DSP，测试机约 **80% 单核占用**。
  这是正常的“用硬件算力换音质和 DSP 功能”，不是故障；会带来相应的耗电/发热。
  想降低占用可降低重采样质量，或使用直通/Direct 模式。
- **DFF（DSDIFF）**：本地播放支持 DFF（应用自带 DFF 解析）；但 DMR 原生解码
  使用 FFmpeg 7.1，**FFmpeg 不含 DSDIFF demuxer**，因此 **DFF 不能通过 DLNA
  投送给 DMR**。需要投送请先转成 **DSF**；DSF 可以正常投送。
- **截图**：`docs/screenshots/` 中的截图使用合成的“示例音轨 - 440Hz / 880Hz”，
  不含版权音乐、封面、账号或个人信息。

## 隐私

- 默认离线；DLNA 关闭时零网络行为。
- DLNA 为可选功能，开启后只加入局域网组播、监听局域网端口、访问局域网内
  控制/媒体地址；不连接互联网、不上传文件。
- 为可选 DLNA 申请 `INTERNET`、`ACCESS_NETWORK_STATE`、`ACCESS_WIFI_STATE`、
  `CHANGE_WIFI_MULTICAST_STATE`、`NEARBY_WIFI_DEVICES`；存储权限仅用于读取
  `laoshitou/` 音乐目录（API 30+ 为「所有文件访问」）。
- `android:allowBackup="false"`，关闭系统云备份；无广告 SDK、无遥测、无账号。

## 安装

```sh
adb install -r LaoShiTou-Android-v1.3-signed.apk
```

- 包名：`com.gu.laoshitou`
- 版本：`1.3`（versionCode 5）
- 最低 Android：8.0（API 26），arm64-v8a
- 签名：APK Signature Scheme v2；证书 SHA-256
  `eb14ff089e17f6790bbe01cf67669152d86a5921e94d1a108dcb8d51945c7fe8`
- 小白用户请看 `docs/APK_USER_GUIDE.md`（安装、放歌、DLNA、小米省电设置、
  常见问题）。

## 源码与许可

- 完整源代码：<https://github.com/xjjilly/laoshitou>
- 源码包：`artifacts/laoshitou-1.3-source.tar.gz`
- 许可证：GNU GPLv3（见 `LICENSE`）
- 第三方组件：见 `THIRD_PARTY_NOTICES.md` 与 `third_party/licenses/`
- FFmpeg 7.1 以 arm64-v8a 静态库链接（`--disable-gpl --disable-nonfree
  --disable-version3`，无 libfdk_aac），构建脚本
  `tools/build_ffmpeg_arm64.sh`，对应源码压缩包随仓库提供
  （`third_party/ffmpeg/ffmpeg-7.1.tar.xz`）。
