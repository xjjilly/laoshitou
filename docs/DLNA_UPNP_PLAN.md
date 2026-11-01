# DLNA/UPnP 发送者与接收者方案（DMC + DMS + DMR）

- 状态：已实现（M0-M4 真机验证中；第三方矩阵待设备）
- 日期：2026-11-01（v4：DMR 改用 MediaPlayer / DSP 旁路、CyberLink 选型、每次联网角色二选一；晚间已升级为 DMR 原生 DSP 管线，见下方更新）
- 目标版本：v2.0（暂定）
- 关联：`README.md` 的隐私声明、`PlaybackService`、`MusicLibrary`、`THIRD_PARTY_NOTICES.md`

## 1. 目标与已确定决策

在老石头现有本地播放器之上，增加**仅限局域网**的 DLNA/UPnP 能力：

| 角色 | 能力 | 谁控制播放 | 谁解码 | 手机出声 |
|---|---|---|---|---|
| **发送者** | DMC 投送端 + DMS 媒体服务器 | 手机（DMC）/ 远端（DMS） | 远端渲染器 | 否 |
| **接收者** | DMR 渲染端 | 远端控制点 | 手机（原生 FFmpeg + DSP 链；失败回退 MediaPlayer） | 是 |

**已确定的产品决策（2026-11-01 用户拍板）：**

1. **DMR 使用 Android MediaPlayer，接受 DSP 旁路**：接收投送时由系统 MediaPlayer 解码/输出，不经过老石头的 FFmpeg + DSP 链（EQ/Crossfeed/BRIR/限制器不生效）。M3 直接用 MediaPlayer 实现 DMR，**不再改造 `AudioEngine`/FFmpeg AVIO**，native 代码零改动。
2. **只允许局域网**：不连接互联网、不接受公网媒体 URL；在角色对话框、接收状态页、README 三层向用户声明。代码层用 URL 白名单与受控 HTTP 数据源强制执行（§9）。
3. **杜绝 DMR 与 DMC 冲突**：每次连接局域网时，第一时间弹窗让用户选择本机角色——**发送者或接收者（二选一）**，另设「保持离线」出口；每次连接重新询问，不静默复用上次角色。一次连接只启动一个角色，DMC 与 DMR 在代码路径上不可能同时存在。对话框附角色说明与「仅局域网」说明。
4. **全程遵守代码规范**：见 §14；新代码与现有仓库风格一致，纯逻辑必须有 JVM 单测。
5. **不重复造轮子**：先检索并复核开源实现，再决定自研范围。本轮复核结论：jupnp 因许可证不可用，改用 **CyberLink for Java（BSD-3-Clause）**；协议栈、设备/服务模型、SOAP、GENA 复用，只有安全敏感的薄层自研（§13）。

> **2026-11-01 晚更新（M4 DSP 接入完成，取代第 1 条旁路方案）**：DMR 现使用
> `JavaMediaSource`（JNI 调用 `VettedMediaDataSource`，LAN-only HTTP Range）+
> 自定义 FFmpeg AVIO（构建仍 `--disable-network`）→ 老石头 DSP 链 → Oboe；
> 新增原生 `seek_ms`/`position_ms`。原始启动失败时由 `DspDmrBackend` 自动回退
> `MediaPlayerBackend`。真机已验证进度/Seek/暂停/音量/自然播完自动切歌与回退路径，
> 详见报告 §2.21。§5.3/§5.3.5 中的“DSP 旁路”为历史方案。

**非目标（本阶段不做）：**

- Chromecast / AirPlay / 互联网串流 / 云端账号。
- 视频投屏、图片投送。
- DMC 投送时把 DSP 应用到远端——DLNA 由渲染器直接拉原始文件，手机不参与解码。
- DMR 接收互联网来源（明确拒绝，见 §9）。
- ~~DMR 上叠加 DSP（已接受旁路；不在 MediaPlayer 输出后做二次处理）。~~
  **2026-11-01 晚已实现原生 DSP 管线（见上方更新）。**

## 2. 角色模型：发送者 / 接收者

### 2.1 每次连接局域网即选择

- `NetworkGate` 检测到手机接入新的局域网（Wi-Fi / 以太网，非蜂窝）且本连接会话尚未选择角色 → 在 App 回到前台的第一时间弹出角色对话框。
- 「连接会话」以一次网络连接为边界：Wi-Fi 断开、重连、切换 SSID/网络、以太网插拔都视为新会话；新会话必须重新选择。
- **每次连接二选一，不静默复用**：对话框可以默认高亮上次使用的角色，一键确认即可；但必须由用户确认，不存在「自动进入上次角色」的路径。「保持离线」始终可选。
- App 启动时已经连着 Wi-Fi 也算当前会话，首次回到前台时弹窗。

| 选项 | 说明（对话框文案） |
|---|---|
| **发送者** | 把手机音乐投送到电视/音箱/DLNA 设备（DMC），或让其他设备浏览并拉取手机曲库（DMS）。手机不出声。 |
| **接收者** | 手机作为 DLNA 音箱，接收其他设备投送的音乐，在本机播放。**使用系统 MediaPlayer，老石头的 Hi-Fi DSP 不参与（DSP 旁路）**。 |
| **保持离线** | 不使用 DLNA，本次连接零网络行为，与旧版一致。 |

- 对话框固定附两段说明：
  - 「DLNA 仅在局域网内通信，不连接互联网；接收者只接受局域网内设备投送的媒体地址。」
  - 「同一连接只启用一个角色：发送者不会启动接收服务，接收者不会启动投送/曲库服务，因此不会出现 DMC 与 DMR 冲突。」
- 选择在本次连接期间生效。用户可在设置中切换角色；切换即先停止当前 DLNA 会话（DMS/DMC/DMR 全停），再启动新角色，避免双会话。
- 发送者角色内可单独开关 DMC 投送与 DMS 曲库共享；接收者角色下只有 DMR。
- 此模型使「DMC 与 DMR 互斥」成为构造性保证，而不是运行时协调。

### 2.2 与本地播放的关系

- 同一时刻只有一个出声端：本地播放、DMC 投送、DMR 接收三者互斥。
  - 开始 DMC 投送 → 停止本地引擎。
  - DMR 收到 `SetAVTransportURI` / `Play` → 停止本地引擎（DMC 因角色模型不可能同时存在）。
  - 本地开始播放 → 若 DMR 正在接收，先结束本地 DMR 会话（`Stop` + 通知控制点）并提示。
- DMC/DMS 由远端解码，DSP 不参与；DMR 由 MediaPlayer 解码，**DSP 旁路**（§5.3.5）。
- MediaSession/通知：
  - DMC：标题「投送到 <设备名>」，播控转发 AVTransport。
  - DMR：标题「接收投送 · <曲名>（DSP 旁路）」，播控对应 MediaPlayer。
- 音频焦点：DMR `Play` 时请求 `AUDIOFOCUS_GAIN`；失焦时暂停并更新 AVTransport 状态；来电/其他 App 播放沿用现有 `PlaybackService` 策略。

### 2.3 NetworkGate 判定

- 只考虑 `TRANSPORT_WIFI` / `TRANSPORT_ETHERNET`，且 `LinkProperties` 上存在 RFC1918 私有 IPv4 地址；蜂窝网络、纯 VPN、无地址时一律不启动 DLNA。
- 通过 `ConnectivityManager.NetworkCallback` 监听 `onAvailable` / `onLost` / `onLinkPropertiesChanged`；SSID 变化或网络句柄变化视为新会话。
- 申请 `MulticastLock`（SSDP 组播必需），按引用计数释放；必要时加 `WifiLock`。
- **HTTP Server 启动时动态解析 IP**：由 `NetworkAddresses.findLan()` 读取当前 Wi-Fi/以太网 IPv4，禁止缓存/写死；Wi-Fi 切换后 NetworkGate 结束旧会话，下一次启动重新解析。`DmrDevice.start()` 已在内部执行该解析，调用方无法传入旧地址。
- 若系统开启了 AP 隔离或组播被过滤，DMC 提供手动输入 `IP:端口` 兜底（该地址同样过 UrlGuard）。
- 后台时检测到新网络，不弹 Activity；记录待选择状态，下次前台立即弹窗。

## 3. 对产品定位的影响（隐私表述）

- 新增 `android.permission.INTERNET`。INTERNET 是安装时权限，无法运行时授权，APK 带 DLNA 后安装页会显示网络权限。
- README「未申请 INTERNET 权限」改为「**默认离线；DLNA 为可选功能，开启后只监听/访问局域网，不连接互联网**」。
- 角色对话框、DMR 状态页、README 三层明示「仅局域网」；DMR 的 URL 白名单在代码层强制（§9）。
- 默认角色为「保持离线」：用户不选择或选择离线时，App 不发起任何网络请求、不监听端口、不加入组播。
- 代码层面无互联网出站请求；所有出站 HTTP 必须经过 `UrlGuard`（§9），可在审查与 Wireshark 中验证。
- GPLv3 不变；第三方库合规要求见 §13。

## 4. 需要新增的权限与系统能力

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />
<uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES"
    android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
```

- `CHANGE_WIFI_MULTICAST_STATE`：SSDP 组播（`239.255.255.250:1900`）在多数手机上被系统过滤，需持 `WifiManager.MulticastLock`。
- `NEARBY_WIFI_DEVICES`（API 33+，运行时权限，`neverForLocation`）：上架权限集必须声明；在角色启用时请求。当前 SSDP 路径使用 `MulticastLock`，不依赖该权限（小米 17 / Android 16 未授权时发现仍通过），但未来读取 SSID/Wi-Fi 扫描必须请求。debug manifest 已声明，release 在 M4 启用 DLNA 时加入并走运行时请求。
- 不需要 `ACCESS_FINE_LOCATION`（声明 `neverForLocation` 后无需位置权限）。
- `usesCleartextTraffic`：DLNA 使用局域网明文 HTTP（`http://`）。targetSdk 35 默认禁止明文，需在 manifest/network security config 中允许；仅用于局域网地址，代码层由 `UrlGuard` 限制。不启用 HTTPS（第一版），因为局域网 DMS 通常不提供可信证书。
- DMR 的 MediaPlayer **不直接访问网络**（§5.3.2 的 `VettedMediaDataSource`），因此 MediaPlayer 自身不受明文策略影响；出站请求由我们受控的 HTTP 客户端完成，仍受 `UrlGuard` 约束。
- targetSdk 36（Android 16）前需核对本地网络访问权限新规；当前 targetSdk 35 在小米 17/Android 16 上已通过真机验证，上架前按当时官方文档复核。
- 只允许 Wi-Fi/以太网；蜂窝网络下不启动 SSDP、HTTP 服务或任何 DLNA 请求。

## 5. 协议与流程

### 5.1 DMC：投送到渲染器（发送者）

```
[手机] --M-SEARCH--> [局域网]
[渲染器] --200 OK (LOCATION/USN/ST)--> [手机]
[手机] --UrlGuard 校验 LOCATION--> [手机]
[手机] --GET deviceDescription.xml (受控 HTTP)--> [渲染器]
[手机] --校验 controlURL/eventSubURL 均为私有 IPv4--> [手机]
[手机] --POST AVTransport: SetAVTransportURI(uri, DIDL-Lite)--> [渲染器]
[手机] --POST AVTransport: Play--> [渲染器]
[渲染器] --GET http://<手机IP>:<端口>/dlna/media/<token>/<id> (Range)--> [手机]
[手机] --POST GetTransportInfo/GetPositionInfo (1s 轮询)--> [渲染器]
```

- **发现**：自研一个薄的 `GuardedDiscovery`（`MulticastSocket` + M-SEARCH），只把通过 `UrlGuard` 的 `LOCATION` 交给后续流程；**不使用** CyberLink `ControlPoint` 的自动描述抓取（它在 SSDP 响应后直接 `Parser.parse(URL)`，无法拦截公网 LOCATION）。
- 设备/服务模型、SOAP 调用与动作参数复用 CyberLink `Device`/`Service`/`Action`；描述 XML 由我们的受控 HTTP 客户端抓取后交给 CyberLink 解析，并显式 `setLocation`，保证相对 URL 解析正确。
- 3 次 M-SEARCH（MX=1/2/3），`ST=urn:schemas-upnp-org:device:MediaRenderer:1`，兜底 `ssdp:all`；监听 `NOTIFY ssdp:alive/byebye` 保持列表新鲜；UDN 去重，设备描述缓存 24h（缓存前同样过 UrlGuard）。
- 控制：`SetAVTransportURI`/`Play`/`Pause`/`Stop`/`Seek`；切歌优先 `SetNextAVTransportURI`，否则 Stop→Set→Play；音量走 `RenderingControl`，无该服务时禁用音量滑杆。
- 投送前用 `ConnectionManager.GetProtocolInfo` 的 Sink 列表匹配格式；不支持 → UI 标红「该设备不支持此格式」+「仍要尝试」，不做静默失败。
- 第一版不做 GENA 订阅（DMC 侧），轮询足够。

### 5.2 DMS：发布曲库（发送者）

- 复用 CyberLink `std` 的 `MediaServer`、`ContentDirectory`、`ConnectionManager`、`FileDirectory` 骨架；设备描述、UDN、friendlyName 换成老石头自己的。
- SSDP 广播 `MediaServer:1`；`ContentDirectory.Browse` 返回专辑容器与曲目；DIDL-Lite 含 `dc:title`、`upnp:artist`、`upnp:album`、`res@protocolInfo/duration/size`。
- 曲库结构直接复用 `MusicLibrary`。CyberLink `FileDirectory` 默认按文件系统扫描；实现时用一个 `MusicLibraryDirectory` 适配层把 `MusicLibrary` 的专辑/曲目映射为 `Directory`/`ItemNode`，避免二次扫描。
- 第一版只做 `BrowseDirectChildren`，处理 `StartingIndex`/`RequestedCount`；不做 `Search`/复杂 `SortCriteria`。
- **暴露范围（已确定）：整库对局域网可见**。启用 DMS 时向用户展示说明：「同一局域网内的设备都可以浏览并播放你的曲库」；随机 token 仍防止直链枚举（§9）。
- 媒体 URL **不使用** CyberLink 默认的 `/ExportContent?id=<真实ID>`（无 token、无 Range）。改为：
  - `/dlna/media/<token>/<id>.<ext>`，token 为 128-bit 随机值，映射到当前白名单曲目；无目录列表、无真实路径回显。`<ext>` 由 `MediaProtocolInfo` 生成：源文件名扩展名优先，其次 MIME；保留源文件后缀以兼容只看后缀的老旧渲染器/前级/车机。
  - 支持 `Range: bytes=start-end` → `206` + `Accept-Ranges` + `Content-Range`；HEAD；DLNA 头 `transferMode.dlna.org: Streaming`、`contentFeatures.dlna.org`（`DLNA.ORG_OP=01`，不宣称时间 Seek）。
  - 实现方式：在 CyberLink `MediaServer.httpRequestRecieved` 中拦截该前缀，复用其内置 `HTTPServer`/`HTTPResponse`，只补 Range/token 逻辑（约 200–300 行），不另造 HTTP 服务器。
- 只绑定 Wi-Fi 网卡 IPv4；拒绝非 GET/HEAD、路径穿越；并发 ≤ 8；会话结束立即停止。

### 5.3 DMR：接收投送（接收者，MediaPlayer + DSP 旁路）

```
[控制点] --M-SEARCH (MediaRenderer:1)--> [手机]
[控制点] --GET description.xml--> [手机]
[控制点] --SUBSCRIBE eventSubURL--> [手机]  (GENA)
[控制点] --POST AVTransport: SetAVTransportURI(uri, metadata)--> [手机]
[控制点] --POST AVTransport: Play--> [手机]
[手机] --UrlGuard 校验 uri--> [手机]
[手机] --VettedMediaDataSource: 受控 HTTP + Range--> [来源 DMS/PC/NAS]
[手机] --MediaPlayer 解码/输出（DSP 旁路）--> 系统音频
[手机] --NOTIFY LastChange--> [控制点]
```

#### 5.3.1 设备与服务

- 设备类型 `MediaRenderer:1`；服务：`AVTransport:1`、`RenderingControl:1`、`ConnectionManager:1`；可选 `X_MS_MediaReceiverRegistrar:1`（Windows「播放到」）。
- 设备描述、SCPD 使用自定义文本（CyberLink 自带 `MediaRenderer` 的描述有 `MediaRender:1` 拼写错误，不能直接用）；服务骨架复用 CyberLink `std` 的 `AVTransport`/`RenderingControl`/`ConnectionManager` 类，动作实现由我们补齐。
- 每个角色独立根设备描述（独立 UDN/LOCATION），共享同一 HTTP 端口；只绑定 Wi-Fi 网卡 IPv4。UDN 由 Android ID 稳定派生，不硬编码。
- 只允许一个 DMR 会话；新的 `SetAVTransportURI` 覆盖当前会话。
- DMR 不提供手动输入来源 URL 的入口，只接受标准 AVTransport 投送，保持攻击面最小。

#### 5.3.2 播放路径（MediaPlayer，已定）

**关键安全设计：MediaPlayer 不直接拿网络 URL。** 使用 `VettedMediaDataSource`（实现 `android.media.MediaDataSource`，API 23+）把受控 HTTP 字节流交给 MediaPlayer：

1. `UrlGuard` 校验初始 URI（§9）；预检 `Content-Length` / `Accept-Ranges` / `Content-Type`。
2. `VettedMediaDataSource.getSize()` 返回文件长度（优先 `Content-Length`，否则从 `Content-Range` 解析；无法确定则报错，不猜测）。
3. `readAt(position, buffer, offset, size)` 从当前位置读取；需要跳转时关闭旧连接、发新的 `Range: bytes=position-` 请求；内部维护约 128 KiB 缓冲，减少 Range 请求次数。
4. 所有重定向由我们的 HTTP 客户端处理：最多 3 跳，每一跳都必须通过 `UrlGuard`，最终 URL 必须是私有 IPv4 字面量；MediaPlayer 永远看不到公网地址。
5. 只允许 `http://`，拒绝 `https`/`file`/`ftp`/`data`/`content`；拒绝 userinfo、loopback、link-local、组播、广播、公网地址。
6. 连接/读取超时、单次读取上限、总并发 1；会话结束/切歌立即关闭数据源。

播放控制：

- `MediaPlayer.setAudioAttributes(USAGE_MEDIA, CONTENT_TYPE_MUSIC)`；`Play` 时 `requestAudioFocus(AUDIOFOCUS_GAIN)`，`Stop`/会话结束释放。
- `setDataSource(VettedMediaDataSource)` → `prepareAsync()`；`OnPrepared` 后 `start()`。
- `Seek` → `seekTo(ms, SEEK_CLOSEST)`（API 26+）；`GetPositionInfo` 读 `currentPosition`/`duration`。
- `Pause`/`Stop` 直接映射；`SetNextAVTransportURI` 保存下一曲，`OnCompletion` 时切换（**不保证无缝/gapless**）。
- 音量/静音用 `MediaPlayer.setVolume`（应用内音量，0–100 映射为 0.0–1.0），**不改系统媒体音量**；`GetVolume` 返回应用内值。
- 错误：`OnError` → AVTransport `STOPPED` + `ERROR_OCCURRED`，LastChange 通知控制点，UI 显示原因。
- 播放完成后：有 NextURI 则切歌，否则 `STOPPED`。
- 与本地引擎互斥：`prepare` 前停止本地播放；本地开始播放时先结束 DMR 会话。

#### 5.3.3 AVTransport 状态机

```
NO_MEDIA_PRESENT --SetAVTransportURI(校验通过)--> STOPPED --Play--> PLAYING
PLAYING --Pause--> PAUSED_PLAYBACK --Play--> PLAYING
PLAYING/PAUSED --Stop--> STOPPED
任意 --> TRANSITIONING（Seek/缓冲/切歌瞬间）
错误 --> STOPPED + LastChange Error
```

动作集：`SetAVTransportURI`、`SetNextAVTransportURI`、`Play`（Speed 仅 1）、`Pause`、`Stop`、`Seek`（REL_TIME + TRACK_NR）、`Next`/`Previous`（无队列返回 701）、`GetMediaInfo`、`GetTransportInfo`、`GetPositionInfo`、`GetDeviceCapabilities`、`GetTransportSettings`、`GetCurrentTransportActions`、`SetPlayMode`（NORMAL/REPEAT_*/SHUFFLE）。

实现要求：sealed class + 显式转移表；非法转移返回 UPnPError，不抛异常；状态变化触发 LastChange。

#### 5.3.4 RenderingControl / ConnectionManager / GENA

- `GetVolume`/`SetVolume`（0-100 → MediaPlayer 应用内音量）、`GetMute`/`SetMute`；`InstanceID≠0` 返回 718。
- `GetProtocolInfo` 宣告 MediaPlayer 实际支持的格式（§7.2，M0 设备矩阵确认后定稿）；`GetCurrentConnectionIDs` 返回 `0`，`GetCurrentConnectionInfo` 返回 OK。`PrepareForConnection` 第一版不支持（返回 UPnPError）。
- GENA：`SUBSCRIBE` 解析 `CALLBACK/NT/TIMEOUT` → 返回 SID；状态变化发 `NOTIFY LastChange`；处理续期与 `UNSUBSCRIBE`。优先复用 CyberLink `Service`/`Subscriber`/`NotifyRequest`；若其自动事件不包含 LastChange 负载，则实现薄的 `DmrEventPublisher` 组装 XML 并发送。BubbleUPnP/Windows「播放到」对事件依赖强，M0 必须验证。

#### 5.3.5 局限与用户可见说明（DSP 旁路）

- **DSP 旁路**：DMR 输出不经过 EQ/Crossfeed/BRIR/限制器；DMR 状态页固定显示「接收投送 · DSP 旁路」，README 说明原因（MediaPlayer 输出无法接入现有 DSP 链）。
- **格式收窄**：不再支持 FFmpeg 全集。APE/WavPack/TTA/DSD 等 MediaPlayer 不支持的格式不宣告、不接受；控制点投送时给出明确错误，不静默失败。
- **无无缝切歌**：`SetNextAVTransportURI` 在完成时切换，可能有短暂间隙。
- **无 DLNA 请求头**：MediaPlayer 不提供自定义 HTTP 头的能力；由 `VettedMediaDataSource` 代发，可按需附加 `transferMode.dlna.org`/`getcontentFeatures.dlna.org`。
- 以上取舍由用户 2026-11-01 决策接受；DSP 原生接收路径不再实施，`AudioEngine`/FFmpeg AVIO 改造从 DLNA 里程碑移除。

### 5.4 HTTP 服务拆分

- **设备托管端点**（description.xml / SOAP 控制 / GENA 事件）：复用 CyberLink 内置 `HTTPServer`（`org.cybergarage.http`），不自研。
- **媒体文件端点**：扩展 CyberLink `MediaServer` 的 HTTP 路由（§5.2），只补 Range/206/token/DLNA 头与安全校验；不自研独立 ServerSocket，也不引入 NanoHTTPD。
- 所有 HTTP 服务只绑定 Wi-Fi 网卡 IPv4；不监听 `0.0.0.0`、loopback 或蜂窝地址。M0 需确认 CyberLink `Device.start()` 的 bind 地址列表只包含 Wi-Fi 地址（其 `HTTPServer.open(InetAddress, int)` 支持指定地址）。
- SSDP 收发由 CyberLink core + `MulticastLock` 管理；`NetworkGate` 只负责「是否局域网」的门控与角色切换。

## 6. 模块划分

```
app/src/main/java/com/gu/laoshitou/dlna/
  DlnaService.kt           # DMR/DMC 会话前台服务（mediaPlayback 通知）
  DlnaNotificationReceiver.kt # 通知动作 → 会话回调
  DlnaController.kt        # 正式角色/服务门面（ui 包）：DMS/DMR/DMC 启停、权限状态
  NetworkGate.kt           # Wi-Fi/以太网检测、私有 IPv4、新会话去重与丢失回调
  LanSessionController.kt  # 每次新局域网会话触发角色选择状态
  RolePromptDialog.kt      # 联网时的全局角色对话框（发送者/接收者/保持离线）
  DlnaScreen.kt            # 正式发送者/接收者界面与权限引导
  DlnaPermissions.kt       # API 33+ NEARBY_WIFI_DEVICES 运行时权限契约
  DlnaPreferences.kt       # 角色、发送者子开关等持久化（不自动复用角色）
  DlnaService.kt           # DMS/DMC 前台服务（specialUse）与生命周期
  UrlGuard.kt              # 局域网 URL 白名单校验（所有出站 HTTP 的统一入口）
  GuardedDiscovery.kt      # M-SEARCH + SSDP 解析 + LOCATION 校验（DMC 发现）
  DmcClient.kt             # 受控 DMC：发现/描述解析/AVTransport+RenderingControl SOAP
  # DMC 投送状态、轮询、暂停/停止、事件发布由 DlnaController 承载
  DmsDevice.kt             # MediaServer:1 设备描述/UDN/服务/动态 IP
  DmsContentDirectory.kt   # ContentDirectory Browse + DIDL-Lite
  DmsConnectionManager.kt  # DMS 协议能力宣告
  DmsDescription.kt        # DMS 设备描述
  DmsLibrary.kt            # DMS 曲库接口
  MusicLibraryDmsLibrary.kt # MusicLibrary → DMS 专辑/曲目适配
  DebugDmsLibrary.kt       # debug 测试音曲库（不进 release）
  DmsMediaEndpoint.kt      # token/Range/206/DLNA 头（复用 CyberLink HTTPServer）
  MediaTokenStore.kt       # 128-bit 随机 token、白名单条目映射
  DlnaUdn.kt               # 按角色由 Android ID 派生稳定 UDN
  MediaProtocolInfo.kt     # 扩展名 → MIME + DLNA.ORG_PN + URL 后缀策略
  DmrDevice.kt             # MediaRenderer 设备描述/UDN/服务注册
  DmrAvTransport.kt        # AVTransport:1 状态机（复用 CyberLink Service 骨架）
  DmrRenderingControl.kt   # 音量/静音映射
  DmrConnectionManager.kt  # 协议能力宣告
  DmrEventPublisher.kt     # LastChange、订阅生命周期
  DmrSession.kt            # 接收会话、队列、与本地播放互斥
  VettedMediaDataSource.kt # 受控 HTTP + Range 的 MediaDataSource（MediaPlayer 唯一输入）
  MediaPlayerBackend.kt    # MediaPlayer 状态回调、音频焦点、音量
```

native 侧（`platform-android`）：**本阶段零改动**。不新增 AVIO、不改 `AudioEngine`、不重编 FFmpeg。

UI：

- `ui/CastScreen.kt`：发送者角色下的设备列表/投送控制。
- `ui/DmrStatus.kt` 或主页卡片：接收者角色下的接收状态、来源、曲名、音量、错误、DSP 旁路说明。
- 设置页：角色切换、发送者子开关、DMS 暴露范围说明。

状态：`PlayerState` 增加 `dlnaRole`（NONE/SENDER/RECEIVER）、`dlnaSessionId`、`castDevices`、`castDevice`、`casting`、`castPlaying`、`castPositionMs`、`dmrActive`、`dmrSource`、`dmrTitle`、`dmrPlaying`、`dmrPositionMs`、`dlnaError`。

## 7. 格式与转码策略

### 7.1 DMC/DMS：原文件直传（第一版）

| 扩展名 | MIME | DLNA.ORG_PN |
|---|---|---|
| .flac | audio/flac | FLAC |
| .wav | audio/wav | LPCM |
| .mp3 | audio/mpeg | MP3 |
| .m4a/.aac | audio/mp4 / audio/aac | AAC_ISO / AAC_ADTS |
| .ogg | audio/ogg | OGG |
| .ape/.wv/.tta | audio/x-ape / audio/x-wavpack / audio/x-tta | 无标准 PN |
| .dsf/.dff | audio/dsd（非标准） | 通常不支持 |

投送前 `GetProtocolInfo` 匹配；DSD/APE/WavPack/TTA 提示优先投送 FLAC/WAV/MP3。

- **URL 后缀策略**：DMC/DMS 生成媒体 URL 时统一调用 `MediaProtocolInfo.ensureMediaSuffix` / `mediaFileName`，确保路径带 `.flac/.wav/.mp3/.m4a/.ogg` 等正确后缀（保留 query）；已知后缀不重复追加，未知格式保持原样并记录。很多老旧前级/车机只按后缀判断格式，不能依赖 DIDL 的 protocolInfo。
- 单元测试覆盖：大写扩展名、MIME 参数、已有后缀、无后缀、query 保留、未知格式。

### 7.2 DMR：MediaPlayer 能力集（设备相关）

- `GetProtocolInfo` 只宣告 M0 设备矩阵验证过的格式；保守基线：
  - MP3（audio/mpeg）
  - FLAC（audio/flac）
  - WAV/LPCM（audio/wav）
  - AAC ADTS / AAC in MP4（audio/aac / audio/mp4）
  - Ogg Vorbis（audio/ogg）
  - Opus（audio/ogg / audio/webm，需设备验证）
- 不宣告/不接受：APE、WavPack、TTA、DSD（.dsf/.dff）、ALAC（除非设备矩阵确认）。
- 不用 `MediaPlayer` 的设备差异做静默降级：不支持就返回明确错误并在 UI 显示。
- M0 在目标机型上用 `MediaDataSource` 播放上述格式并测试 seek/duration/暂停恢复，结果写入本文档与测试记录。

### 7.3 转码（可选，后期）

- 用现有 FFmpeg 输出 16/44.1k 或 24/48k WAV（LPCM），兼容性最好、Range/Seek 可直接换算。
- 默认关闭，作为「兼容模式」显式开启；DSD→PCM 必须降频。
- 转码属于 DMS 发送端能力，不影响 DMR 的 MediaPlayer 路径。

## 8. Android 后台与系统限制

- **DMR 接收期间**手机确实在播放，复用 `PlaybackService` 的 `mediaPlayback` 前台服务合法。
- **已实现（M4）**：`DlnaService`（`foregroundServiceType="mediaPlayback"`）承载 DMR/DMC 会话通知（MediaStyle 3 动作），会话停止即退出；进程级 `DlnaMediaSession` 单例由服务与 UI 共享。已实测 FGS 启动/退出与通知。
- **DMC 投送期间**手机不出声，但 DMS 需要持续提供文件。第一版使用独立的 `DlnaService`，前台服务类型 `specialUse`，subtype `dlna-media-server`，并声明 `FOREGROUND_SERVICE_SPECIAL_USE`；会话结束立即停止。（DMS 长驻的 specialUse FGS 仍待实现，当前 DMS 仅在 App 前台有效）
- DMS 长驻仅在前台或投送会话期间运行；不做开机自启/常驻后台。
- `MulticastLock` 引用计数；`WifiLock` 按需。
- 息屏后组播可能被省电策略丢弃，UI 提供「重新扫描」。
- AP 隔离时 SSDP 不可用：DMC 提供手动输入 `IP:端口` 兜底（同样过 UrlGuard）。
- 来电/蓝牙/耳机路由切换：MediaPlayer 使用系统路由；沿用现有 `PlaybackService` 的暂停/恢复策略。
- Wi-Fi 断开：立即停止 DMR/DMS/DMC 会话，回到「等待下一次连接选择」；不自动重连。
- MediaPlayer 的生命周期绑定前台服务，避免进程被杀后控制点状态悬挂。

## 9. 安全与隐私

### 9.1 只允许局域网（代码层强制）

- 只监听 Wi-Fi 网卡 IPv4；蜂窝网络下全部禁用。
- **`UrlGuard` 统一入口**（所有出站 HTTP 必过）：
  - 只接受 `http://`；拒绝 `https`/`file`/`ftp`/`data`/`content`/`rtsp` 等。
  - 主机必须是 **IPv4 字面量**，且属于 `10/8`、`172.16/12`、`192.168/16`；不接受主机名（避免 DNS rebinding）。
  - 拒绝 `localhost`、`127.0.0.0/8`、`0.0.0.0`、`169.254/16`、组播（224/4）、广播、保留地址与公网地址。
  - 拒绝 userinfo、fragment、非法端口；限制 URL 长度。
  - 重定向：最多 3 跳，逐跳校验；任何一跳不合法立即失败。
- **DMR**：`VettedMediaDataSource` 是 MediaPlayer 唯一输入；MediaPlayer 不直接访问网络（§5.3.2）。
- **DMC**：`GuardedDiscovery` 在解析 SSDP 响应前校验 `LOCATION`；描述抓取、`controlURL`、`eventSubURL`、手动 IP 全部过 `UrlGuard`。不使用 CyberLink `ControlPoint` 的自动描述抓取。
- **DMS**：媒体 URL 带 128-bit token；无目录列表、无真实路径回显；服务用完即停；并发上限。
- **GENA 回调**：只向订阅发起方（局域网地址）发 NOTIFY；校验 `CALLBACK` 为 http 且非 loopback。
- XML 解析禁用 DTD/外部实体；DIDL/设备名严格 XML 转义。CyberLink 的 XML 解析入口在 M0 做 XXE 检查，不满足则包一层拒绝 DOCTYPE 的输入流。
- 威胁模型：局域网内设备不完全可信；重点防 SSRF、路径穿越、拒绝服务与未授权文件读取。AP 隔离、ARP 欺骗等链路层攻击不在本方案覆盖范围。

### 9.2 隐私声明与可审计性

- 角色对话框、DMR 状态页、README 三层明示「仅局域网」。
- README 隐私段落改写：
  - 默认离线，DLNA 关闭时零网络行为。
  - 开启后仅加入局域网组播、监听局域网端口、访问局域网内媒体/控制地址；不连接互联网、不上传文件。
  - 声明 INTERNET 权限的原因；DMR 拒绝公网 URL。
  - 说明 DMR 是 DSP 旁路。
- 不引入统计、崩溃上报、远程配置；无互联网出站请求。
- 验收时用 Wireshark 确认除局域网组播/单播外无外部流量。
- 媒体 URL token、UrlGuard、Range 解析、XML 转义、并发上限列为代码审查必查项。

## 10. 测试计划

### 10.1 JVM 单元测试（无需真机）

- `UrlGuard`：私网/公网/loopback/link-local/组播/广播/file:///https/主机名/userinfo/大小写/重定向链/超长 URL。
- `VettedMediaDataSource`：用本地 `ServerSocket` 假 HTTP 服务测试顺序读、跨缓冲读、seek、EOF、Content-Length/Content-Range、206、超时、重定向拒绝、非私有地址拒绝。
- `DmsMediaEndpoint`：Range 单段/开区间/越界/多段拒绝、206 头、HEAD、403/404、token 校验、路径穿越、并发上限。
- AVTransport 状态机：合法/非法转移、Seek 边界、空队列 Next/Previous、MediaPlayer 错误映射、UPnPError。
- RenderingControl：0-100 音量映射、静音、InstanceID。
- DIDL-Lite：生成/解析、特殊字符转义、时长格式。
- `MediaProtocolInfo`：扩展名 → MIME/PN、大小写、未知扩展名；`ensureMediaSuffix`/`mediaFileName` 的后缀补齐、已有后缀不重复、query 保留。
- `MediaToken`：白名单、过期、并发上限。
- 角色模型：网络变化 → 对话框状态机、会话切换清理、不静默复用角色。

### 10.2 集成（真机 + 局域网）

**目标设备（已确定）：大部分安卓手机**。安卓手机通常不内置 DLNA，依赖第三方应用充当渲染器/控制点，测试矩阵以这些应用为主：

- 安卓手机渲染器/控制点：BubbleUPnP（含本地渲染器）、VLC、海贝音乐、飞傲音乐、Hi-Fi Cast、Kodi。
- 小米系设备：验证 MIUI 自带投屏/互传的 DLNA 兼容性；小米电视/盒子。
- 开发验证设备：**Mi 10 Pro**（Android 13 / API 33 / arm64-v8a，USB/Wi-Fi 调试）与 **Xiaomi 17**（Android 16 / API 36 / arm64-v8a）；用于双机 DMC/DMR 与 Android 16 兼容性验证。
- 其他端（PC/音箱作为对端）：foobar2000 UPnP、Windows「播放到」、WiiM/HEOS 类音箱、MiniDLNA/NAS 作为 DMR 来源。

- DMC：Android TV/小米电视、WiiM/HEOS 类音箱、foobar2000 UPnP、VLC、Kodi、BubbleUPnP、Windows「播放到」。
- DMS：电视/音箱浏览、BubbleUPnP/VLC/Kodi 拉流。
- DMR：BubbleUPnP「Play to」、Windows「播放到」、foobar2000、VLC、Kodi；播放/暂停/停止/seek/音量/静音/切歌；来源含手机 DMS、MiniDLNA、NAS。
- DMR 格式矩阵：MP3/FLAC/WAV/AAC/OGG 各 44.1/48/96/192k、16/24-bit；不支持格式的明确错误。
- 安全：公网 URL、重定向到公网、`file://`、loopback、组播地址、超长 URL、异常 Content-Length、恶意 Range、token 猜测。
- 场景：中文/日文文件名、超长路径、单声道、播放中 Wi-Fi 断开/切换、来源断电、息屏、来电、蓝牙/耳机路由切换。
- 角色切换与本地播放互斥矩阵；每次连接都弹窗（含 App 启动时已联网）。
- 30 分钟长跑（内存/线程/电量/端口泄漏）。
- Wireshark 确认无外网流量。

## 11. 里程碑与估算

| 阶段 | 内容 | 估算 |
|---|---|---|
| M0 Spike | CyberLink 2.2.0 在 Android 的构建/R8/Java 11 验证；许可证归档；`GuardedDiscovery` 发现 1 台渲染器；最小 Range 端点 + `VettedMediaDataSource` 用 MediaPlayer 播 WAV/FLAC 并 seek；GENA 订阅验证；CyberLink bind 地址与 XXE 检查 | 3-5 天 |
| M1 DMC MVP | 设备列表/手动 IP、受控发现、连接、播放/暂停/停止/seek/切歌、进度轮询、错误提示 | 4-6 天 |
| M2 DMS MVP | MediaServer 适配、ContentDirectory Browse、ConnectionManager、token + Range 端点、MusicLibrary 映射 | 3-5 天 |
| M3 DMR（MediaPlayer） | 自定义设备/SCPD、AVTransport/RenderingControl/ConnectionManager/GENA、`VettedMediaDataSource`、MediaPlayer 后端、音频焦点、音量、队列、与本地互斥、角色对话框 | 6-9 天 |
| M4 兼容与打磨 | 格式矩阵、协议匹配、不支持提示、网络切换恢复、通知/MediaSession、设置项、README/隐私/THIRD_PARTY_NOTICES、长跑 | 4-6 天 |

合计约 **20-31 个开发日**（不含真机采购与兼容性返工）。本地播放的 seek/position 仍是 README 未完成项，但**不再是 DLNA 的前置条件**，按独立需求排期。

**M0 验收**：CyberLink 许可证与 Android 构建结论明确；在 Mi 10 Pro + 2 台真实渲染器上投送 WAV/FLAC 成功；MediaPlayer 经 `MediaDataSource` 播放局域网 Range 流并可 seek；Wireshark 无外网流量。
**M1 验收**：设备列表 5 秒内出现；连续投送 10 首不崩溃；渲染器断电有明确提示且可回退本地。当前正式 DMC 已在本方 DMR 上验证发现/投送/暂停/停止/进度；第三方渲染器矩阵、Seek/音量 UI 操作与错误恢复仍待补。
**M2 验收**：2 个第三方控制点可浏览并拉流播放、seek。当前 Browse/Range/真实曲库已由主机验证，第三方控制点浏览仍未完成。
**M3 验收**：BubbleUPnP + Windows「播放到」可发现手机、投送 FLAC/MP3 播放；暂停/停止/seek/音量可用；DSP 旁路有 UI 说明；接收时本地播放自动停止；公网/重定向 URL 被拒绝。

> **M3 进度（2026-11-01）**：本机侧已全部通过——主机 SOAP 控制（SetURI/Play/Pause/Seek/Stop/音量/GetPositionInfo）、GENA LastChange、公网 URL 拒绝（714）、DSP 旁路 UI 说明、接收时停本地播放、MediaSession 双向联动、真实 Wi-Fi 切换。**第三方控制点（BubbleUPnP/Windows「播放到」）尚未验收**（VLC/Kodi/ControlDLNA 均不可用，见报告 §2.9）。已修复一个第三方控制点强依赖的缺口：`SetNextAVTransportURI` 预置的下一首在自然播完后**自动切换**（此前只保存不切换；显式 Stop 不触发）。

### 11.1 M3/M4 后续验证项分析（下一步）

| 项 | 现状 | 分析与做法 |
|---|---|---|
| DMC Seek/音量真机操作 | **已通过**：正式 UI Seek 滑杆 → `DMC SOAP Seek 200`、UI 跳至 00:00:18；音量滑杆 → UI 29、DMR `GetVolume=29` | 完成；后续可补非常规值/边界值 |
| 渲染器断电恢复 | **已实现并通过**：连续 3 次轮询失败置 `castOffline` + UI「渲染器已离线，正在重试…」；重启渲染器后 8 秒内自动清除并恢复轮询 | 说明：恢复后不自动重投，需用户重新选曲投送；可后续按需加“恢复后重投” |
| 第三方渲染器兼容性 | 未测（缺设备） | 需真实目标：BubbleUPnP 本地渲染器（免费）、Windows「播放到」/foobar2000 UPnP、WiiM/电视。测试点：发现速度、`GetProtocolInfo` 匹配、`SetAVTransportURI`+`Play`、暂停/停止/Seek、音量、进度回读差异（部分设备 RelTime 为 0，轮询需容错）。建议由用户提供设备/安装 BubbleUPnP 后逐项过 |
| 小米息屏后台冻结 | **已定位并验证**：Greezer 在息屏后冻结 UID（`cgroup.freeze=1`），DMS 入站/出站全断；最近任务锁定、Doze 白名单、`power_pkg_white_list`/`cloud_memory_freeze_whitelist` 均无效 | 用户需把“省电策略”设为“无限制”（设置→应用信息→电量详情）；设置后 12 分钟息屏 DMS 6/6 采样 200。已在 `DlnaScreen` DMS 区域加入「息屏保活」卡片（原因 + 手动路径，纯文字提示，不自动修改/跳转）；README 与用户手册同步；AOSP 侧电池优化豁免仍建议保留 |
| 30 分钟长跑与无外网 | **已通过**：90/90 采样、45 次自动重播、0 异常、Range 全 `206 524288`；socket 表 13 次采样 0 个非私网远端 | Wireshark 原始抓包待主机安装 tshark/镜像端口；长跑已在息屏 + 省电策略=无限制下完成 |

### 11.2 DMR 预加载与队列可行性（回答 2026-11-01 提问）

结论：**可行，分三层实现，建议先做第一层、第二层按需、第三层放在 DMC 侧**。

1. **下一首预置 + 自动切换（已实现）**：`SetNextAVTransportURI`（UPnP 标准）预置下一首，自然播完自动切换；控制点（BubbleUPnP/Windows）本就会发送该动作，这是生态标准路径。
2. **预加载（建议实现）**：维护 1 个 standby `MediaPlayer`，当前曲播放中收到 `SetNextAVTransportURI` 后即 `prepareAsync`（经 `VettedMediaDataSource` 预取头部/首段，局域网内开销小）；当前曲完成时直接 `start()` 切换，缩短切歌间隙。约束：同一时刻至多 1 个 standby 实例；切换后旧播放器释放；预加载失败不影响当前曲，切换时再报错。代价：内存 +1 个 MediaPlayer，无音频输出冲突（standby 不 start）。
3. **多首队列 + 每首固定设置**：UPnP 规范只有单 `NextURI`，多首队列建议**由 DMC（投送端）维护**——DMC 端保存队列与每首设置（如预置音量、是否跳过 DSP 无关项），在 `GetTransportInfo` 轮询到 STOPPED 时下发下一首的 `SetNextAVTransportURI`/`SetAVTransportURI`。这样 DMR 保持标准行为、兼容所有控制点，且「提前设置好下几首」在投送端天然成立。若确需 DMR 侧队列（控制点能力弱），可扩展内部队列，但会偏离标准、增加互操作风险，不推荐第一版做。
**M4 验收**：每次联网角色弹窗与互斥矩阵通过；README/隐私/第三方声明完成；Wireshark 无外网流量；长跑稳定。

> **2026-11-01 真机 M0/M2/DMC/M4 进度**：SSDP 发现、`VettedMediaDataSource` + MediaPlayer 播放/seek/暂停、CyberLink DMR 设备托管、主机 SOAP 控制、GENA 订阅/LastChange 已通过；公网 URL 投送返回 UPnPError 714 并拒绝播放。**双机 DMC→DMR** 通过：小米 17（Android 16）发现并控制 Mi 10 Pro（Android 13）的 DMR，跨机 Range 拉流和 STOPPED/PLAYING 状态正常。**DMS** 已通过 host Browse：测试曲库与真实曲库（8 专辑/DFF）均能返回 token + 正确后缀 URL，HEAD 200、Range 206/416、错误 token 400。**正式发送者 UI** 已接入三个主界面，包含 NetworkGate 自动角色弹窗、权限引导、DMS 启停、**DMC 产品化**（发现/选择/投送/暂停/停止/进度）与**格式匹配提示**（GetProtocolInfo + 仍要尝试/取消）。**M4**：Oboe 共享模式在 Mi 10 Pro 验证通过（Float/48k，混音器输出，有声音欠载 0）；`DlnaMediaSession` 已上线，DMR（DIDL 标题、系统播控键双向联动）与 DMC（投送目标/进度）均验证；发送者 DMS/DMC 子开关持久化。详见 `docs/DLNA_M0_TEST_REPORT.md`。待补：MediaSession 前台服务、真实 Wi-Fi 切换复测、30 分钟长跑、Wireshark 无外网流量、第三方渲染器/控制点矩阵。

> **2026-11-01 21:47 补充（小米 17 真机）**：DMS 长驻 specialUse FGS 在小米 HyperOS 上默认会被 PowerKeeper/Greezer 于息屏后冻结（`cgroup.freeze=1`，UID 网络全断）；最近任务锁定、Doze 白名单、`power_pkg_white_list`/`cloud_memory_freeze_whitelist` 均无效。将“省电策略”设为“无限制”后，12 分钟息屏测试 6/6 采样 DMS HTTP 200。详见报告 §2.19。

> **2026-11-01 22:23 补充**：30 分钟 DMR 长跑已在“省电策略=无限制”+ 息屏下通过（90/90 采样、45 次自动重播、0 异常、Range 全 206）；socket 表替代取证 13 次采样无任何非私网远端。详见报告 §2.20。剩余：Wireshark 原始抓包（缺 tshark）、第三方渲染器/控制点矩阵（缺设备）。

> **2026-11-01 晚间补充（M4 DSP 接入）**：DMR 已从 MediaPlayer/DSP 旁路升级为原生 FFmpeg + DSP 管线（自定义 AVIO，FFmpeg 仍 `--disable-network`），新增原生 seek/position 与 MediaPlayer 回退。真机验证进度/Seek/暂停/音量/自然播完自动切歌/回退；详见报告 §2.21。剩余：真实 FLAC/DSD 样本、DSP 听感/仪器验证、Visual EQ 的 DMR 实时同步、第三方矩阵。

> **2026-11-01 深夜补充（原生 DMR 30 分钟混合长跑）**：90/90 采样，Seek 30/30、切歌 15/15；PSS 平稳 177 MB、Java/Native Heap 无持续爬升；线程 50→54 后平台化；Oboe 延迟 29–33 ms（平均 30.5 ms）；稳态 underrun 0，仅 Seek/切歌瞬间一次性 burst，XRuns 累计 1。CPU 约 25–40% 单核（解码/拉流 + AudioTrack/DSP），属当前 DSP 配置的预期开销。详见报告 §2.22。

> **2026-11-01 深夜补充（真实格式/DSP/Visual EQ）**：真实 FLAC（44.1k）与 DSF（DSD64）原生 DMR 投送通过（FLAC Seek 1:00→1:02，DSF Seek 1:00→1:17，DSF CPU ~80% 单核，属 DSD 重采样预期）；DFF 因 FFmpeg 无 DSDIFF demuxer，回退 MediaPlayer 失败（DMR 不支持 DFF；本地播放器支持）。离线 DSP 仪器验证：EQ 三频点 −23.92/−0.14/−23.94 dB（理论 −24/0/−24）、dry 逐样本一致、Crossfeed −19.9 dB、Limiter −6.00 dBFS，Host CTest 18/18。Visual EQ 在 DMR 启动/Seek/切歌后保持，运行中“应用（实时生效）”日志确认。第三方矩阵按用户要求不再考虑。详见报告 §2.23。

## 12. 风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| 接受 DSP 旁路后 DMR 音质与本地播放不一致 | 用户预期落差 | 角色对话框、DMR 状态页、README 三处明示；不隐藏 |
| MediaPlayer 格式/设备差异 | 部分格式无法接收 | 诚实宣告、明确错误、M0 设备矩阵；不静默失败 |
| MediaPlayer 自定义请求头受限 | 个别 DMS 拉流失败 | `VettedMediaDataSource` 代发 DLNA 头；记录兼容性缺口 |
| `MediaDataSource` 在部分容器/设备上不兼容 | DMR 无法播放 | M0/M3 真机验证；失败时回退本地回环代理（受控 HTTP → MediaPlayer），仍不直连网络 |
| jupnp 许可证不合规 | 无法并入 GPLv3 | 已复核为仅 CDDL-1.0，**不采用**；改用 CyberLink BSD-3（§13） |
| CyberLink std 是参考骨架（Play/Stop/Pause 为空实现） | DMR/DMS 需补大量逻辑 | 只复用 core + 服务骨架；状态机/MediaPlayer/媒体端点自研薄层；M0 验证 |
| CyberLink Android 兼容（`javax.imageio`、MySQL 类、module-info） | 构建/运行问题 | 不使用图像/MySQL 类；R8 keep/dontwarn；M0 构建验证；不加载相关类 |
| CyberLink 默认 HTTP 服务绑定所有网卡 | 超出局域网暴露面 | 只传 Wi-Fi IPv4 bind 地址；M0 验证 `Device.start()`；不满足则覆盖/补丁 |
| 新 INTERNET 权限与「无网络」定位冲突 | 用户信任 | 默认离线 + 角色弹窗 + README 改写 + Wireshark 可审计 |
| DMR 被投送恶意 URL / 重定向 | SSRF、访问公网 | `UrlGuard` + `VettedMediaDataSource` 逐跳校验；单会话；超时/大小限制 |
| 局域网内恶意控制点 | DoS/未授权控制 | token、单会话、并发上限、回调校验；威胁模型见 §9.1 |
| Android 14+ FGS 限制 | 息屏中断 | DMR 复用 `mediaPlayback`；DMS/DMC 用 `specialUse` 并声明；会话结束即停 |
| 厂商省电冻结后台服务（小米 HyperOS 实测） | DMS 息屏后网络全断 | 引导用户将“省电策略”设为“无限制”；DMS 开启时文字提示应用电量详情路径（不自动跳转/修改）；README/用户手册记录。最近任务锁定只防清理，不足以豁免 |
| 组播被过滤/AP 隔离 | 发现不到设备 | MulticastLock；`ssdp:all` 兜底；手动 IP |
| GPLv3 依赖合规 | 合规风险 | CyberLink BSD-3 记录到 `THIRD_PARTY_NOTICES.md`；保留 LICENSE 文本与来源哈希 |

## 13. 依赖决策与开源调研结论（2026-11-01 实测）

**方法**：从 Maven Central 下载 POM 与 `-sources.jar`，逐文件检查版权/SPDX 头；核对上游仓库 LICENSE 与最近提交时间；不运行下载的代码。

### 13.1 jupnp：复核不通过，不采用

| 证据 | 结果 |
|---|---|
| `org.jupnp:org.jupnp:3.0.5` POM `<licenses>` | 仅 `CDDL-1.0` |
| 源码标头（核心 367 + support 193 + android 6 个 `.java`） | **566/566** 为 `SPDX-License-Identifier: CDDL-1.0`；无 LGPL/CDDL 双授权 |
| 2.7.1 源码 | 377 个 `.java` 中仅 5 个同时出现 LGPL 字样，整体仍为 CDDL；不足以改变结论 |
| 与 GPLv3 的兼容性 | CDDL-1.0 与 GPLv3 不兼容（FSF 许可列表），无法并入本项目 |

结论：**jupnp 3.0.5 不可用**；原规划的「M0 复核双授权」已提前完成并否决。不得以「运行时进程隔离」等方式规避，按 GPL 合规保守处理。

### 13.2 CyberLink for Java：采用

| 项目 | 结论 |
|---|---|
| 坐标 | `org.cybergarage.upnp:core:2.2.0`、`org.cybergarage.upnp:std:2.2.0` |
| 许可证 | **BSD-3-Clause**（仓库 `LICENSE.txt`；父 POM `<license><name>BSD</name>`） |
| 活跃度 | GitHub `cybergarage/cybergarage-upnp` 最近提交 2026-10-22；Maven Central 2.2.0 同日发布 |
| 依赖 | core 无运行时依赖；std 仅依赖 core；Java 11 release target |
| 提供 | UPnP 设备/控制点、SSDP、SOAP、GENA、HTTP 服务器、`MediaServer`/`MediaRenderer`/`AVTransport`/`ContentDirectory` 骨架 |
| 合规 | BSD-3 与 GPLv3 兼容；需在 `THIRD_PARTY_NOTICES.md` 保留版权、许可文本与来源 |
| 校验和（SHA-256） | core jar `1183e3bb8aba9913b3ccedd4e902a011ef9f6fbcad91059eb334ee5a181fbb71`；std jar `26049283ee1170cf7dc10716951066d0e1aa5d542a5ba949043489a3c601fe58`；core sources `40e9868c883085ec07f696a5377415a5b5247e45342c846ce337a57b9b463b7f`；std sources `77b310d065f4eadd353a9bb98587d9003b3ee361333ccc3274006b7157d2e260` |

**已知缺口（必须自研/适配，不算重复造轮子）：**

- `std` 的 `AVTransport` 只保存 URI，`Play`/`Stop`/`Pause` 为空实现；状态机、LastChange、MediaPlayer 后端需补齐。
- `std` 的媒体导出 `/ExportContent?id=...` 无 Range/206、无 token；改为自研 token + Range 端点（复用 CyberLink `HTTPServer`）。
- `std` 的 `MediaRenderer` 描述里 `MediaRender:1` 拼写错误；使用自定义描述/SCPD。
- `std` 的 `ImageIOFormat` 引用 `javax.imageio`（Android 无此类），MySQL/MythTV 类不在 DMS 路径；不加载、必要时 R8 排除。
- CyberLink `Device.start()` 可能绑定所有网卡；只传 Wi-Fi IPv4 地址并验证。
- **已解决（M0 真机）**：CyberLink `JaxpParser` 在 Android 上因 Apache JAXP feature 失败，改用 `AndroidXmlParser`（仅可移植配置、拒绝 DOCTYPE/外部实体）；见 `docs/DLNA_M0_TEST_REPORT.md`。
- **已解决（M0 真机）**：CyberLink 读取 `sendEvents` 属性而标准 SCPD 使用子元素，`LastChange` 需显式 `setSendEvents(true)`，否则 GENA 不发送事件。
- **已规避（M2 真机）**：CyberLink `getContentRange()` 只正确解析 `bytes start-end/total`，标准 `Range: bytes=start-end` 会被当成 0-0 并返回整文件；`DmsMediaEndpoint` 先自行解析单段 Range 再规范化请求头，错误响应前清空请求头以避免被重新应用。

### 13.3 备选（仅当 CyberLink 不满足 M0 时）

| 库 | 结论 |
|---|---|
| Ball UPnP（Apache-2.0，2025 活跃） | 许可证友好，但社区成熟度低（1 star/0 fork），DLNA profile 需更多自研；作为备选 |
| Cling 2.x（LGPL-2.1+） | 许可证兼容，但不在 Maven Central、2020 后停更；Android/Jetty 依赖重；不作为首选 |
| NanoHTTPD | 2016 年后停更；媒体端点安全要求特殊；不采用 |
| 自研完整 UPnP 栈 | 重复造轮子，违反本轮决策；仅在开源栈全部不满足时兜底 |

### 13.4 自研范围（收敛为安全敏感薄层）

- `UrlGuard`（局域网 URL 白名单/重定向校验）；
- `GuardedDiscovery`（DMC 发现 + LOCATION 校验）；
- `VettedMediaDataSource`（受控 HTTP + Range → MediaPlayer）；
- DMR 状态机/LastChange/MediaPlayer 后端适配；
- DMS token + Range 媒体端点；
- 角色对话框/NetworkGate/DlnaService。

协议栈（SSDP/SOAP/GENA/HTTP 服务）、设备/服务模型均复用 CyberLink。

## 14. 代码规范

新代码遵循仓库既有约定，并明确以下条目：

- **语言风格**：Kotlin 官方风格（`gradle.properties` 已设 `kotlin.code.style=official`）；C++ 本阶段不改动，沿用 `.clang-format` 与 `-Wall -Wextra -Werror`；新文件带 SPDX 头 + 版权行，与现有文件一致。
- **包结构**：DLNA 代码统一在 `com.gu.laoshitou.dlna` 包，UI 在 `ui/`；第三方适配器与业务逻辑分层，不把 CyberLink 类型泄漏到 UI/`PlayerState`。
- **线程模型**：网络 IO（HTTP/SOAP/SSDP 回调外）一律在协程 `Dispatchers.IO`，禁止主线程阻塞；`VettedMediaDataSource.readAt` 由 MediaPlayer 原生线程调用，必须线程安全、避免热路径分配；音频回调不分配、不加锁、不日志（既有约定）。
- **状态机**：AVTransport/DMR 会话用 sealed class + 显式转移表；非法转移返回 UPnPError，不抛异常。
- **错误处理**：UPnPError 码集中定义；用户可见文案全中文、与现有 UI 措辞一致；日志用 `android.util.Log` + 模块 TAG；不记录媒体 URL 的 token/完整路径。
- **网络地址**：HTTP Server/设备描述/媒体 URL 的局域网 IP 必须在启动时通过 `NetworkAddresses.findLan()` 动态解析，禁止写死或跨会话缓存；网络切换由 NetworkGate 终止会话并重新解析。
- **媒体 URL**：DMC/DMS 必须使用 `MediaProtocolInfo` 保证正确后缀；不得发送无后缀的媒体 URL 给渲染器。
- **安全约束即代码审查项**：UrlGuard、token 校验、XML 转义、Range 边界、并发上限、bind 地址逐条核对。
- **测试**：纯逻辑（状态机/DIDL/UrlGuard/Range/DataSource/角色会话）必须有 JVM 单测，与 `core-dsp/tests` 的 host 测试风格对齐；真机验证记录写入 `docs/`。
- **第三方代码**：不修改 CyberLink 源码；通过子类/适配器扩展。若确需补丁，单独记录并保留 BSD-3 声明。新增依赖与许可证同步更新 `THIRD_PARTY_NOTICES.md`。
- **提交**：沿用仓库提交风格（`feat: 新增文件夹导入; bump version to 1.1` 一类），按里程碑分提交，单次提交可编译。

## 15. 已确定与待确认

**已确定：**

1. DMR 使用 Android MediaPlayer，接受 DSP 旁路；M3 为 DMR（MediaPlayer），不做 native AVIO/AudioEngine 改造。
2. DMR 只允许局域网来源，UI/README 明示；`VettedMediaDataSource` 强制逐跳白名单。
3. 角色模型：每次连接局域网第一时间弹窗，发送者/接收者二选一，另设「保持离线」；每次连接都需确认（可默认高亮上次角色，但不静默复用）；一次连接一个角色，杜绝 DMC/DMR 冲突；对话框附说明。
4. 协议栈采用 CyberLink 2.2.0（BSD-3-Clause）；jupnp（CDDL-1.0）复核不通过，不采用。
5. 全程遵守代码规范；先复用开源、后自研安全薄层。
6. 目标设备：大部分安卓手机（第三方 UPnP 应用充当渲染器/控制点）；开发验证设备为 Mi 10 Pro（Android 13 / API 33）与 Xiaomi 17（Android 16 / API 36）。
7. DMS 暴露范围：整库对局域网可见，启用时向用户展示说明。
8. DMR 不提供手动 URL 入口，只接受标准 AVTransport 投送。
9. M0 真机链路已通过（Mi 10 Pro + Xiaomi 17 + 主机控制点）；详见 `docs/DLNA_M0_TEST_REPORT.md`。
10. M2 核心链路已通过：DMS Browse（测试曲库与真实 8 专辑曲库）、token URL + 正确后缀、HEAD 200、Range 206/416、错误 token 400；第三方控制点实际浏览仍待补。
11. 正式发送者 UI 已接入极简/沉浸/高保真三个主界面：角色选择、仅局域网说明、All Files Access / Nearby Wi-Fi 权限引导、DMS 启停、DMR 最小启停；主清单已加入网络权限。
12. VLC 3.7 Beta 的 UPnP Browse 在小米 17 上因组播发现超时未列出 DMS；同网段主机 SSDP 正常，VLC 直接播放 DMS FLAC 已验证 Range 拉流。第三方浏览验收待 BubbleUPnP/Windows「播放到」。
13. NetworkGate + 全局角色弹窗已接入：小米 17 启动 App 即弹出角色选择，发送者选择后进入正式 DLNA 页面；**真实 Wi-Fi 断开/重连复测通过**（断开停服务，重连后未选角色前端口不可达、弹窗重新出现）。
14. DMC 产品化核心已通过：正式 UI 发现/选择渲染器、专辑曲目选择、投送、暂停/继续、停止、1 秒进度轮询；投送媒体由 DMS token 端点提供（需先启动曲库共享）；投送前停止本地播放。Seek/音量控件已接入，本轮未逐项 UI 操作；第三方渲染器矩阵未测。

**待确认：**

1. DMR 格式宣告的最终列表（M0 设备矩阵后定稿；默认 §7.2 保守集）。
2. DMS/DMC 前台服务是否长期使用 `specialUse`，还是第一版仅在前台运行（影响后台投送体验与 Play 审核）。
3. 是否允许私有网段主机名（默认仅 IPv4 字面量，最安全）。
4. NetworkGate 自动角色弹窗已接入并在 App 启动时验证；真实 Wi-Fi 断开/切换后的停止与重新弹窗仍待设备复测。
5. M2 第三方控制点浏览：VLC 3.7 无 MulticastLock 导致 UPnP 发现超时；ControlDLNA targetSdk 19 无法在 Android 16 安装；Kodi 21.3 Android 版无 UPnP 客户端入口；DroidDLNA 无 release。需 BubbleUPnP、Windows 桌面控制点或用户提供控制点 APK。
