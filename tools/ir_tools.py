#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (c) 2026 老石头 contributors
"""Build stereo IR WAVs for the player's BRIR convolver.

Subcommands:
  pair       Interleave two mono HRIR WAVs (left ear, right ear) into one
             stereo WAV that LoadIrWav() accepts.
  sofa-to-wav Pick one source direction from a SOFA file and write a
             24-bit stereo PCM WAV.
  info       Print the format of a WAV file.

SOFA support needs netCDF4 and numpy:  python -m pip install netCDF4 numpy
"""

from __future__ import annotations

import argparse
import math
import os
import wave

import numpy as np

try:
    import netCDF4
except ImportError:  # Only the sofa-to-wav subcommand needs this.
    netCDF4 = None


def _read_wav(path: str) -> tuple[int, np.ndarray, int]:
    """Returns (sample_rate, samples[frames, channels], bits)."""
    with wave.open(path, "rb") as wav:
        channels = wav.getnchannels()
        rate = wav.getframerate()
        width = wav.getsampwidth()
        frames = wav.getnframes()
        raw = wav.readframes(frames)
    if width == 2:
        data = np.frombuffer(raw, dtype="<i2").astype(np.float64) / 32768.0
    elif width == 3:
        bytes_ = np.frombuffer(raw, dtype=np.uint8).reshape(-1, 3).astype(np.int32)
        values = bytes_[:, 0] | (bytes_[:, 1] << 8) | (bytes_[:, 2] << 16)
        values = np.where(values & 0x800000, values | ~0xFFFFFF, values)
        data = values.astype(np.float64) / 8388608.0
    elif width == 4:
        data = np.frombuffer(raw, dtype="<i4").astype(np.float64) / 2147483648.0
    else:
        raise ValueError(f"unsupported PCM width: {width * 8} bit")
    return rate, data.reshape(-1, channels), width * 8


def _write_wav24(path: str, rate: int, left: np.ndarray, right: np.ndarray) -> None:
    frames = min(len(left), len(right))
    interleaved = np.empty(frames * 2, dtype=np.float64)
    interleaved[0::2] = left[:frames]
    interleaved[1::2] = right[:frames]
    values = np.clip(np.round(interleaved * 8388608.0), -8388608, 8388607).astype(np.int32)
    raw = bytearray(frames * 6)
    for index, value in enumerate(values):
        packed = int(value) & 0xFFFFFF
        offset = index * 3
        raw[offset] = packed & 0xFF
        raw[offset + 1] = (packed >> 8) & 0xFF
        raw[offset + 2] = (packed >> 16) & 0xFF
    directory = os.path.dirname(os.path.abspath(path))
    os.makedirs(directory, exist_ok=True)
    with wave.open(path, "wb") as wav:
        wav.setnchannels(2)
        wav.setsampwidth(3)
        wav.setframerate(rate)
        wav.writeframes(bytes(raw))


def _unit_from_spherical(azimuth_deg: float, elevation_deg: float) -> np.ndarray:
    az = math.radians(azimuth_deg)
    el = math.radians(elevation_deg)
    return np.array(
        [math.cos(el) * math.cos(az), math.cos(el) * math.sin(az), math.sin(el)],
        dtype=np.float64,
    )


def _positions_to_unit(positions: np.ndarray, position_type: str, units: str) -> np.ndarray:
    if position_type.lower() != "spherical":
        norm = np.linalg.norm(positions, axis=1, keepdims=True)
        return positions / np.maximum(norm, 1.0e-12)

    unit_names = [part.strip().lower() for part in units.split(",")]
    radians = unit_names[:2] == ["radian", "radian"]
    az = np.radians(positions[:, 0]) if not radians else positions[:, 0]
    el = np.radians(positions[:, 1]) if not radians else positions[:, 1]
    return np.stack(
        [np.cos(el) * np.cos(az), np.cos(el) * np.sin(az), np.sin(el)], axis=1
    )


def _normalize_ir(data: np.ndarray) -> np.ndarray:
    if np.issubdtype(data.dtype, np.integer):
        limit = float(np.iinfo(data.dtype).max) + 1.0
        return data.astype(np.float64) / limit
    return data.astype(np.float64)


def _cmd_pair(args: argparse.Namespace) -> int:
    left_rate, left, left_bits = _read_wav(args.left)
    right_rate, right, right_bits = _read_wav(args.right)
    if left_rate != right_rate:
        raise SystemExit(f"sample rate mismatch: {left_rate} vs {right_rate}")
    if left.shape[1] != 1 or right.shape[1] != 1:
        raise SystemExit("pair expects two mono WAV files")
    _write_wav24(args.output, left_rate, left[:, 0], right[:, 0])
    print(
        f"wrote {args.output}: 2 ch, {left_rate} Hz, "
        f"{min(len(left), len(right))} frames (from {left_bits}/{right_bits} bit)"
    )
    return 0


def _cmd_info(args: argparse.Namespace) -> int:
    rate, data, bits = _read_wav(args.input)
    peak = float(np.max(np.abs(data))) if data.size else 0.0
    print(
        f"{args.input}: {data.shape[1]} ch, {rate} Hz, {bits} bit, "
        f"{data.shape[0]} frames, peak {peak:.6f}"
    )
    return 0


def _cmd_sofa(args: argparse.Namespace) -> int:
    if netCDF4 is None:
        raise SystemExit("netCDF4 is not installed; pip install netCDF4")
    dataset = netCDF4.Dataset(args.input)
    ir_var = dataset.variables["Data.IR"]
    rate_var = dataset.variables["Data.SamplingRate"]
    position_var = dataset.variables["SourcePosition"]
    rate = int(round(float(rate_var[0])))

    positions = np.asarray(position_var[:], dtype=np.float64)
    position_type = getattr(position_var, "Type", "spherical")
    units = getattr(position_var, "Units", "degree, degree, metre")
    unit_vectors = _positions_to_unit(positions, position_type, units)

    if args.index is not None:
        index = args.index
    else:
        target = _unit_from_spherical(args.azimuth, args.elevation)
        index = int(np.argmax(unit_vectors @ target))

    if index < 0 or index >= positions.shape[0]:
        raise SystemExit(f"position index {index} out of range")

    ir = _normalize_ir(np.asarray(ir_var[index, :, :]))
    if ir.shape[0] < 2:
        raise SystemExit(f"SOFA file has {ir.shape[0]} receiver(s); need stereo")
    left = np.array(ir[0, :], dtype=np.float64)
    right = np.array(ir[1, :], dtype=np.float64)

    if "Data.Delay" in dataset.variables:
        delay = np.asarray(dataset.variables["Data.Delay"][:], dtype=np.float64).reshape(-1)
        left_delay = int(round(delay[0])) if delay.size > 0 else 0
        right_delay = int(round(delay[1])) if delay.size > 1 else left_delay
        if left_delay > 0:
            left = np.concatenate([np.zeros(left_delay), left])
        if right_delay > 0:
            right = np.concatenate([np.zeros(right_delay), right])

    if args.max_seconds > 0:
        limit = int(rate * args.max_seconds)
        left = left[:limit]
        right = right[:limit]

    _write_wav24(args.output, rate, left, right)
    used = positions[index]
    print(
        f"wrote {args.output}: index {index}, position "
        f"{np.round(used, 3).tolist()} ({position_type}, {units}), "
        f"{rate} Hz, {len(left)} frames, peak "
        f"{max(float(np.max(np.abs(left))), float(np.max(np.abs(right)))):.6f}"
    )
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    pair = sub.add_parser("pair", help="interleave two mono WAVs into a stereo WAV")
    pair.add_argument("left")
    pair.add_argument("right")
    pair.add_argument("output")
    pair.set_defaults(func=_cmd_pair)

    info = sub.add_parser("info", help="print WAV format")
    info.add_argument("input")
    info.set_defaults(func=_cmd_info)

    sofa = sub.add_parser("sofa-to-wav", help="extract one direction from a SOFA file")
    sofa.add_argument("input")
    sofa.add_argument("output")
    sofa.add_argument("--azimuth", type=float, default=0.0, help="target azimuth, degrees")
    sofa.add_argument("--elevation", type=float, default=0.0, help="target elevation, degrees")
    sofa.add_argument("--index", type=int, default=None, help="use this measurement index")
    sofa.add_argument("--max-seconds", type=float, default=2.0)
    sofa.set_defaults(func=_cmd_sofa)

    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
