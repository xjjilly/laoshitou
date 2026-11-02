# LaoShiTou (老石头) v1.3.1 — 第三方 DLNA 渲染器互操作与 MIUI 省电提示

本地优先、音质优先的 Android 播放器。无广告、无在线曲库、无账号、无遥测；
**默认离线**，DLNA 为可选功能，开启后只在本机 Wi-Fi/以太网内通信，不连接互联网。

作者：[@xjjilly](https://github.com/xjjilly)

> **本软件所有代码开源，免费下载。如果你是花钱买的，你被骗了，请立刻退款并举报。**

## 本版更新

- **DMC 第三方渲染器互操作首测（Kodi 21.3）**：
  - 发现、描述解析、`GetProtocolInfo`、`SetAVTransportURI`、`Play`、暂停/继续、
    Seek、音量、停止均通过；真实 FLAC 投送正常，DMS 持续收到 Range 请求。
  - 渲染器离线检测与恢复：连续 3 次轮询失败显示「渲染器已离线…」；渲染器
    重新上线后轮询自动恢复（不自动重投）。
- **DIDL-Lite 元数据**：`SetAVTransportURI` 的 `CurrentURIMetaData` 由空串升级为
  标准 DIDL-Lite（`dc:title`、`upnp:class`、`res@protocolInfo`、可选 `size`），
  提升与电视/AVR/BubbleUPnP/Windows Media Player 等第三方设备的兼容性。
  Kodi 实测可正确回读标题。
- **MIUI/HyperOS 同机渲染器提示**：当投送目标运行在本机（渲染器 IP 等于本机
  局域网 IP，例如同机的 Kodi）时，DMC 面板会提示把该渲染器应用的「省电策略」
  设为「无限制」，避免后台 UPnP 服务被系统冻结导致投送中断。
- **测试**：单元测试 26/26 通过（新增 DIDL 元数据与同机渲染器判断用例）。

## 修复 / 兼容性

- 保持 v1.3 的全部 DLNA、DMR 原生 DSP、Visual EQ、DSD 与稳定性改进。
- 本版为 v1.3 的互操作增强，无破坏性变更；使用相同签名覆盖安装可保留数据。

## 格式与性能说明

- **DSD（DSF）**：DSD64 原始数据率约 2.8 MHz/声道，播放时需转 PCM、重采样到
  输出采样率并经过 DSP，测试机约 **80% 单核占用**。这是正常的“用硬件算力换
  音质和 DSP 功能”，会带来相应耗电/发热；可降低重采样质量或使用直通模式。
- **DFF（DSDIFF）**：本地播放支持 DFF（应用自带 DFF 解析）；但 DMR 原生解码
  使用 FFmpeg 7.1，**FFmpeg 不含 DSDIFF demuxer**，因此 **DFF 不能通过 DLNA
  投送给 DMR**。需要投送请先转成 **DSF**；DSF 可以正常投送。

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
adb install -r LaoShiTou-Android-v1.3.1-signed.apk
```

- 包名：`com.gu.laoshitou`
- 版本：`1.3.1`（versionCode 6）
- 最低 Android：8.0（API 26），arm64-v8a
- 签名：APK Signature Scheme v2；证书 SHA-256
  `eb14ff089e17f6790bbe01cf67669152d86a5921e94d1a108dcb8d51945c7fe8`
- 小白用户请看 `docs/APK_USER_GUIDE.md`（安装、放歌、DLNA、小米省电设置、
  常见问题）。

## 源码与许可

- 完整源代码：<https://github.com/xjjilly/laoshitou>
- 源码包：`artifacts/laoshitou-1.3.1-source.tar.gz`
- 许可证：GNU GPLv3（见 `LICENSE`）
- 第三方组件：见 `THIRD_PARTY_NOTICES.md` 与 `third_party/licenses/`
- FFmpeg 7.1 以 arm64-v8a 静态库链接（`--disable-gpl --disable-nonfree
  --disable-version3`，无 libfdk_aac），构建脚本
  `tools/build_ffmpeg_arm64.sh`，对应源码压缩包随仓库提供
  （`third_party/ffmpeg/ffmpeg-7.1.tar.xz`）。
