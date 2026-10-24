# Third-party versions

All dependencies are pinned. Do not update without re-running the host tests
and a device playback check.

| Library | Pin | Upstream | License | Used for |
|---|---|---|---|---|
| Oboe | 1.11.0 | google/oboe | Apache-2.0 | Android low-latency output |
| libsamplerate | commit `0844c208f683527c08ea8a80acc13b398aa9c8bf` | libsndfile/libsamplerate | BSD-2-Clause | Sample-rate conversion |
| DSPFilters | commit `acc49170e79a94fcb9c04b8a2116e9f8dffd1c7d` | vinniefalco/DSPFilters | MIT | RBJ biquad coefficient design |
| bs2b | commit `5200eaeb3afe9f469e5627204d3831cefd636dce`, bundled `libbs2b-3.1.0` | DeaDBeeF-Player/bs2b (original by Boris Mikhaylov) | MIT | Crossfeed |
| SPSCQueue | commit `59a6a938513ea5004817383711ed35d32385d3ee` | rigtorp/SPSCQueue | MIT | Control/command queue |
| PortAudio ring buffer | commit `1ca0eeedb678c3cca80f3306c474d69a7a4b5278` | PortAudio/portaudio | MIT | SPSC PCM ring buffer |
| FFmpeg | 7.1, arm64-v8a static | ffmpeg.org | LGPL-2.1-or-later (no GPL) | Decoding: FLAC/WAV/AIFF/CAF/ALAC/AAC/MP3/Opus/Vorbis/APE/WavPack/TTA/DSF |

Notes:

- The DeaDBeeF `bs2b.c` plugin wrapper is not compiled. The standalone
  `libbs2b-3.1.0` source is MIT (see `third_party/bs2b/libbs2b-3.1.0/COPYING`).
- Oboe is resolved from Google Maven as `com.google.oboe:oboe:1.11.0`; its AAR
  contains Prefab modules for arm64-v8a and x86_64.
- FFmpeg is configured with `--disable-gpl --disable-nonfree
  --disable-version3 --disable-encoders` and built as arm64-v8a static
  libraries. `CONFIG_LIBFDK_AAC`, `CONFIG_GPL`, `CONFIG_NONFREE` and
  `CONFIG_VERSION3` are all `0`. The app is GPLv3; because FFmpeg is linked
  statically, the complete corresponding source (this repository plus the
  exact FFmpeg 7.1 tarball) must accompany binary releases. See
  `THIRD_PARTY_NOTICES.md` for the source URL and SHA-256.
