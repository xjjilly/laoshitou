# PC 功能移植到 Android — 可行性评估

> 评估日期：2026-10-29
> 结论：**大部分可移植，且成本不高**。核心原因是两端共用同一份 `core-dsp`
> （`app/src/main/cpp/CMakeLists.txt` 直接 `add_subdirectory(core-dsp)`），
> FIR、多段压缩、Peq、BRIR 卷积等实现已经在安卓包里编译，缺的只是
> **参数接线 + JNI + Compose UI**。

---

## 1. 现状差异（已核对代码）

### 1.1 Android 已有的能力（无需移植）

- 16 段可视化 PEQ 编辑器（拖拽、选中读数、Preamp、自动 Preamp、重置、应用）
- AutoEQ 配置文件导入与 16 段拟合
- Crossfeed + 强度、M/S 声场宽度、响度补偿
- BRIR 卷积（但**没有内置素材**，需用户自己放 WAV）
- 限幅器、采样率策略（同族升频 / 强制输出率 / USB DAC 192k）
- Oboe 独占优先与共享回退、Bit-perfect 直通
- 曲库扫描、播放列表（顺序/循环/随机）、拖动进度、媒体会话/前台服务

### 1.2 PC 有、Android 没有的能力

| # | PC 功能 | Android 现状 | 代码位置 |
|---|---|---|---|
| 1 | 10 个场景预设 | 只有 4 个（录音棚/深夜动铁/近场书架/Live） | `ui/PlayerState.kt` SoundPreset、`MainActivity.kt` applyPreset |
| 2 | 用户预设保存/删除 | 无（只有编辑值自动保存） | — |
| 3 | 频响面板精确数值气泡 | 只有曲线下方一行选中读数 | `ui/HifiDashboardScreen.kt` EqEditorCard |
| 4 | ±0.5dB 加减号微调 | 无 | — |
| 5 | EQ **播放中实时生效** | **必须重新播放**（写 profile 文件，下次播放生效） | `MainActivity.kt` applyEqEditor |
| 6 | 多段压缩 | 无（只有单段限幅器） | core-dsp 已有 `MultibandCompressor`，未接线 |
| 7 | 线性相位 FIR EQ | 无（即使用不了） | core-dsp 已有 `FirEqProcessor`，未接线 |
| 8 | 内置 BRIR 测试素材 | 无（`assets/` 只有 licenses） | — |
| 9 | 波形/频谱 + 皮肤/动画 | 无（只有频响曲线画布） | — |
| 10 | 系统接管（处理系统所有声音） | 无 | 平台机制不同，见 §4 |

---

## 2. 逐项可行性

| 功能 | 工作量 | 难度 | 说明 |
|---|---|---|---|
| **10 个场景预设** | 0.5–1 天 | 低 | 仿照现有 NightIem 的写法，为每个预设生成 16 段 profile 文本；联动 crossfeed/声场/响度。多段压缩/线性相位两项要等 #6/#7 接线后再纳入预设 |
| **用户预设保存/删除** | 1–2 天 | 低 | 复用 `player_settings` SharedPreferences，存 JSON（名称 + 16 增益 + preamp + 开关）；预设区加「保存当前/删除」+ 命名对话框 |
| **数值气泡 + ±0.5dB** | 0.5–1 天 | 低 | 已有拖拽与选中索引，Canvas 里补一个圆角气泡 + 两个按钮即可 |
| **曲线保存按钮** | 0.1 天 | 很低 | 已有自动保存，加显式按钮与「已保存」提示 |
| **EQ 实时生效** | 1–2 天 | 中 | core-dsp 的 `DspChain::update_peq()` 本来就为运行中热更新设计（无分配、无锁）；加一个 JNI `updateEq(gains, preamp)`，在控制线程构造 PeqConfig 后调用。注意：线性相位模式下会顺带重设计 2048 抽头核，仍在控制线程，安全 |
| **多段压缩** | 1–2 天 | 中低 | 四处接线：`PlaybackOptions` 加字段 → `configure_processing()` 填 `chain_config.multiband` → JNI `startFile` 加参数（或新方法）→ Hifi 界面加开关与三个滑块。CPU 开销很小 |
| **线性相位 FIR** | 1–2 天 | 中 | 同样四处接线。`FirEqProcessor` 已在共享库中且半速 bug 已修复；>96kHz 自动降级逻辑已存在。主要工作是真机验证 |
| **内置 BRIR 素材** | 0.5 天 | 低 | `samples/ir/*.wav` 放进 `assets/ir/`，首次启动复制到 `filesDir`，IR 列表默认展示；注意随包附 SADIE-II / IoSR 许可 |
| **波形/频谱 + 皮肤/动画** | 3–5 天 | 中高 | 需要原生侧加无锁 tap、JNI 轮询、Compose Canvas 绘制；纯视觉，建议最后做 |
| **系统接管** | 1–2 周且受限 | 高 | 不建议，见 §4 |

**推荐移植集**（#1–#8）合计约 **5–9 人日**，另加真机测试 2–3 天。

---

## 3. 关键风险与对策

### 3.1 线性相位 FIR 跑在音频回调里

Android 的 DSP 链运行在 **Oboe 回调**（`platform-android/src/OboeOutput.cpp` 的
`onAudioReady` → `chain->process()`），而 PC 端特意把 DSP 放在了生产者线程。

- 开销估算：每 512 帧约 4 次 1024 点 FFT + 8K 复数乘加，48kHz 下每秒不到
  10 MFLOP——现代 arm64 很轻松；主要是低端机 + 96kHz + 小缓冲的抖动风险。
- 对策：保留 >96kHz 自动降级；真机测低端机；必要时在 FIR 模式下提高 Oboe 缓冲。
- **延迟**：+1536 帧（约 32ms @48k）。听歌无感，但看视频/打游戏会有轻微
  音画不同步（视频 App 不会补偿）。需要在 UI 提示。

### 3.2 多段压缩会取代限幅器

与 PC 行为一致（`limiter_enabled_ = limiter && !multiband.enabled`）。
接线时要保证 Android 现有的限幅器开关语义不变。

### 3.3 UI 落点

Android 有三套界面（极简/沉浸/高保真），EQ 编辑器只在高保真里。
建议新功能先全部落在**高保真**界面；极简/沉浸保持现状。

### 3.4 预设的数据一致性

若希望 PC 与 Android 的预设/用户预设能互相导入，建议直接把 PC 的
`laoshitou_pc.json` 预设 schema 作为两端公共格式（name + 16 gains + preamp +
crossfeed/width/stereo/loudness/multiband/linear_phase）。

---

## 4. 不建议移植的部分

| PC 功能 | 原因 |
|---|---|
| **系统接管（VB-Cable + WASAPI 采集）** | Android 没有等价物。Android 10+ 的 `AudioPlaybackCapture` 必须由用户通过 MediaProjection 授权，且应用可以拒绝被捕获（流媒体/DRM 应用普遍禁止），交互与 PC 完全不同，收益低、限制大 |
| **VST3 插件** | 平台不支持 |
| **WASAPI 独占 / VB-Cable / 设备枚举** | Windows 专属；Android 已有 Oboe 独占与 USB DAC 处理 |

---

## 5. 建议实施顺序

1. **接线先行**：`PlaybackOptions` + JNI + `configure_processing` 同时加
   `eq_linear_phase` 与 `multiband`（一次改完 JNI 签名，避免反复动 22 参数接口）。
2. **多段压缩 UI**（依赖 1）。
3. **线性相位开关 + 真机验证**（依赖 1）。
4. **10 个场景预设**（可引用 2/3 的新字段）。
5. **用户预设保存/删除**（schema 与 PC 对齐）。
6. **EQ 面板气泡 + ±0.5dB + 保存按钮**。
7. **EQ 实时生效**（JNI `updateEq`）。
8. **内置 BRIR 素材**。
9. （可选）波形/频谱。

## 6. 验证方式

- 主机侧：core-dsp 现有 18 个测试已覆盖 FIR 帧块连续性、多段压缩、Peq。
- 真机：重点测 48k/96k/192k 下的欠载计数（状态屏）、线性相位开关前后时长
  与音高、切歌是否爆音、EQ 实时更新是否引起 xrun。
- 回归：不改 PC 端代码的共享部分；Android 侧新增字段默认关闭，保证老配置兼容。

---

## 7. 实施状态（2026-10-29 已完成）

按第 5 节顺序落地：

- [x] **接线**：`PlaybackOptions` 增加 `eq_linear_phase` / `multiband`；
  `configure_processing()` 填入 `DspChainConfig`；JNI `startFile` 增加 5 个参数
  （Kotlin 描述符 `(…ZZDDD)Z`，与 C++ 逐项核对一致）。
- [x] **多段压缩**：高保真「高级设置」增加开关、低/高分频与低频阈值。
- [x] **线性相位 FIR**：EQ 卡片与高级设置各一个开关；显示「运行中 / 已降级 /
  未生效」状态；`>96kHz` 自动降级并提示。
- [x] **场景预设**：新增 8 个（FPS、语音/播客、流行、摇滚、古典/原声、低音增强、
  高音/空气感、电影），加上原有 4 个共 12 个；预设联动 EQ/Crossfeed/声场/
  多段压缩/线性相位。低音增强带 −3dB Preamp，避免削波警告。
- [x] **用户预设**：保存 / 应用 / 删除，SharedPreferences JSON 持久化。
- [x] **EQ 面板**：选中频点浮动精确数值气泡；`−` / `+` 按钮 ±0.5dB；
  显式「保存」按钮。
- [x] **EQ 实时生效**：JNI `updateEq` → `DspChain::update_peq`；Direct 模式下
  自动回退为「下次播放生效」。`DspChain::update_peq` 现在会启用 EQ 级，
  修复了「启动时 EQ 关闭、运行中再调不生效」的问题（PC 端同样受益）。
- [x] **内置 BRIR**：`assets/ir/` 打包 9 个 WAV（656 KB），首次启动复制到
  `Android/data/com.gu.laoshitou/files/IR/`，IR 列表直接可见；随包附
  SADIE-II / IoSR 许可。
- [ ] **波形/频谱可视化**：未实施（优先级低）。

验证结果：

- `:app:assembleDebug` / `:app:assembleRelease` 通过；release 签名证书与线上
  v1.1 一致（SHA-256 `eb14ff08…`），可覆盖升级并保留用户数据。
- core-dsp 主机测试 **18/18** 通过。
- 新增 JNI 符号 `updateEq` / `playbackLinearPhaseActive` / `playbackFirDegraded`
  在 `.so` 中可见，Kotlin 描述符（27 参数）与 native 参数逐一匹配。
- 改动文件已通过 `clang-format` 检查。
- **真机实测（小米 10 Pro / Android 13 / MIUI）**：
  - v1.2 覆盖安装成功、启动无崩溃；9 个内置 BRIR 自动复制到 IR 目录。
  - 播放正常（192k 源 → 48k 输出），新增 JNI 参数无异常。
  - EQ 拖拽浮出精确数值气泡；`−` 微调 18.0 → 17.5 dB 正确。
  - 播放中点「应用」→ **EQ 0 段 → 16 段**，欠载保持 0，CPU 12–15%。
  - 线性相位开启后重播 → 状态「运行中（约 35ms 延迟）」，欠载 0。
  - 多段压缩开启后重播 → 限制器自动让位（状态屏「限制器=否」），欠载 0。
  - 用户预设保存 → 强制停止并重启应用后仍在（测试预设已删除）。
  - 场景预设「FPS 听声辩位」生效：Crossfeed 关闭、声场 125%。
  - 真机发现并修复：EQ 卡片按钮行过挤（应用按钮被压成竖条）、
    预设区原先只出现在极简/沉浸界面（已抽成公共 `PresetSection`，HiFi 也可用）。

仍需真机覆盖：96k/192k 输出、USB DAC 独占、切歌爆音、长时间发热。
