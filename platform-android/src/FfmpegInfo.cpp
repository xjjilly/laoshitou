// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#include "audio/FfmpegInfo.h"

extern "C" {
#include <libavutil/avutil.h>
}

namespace audio {

const char* FfmpegVersion() noexcept { return av_version_info(); }

}  // namespace audio
