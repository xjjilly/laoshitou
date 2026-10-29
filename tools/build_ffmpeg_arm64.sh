#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROOT="$PROJECT_ROOT/.ffmpeg-build"
TARBALL="$PROJECT_ROOT/third_party/ffmpeg/ffmpeg-7.1.tar.xz"
export TMPDIR="$ROOT/tmp"
export TMP="$TMPDIR"
export TEMP="$TMPDIR"
mkdir -p "$TMPDIR"

SRC=$ROOT/ffmpeg-7.1
# Windows build hosts only. Override the toolchain roots via the environment.
NDK_ROOT="${ANDROID_NDK_HOME:-E:/Tools/android-sdk/ndk/28.2.13676358}"
LLVM_MINGW="${LLVM_MINGW_ROOT:-E:/Tools/llvm-mingw-20261006-ucrt-x86_64}"
TOOLCHAIN="$NDK_ROOT/toolchains/llvm/prebuilt/windows-x86_64"
SYSROOT_WIN=$TOOLCHAIN/sysroot
CC_WIN=$TOOLCHAIN/bin/clang.exe
CXX_WIN=$TOOLCHAIN/bin/clang++.exe
HOST_CC_WIN=$LLVM_MINGW/bin/gcc.exe
AR_WIN=$TOOLCHAIN/bin/llvm-ar.exe
RANLIB_WIN=$TOOLCHAIN/bin/llvm-ranlib.exe
STRIP_WIN=$TOOLCHAIN/bin/llvm-strip.exe
NM_WIN=$TOOLCHAIN/bin/llvm-nm.exe
# Relative to $SRC after `cd "$SRC"`: <project>/third_party/ffmpeg/arm64-v8a
PREFIX=../../third_party/ffmpeg/arm64-v8a
TARGET=aarch64-linux-android26
MAKE="$LLVM_MINGW/bin/mingw32-make.exe"
export PATH="$LLVM_MINGW/bin:$PATH"

rm -rf "$SRC"
mkdir -p "$ROOT"
tar -xJf "$TARBALL" -C "$ROOT"
cd "$SRC"

CFLAGS="--target=$TARGET --sysroot=$SYSROOT_WIN -O3 -fPIC -DANDROID"
LDFLAGS="--target=$TARGET --sysroot=$SYSROOT_WIN -Wl,-z,max-page-size=16384"

bash ./configure \
  --prefix="$PREFIX" \
  --target-os=android \
  --arch=aarch64 \
  --cpu=armv8-a \
  --enable-cross-compile \
  --cc="$CC_WIN" \
  --cxx="$CXX_WIN" \
  --host-cc="$HOST_CC_WIN" \
  --ar="$AR_WIN" \
  --ranlib="$RANLIB_WIN" \
  --strip="$STRIP_WIN" \
  --nm="$NM_WIN" \
  --extra-cflags="$CFLAGS" \
  --extra-ldflags="$LDFLAGS" \
  --enable-pic \
  --disable-asm \
  --enable-static \
  --disable-shared \
  --disable-everything \
  --enable-protocol=file,pipe \
  --enable-demuxer=aac,aiff,ape,caf,dsf,flac,matroska,mov,mp3,ogg,tta,w64,wav,wv \
  --enable-decoder=aac,alac,ape,dsd_lsbf,dsd_lsbf_planar,dsd_msbf,dsd_msbf_planar,flac,mp3,mp3float,opus,pcm_f32be,pcm_f32le,pcm_f64be,pcm_f64le,pcm_s16be,pcm_s16le,pcm_s24be,pcm_s24le,pcm_s32be,pcm_s32le,pcm_u8,tta,vorbis,wavpack \
  --enable-parser=aac,aac_latm,flac,mpegaudio,opus,vorbis \
  --enable-bsf=extract_extradata \
  --disable-programs \
  --disable-doc \
  --disable-avdevice \
  --disable-swscale \
  --disable-postproc \
  --disable-avfilter \
  --disable-network \
  --disable-encoders \
  --disable-muxers \
  --disable-hwaccels \
  --disable-gpl \
  --disable-nonfree \
  --disable-version3 \
  --disable-autodetect \
  --disable-iconv \
  --disable-zlib \
  --disable-bzlib \
  --disable-lzma \
  --disable-symver \
  --disable-debug

"$MAKE" -j8
"$MAKE" install

# The app's CMake expects shared headers at third_party/ffmpeg/include and
# arm64-v8a-specific static libraries at third_party/ffmpeg/arm64-v8a/lib.
mkdir -p "$PROJECT_ROOT/third_party/ffmpeg/include"
cp -a "$PREFIX/include/." "$PROJECT_ROOT/third_party/ffmpeg/include/"
rm -rf "$PREFIX/include" "$PREFIX/lib/pkgconfig" "$PREFIX/share" "$PREFIX/bin"

echo "=== installed ==="
ls -lh "$PREFIX/lib" | head -40
