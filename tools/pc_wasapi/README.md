# PC WASAPI 探测/播放工具

用于在 Windows 上检查 USB DAC/小尾巴的端点与独占模式支持，并把
`hifi_offline` 生成的 WAV 直接以 WASAPI 独占模式送到指定设备，做主观试听。

## 运行环境

- Windows 10/11
- PowerShell 7（`pwsh`，脚本通过 `Add-Type` 编译 C#，无需安装 .NET SDK）
- `tools/pc_wasapi/WasapiTest.cs`、`run.ps1`

## 探测设备与支持格式

```powershell
pwsh -NoProfile -File tools/pc_wasapi/run.ps1 probe
```

输出示例（本机 lifeme 小尾巴）：

```
[0] 耳机 (lifeme Hi-Fi Auido Pro)   <-- default (console)
    mix: 48000 Hz, 2 ch, 32-bit float
    exclusive: 44100 Hz: 16-bit, 24-bit, 32-bit |
               48000 Hz: 16-bit, 24-bit, 32-bit |
               96000 Hz: 16-bit, 24-bit, 32-bit |
               192000 Hz: 16-bit, 24-bit, 32-bit |
               384000 Hz: 16-bit, 24-bit, 32-bit
```

`exclusive` 行是 WASAPI 独占模式的 `IsFormatSupported` 结果；`mix` 是 Windows
共享混音格式（通常是 48 kHz float32）。

## 播放 WAV

```powershell
# 独占模式，指定设备名子串（不区分大小写）
pwsh -NoProfile -File tools/pc_wasapi/run.ps1 play `
  --file build-host/listen_excerpt_eq.wav `
  --device lifeme

# 强制共享模式（要求文件采样率等于 mix 采样率）
pwsh ... play --file x.wav --device lifeme --shared

# 调整电平（dB）
pwsh ... play --file x.wav --device lifeme --gain-db -6
```

播放器行为：

1. 优先尝试 **WASAPI 独占模式**，格式顺序 float32 → PCM32 → PCM24 → PCM16，
   采样率取 WAV 文件采样率。
2. 独占失败时退回共享模式（仅当文件采样率等于 mix 采样率）。
3. 支持 PCM 16/24/32-bit 与 float32/64 WAV，单声道会复制成立体声。
4. 采用轮询方式写 `IAudioRenderClient`，200 ms 缓冲；播放结束后等待队列清空。

## 注意

- 6 单元动铁耳机灵敏度高，先用低电平（例如 -20 ~ -30 dBFS）试听。
- 独占模式下 Windows 音量/静音是否生效取决于 DAC 驱动；先确认系统音量。
- 本工具不做重采样；需要不同采样率时先用 `hifi_offline --rate` 转换。
- 这不是声学测量工具。没有测量麦克风/耦合腔时，只能做主观听感与格式验证。
