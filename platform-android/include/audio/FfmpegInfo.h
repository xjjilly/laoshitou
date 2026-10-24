// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#pragma once

namespace audio {

// Returns the linked FFmpeg version string. Used to verify that the static
// FFmpeg libraries are actually pulled into the final shared library.
const char* FfmpegVersion() noexcept;

}  // namespace audio
