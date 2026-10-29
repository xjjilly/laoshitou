# 离线 DSP 测试方案（WAV → DSP → REW）

## 1. 目的

用 PC 离线工具复用手机端同一份 `core-dsp` 代码，在不连接 Android 设备、
不依赖实时音频回调的情况下验证：

- PEQ / Preamp（PK、LSC、HSC）
- 搁架 Q / 斜率 S、二阶 / 四阶级联
- 交叉馈送（libbs2b weak / medium / strong）
- 限幅器（look-ahead limiter）
- 重采样（44.1/48/192 kHz，及 DSD 解码后的 352.8 kHz 路径参数）

离线工具不包含 Oboe、Android 服务、FFmpeg 解码和 DSD 容器解析；DSD/DFF
播放仍需在手机上单独验证。

## 2. 被测链路

```
WAV (PCM 16/24/32 或 float32/64, mono/stereo)
  -> [可选] libsamplerate (自动：PCM ≤192k 用 BEST，DSD/更高用 MEDIUM)
  -> PEQ / Preamp (PK/LSC/HSC/HPF/LPF, order 1 或 2, Q 或 S)
  -> loudness (音量相关低频/高频补偿)
  -> M/S 声场宽度
  -> crossfeed (libbs2b 预设或 0-100% 连续强度)
  -> limiter (可选，-1 dBFS look-ahead)
  -> float32 WAV
```

手机端 `AudioEngine` 的顺序与此一致：解码 → 重采样到目标率 → DSP chain。
离线工具不应用播放音量/淡入淡出。

## 3. 工具与构建

源码：`tools/hifi_offline.cpp`，构建目标：`hifi_offline`。

```sh
cmake -S . -B build-host -G Ninja \
  -DCMAKE_C_COMPILER=clang \
  -DCMAKE_CXX_COMPILER=clang++ \
  -DCMAKE_EXE_LINKER_FLAGS=-static
cmake --build build-host

# 可执行文件
build-host/tools/hifi_offline
```

常用参数：

| 参数 | 说明 |
|---|---|
| `--input <wav>` | 输入 WAV（PCM 8/16/24/32 或 float 32/64，单/双声道） |
| `--output <wav>` | 输出 float32 WAV（始终立体声） |
| `--rate <hz>` | 输出采样率，默认与输入相同 |
| `--eq <profile>` | AutoEQ txt / CSV；不填则不加载 EQ |
| `--order <1\|2>` | 1 = 二阶 biquad，2 = 两级级联（四阶） |
| `--slope <S>` | 搁架改用 RBJ 斜率 S（0.05–1.0），不填则用 Q |
| `--crossfeed <off\|weak\|medium\|strong>` | 默认 off |
| `--crossfeed-width <0..1>` | 连续 Crossfeed 强度（覆盖预设） |
| `--width <0..1.5>` | M/S 声场宽度（0=单声道，1=原始，1.5=加宽） |
| `--loudness-volume <0..1>` | 固定响度补偿音量（1=关闭） |
| `--limiter` | 开启限制器（默认关闭，便于测量） |
| `--dry` | 完全旁路重采样和 DSP，用于基准/空测 |

示例：

```sh
# 与手机默认相近：处理模式 + weak 交叉馈送 + 限制器
hifi_offline --input sweep.wav --output sweep_wet.wav \
  --eq samples/eq_demo.csv --crossfeed weak --limiter

# 只用 EQ，验证频响
hifi_offline --input sweep.wav --output sweep_eq.wav \
  --eq samples/eq_test.txt

# 四阶 + 搁架斜率
hifi_offline --input sweep.wav --output sweep_eq4.wav \
  --eq samples/eq_demo.csv --order 2 --slope 0.7

# 重采样
hifi_offline --input sweep_192k.wav --output sweep_48k.wav --rate 48000

# 空测基准（float32 输出与输入样本一致）
hifi_offline --input sweep.wav --output sweep_dry.wav --dry
```

## 4. 测试信号

| 信号 | 参数 | 用途 |
|---|---|---|
| 对数扫频 | 20–20,000 Hz，8–10 s，-20 dBFS，48 kHz | 频响、EQ、阶数、斜率 |
| 对数扫频 | 20–20,000 Hz，3 s，192 kHz | 重采样 192k→48k |
| 左声道单音 | 100 Hz / 2 kHz，-20 dBFS，3 s | 交叉馈送 |
| 1 kHz 正弦 | 0 dBFS（振幅 0.999） | 限制器 |
| 粉红噪声 | -20 dBFS | 听感/波形 sanity check |

电平建议 -20 dBFS；若 EQ 有提升，先看工具打印的
`Peak after processing`，避免削波或触发限制器。

## 5. REW 操作步骤

### 5.1 生成扫频与处理

1. REW → **Generator → Measurement Sweep**：
   - Start 20 Hz，End 20,000 Hz，Length 1M samples（或 ≥8 s）；
   - Level -20 dBFS；
   - **Save to file** 保存为 `sweep.wav`。
2. 用工具生成 `sweep_dry.wav`（`--dry`）和 `sweep_wet.wav`（加 `--eq`）。
3. 保持 dry/wet 的采样率、长度一致；若做了 `--rate` 转换，见 5.4。

### 5.2 频响与 EQ（推荐：扫频反卷积）

1. REW → **File → Import → Import Sweep Recordings...**
2. 选择 `sweep_wet.wav`，按 REW 提示填入扫频参数
   （Start 20、End 20000、Length 与生成时一致）。
3. REW 会显示反卷积后的频率响应/脉冲；打开 **All SPL**。
4. 平滑设为 **1/48 octave**；用光标读取 20/50/100/1k/10k/20k Hz。
5. 需要对比目标曲线时，把 EQ profile 的理论响应导入/叠加，或用
   **Trace Arithmetic A/B**（见 5.3）。
6. **File → Export → Export measurement as text** 保存结果。

### 5.3 双 FFT 交叉验证（可选）

1. REW → **File → Import → Import Audio Data...**，分别导入 dry 和 wet。
2. 两个测量使用相同的 FFT 长度/窗函数；
3. **All SPL → Trace Arithmetic**，操作 `A / B`（wet / dry）得到传递函数；
4. 检查 20 Hz–19 kHz 的幅度是否与 5.2 一致。

> 扫频信号下 A/B 的频响是有效的（LTI 系统 Y(f)=H(f)X(f)），但要求两文件
> 长度一致、无削波/非线性；边界处（接近 20 kHz）受窗函数影响，读数仅作参考。

### 5.4 重采样验证

- 输入 192 kHz 扫频，工具 `--rate 48000` 输出 48 kHz；
- REW 直接按工具输出的 48 kHz 文件做扫频分析（不需要 dry 参考）；
- 读取 20 Hz–19 kHz 的偏差（应 ≤ ±0.1 dB）；
- 用 30 kHz 单音检查镜像/阻带：本机实测 192k→48k 后约 -82 dBFS
  （相对输入 -23 dBFS，抑制约 59 dB）。
- 工具/App 的“自动”质量：PCM ≤192k 用 BEST（192k→48k 实测手机端
  欠载 0）；DSD/352.8k 及以上用 MEDIUM。可用 `--quality` 强制对比。

### 5.5 交叉馈送

1. 生成左声道单音（100 Hz 和 2 kHz），分别用
   `--crossfeed off/weak/medium/strong` 处理。
2. REW 导入音频数据，分别查看 L/R 声道在对应频率的峰值；
3. 计算 `R/L`（dB）。本机实测（48 kHz）：

| 预设 | 100 Hz R/L | 2 kHz R/L |
|---|---|---|
| off | < -200 dB | < -200 dB |
| weak | -4.62 dB | -15.87 dB |
| medium | -6.11 dB | -17.18 dB |
| strong | -9.62 dB | -20.82 dB |

> 预设来自 libbs2b 标准参数；数越负表示右声道相对越小。
> 三种预设应明显不同且可重复；2 kHz 的串音应明显低于 100 Hz。

### 5.6 声场与响度

- **M/S 宽度**：`--width 0` 应得到 L=R（单声道）；`--width 1` 与 dry 逐点一致；
  `--width 1.5` 时 L-R 差异增大。M/S 矩阵不改变 L+R，所以单声道兼容性不变。
- **Crossfeed 强度**：固定 100 Hz 左声道单音，分别用 `--crossfeed-width 0.2`
  和 `0.9`；R/L 应明显不同，用 REW 或脚本记录。
- **响度补偿**：`--loudness-volume 0.2` 对比 `1.0`，扫频在 60 Hz 附近应有
  约 +3 dB、10 kHz 附近约 +1.5 dB，1 kHz 基本不变。App 内该功能随系统音量
  实时变化；离线工具使用固定音量，便于测量。
- **单声道兼容**：工具会打印 `Mono compatibility: x dB`。0 dB 表示完全相关，
  -3 dB 为不相关立体声；低于 -6 dB 会警告，需检查 Crossfeed/相位/混响。

### 5.7 限制器

1. 生成 0 dBFS、1 kHz 正弦；
2. 用 `--eq samples/eq_test.txt` 处理（+6 dB PK 与 -3 dB preamp 后峰值约
   +3 dBFS）；
3. 不加 `--limiter` 时，工具应打印峰值 > 0 dBFS 并给出警告；
4. 加 `--limiter` 时，峰值应为 -1 dBFS 附近（本机 -0.98 dBFS），
   波形不应削平（限制器为 look-ahead）；
5. REW 导入波形检查峰值/THD；如需，比较有无限制器的频谱差异。

## 6. 测试用例与判定

| 编号 | 用例 | 输入/命令 | 预期结果 | 判定 |
|---|---|---|---|---|
| OFF-01 | 空测 | `--dry` | 输出与输入样本逐点一致（float32） | max diff = 0（本机已验证） |
| OFF-02 | 空测 + 重采样 | `--rate 48000` | 1 kHz 幅度偏差 ≤ 0.05 dB | 本机 0.000 dB |
| EQ-01 | PK 增益 | `eq_test.txt`，1 kHz 正弦 | +3.00 dB（+6 PK −3 preamp） | ±0.1 dB |
| EQ-02 | 全频响应 | `eq_test.txt`，扫频 | 20 Hz ≈ 0 dB，1 kHz +3 dB，20 kHz ≈ -5 dB | ±0.5 dB |
| EQ-03 | 四阶 | `--order 2` + 搁架 | 低频/高频渐近增益不变；过渡带更陡 | 与二阶对比 |
| EQ-04 | 斜率 S | `--slope 0.5` vs `1.0` | 渐近增益一致；S 越小过渡越缓 | 与 Q 版本对比 |
| XF-01 | 交叉馈送关闭 | `--crossfeed off` | R/L < -100 dB | 通过 |
| XF-02 | 三档预设 | weak/medium/strong | 三档 R/L 不同，2 kHz 串音低于 100 Hz | 记录表格 |
| SS-01 | M/S 宽度 | `--width 0` / `1` / `1.5` | 0 时 L=R；1 时逐点不变；1.5 时 L-R 差增大 | 见 DspChain 测试 |
| SS-02 | Crossfeed 强度 | `--crossfeed-width 0.2` vs `0.9` | 两者输出明显不同；0 时旁路 | 记录 R/L |
| LD-01 | 响度补偿 | `--loudness-volume 1.0` vs `0.2`，扫频 | 60 Hz 提升 >3 dB、10 kHz >1.5 dB，中频不变 | ±0.5 dB |
| MC-01 | 单声道兼容 | 任意空间设置 | 工具打印 Mono compatibility；< -6 dB 警告 | 无警告 |
| LIM-01 | 限制器关闭 | 0 dBFS + EQ | 峰值 > 0 dBFS，工具警告 | 通过 |
| LIM-03 | 限制器阈值 | `--limiter --limiter-threshold -6` | 峰值 ≤ −6 dBFS（约 0.501） | ±0.01 |
| LIM-02 | 限制器开启 | 同上 + `--limiter` | 峰值 ≤ -0.9 dBFS，无平顶 | 通过 |
| RS-01 | 192k→48k 通带 | 扫频 | 20 Hz–19 kHz ≤ ±0.1 dB | 通过 |
| RS-02 | 192k→48k 阻带 | 30 kHz 单音 | 输出 ≤ -70 dBFS | 本机 -82.1 dBFS |
| FIT-01 | CSV 拟合 | `eq_demo.csv` | 输出与拟合曲线一致（容差 ±1.5 dB） | 见工具打印的 bands/preamp |
| DSD-01 | DFF 播放 | 手机端 | 352800 Hz 源，欠载 0 | 手机单独测试 |

## 7. 记录模板

| 字段 | 内容 |
|---|---|
| 日期 / 测试人 | |
| 工具版本 / 提交 | |
| REW 版本 | |
| 输入文件、采样率、位深 | |
| EQ 文件、order、slope | |
| crossfeed / limiter | |
| 20 Hz / 100 Hz / 1 kHz / 10 kHz / 20 kHz 实测 | |
| 峰值（工具打印 / REW） | |
| 结论（通过/失败/备注） | |

## 8. 注意事项

- **测量 EQ 时关闭限制器**，或使用 -20 dBFS 扫频；限制器会压缩峰值并引入
  非线性，影响频响读数。
- **空测必须 `--dry`**：它同时旁路重采样和 DSP，输出 float32 样本应与输入
  逐点一致；若不一致，先检查 WAV 读取/写入。
- **四阶/斜率会改变拟合结果**：对响应 CSV，工具在加载时按 order/slope
  重新拟合，因此比较时应以工具打印的 band 数和 preamp 为准。
- **DSD 不在离线工具范围内**：DFF/DSF 的容器解析、FFmpeg dsd 解码、
  352.8k→48k 的实时性能需要在手机上验证（当前实测欠载 0）。
- REW 的 20 kHz 附近读数受扫频端点/窗函数影响，验收以 20 Hz–19 kHz 为主。
- 所有文件保持在 PC 本地处理，避免经过 Android 的 SAF/缓存复制路径。

## 9. BRIR 卷积验证（新增）

工具参数：`--brir <stereo.wav> --brir-mix <0..1>`。

- **IR 加载**：立体声 WAV（PCM16/24/32 或 float32），单声道会复制到双耳；IR 可自动重采样到处理采样率，最长截断到 2 秒。
- **冲激响应**：输入 1 个样本的脉冲，输出应是延迟 2048 帧（约 43 ms @ 48 kHz）的归一化 BRIR；用 REW 导入输出检查左右耳 IR 的 ITD/ILD。
- **湿/干混合**：mix=0 时输出应与 dry 逐点一致（BRIR 关闭）；mix=1 时应有明显的房间尾音；中间值检查无梳状滤波（干声已延迟 2048 帧对齐）。
- **单声道兼容**：工具会打印 Mono compatibility；BRIR/交叉馈送后若低于 −6 dB 需检查相位。
- 当前 `samples/brir_demo.wav` 为**合成** IR，仅用于功能验证；真实空间感请替换为 KEMAR BRIR（注意 CC-BY 等许可证）。
