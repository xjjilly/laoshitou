# Third-party notices

老石头 is licensed under GNU GPLv3 (see `LICENSE`). The components below keep
their own licenses. This file is the release notice required when distributing
binaries.

## FFmpeg 7.1

- License: LGPL-2.1-or-later (see `third_party/ffmpeg/COPYING.LGPLv2.1` and
  `COPYING.LGPLv3`).
- Build: arm64-v8a static libraries, configured with
  `--disable-gpl --disable-nonfree --disable-version3`.
- Verified configuration: `CONFIG_LIBFDK_AAC=0`, `CONFIG_GPL=0`,
  `CONFIG_NONFREE=0`, `CONFIG_VERSION3=0`.
- AAC decoding uses FFmpeg's native `aac` decoder; all encoders are disabled.
  No `libfdk_aac` or other commercially licensed codec is compiled in.
- Source: <https://ffmpeg.org/releases/ffmpeg-7.1.tar.xz>
  SHA-256 `40973d44970dbc83ef302b0609f2e74982be2d85916dd2ee7472d30678a7abe6`.
  The exact tarball is included at `third_party/ffmpeg/ffmpeg-7.1.tar.xz`.
- Build script: `tools/build_ffmpeg_arm64.sh`.

Because FFmpeg is linked statically, a binary release must include the complete
corresponding source: this repository plus the exact FFmpeg source tarball
above. The app itself is GPLv3, so the combined work is distributed under
GPLv3; the FFmpeg LGPL terms are satisfied through the same source
distribution.

## Libraries linked into the APK

| Component | Version / pin | License | Purpose |
|---|---|---|---|
| Oboe | 1.11.0 (`com.google.oboe:oboe`) | Apache-2.0 | Low-latency audio output |
| libsamplerate | `0844c208f683527c08ea8a80acc13b398aa9c8bf` (0.2.x) | BSD-2-Clause | Sample-rate conversion |
| libbs2b | 3.1.0 | MIT | Crossfeed |
| rigtorp/SPSCQueue | `59a6a938513ea5004817383711ed35d32385d3ee` | MIT | Lock-free command queue |
| PortAudio `pa_ringbuffer` | `1ca0eeedb678c3cca80f3306c474d69a7a4b5278` | MIT | SPSC PCM ring buffer |
| AutoEQ filter formulas | upstream formulas | MIT | PK/LSC/HSC coefficient design |
| DSPFilters | `acc49170e79a94fcb9c04b8a2116e9f8dffd1c7d` | MIT | Vendored RBJ reference (not linked) |

`libebur128` and `rnnoise` are listed as planned in `README.md` but are not
compiled into the current APK.

License texts:

- Oboe — `third_party/licenses/Oboe-LICENSE.txt`,
  `third_party/licenses/Oboe-NOTICE.txt`.
- AutoEQ — `third_party/licenses/AutoEQ-LICENSE.txt`.
- libsamplerate — `third_party/libsamplerate/COPYING`.
- bs2b — `third_party/bs2b/COPYING`.
- SPSCQueue — `third_party/SPSCQueue/LICENSE`.
- PortAudio ring buffer — `third_party/portaudio/LICENSE.txt`.
- FFmpeg — `third_party/ffmpeg/COPYING.LGPLv2.1`,
  `third_party/ffmpeg/COPYING.LGPLv3`.

## Test IR / HRTF / BRIR assets

These are **not** in the APK. They are test material under `samples/ir/`:

- SADIE II BRIR — Copyright 2018 University of York, Apache-2.0
  (`samples/ir/licenses/SADIE_II_LICENSE.txt`).
- IoSR/University of Surrey anechoic BRIR (used as HRTF) — MIT
  (`samples/ir/licenses/IoSR_RealRoomBRIRs_LICENSE.txt`).
- MIT Environmental Impulse Response Survey — CC BY 4.0. Cite: Traer, J. &
  McDermott, J. H. (2016), *Statistics of natural reverberation enable
  perceptual separation of sound and space*, PNAS 113(48).

The unlicensed KEMAR files and the raw source datasets used during development
were removed before release.
