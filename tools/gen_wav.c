// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

/*
 * Generates a stereo 16-bit PCM sine WAV for on-device playback tests.
 *
 * usage: gen_wav <sample_rate> <frequency_hz> <seconds> <output.wav>
 */

#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

static void WriteU16(FILE* file, uint16_t value) {
  fputc(value & 0xff, file);
  fputc((value >> 8) & 0xff, file);
}

static void WriteU32(FILE* file, uint32_t value) {
  WriteU16(file, (uint16_t)(value & 0xffffu));
  WriteU16(file, (uint16_t)((value >> 16) & 0xffffu));
}

int main(int argc, char** argv) {
  if (argc != 5) {
    fprintf(stderr, "usage: %s <sample_rate> <frequency_hz> <seconds> <out.wav>\n",
            argv[0]);
    return 1;
  }

  const int sample_rate = atoi(argv[1]);
  const double frequency = atof(argv[2]);
  const int seconds = atoi(argv[3]);
  const char* path = argv[4];
  if (sample_rate <= 0 || frequency <= 0.0 || seconds <= 0) {
    return 1;
  }

  const int channels = 2;
  const int bits_per_sample = 16;
  const long frames = (long)sample_rate * (long)seconds;
  const uint32_t data_bytes =
      (uint32_t)(frames * channels * (bits_per_sample / 8));

  FILE* file = fopen(path, "wb");
  if (file == NULL) {
    perror(path);
    return 1;
  }

  fwrite("RIFF", 1, 4, file);
  WriteU32(file, 36u + data_bytes);
  fwrite("WAVE", 1, 4, file);
  fwrite("fmt ", 1, 4, file);
  WriteU32(file, 16u);
  WriteU16(file, 1u);
  WriteU16(file, (uint16_t)channels);
  WriteU32(file, (uint32_t)sample_rate);
  WriteU32(file, (uint32_t)(sample_rate * channels * (bits_per_sample / 8)));
  WriteU16(file, (uint16_t)(channels * (bits_per_sample / 8)));
  WriteU16(file, (uint16_t)bits_per_sample);
  fwrite("data", 1, 4, file);
  WriteU32(file, data_bytes);

  const double two_pi = 6.283185307179586476925286766559;
  for (long i = 0; i < frames; ++i) {
    const double t = (double)i / (double)sample_rate;
    const double sample = 0.25 * 32767.0 * sin(two_pi * frequency * t);
    const int16_t pcm = (int16_t)sample;
    WriteU16(file, (uint16_t)pcm);
    WriteU16(file, (uint16_t)pcm);
  }

  fclose(file);
  return 0;
}
