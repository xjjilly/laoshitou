# DLNA M0 真机验证报告

- 日期：2026-11-01
- 范围：M0 Spike + 最小 DMR 端到端联调 + 双机 DMC→DMR + DMS Browse/Range（debug 构建）
- 结论：**核心链路通过**。SSDP、受控 HTTP Range 拉流、MediaPlayer 播放/seek/暂停、CyberLink DMR 设备托管、主机 SOAP 控制、GENA 事件、小米 17 → Mi 10 Pro 双机投送，以及真实曲库 DMS Browse/Range 均已在真机验证。

## 1. 环境

| 项 | 值 |
|---|---|
| 手机 A（DMR） | Mi 10 Pro，Android 13（API 33），arm64-v8a，Wi-Fi `192.168.1.3` |
| 手机 B（DMC/媒体源） | Xiaomi 17（25113PN0EC），Android 16（API 36），arm64-v8a，Wi-Fi `192.168.1.6` |
| 测试主机 | Windows，`192.168.1.8` |
| 局域网其他设备 | `192.168.1.10`（fnOS NAS，仅 `Storage:1`） |
| 构建 | `:app:assembleDebug`，调试包 |
| 网络权限 | 仅 debug manifest：INTERNET / ACCESS_NETWORK_STATE / ACCESS_WIFI_STATE / CHANGE_WIFI_MULTICAST_STATE / NEARBY_WIFI_DEVICES（neverForLocation；当前未授权）；`usesCleartextTraffic=true` |
| 测试入口 | `com.gu.laoshitou.dlna.M0Activity`（debug source set，不进 release） |

## 2. 结果汇总

### 2.1 SSDP

- 手机 `GuardedDiscovery` 发送 M-SEARCH，5 秒内发现 `192.168.1.10` 的 3 条记录（`upnp:rootdevice`、UDN、`Storage:1`）。
- 主机侧 M-SEARCH（`ST=MediaRenderer:1`）发现手机 DMR：

```
SSDP ST=urn:schemas-upnp-org:device:MediaRenderer:1
     LOCATION=http://192.168.1.3:39520/description.xml
     USN=uuid:edcd3bd8-dd72-3bef-a722-2c420a0be37e::urn:schemas-upnp-org:device:MediaRenderer:1
```

- UDN 由 Android ID 稳定派生（`UUID.nameUUIDFromBytes`），不是硬编码测试值。
- 证明 CyberLink SSDP 广播 + MulticastLock + 私有 IPv4 绑定可用。

### 2.2 MediaPlayer + VettedMediaDataSource

测试音：30 秒 44.1 kHz/16-bit 立体声 WAV，由手机内 `DebugRangeServer` 在 `192.168.1.3` 的随机端口提供 HTTP Range。

| 验证点 | 结果 |
|---|---|
| HEAD 预检 Content-Length | 通过 |
| `Range: bytes=0-` / 跳读请求 | 通过（服务端日志可见 HEAD、`bytes=0-`、`bytes=100238-`、`bytes=0-`） |
| MediaPlayer prepared | `duration=30000 ms` |
| 播放推进 | `playing=true`，position 持续增长 |
| Seek | 成功，`GetPositionInfo` 返回 `00:00:05` |
| Pause | 成功，状态 `PAUSED_PLAYBACK`，position 保持 |
| Resume | 从暂停位置继续，不回到 0 |
| Stop | 成功，状态 `STOPPED` |

- MediaPlayer 全程只持有 `VettedMediaDataSource`，没有拿到 URL；HTTP 请求由 `HttpRangeSource` 发出，逐跳过 `UrlGuard`。

### 2.3 CyberLink DMR 设备

- 自定义 `MediaRenderer:1` 描述（独立 UDN、AVTransport/RenderingControl/ConnectionManager 及 SCPD/control/event URL）可从主机访问：

```
GET http://192.168.1.3:39520/description.xml -> HTTP 200
```

- 服务端自动补全 `<URLBase>http://192.168.1.3:39520</URLBase>`；服务 URL 与 SCPD 均可访问。

### 2.4 主机 SOAP 联调（主机作为控制点）

| 动作 | 结果 |
|---|---|
| `SetAVTransportURI`（局域网 URL） | 200 |
| `SetAVTransportURI` 公网 URL（`http://8.8.8.8/...`） | HTTP 500 + `UPnPError 714`（Only LAN http URLs are allowed）；未进入播放，日志确认“只允许 RFC1918 私有地址” |
| `SetURI→Play`、`Stop→Play` 紧邻（无等待） | 2 秒后均为 `PLAYING`（`needsFreshPlay` 竞态修复后复测） |
| `Play` | 200；`GetTransportInfo` 返回 `PLAYING` |
| `GetPositionInfo` | 返回 `TrackDuration=00:00:10/30`、`RelTime`、`TrackURI` |
| `Seek REL_TIME=00:00:05` | 200；随后 position 为 5s |
| `Pause` | 200；GENA 事件 `PAUSED_PLAYBACK` |
| `Play`（恢复） | 200；从暂停位置继续，GENA `PLAYING` |
| `Stop` | 200；GENA `STOPPED` |
| `SetVolume 30` / `GetVolume` | 返回 `CurrentVolume=30` |
| `GetProtocolInfo` | 返回 MP3/FLAC/WAV/AAC/Ogg 的 Sink 列表 |

### 2.5 GENA

- `SUBSCRIBE` 返回 `HTTP/1.1 200 OK`、`SID`、`TIMEOUT: Second-300`。
- 主机回调服务器收到 `NOTIFY`，`NTS: upnp:propchange`，`SEQ` 从 0 递增；`LastChange` 覆盖：

```
STOPPED -> TRANSITIONING -> PLAYING -> PAUSED_PLAYBACK -> PLAYING -> STOPPED
```

- `LastChange` 属性正确 XML 转义；`CurrentTransportActions` 随状态更新。

### 2.6 双机 DMC → DMR（Android 16 → Android 13）

小米 17 运行 debug DMC 客户端（`--ez dmc true`），Mi 10 Pro 运行 DMR（`--ez dmr true`）：

| 步骤 | 结果 |
|---|---|
| 小米 17 SSDP 发现 | 6 条 SSDP 记录，其中 1 个 MediaRenderer 候选 |
| 目标识别 | `老石头 DMR @ http://192.168.1.3:39520/description.xml` |
| 描述解析 | 受控 HTTP 获取，解析出 `controlURL=http://192.168.1.3:39520/dlna/avtransport/control` |
| `SetAVTransportURI` + `Play` | 均返回 HTTP 200 |
| 跨机拉流 | Mi 10 Pro DMR 从小米 17 的 `http://192.168.1.6:40175/tone.wav` 请求 HEAD/Range（零拷贝源在小米 17，日志可见） |
| 状态 | Mi 10 Pro DMR 进入 `PLAYING`，`GetTransportInfo` 返回 `PLAYING` |
| 时长/进度 | `TrackDuration=00:00:30`；刚起播时 `RelTime=00:00:00`（1s 内，属正常） |
| Stop | HTTP 200；Mi 10 Pro DMR 回到 `STOPPED` |
| Android 16 兼容 | 小米 17 的 SSDP、MediaPlayer+Range、CyberLink 渲染器自检同样全部通过 |

- HyperOS 默认拦截 `adb install`（`INSTALL_FAILED_USER_RESTRICTED`）；本机通过 `adb push` + `pm install` 完成测试安装，正式分发不受影响。

### 2.7 追加要求的落实

| 要求 | 落实 |
|---|---|
| 换 Wi-Fi 后 IP 变化 | `DmrDevice.start()` / `DmsDevice.startDevice()` 内部调用 `NetworkAddresses.findLan()` 实时解析当前局域网 IPv4，调用方不能再传入旧 IP；小米 17 复测自动绑定 `192.168.1.6`。全仓库 app 代码无写死 `192.168/10.x/172.16-31` 地址。Wi-Fi 切换由 NetworkGate 结束会话，下一次启动重新解析。 |
| Android 13+ 局域网权限 | debug manifest 已声明 `NEARBY_WIFI_DEVICES`（`neverForLocation`），安装后 `granted=false`；当前 SSDP 走 `CHANGE_WIFI_MULTICAST_STATE` + `MulticastLock`，不依赖该权限。release 在角色启用时请求，并列入上架权限清单；targetSdk 36 本地网络权限新规按官方文档复核。 |
| 给 DMR 的 URL 带正确后缀 | 新增 `MediaProtocolInfo`：源文件名扩展名优先、MIME 兜底；`ensureMediaSuffix` 保留 query、已有后缀不重复追加；DMS 的 token URL 使用 `mediaFileName(id, name, mime)`。7 个 JVM 用例通过。 |

### 2.8 DMS Browse / Range

**测试音 DMS（小米 17，测试曲库兜底）**

- 主机 SSDP `ST=urn:schemas-upnp-org:device:MediaServer:1` 发现：
  `LOCATION=http://192.168.1.6:39520/description.xml`。
- description HTTP 200；ContentDirectory `Browse` 根对象返回 1 个专辑，专辑返回 1 个曲目。
- DIDL 中的媒体 URL 形如
  `http://192.168.1.6:39520/dlna/media/<token>/a0-t0.wav`：动态 IP、128-bit token、`.wav` 后缀齐全。
- HEAD：`200`，`Content-Type=audio/wav`，`Content-Length=5292044`，`Accept-Ranges=bytes`，DLNA `contentFeatures` 正确。
- Range `bytes=1000-1999`：`206`，`Content-Length=1000`，`Content-Range=bytes 1000-1999/5292044`，实际下载 1000 字节。
- 开区间 `bytes=5290000-`：`206`，长度 2044；越界 Range 返回 `416`；错误 token 返回 `400`。

**真实曲库 DMS（Mi 10 Pro，8 专辑/16+ 曲目）**

- 授予 All Files Access（`appops set ... MANAGE_EXTERNAL_STORAGE allow`）后，DMS 识别真实曲库，日志：`DMS 曲库：真实曲库，专辑=8`。
- Browse 根对象返回 8 个专辑容器（BEYOND、DSD-陈慧娴等）；专辑 `a0` 返回 16 个曲目。
- DFF 曲目返回 `protocolInfo=http-get:*:audio/dsd:DLNA.ORG_OP=01`，URL 为
  `.../dlna/media/<token>/a0-t0.dff`，大小 142785922 字节。
- HEAD 返回 `200 audio/dsd`；Range `bytes=0-99` 返回 `206` + 100 字节，`Content-Range` 正确。
- DSD 仅做文件直传；DMR（MediaPlayer）不支持 DSD，这是已接受的格式收窄。

### 2.9 正式发送者 UI 与 VLC 兼容性

**正式 UI（小米 17）**

- 极简/沉浸/高保真三个主界面均新增 `DLNA` 入口，进入正式 `DLNA 局域网` 页面。
- 页面包含：当前局域网 IP、发送者/接收者/保持离线角色卡、角色说明、仅局域网声明、All Files Access 与 Nearby Wi-Fi 权限状态/授权入口、DMS 启停、DMR 最小启停。
- 实测：选择「发送者」后权限提示正确；授予权限并放入测试 WAV 后，DMS 从正式 UI 启动成功，显示 `1 张专辑 / 1 首曲目` 和 `http://192.168.1.6:39520/description.xml`。
- 主清单已加入 INTERNET / ACCESS_NETWORK_STATE / ACCESS_WIFI_STATE / CHANGE_WIFI_MULTICAST_STATE / NEARBY_WIFI_DEVICES，README/关于页隐私说明已同步为「默认离线、DLNA 可选、仅局域网」。

**VLC 兼容性**

- VLC 3.7 Beta 1 的 Browse 页面只显示 SMB；日志显示它启动了 libupnp（`Initializating libupnp on 'wlan0'`），随后 `libvlc services discovery: search timeout`，未列出 DMS。
- 同一时刻主机 M-SEARCH 能发现 Mi 10 Pro 的 DMS（`LOCATION=http://192.168.1.3:39520/description.xml`），说明 DMS 的 SSDP 正常；VLC 未持有 Android MulticastLock，Wi-Fi 组播过滤是更可能的原因。
- 用 VLC 直接打开 DMS 的 FLAC URL（ACTION_VIEW）后，DMS 日志出现 `DMS Range: 0-130874259/130874260`，VLC 读到曲目元数据（title=Amani），证明第三方播放器可通过本 DMS 的 Range 端点拉流；但 VLC 的 UPnP **浏览**未完成，M2 第三方控制点验收仍需 BubbleUPnP 或带 MulticastLock 的控制点。

**开源控制点尝试**

- **ControlDLNA 1.0.2**（BSD-3-Clause，GitHub release；SHA-256 `02ee06fe…d75b3`）：APK `targetSdkVersion=19`，Android 16 拒绝安装；项目已标注 unmaintained。
- **DroidDLNA**（GPL-3.0，355 stars）：无 release，仓库 2015 年后停更，不采用。
- **Kodi 21.3 Omega arm64**（GPLv2，官方镜像；SHA-256 `34e75267…5b44`，targetSdk 34）：安装成功并开启 JSON-RPC；但 Android 版 Kodi 的 Browse 只有 Android music / External storage / NFS / SMB / Zeroconf / Add network location，**没有 UPnP 客户端入口**，`Files.GetDirectory("upnp://")` 返回 Invalid params。测试后已卸载。
- 结论：目前没有找到可用的开源 Android DMS 控制点；第三方浏览验收需 BubbleUPnP、Windows「播放到」/桌面控制点，或用户另行提供控制点 APK。

### 2.10 自动角色弹窗（NetworkGate）

- `NetworkGate` 监听 ConnectivityManager；检测到 Wi-Fi/以太网私有 IPv4 后，按「接口名:IP」去重，每个新局域网会话只触发一次。
- 小米 17 启动 App 时立即弹出「选择本机 DLNA 角色」，显示当前 IP `192.168.1.6`、发送者/接收者说明、「保持离线」以及“仅局域网、每次重新选择、同一连接一个角色”的说明。
- 选择「发送者」后自动进入正式 DLNA 页面，角色卡显示“已选择”；权限卡显示 All Files Access 已授权、Nearby Wi-Fi 未授权（可点授权）。
- 网络断开/变化时 `LanSessionController` 关闭弹窗、清空当前角色并停止 DMS/DMR；下一次连接重新弹窗（代码路径已实现，未通过真实 Wi-Fi 切换复测）。

### 2.11 DMC 产品化（正式 UI 投送）

- `DmcClient` 由 debug 测试客户端提升为主代码：受控发现 MediaRenderer、抓取/校验描述、解析 AVTransport 与 RenderingControl controlURL，SOAP 控制走 `UrlGuard` 校验过的地址。
- 正式发送者面板新增「投送端（DMC）」：
  - 发现投送设备（`GuardedDiscovery` + 描述解析）；
  - 设备列表与选择；
  - 专辑/曲目选择；
  - 投送、暂停/继续、停止、Seek、音量、1 秒进度轮询。
- 投送媒体 URL 由当前 DMS 的 token 端点生成，因此要求先启动「曲库共享」；DMC 开始投送会停止本地播放。
- 小米 17（DMC）→ Mi 10 Pro（DMR）实测：
  - 发现 1 个 MediaRenderer（`老石头 DMR @ 192.168.1.3`）；
  - 选择 `DMC-Test/test.wav` 投送；
  - DMS 日志出现 `Range: 0-5292043/5292044`、`100238-5292043/5292044`；
  - Mi 10 Pro DMR：`STOPPED → TRANSITIONING → PLAYING`；
  - 正式 UI：`PLAYING 00:00:13 / 00:00:30`；
  - 暂停：DMR `PAUSED pos=19722/30000`，UI `PAUSED_PLAYBACK 00:00:19/00:00:30`；
  - 继续：DMR `PLAYING pos=19722/30000`，UI `PLAYING 00:00:22/00:00:30`；
  - 停止：DMR `STOPPED`。
- 本轮未逐项操作 UI 的 Seek/音量滑杆；对应 SOAP 动作在主机侧已验证。DMC 目前使用 1 秒轮询，不做 GENA 订阅。

### 2.12 M4：Oboe 共享模式验证（Mi 10 Pro）

- 背景：Mi 10 Pro 上安装的 v1.2 实际是**本机 Android Debug 证书**签名（`CN=Android Debug`，SHA-256 `6abcad84…`），并非 release 签名；release 证书（`laoshitou-release.jks`）是另一套，`install -r` release 报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` 属预期。
- 用本机 debug 包 `install -r` 原地升级成功，用户数据保留（EQ/预设/IR 不丢）。
- 播放验证（用户实测听感 + 日志）：
  - `OboeOutput: opened: format=2 rate=48000 channels=2`（format=2 = Float，通过代码的 Float 校验）；
  - 无 `openStream failed` / `stream closed` / 独占降档重试日志；
  - MediaSession `LaoshitouPlayback` `state=3（PLAYING）`；
  - `audio_flinger` 显示所有输出线程为 `type 0 (MIXER)`，无 MMAP/独占输出；
  - **有声音，欠载 0**（用户确认）。
- 结论：Shared + LowLatency + Float 输出路径在真机验证通过；系统混音器始终参与。

### 2.13 M4：DMR/DMC MediaSession 与格式提示

**DMR MediaSession（Xiaomi 17 生产 DMR）**

- 新增 `DlnaMediaSession`（framework `MediaSession`，tag `LaoshitouDlna`）：DMR 播放时显示接收曲目，播控回调接 `DmrAvTransport.playNow/pauseNow/stopNow/seekNow`（新增的重构接口，SOAP 动作与系统播控共用同一逻辑）。
- 主机 SOAP 投送 Mi 10 Pro DMS 的 FLAC（DIDL `dc:title=Amani`）后：
  - session `active=true`，`state=PLAYING`，`buffered position=288287`；
  - metadata：`Amani / DLNA · DSP 旁路`（标题从 DIDL 解析）。
- 状态联动：主机 Pause → `PAUSED pos=11902`；系统 MEDIA_PLAY 键 → `PLAYING pos=11902`（恢复同位置）；MEDIA_PAUSE 键 → `PAUSED pos=15018`；主机 Stop → `STOPPED`、session `active=false`。外部控制与系统播控双向一致。

**DMC MediaSession 与格式提示（Xiaomi 17 生产 DMC → Mi 10 Pro DMR）**

- 投送 30 秒 WAV 后：session `active=true`、`state=PLAYING`、`position=2000/buffered=30000`，metadata `test / 投送到 老石头 DMR`。
- 格式提示：投送 `.dff`（audio/dsd，Sink 未声明）弹出「格式可能不受支持：该设备未声明支持 DFF（audio/dsd）…」+「仍要尝试 / 取消」；点取消正常关闭，随后投送 `.wav` 直接成功。M4 的“不支持提示”闭环验证。
- 发送者子开关（DMS/DMC 独立开关）显示正常并持久化。

### 2.14 M4：前台服务、媒体按键与 Wi-Fi 切换

**前台服务（FGS）**

- 新增 `DlnaService`（`foregroundServiceType="mediaPlayback"`）：会话 active 时显示 `MediaStyle` 常驻通知（播放/暂停/停止 3 个动作），会话停止即退出。`DlnaMediaSession` 改为进程级单例（`getOrCreate`/`current`），服务与控制器共享同一会话 token。
- 小米 17 实测：DMR 播放中 `dumpsys activity services` 显示 `isForeground=true foregroundId=41 types=0x00000002(mediaPlayback)`，通知 `dlna_playback` 通道、3 动作、ONGOING|FOREGROUND_SERVICE；主机 Stop 后服务退出（仅剩通道定义）。
- 通知动作经 `DlnaNotificationReceiver`（exported=false）路由到会话回调。

**媒体按键**

- 会话回调新增 `onMediaButtonEvent`：处理 MEDIA_PLAY/PAUSE/STOP 硬件键（无 `MediaButtonReceiver` 时部分 OEM 直接投递给活跃会话）。

**真实 Wi-Fi 切换（Xiaomi 17）**

- `svc wifi disable`：DMR HTTP 端口不再可达；Wi-Fi 关闭后一切 DLNA 服务随会话停止。
- `svc wifi enable`：重连后**未选角色前** `http://192.168.1.6:39520` 连接失败（服务确实已停止，而非仅网络不可达）；角色弹窗重新出现「已接入局域网：192.168.1.6」。
- NetworkGate「新会话→弹窗、断网→停服务」路径真实复测通过。

### 2.16 M3 收尾：SetNextAVTransportURI 自动切歌修复

- 缺口：DMR 的 `SetNextAVTransportURI` 此前只保存 NextURI，播放自然完成后不切换（BubbleUPnP/Windows「播放到」依赖自动切歌）。
- 修复：`DmrAvTransport.onStateChanged` 在 `STOPPED && position >= duration`（自然完成）时 `advanceTo(nextUri)`；显式 Stop（position 归 0）不触发。`Next` 动作复用同一 `advanceTo`。
- 状态：已编译 + 单测通过；真机复测（SetNextAVTransportURI → 播完自动切）列入下一步验证项（§11.1）。

### 2.17 M3/M4 后续验证项与预加载可行性分析

- 已写入 `docs/DLNA_UPNP_PLAN.md` §11.1（DMC Seek/音量真机操作、渲染器断电恢复、第三方渲染器兼容性的现状与做法）与 §11.2（预加载/队列三层方案：标准 NextURI 自动切换已实现 → standby MediaPlayer 预加载 → 多首队列与每首固定设置放 DMC 侧）。

### 2.15 M4：10 分钟 DMR 浸泡测试

- 环境：主机 `HostProbe serve` 提供 30 秒 WAV（Range/206），Xiaomi 17 生产 DMR 播放，每 20 秒 SOAP 轮询位置/状态，每 60 秒拉 512KB Range 切片，共 30 个采样点（10 分钟）。
- 结果：DMR 持续播放，30 秒音轨完成 9 次自动重播；位置逐点推进无停顿；10 次 Range 全部 `206 524288`。
- 异常 1 次：第 24 点（约 8.5 分钟处）连续两个 SOAP 轮询超时 8 秒（`GetTransportInfo`/`GetPositionInfo`），随后自动恢复（第 25 点 `PLAYING pos=00:00:15`），期间 Range 拉流正常——判定为瞬时网络/HTTP 服务抖动，未造成播放中断。
- 资源（测试结束时）：Java Heap 32.5MB/52.6MB、TOTAL PSS 176MB、50 线程；单样本未见明显泄漏。
- 30 分钟完整长跑与 Wireshark 无外网流量（主机未装 Wireshark/tshark）仍未做，列为遗留。

### 2.18 M4：DMC Seek/音量与渲染器断电恢复真机验证

**DMC Seek/音量（小米 17 正式 UI → Mi 10 Pro DMR）**

- Seek：点按进度滑杆约 50% → `DMC SOAP Seek → HTTP 200`；UI 由 `00:00:11/00:00:30` 跳到 `00:00:18/00:00:30`，DMR 位置同步。
- 音量：点按音量滑杆约 30% → UI `音量 29`；主机查询 DMR `GetVolume` 返回 `CurrentVolume=29`，两端一致。
- 结论：正式面板的 Seek/音量滑杆端到端可用。

**渲染器断电恢复**

- 新增 `castOffline` 与连续失败阈值（3 次轮询）：连续失败即置离线并在 UI 显示「渲染器已离线，正在重试…」+ 错误说明；恢复后自动清空并继续轮询。
- 实测：DMC 投送播放中 `force-stop` Mi 10 Pro 的 DMR 进程（模拟断电）→ 小米 17 UI 出现离线提示；重启 DMR 后 **8 秒内提示自动清除，SOAP 轮询恢复 HTTP 200**（重启后的 DMR 无媒体，状态为 `STOPPED 00:00:00`，符合预期）。
- 说明：门槛 3 次 + 1 秒轮询，离线识别约 3 秒；恢复后不会自动重投，需要用户重新选择曲目投送（当前设计）。

**屏幕/长测设置**

- 小米 17 系统 `screen_off_timeout` 原为 10 分钟；测试期间临时调至 30 分钟并开启 `stay_on_while_plugged_in`，结束后已恢复 `600000` / `0`。

**遗留（M4 待补）**

- 30 分钟完整长跑已通过（见 §2.20）；Wireshark 原始抓包仍缺 tshark（已用 Android socket 表替代取证，见 §2.20）；后台启动 FGS 限制、第三方渲染器/控制点矩阵（缺设备，BubbleUPnP/Windows 控制点待用户提供）。小米息屏冻结问题已定位并验证，见 §2.19。

### 2.19 M4：小米 HyperOS 息屏冻结与 DMS 长驻（2026-11-01）

**现象**：DMS（`specialUse` FGS）在小米 17 上息屏后完全不可达；主机 curl/ping 超时，手机自连 `192.168.1.9:39520` 也超时（路由 `local ... dev lo`）。但进程存活、41 个线程均为 S 状态、FGS `isForeground=true`、WifiLock（`laoshitou-dlna`）与 MulticastLock 均被持有。

**定位**：
- 对照实验：同网段、同息屏状态下，shell UID 的临时监听端口可从主机连通；应用 UID 下新建的任意监听端口（`run-as ... nc -l`）均不可达 → 排除 Wi-Fi/路由/组播，定位到 UID 级网络切断。
- `/sys/fs/cgroup/apps/uid_10318/pid_30147/cgroup.freeze` 读出 `1`，`dumpsys greezer` 的 Frozen processes 列表包含 PID 30147 → 小米 PowerKeeper/Greezer 在息屏后冻结了应用进程，入站/出站网络随之中断。
- `dumpsys netpolicy` 显示该 UID `procState=FGS`、`effective=NONE`；`deviceidle` 状态为 ACTIVE。AOSP 侧并无限制，属于厂商省电管控。

**无效的豁免尝试**：
- `cmd deviceidle whitelist +com.gu.laoshitou`（电池优化白名单）：30 秒内仍 `freeze=1`。
- 最近任务锁定（长按卡片 → 锁图标，`settings system locked_apps` 已包含该包，界面显示锁图标）：仍冻结。
- 写入 `power_pkg_white_list`、`cloud_memory_freeze_whitelist`：无效（测试后已恢复原值）。

**有效修复**：设置 → 应用信息 → 老石头 → 电量详情 → 省电策略 → **无限制**（界面说明该策略才会取消“后台联网”等限制）。设置后：
- 90 秒息屏对照：`freeze=0`、DMS 全程 HTTP 200。
- 完整 12 分钟息屏测试（6 个采样点，每 2 分钟一次，跨越 10 分钟系统锁屏门槛）：6/6 `dms_http=200`、`freeze=0`、FGS 存活；亮屏后 DMS 仍 200。
- 结论：小米设备上 DMS 长驻依赖用户将“省电策略”设为“无限制”；最近任务锁定只防清理，不能替代。产品已在 `DlnaScreen` 的发送者 DMS 区域加入「息屏保活（DMS 长驻）」卡片：小米/HyperOS 显示原因与完整设置路径，**只做文字提示，不自动修改系统设置、不自动跳转页面**；README 与 `docs/APK_USER_GUIDE.md` 同步说明。

### 2.20 M4：30 分钟 DMR 长跑与无外网流量取证（2026-11-01）

**环境**：主机 `HostProbe serve` 提供 30 秒 WAV（Range/206，`192.168.1.8:18099`）；小米 17 生产 DMR（`192.168.1.9:39520`，`mediaPlayback` FGS + WifiLock/MulticastLock），息屏 Dozing，省电策略=无限制；每 20 秒 SOAP 轮询 `GetTransportInfo`/`GetPositionInfo`，每 60 秒拉 512KB Range 切片。

**结果**：
- 90/90 采样完成，`restarts=45`（30 秒音轨自然播完后自动重播），`anomalies=0`；所有 Range 响应均为 `206 524288`；无 SOAP 超时/状态异常。
- 结束（22:22:53）DMR 仍 HTTP 200、`cgroup.freeze=0`；上次浸泡末段因 Wi-Fi teardown 失败的问题未复现。
- 进程存活、FGS 保持；未出现进程被杀或冻结。

**无外网流量取证（Wireshark 替代）**：主机未安装 Wireshark/tshark。用 `run-as` 读取应用 `fd` 中的 socket inode，与 shell 可读的 `/proc/net/{tcp,tcp6,udp,udp6}` 按 inode 匹配，13 次采样（约 24 分钟）结果：
- 仅见 DMR 监听 `192.168.1.9:39520`、到主机 `192.168.1.8:18099` 的 TCP 连接（每次 Range 请求新建）、SSDP 的 UDP 1900 监听；
- **0 个非私有远端地址**（脚本对非 RFC1918/loopback/unspecified/multicast 远端标记 `NON-PRIVATE`）。
- 结论：测试期间应用无对外网连接；Wireshark 原始抓包待主机安装 tshark/镜像端口后补。

### 2.21 M4：DMR 原生 DSP 管线（2026-11-01）

**实现**：
- `JavaMediaSource` 实现 `audio::MediaSource`，通过 JNI 调用现有的
  `VettedMediaDataSource`（LAN-only HTTP Range、逐跳重定向校验）；FFmpeg 仍为
  `--disable-network`，由自定义 AVIO 读写，不放开 FFmpeg 网络栈。
- `FfmpegDecoder::open_source` + 自定义 AVIO（read/seek）支持随机访问流；
  新增原生 `seek_ms`（`av_seek_frame` + `avcodec_flush_buffers`）与
  `position_ms`（解码位置减 ring 缓冲）。**注意不能调用 `avformat_flush`**：
  它会把自定义 AVIO 位置冲回起点，表现为 Seek 后从头播放。
- `AudioEngine::seek_to_ms` 通过停输出/停 producer/reset ring/resampler/DSP/
  重开输出实现无竞态 seek；`position_ms` 用于 DMR 的 `GetPositionInfo`。
- `NativeDmrBackend` 走 `NativeBridge.startSource`；`DspDmrBackend` 组合层在
  原生启动失败时自动回退 `MediaPlayerBackend`（同一 LAN-only 数据源）。
- DMR 使用启动接收时捕获的播放器 DSP 配置（处理/直通、EQ 配置文件、
  Crossfeed、BRIR、响度、限制器、多段压缩等），音量映射到原生 gain +
  loudness；不支持的格式自动回退系统播放器。

**真机验证（小米 17，主机 44.1k WAV → 48k Oboe）**：
- 日志：`OboeOutput: opened: format=2 rate=48000`；
  `DMR 原生 DSP: processing=true eq=0 crossfeed=true limiter=true brir=false`
  （`eq=0` 因未选择 EQ 文件；Crossfeed/Limiter 已生效，DSP 链未旁路）。
- `GetTransportInfo=PLAYING`，`GetPositionInfo` 正常前进；
  Seek 到 15s 后位置 17s→19s（正常推进）。
- 暂停：位置冻结在 1s；继续后回到 `PLAYING`。
- 音量：`SetVolume 80` → `GetVolume 80`。
- `SetNextAVTransportURI`：30s 自然播完后 `CurrentURI` 自动切到下一首并继续
  `PLAYING`（日志出现第二次 Oboe open 与原生 DSP 启动）。
- 回退：投送非音频文件时日志
  `DMR 原生播放失败，回退 MediaPlayer: 原生 DSP 播放启动失败`，MediaPlayer
  随后按预期报错。

**后续（2026-11-01 深夜已补）**：真实 FLAC/DSF 投送、DSP 仪器验证、
Visual EQ 同步已见 §2.23；DFF over DMR 仍不支持（本地播放器支持 DFF）；
第三方矩阵按用户要求不再考虑。

### 2.22 M4：原生 DMR 30 分钟混合长跑（2026-11-01）

**方法**：小米 17 原生 DMR（FFmpeg → DSP → Oboe，48k 输出，44.1k 源）；
主机提供 5 分钟 WAV（18099/18101 两个 URI）。90 个 20 秒采样；每 60 秒 Seek
（5/15/25 秒轮换），每 120 秒切换 URI；每 60 秒采集 CPU、线程数、
PSS/Java Heap/Native Heap；Oboe 延迟/underrun/XRuns 每 10 秒由
`DMR 监控` 日志输出。

**结果**：
- 90/90 采样；Seek 30/30、URI 切换 15/15 成功；状态只有
  `PLAYING`/`TRANSITIONING`（切歌瞬间），无 `STOPPED`/`ERROR`。
- **CPU**：进程约 25–40% 单核（`dumpsys cpuinfo` 35–40%，`/proc` 10 秒窗口
  25.6%）。热点：解码/拉流线程约 18–25%，AudioTrack/DSP 约 3.5–7%。
  属于 44.1k→48k BEST 重采样 + FFmpeg `--disable-asm` + DSP 的预期开销；
  若需降低可降低重采样质量或在 DMR 使用直通。
- **内存**：稳定播放样本 PSS 177.7 MB → 177.8 MB（min/avg/max
  176.3/177.1/177.8 MB），无爬升；Java Heap 26.8 → 26.5 MB（GC 锯齿）；
  Native Heap 21.7 → 22.5 MB（+0.7 MB/30 分钟，PSS 无对应增长）。
  切换/Seek 瞬间 Native Heap 短时约 31 MB（新旧引擎交接），随后回落，
  不是持续泄漏。
- **线程**：50 → 54；前 10 分钟到 51，之后缓慢到 54 并保持；没有随
  45 次 Seek/切换线性增长（每次新建的 producer 线程会退出）。
- **Oboe 延迟**：29.1–32.8 ms，平均 30.5 ms（日志 10 秒粒度平均 30.9 ms），
  稳定。
- **Underrun/XRuns**：稳态 0；只在 Seek/切歌/采样命令附近出现一次性 burst
  （监控尾段 3 次、每次约 35–36k 帧 ≈0.75 秒），之后保持不增长；
  XRuns 累计 1。判定为过渡/采样干扰，不是持续欠载。
- **结论**：原生 DMR DSP 路径 30 分钟稳定；无内存缓慢爬升、无线程泄漏
  迹象；延迟稳定约 30 ms；Seek/切歌混合负载通过。

### 2.23 M4：真实 FLAC/DSF、DSP 仪器验证与 Visual EQ 同步（2026-11-01）

**真实格式投送**
- 真实 FLAC（92 MB，44.1k，3:25，主机 18099）：原生 DMR `PLAYING`、Oboe 48k、
  `DMR 原生 DSP: processing=true eq=16 crossfeed=true limiter=true`；
  Seek 1:00 → 1:02。
- 真实 DSF（123 MB，DSD64，3:01，主机 18101）：原生 DMR `PLAYING`、Oboe 48k、
  Seek 1:00 → 1:17；Oboe latency ~30 ms、underruns 0；CPU ~80% 单核
  （DSD64→48k 重采样 + DSP 的预期开销）。
- DFF（未压缩 DSD64）：原生 FFmpeg 无 DSDIFF demuxer →
  `DMR 原生播放失败` → 回退 MediaPlayer → 失败（`STOPPED`）。结论：DMR 原生
  DSD 支持 **DSF**；**DFF 仍为已知边界**（本地播放器通过自研 DFF 解析支持 DFF）。

**DSP 仪器验证**
- 离线 `hifi_offline`（与 DMR 同一 `core-dsp` 链）：
  - 3 段 EQ（100 Hz −12 dB、1 kHz +12 dB、10 kHz −12 dB，Preamp −12）对三音
    信号实测相对变化：**−23.92 / −0.14 / −23.94 dB**（理论 −24/0/−24）。
  - `--dry` 输出与输入逐样本一致（max abs diff **0.0**）。
  - Crossfeed weak：左声道脉冲在右声道产生 **−19.9 dB** 串音（dry 右声道 0）。
  - Limiter 阈值 −6 dBFS：0.9 峰值（−0.92 dBFS）被压到 0.501187（**−6.00 dBFS**）。
- Host CTest **18/18** 通过。
- DMR 日志同时显示 `processing=true, eq=16, crossfeed=true, limiter=true`，
  证明同一 DSP 链已接入接收路径。
- 听感验证需要用户在真机实际试听强 EQ/Crossfeed 配置；本轮完成仪器侧与
  链路侧验证。

**Visual EQ DMR 同步**
- 写入强 16 段 EQ（1 kHz +12 dB、10 kHz −12 dB、Preamp −12）并启用
  `use_visual_eq`。
- DMR 启动日志 `eq=16`；Seek 1:00 后 `eq=16`；切换 URI（FLAC→DSF）后 `eq=16`。
- 运行中返回播放器 → 高保真 → 16 段 EQ，按钮显示“应用（实时生效）”；
  点击后日志 `16 段视觉 EQ 已实时应用到 DMR`，DMR 保持 `PLAYING`、`eq=16`。
- 实现：`NativeDmrBackend` 在启动/Seek 后从 `player_settings` 重放 Visual EQ；
  `applyEqEditor` 在 `state.playing || dmrRunning` 时调用
  `NativeBridge.updateEq`；`PlayerState.dmrRunning` 同步按钮文案。

**结论/边界**：真实 FLAC/DSF 与 DSP 链路验证通过；DFF over DMR 不支持；
第三方矩阵按用户要求不再考虑；听感确认留给用户。

## 3. 联调中发现并修复的问题

1. **MediaPlayer 启动即报错**：M0 harness 的 `startMediaPlayer()` 先调用 `stopPlayback()`，把刚创建的 Range 服务与数据源关闭。
   - 修复：拆分为 `releasePlayer()`（只释放播放器）与 `stopPlayback()`（释放播放器 + 数据源 + 服务）。
2. **CyberLink 默认 `MediaRenderer()` 启动 NPE**：`JaxpParser` 在 Android 上设置了不支持的 Apache JAXP feature，描述解析失败被构造器静默吞掉，`deviceNode` 为 null。
   - 修复：新增 `AndroidXmlParser`（仅用可移植 JAXP 配置、拒绝 DOCTYPE/外部实体），在 `CyberLinkBootstrap` 中安装；DMR 使用自定义描述。
3. **GENA 不发送事件**：CyberLink `StateVariable.isSendEvents()` 读取 `sendEvents` 属性，而标准 SCPD 使用 `sendEventsAttribute` 子元素，`LastChange` 被跳过。
   - 修复：加载 SCPD 后显式 `setSendEvents(true)`（AVTransport 的 LastChange，RenderingControl 的 Volume/Mute）。
4. **暂停后 Play 从 0 重新开始**：AVTransport 的 Play 无条件重新拉流。
   - 修复：按 backend 状态区分 `PLAYING/TRANSITIONING`（幂等）、`PAUSED`（resume）、其余（重新 play）。
5. **DMR 日志包含媒体 URI**：不符合“不记录 token/完整路径”的规范。
   - 修复：状态日志不再输出 URI；拒绝日志只输出原因。
6. **SetAVTransportURI/Stop 后紧邻 Play 的竞态**：backend 的异步 stop 尚未执行，Play 可能误判为“已在播放”而跳过新 URI。
   - 修复：`needsFreshPlay` 标记强制下一次 Play 重新拉流；SetURI+Play、Stop+Play 紧邻复测均为 `PLAYING`。
7. **DMR UDN 固定测试值**：多台手机会冲突。
   - 修复：由 Android ID 派生稳定 UUID；复测 description 返回 `uuid:edcd3bd8-dd72-3bef-a722-2c420a0be37e`。
8. **DMS `start()` 被父类方法遮蔽**：`DmsDevice` 继承 `Device.start()`，自定义 `start(port: Int = ...)` 在 Kotlin 重载解析时被无参父类方法抢先，绕过初始化并触发 NPE。
   - 修复：改名为 `startDevice()`，避免遮挡 `Device.start()`。
9. **CyberLink Range 解析不符合标准 `bytes=start-end`**：`getContentRange()` 期望 `bytes start-end/total`，标准头被解析成 0-0 并返回整文件。
   - 修复：媒体端点自行解析单段 Range，规范化为 CyberLink 可解析的 `bytes start-end/total` 再交给 `request.post()`；非法 Range 先清空请求头再返回 416，错误 token 清空头后返回 400，避免被 Range 逻辑覆盖。

## 4. 尚未覆盖 / 后续

- **真实第三方控制点**：VLC 3.7 Beta 的 UPnP Browse 因组播发现超时未列出 DMS（同网段主机 SSDP 正常）；VLC 直接打开 DMS FLAC 已验证 Range 拉流。ControlDLNA（targetSdk 19）与 Kodi 21.3（Android 版无 UPnP 客户端入口）均不可用；DroidDLNA 无 release。M2 第三方浏览验收待 BubbleUPnP/Windows 桌面控制点或用户提供 APK。
- **真实 DMS 来源**：本次媒体源为手机内 Range 服务（仍是 LAN HTTP）。NAS 只提供 `Storage:1`，未提供 ContentDirectory；需找 MiniDLNA/NAS/Plex 等 DMS 复测。
- **自动角色弹窗**：NetworkGate 与全局角色弹窗已实现并在启动时验证；网络断开/切换时停止服务与重新弹窗的代码路径已接入，但未通过真实 Wi-Fi 切换做设备复测。
- **DMS/DMC 产品化**：DMC 已具备正式设备发现/选择、专辑曲目选择、投送、暂停/继续、停止、进度轮询；Seek/音量控件已接入但本轮未逐项 UI 操作，第三方渲染器矩阵未测。DMS 已有正式 UI/权限引导/Browse/Range/真实曲库，但尚无曲库刷新、Search、ContentDirectory GENA、token 过期/并发上限，也未用第三方控制点实际浏览。
- **格式矩阵**：只测 WAV；MP3/FLAC/AAC/OGG、APE/DSD 拒绝路径待测。
- **长跑/异常**：30 分钟长跑、Wi-Fi 断开/切换、来电/蓝牙路由、来源断电未测。
- **安全设备测试**：公网 URL、重定向到公网、`file://`、loopback、超大 Range 等仅有 JVM 单测，需在真机上通过恶意 SOAP/HTTP 再测一遍。
- **真机音频确认**：日志与 AudioTrack/焦点状态正常；听感确认需人工完成。

## 5. 复现命令（摘要）

```bash
# 构建
./gradlew :app:assembleDebug

# 小米 17（HyperOS 可能拦截 adb install）
adb -s <xiaomi-serial> push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/laoshitou-debug.apk
adb -s <xiaomi-serial> shell pm install -r /data/local/tmp/laoshitou-debug.apk

# Mi 10 Pro 启动 DMR（保持 180 秒）
adb -s <mi-serial> shell am start -n com.gu.laoshitou/com.gu.laoshitou.dlna.M0Activity --ez dmr true

# 小米 17 启动 DMC 双机联调
adb -s <xiaomi-serial> shell am start -n com.gu.laoshitou/com.gu.laoshitou.dlna.M0Activity --ez dmc true

# 启动 DMS（真实曲库优先，空库时用测试音兜底）
adb -s <serial> shell am start -n com.gu.laoshitou/com.gu.laoshitou.dlna.M0Activity --ez dms true

# 真机测试真实曲库时授予 All Files Access（正式版由 UI 引导）
adb -s <serial> shell appops set com.gu.laoshitou MANAGE_EXTERNAL_STORAGE allow

# 从主机访问描述
curl http://192.168.1.3:39520/description.xml
curl http://192.168.1.6:39520/description.xml

# ContentDirectory Browse（示例）
curl -X POST http://192.168.1.6:39520/dlna/contentdirectory/control \
  -H 'Content-Type: text/xml; charset="utf-8"' \
  -H 'SOAPACTION: "urn:schemas-upnp-org:service:ContentDirectory:1#Browse"' \
  --data-binary '<SOAP Envelope...>'

# 媒体 Range（URL 来自 Browse 返回的 <res>）
curl -I 'http://192.168.1.6:39520/dlna/media/<token>/a0-t0.wav'
curl -H 'Range: bytes=1000-1999' -o slice.bin \
  'http://192.168.1.6:39520/dlna/media/<token>/a0-t0.wav'

# 主机作为控制点
curl -X POST http://192.168.1.3:39520/dlna/avtransport/control \
  -H 'Content-Type: text/xml; charset="utf-8"' \
  -H 'SOAPACTION: "urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI"' \
  --data-binary '<SOAP Envelope...>'
```

- M0 harness 源码：`app/src/debug/java/com/gu/laoshitou/dlna/M0Activity.kt`、
  `DebugRangeServer.kt`、`DmcTestClient.kt`、`DebugDmsLibrary.kt`。
- DMR 实现：`app/src/main/java/com/gu/laoshitou/dlna/Dmr*.kt`、
  `MediaPlayerBackend.kt`、`AndroidXmlParser.kt`。
- DMS 实现：`app/src/main/java/com/gu/laoshitou/dlna/Dms*.kt`、
  `MediaTokenStore.kt`、`MediaProtocolInfo.kt`、`MusicLibraryDmsLibrary.kt`。
