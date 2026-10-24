# FFmpeg (music-only static build)

- Version: 7.1
- ABI: arm64-v8a
- License: LGPL v2.1 or later (see `COPYING.LGPLv2.1`; `COPYING.LGPLv3` is
  included as well).
- Build: static libraries, no GPL, no non-free, no external codec libraries.

Configure summary:

```
--disable-everything
--enable-protocol=file,pipe
--enable-demuxer=aac,aiff,ape,caf,dsf,flac,matroska,mov,mp3,ogg,tta,w64,wav,wv
--enable-decoder=aac,alac,ape,dsd_lsbf,dsd_lsbf_planar,dsd_msbf,
                 dsd_msbf_planar,flac,mp3,mp3float,opus,
                 pcm_f32be,pcm_f32le,pcm_f64be,pcm_f64le,
                 pcm_s16be,pcm_s16le,pcm_s24be,pcm_s24le,
                 pcm_s32be,pcm_s32le,pcm_u8,tta,vorbis,wavpack
--enable-parser=aac,aac_latm,flac,mpegaudio,opus,vorbis
--enable-bsf=extract_extradata
--disable-programs --disable-doc --disable-network
--disable-avdevice --disable-swscale --disable-postproc --disable-avfilter
--disable-gpl --disable-nonfree --disable-version3
--enable-static --disable-shared --enable-pic --disable-asm
```

`--disable-asm` is required because FFmpeg's aarch64 NEON assembly contains
non-PIC relocations that lld rejects when the static archives are linked into
`libhifiplayer.so`. Quality is unaffected; only CPU cost is higher.

Coverage:

- FLAC, WAV/W64, AIFF, CAF, ALAC, AAC, MP3, Opus, Vorbis, APE, WavPack, TTA,
  DSF (DSD).
- DSF is supported through FFmpeg's `dsf` demuxer. FFmpeg 7.1 does not provide
  a DSDIFF/DFF demuxer, so `.dff` needs a separate parser or a newer FFmpeg
  build.

Licensing note: the static libraries are linked into `libhifiplayer.so`.
For personal use this is fine. If the APK is distributed, LGPL 2.1+ requires
providing the corresponding source and relinkable object files, or switching
to dynamically linked FFmpeg shared libraries.
