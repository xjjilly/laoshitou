# Sample-rate policy

The policy lives in `core-dsp/include/dsp/SampleRatePolicy.h` and is a pure
function so it can be covered by host tests. It is intentionally hard-coded:
the 44.1 kHz and 48 kHz families never mix by default.

## Family ladders

| Family | Ladder (Hz) |
|---|---|
| 44.1 kHz | 44100, 88200, 176400 |
| 48 kHz | 48000, 96000, 192000 |

Classification uses divisibility, not a closed list:

- `rate % 11025 == 0` -> 44.1 family (44100, 88200, 176400, 22050, 11025, ...)
- `rate % 8000 == 0` or `rate % 12000 == 0` -> 48 family (48000, 96000,
  192000, 24000, 32000, 16000, 8000, 12000, ...)
- everything else -> unknown

## Processing mode

1. Classify the source rate.
2. Walk the source family ladder from the highest rate down.
3. Pick the first candidate that the device supports **and** whose ratio with
   the source is an exact integer.
4. If no same-family candidate exists, choose the closest reported rate and
   set `cross_family_fallback = true`. This keeps a 44.1-family source on 48k
   when the device reports both 48k and 96k, even if the native rate is 96k.
   Only use the native rate when the platform reported no rate list. Never
   pick the opposite family's highest ladder rate merely because it is
   supported.

Consequences:

- 44.1 kHz and 88.2 kHz both target 176.4 kHz when supported.
- 48 kHz and 96 kHz both target 192 kHz when supported.
- 44.1 kHz on a device that only exposes 48/96/192 kHz targets 48 kHz (native)
  and is flagged. It is **not** converted to 96 kHz.
- 32 kHz is a 48-family rate: 32 -> 96 (3x) or 32 -> 192 (6x). A 32 -> 48
  conversion is 1.5x and is flagged as `non_integer_ratio` if it is the only
  option.
- If the device tops out below the source rate, the same family is used for a
  downsample (for example 176.4 -> 88.2) and `device_limited` is set.

## Direct mode

- Target = source rate when the device supports it; no DSP, no resampling.
- If the source rate is not supported, prefer the device native rate in the
  same family, then the closest reported rate.
- Any fallback is flagged so the UI can show it.

## Device capabilities

The caller fills `DeviceRates` from:

- `AudioDeviceInfo.getSampleRates()` for the active output route;
- `AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE` as the native rate.

The actual Oboe stream rate is authoritative. Open the stream first, read
`getSampleRate()`, then configure the resampler for that rate.

## Key examples

| Source | Device supports | Processing target | Flags |
|---|---|---|---|
| 44100 | 44100, 48000, 88200, 96000, 176400, 192000 | 176400 | 4x, same family |
| 88200 | same | 176400 | 2x, same family |
| 48000 | same | 192000 | 4x, same family |
| 96000 | same | 192000 | 2x, same family |
| 44100 | 48000, 96000, 192000 | 48000 | cross-family fallback, non-integer |
| 48000 | 44100, 88200 | 44100 | cross-family fallback, non-integer |
| 44100 | 44100, 88200 | 88200 | 2x, same family |
| 48000 | 48000, 96000 | 96000 | 2x, same family |
| 176400 | 44100, 88200 | 88200 | same-family downsample, device-limited |
| 32000 | 48000, 96000, 192000 | 192000 | 6x, same family |

## Tests

`core-dsp/tests/SampleRatePolicyTest.cpp` covers classification, ladders,
integer-ratio checks, family selection, device-limited downsampling, empty
device capability lists, Direct mode, and the explicit assertion that a 44.1
source on a 48/96/192 device never targets 96.
