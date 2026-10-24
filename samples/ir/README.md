# Test IR / HRTF / BRIR assets

**License summary**

- `brir_sadie_h16_room_front_48k.wav` — SADIE II, Copyright 2018 University of
  York, **Apache-2.0**. Full text: `licenses/SADIE_II_LICENSE.txt`.
- `hrtf_unis_anechoic_front_48k.wav` — IoSR/University of Surrey,
  **MIT**. Full text: `licenses/IoSR_RealRoomBRIRs_LICENSE.txt`.
- `ir_mit_bedroom_16k.wav` — MIT Environmental Impulse Response Survey,
  **CC BY 4.0**. Attribution and citation below.

The unlicensed KEMAR files and the raw multi-hundred-MB datasets are **not**
included in this repository. They were used only during local testing and were
removed before release.

These WAVs are test material for the BRIR convolver. They are **not** bundled
into the APK. Push them to the app's `IR/` folder and select one in
高保真 → 高级设置 → BRIR, or set `brir_path` in `player_settings`.

```
adb push samples/ir/*.wav /sdcard/Android/data/com.gu.laoshitou/files/IR/
```

`BrirProcessor` resamples an IR to the output rate, normalizes its peak to 0.9
and truncates it to 2 seconds, so a 44.1/48 kHz source IR is fine at any output
rate.

| File | Kind | Format | Taps | Source |
|---|---|---|---|---|
| `hrtf_unis_anechoic_front_48k.wav` | HRTF / anechoic binaural response, Neutrik Cortex mk.2 HATS, front | 48 kHz, 24-bit PCM, 2 ch | 591 | [IoSR-Surrey/RealRoomBRIRs](https://github.com/IoSR-Surrey/RealRoomBRIRs) @ `b41d237`, `UniS_Anechoic_BRIR_48k.sofa`, MIT |
| `brir_sadie_h16_room_front_48k.wav` | BRIR, reverberant room, H16 subject, front | 48 kHz, 24-bit PCM, 2 ch | 16,320 | [SADIE II](https://www.york.ac.uk/sadie-project/database.html), Zenodo [10.5281/zenodo.12092466](https://doi.org/10.5281/zenodo.12092466), `H16_48K_24bit_0.3s_FIR_SOFA.sofa`, Apache-2.0 |
| `ir_mit_bedroom_16k.wav` | Environmental room IR, mono | 16 kHz, 24-bit PCM, 1 ch | 7,745 | [MIT Environmental Impulse Response Survey](https://mcdermottlab.mit.edu/Reverb/IR_Survey.html), `h001_Bedroom_65txts.wav`, CC BY 4.0 |

Notes:

- The MIT environment IR is mono; `LoadIrWav()` duplicates it to both ears.
  This exercises the mono-IR path and the 16 kHz → output-rate resampling
  path.
- The UniS file is an anechoic HATS measurement. It is spectrally close to a
  KEMAR HRTF but carries an explicit MIT license.

## How the WAVs were produced

Source downloads are **not** stored in this repository. Download them from the
URLs below if you need to regenerate the WAVs.

| Source | URL | SHA-256 |
|---|---|---|
| SADIE II H16 BRIR SOFA archive | `https://zenodo.org/records/12092466/files/H16_BRIR_SOFA.zip?download=1` | `581b6af843f5de6135881a77b031b69c2c6451055099c6dfaaba9bdc65eec05b` |
| UniS anechoic BRIR SOFA | `https://github.com/IoSR-Surrey/RealRoomBRIRs/blob/master/UniS_Anechoic_BRIR_48k.sofa` | `8babab2a899bb6792e0746078c06834aeb79bcc13edfa0650ebaf18a8ba34c37` |
| MIT environmental IR (16 kHz mirror) | `https://hf-mirror.com/datasets/TaterTotterson/MIT_environmental_impulse_responses/resolve/main/16khz/h001_Bedroom_65txts.wav` | `a975555170188fd061f212168a5210dd0422e790af19cca732ef2c3e8f67382e` |

Generated WAVs:

| File | SHA-256 |
|---|---|
| `hrtf_unis_anechoic_front_48k.wav` | `5341e981f2813828cd048dfe8fd117beb6df93ace661a3e19df3feadead4f8c8` |
| `brir_sadie_h16_room_front_48k.wav` | `beb12c7015978b0700a39169413be943de9da4c6e864acf586c2c498626826f1` |
| `ir_mit_bedroom_16k.wav` | `a975555170188fd061f212168a5210dd0422e790af19cca732ef2c3e8f67382e` |

Commands:

```sh
# BRIR: pick the measurement nearest to azimuth 0 / elevation 0.
unzip -j H16_BRIR_SOFA.zip \
  'H16_BRIR_SOFA/H16_48K_24bit_0.3s_FIR_SOFA.sofa' -d .
python tools/ir_tools.py sofa-to-wav \
  H16_48K_24bit_0.3s_FIR_SOFA.sofa \
  samples/ir/brir_sadie_h16_room_front_48k.wav --max-seconds 0.34

# HRTF (anechoic HATS response).
python tools/ir_tools.py sofa-to-wav \
  UniS_Anechoic_BRIR_48k.sofa \
  samples/ir/hrtf_unis_anechoic_front_48k.wav --max-seconds 1.0
```

`tools/ir_tools.py` needs `netCDF4` and `numpy` for `sofa-to-wav`.

## Attribution

- **SADIE II** — Copyright 2018 University of York, Apache License 2.0.
  Full text: `licenses/SADIE_II_LICENSE.txt`.
- **UniS RealRoomBRIRs** — MIT License, University of Surrey.
  Full text: `licenses/IoSR_RealRoomBRIRs_LICENSE.txt`.
- **MIT Environmental Impulse Response Survey** — CC BY 4.0. Please cite:
  Traer, J. & McDermott, J. H. (2016), *Statistics of natural reverberation
  enable perceptual separation of sound and space*, PNAS 113(48).
