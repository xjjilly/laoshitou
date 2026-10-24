using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

namespace WasapiTest
{
    internal enum EDataFlow
    {
        Render = 0,
        Capture = 1,
        All = 2,
    }

    internal enum ERole
    {
        Console = 0,
        Multimedia = 1,
        Communications = 2,
    }

    [StructLayout(LayoutKind.Sequential, Pack = 2)]
    internal struct WaveFormatEx
    {
        public ushort FormatTag;
        public ushort Channels;
        public uint SampleRate;
        public uint AvgBytesPerSec;
        public ushort BlockAlign;
        public ushort BitsPerSample;
        public ushort ExtraSize;
    }

    [StructLayout(LayoutKind.Sequential, Pack = 2)]
    internal struct WaveFormatExtensible
    {
        public WaveFormatEx Format;
        public ushort ValidBits;
        public uint ChannelMask;
        public Guid SubFormat;
    }

    [StructLayout(LayoutKind.Sequential, Pack = 4)]
    internal struct PropertyKey
    {
        public Guid FormatId;
        public uint Id;
    }

    [StructLayout(LayoutKind.Explicit)]
    internal struct PropVariant
    {
        [FieldOffset(0)] public ushort VarType;
        [FieldOffset(8)] public IntPtr Pointer;
    }

    [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
    internal class MmDeviceEnumerator
    {
    }

    [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IMMDeviceEnumerator
    {
        [PreserveSig] int EnumAudioEndpoints(EDataFlow dataFlow, int stateMask,
                                             out IMMDeviceCollection devices);
        [PreserveSig] int GetDefaultAudioEndpoint(EDataFlow dataFlow, ERole role,
                                                  out IMMDevice endpoint);
        [PreserveSig] int GetDevice([MarshalAs(UnmanagedType.LPWStr)] string id,
                                    out IMMDevice device);
        [PreserveSig] int RegisterEndpointNotificationCallback(IntPtr client);
        [PreserveSig] int UnregisterEndpointNotificationCallback(IntPtr client);
    }

    [ComImport, Guid("0BD7A1BE-7A1A-44DB-8397-CC5392387B5E"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IMMDeviceCollection
    {
        [PreserveSig] int GetCount(out uint count);
        [PreserveSig] int Item(uint index, out IMMDevice device);
    }

    [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IMMDevice
    {
        [PreserveSig] int Activate(ref Guid iid, int clsCtx, IntPtr activationParams,
                                   [MarshalAs(UnmanagedType.IUnknown)] out object interfacePointer);
        [PreserveSig] int OpenPropertyStore(int access, out IPropertyStore properties);
        [PreserveSig] int GetId([MarshalAs(UnmanagedType.LPWStr)] out string id);
        [PreserveSig] int GetState(out int state);
    }

    [ComImport, Guid("886d8eeb-8cf2-4446-8d02-cdba1dbdcf99"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IPropertyStore
    {
        [PreserveSig] int GetCount(out uint count);
        [PreserveSig] int GetAt(uint index, out PropertyKey key);
        [PreserveSig] int GetValue(ref PropertyKey key, out PropVariant value);
        [PreserveSig] int SetValue(ref PropertyKey key, ref PropVariant value);
        [PreserveSig] int Commit();
    }

    [ComImport, Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IAudioClient
    {
        [PreserveSig] int Initialize(int shareMode, int streamFlags,
                                     long bufferDuration, long periodicity,
                                     IntPtr format, IntPtr sessionGuid);
        [PreserveSig] int GetBufferSize(out uint bufferFrames);
        [PreserveSig] int GetStreamLatency(out long latency);
        [PreserveSig] int GetCurrentPadding(out uint padding);
        [PreserveSig] int IsFormatSupported(int shareMode, IntPtr format,
                                            out IntPtr closestMatch);
        [PreserveSig] int GetMixFormat(out IntPtr deviceFormat);
        [PreserveSig] int GetDevicePeriod(out long defaultPeriod, out long minimumPeriod);
        [PreserveSig] int Start();
        [PreserveSig] int Stop();
        [PreserveSig] int Reset();
        [PreserveSig] int SetEventHandle(IntPtr eventHandle);
        [PreserveSig] int GetService(ref Guid iid,
                                     [MarshalAs(UnmanagedType.IUnknown)] out object service);
    }

    [ComImport, Guid("F294ACFC-3146-4483-A7BF-ADDCA7C260E2"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IAudioRenderClient
    {
        [PreserveSig] int GetBuffer(uint framesRequested, out IntPtr data);
        [PreserveSig] int ReleaseBuffer(uint framesWritten, int flags);
    }

    internal static class Native
    {
        [DllImport("ole32.dll")]
        internal static extern int PropVariantClear(ref PropVariant value);

        [DllImport("ole32.dll")]
        internal static extern void CoTaskMemFree(IntPtr pointer);
    }

    internal sealed class WavData
    {
        public int SampleRate;
        public int Channels;
        public float[] Samples;  // Interleaved stereo.
        public long Frames
        {
            get { return Samples.Length / 2; }
        }
    }

    internal sealed class FormatInfo
    {
        public int SampleRate;
        public int Channels;
        public int Bits;
        public bool IsFloat;
    }

    public static class Program
    {
        private static readonly Guid SubtypePcm =
            new Guid("00000001-0000-0010-8000-00aa00389b71");
        private static readonly Guid SubtypeFloat =
            new Guid("00000003-0000-0010-8000-00aa00389b71");

        private const int SharedMode = 0;
        private const int ExclusiveMode = 1;
        private const int DeviceStateActive = 0x1;
        private const int ClsCtxAll = 23;
        private const int StgmRead = 0;

        public static int Main(string[] args)
        {
            Console.OutputEncoding = Encoding.UTF8;
            try
            {
                if (args.Length == 0 || args[0] == "probe")
                {
                    return Probe();
                }
                if (args[0] == "play")
                {
                    return Play(args);
                }
                Usage();
                return 2;
            }
            catch (Exception error)
            {
                Console.Error.WriteLine("error: " + error);
                return 1;
            }
        }

        private static void Usage()
        {
            Console.WriteLine("wasapi_test probe");
            Console.WriteLine("wasapi_test play --file <x.wav> [--device <name>] [--shared] [--gain-db <db>]");
        }

        private static int Probe()
        {
            IMMDeviceEnumerator enumerator =
                (IMMDeviceEnumerator)new MmDeviceEnumerator();

            string defaultId = null;
            IMMDevice defaultDevice;
            if (enumerator.GetDefaultAudioEndpoint(EDataFlow.Render, ERole.Console,
                                                    out defaultDevice) == 0)
            {
                defaultDevice.GetId(out defaultId);
                Marshal.ReleaseComObject(defaultDevice);
            }

            IMMDeviceCollection collection;
            Check(enumerator.EnumAudioEndpoints(EDataFlow.Render, DeviceStateActive,
                                                out collection), "EnumAudioEndpoints");
            uint count;
            Check(collection.GetCount(out count), "GetCount");

            for (uint index = 0; index < count; ++index)
            {
                IMMDevice device;
                Check(collection.Item(index, out device), "Item");
                string id;
                device.GetId(out id);
                string name = FriendlyName(device);
                Console.WriteLine("[" + index + "] " + name +
                                  (id == defaultId ? "   <-- default (console)" : ""));

                IAudioClient client = ActivateClient(device);
                if (client != null)
                {
                    IntPtr mixFormat;
                    if (client.GetMixFormat(out mixFormat) == 0)
                    {
                        Console.WriteLine("    mix: " + DescribeFormat(mixFormat));
                        Native.CoTaskMemFree(mixFormat);
                    }

                    List<string> rates = new List<string>();
                    int[] candidates = { 44100, 48000, 88200, 96000, 176400,
                                         192000, 352800, 384000, 705600, 768000 };
                    foreach (int rate in candidates)
                    {
                        List<string> depths = new List<string>();
                        TryExclusive(client, rate, 16, false, depths);
                        TryExclusive(client, rate, 24, false, depths);
                        TryExclusive(client, rate, 32, false, depths);
                        TryExclusive(client, rate, 32, true, depths);
                        if (depths.Count > 0)
                        {
                            rates.Add(rate + " Hz: " + string.Join(", ", depths));
                        }
                    }
                    Console.WriteLine("    exclusive: " + (rates.Count == 0
                        ? "(none reported)"
                        : string.Join(" | ", rates)));
                    Marshal.ReleaseComObject(client);
                }
                Marshal.ReleaseComObject(device);
            }

            Marshal.ReleaseComObject(collection);
            Marshal.ReleaseComObject(enumerator);
            return 0;
        }

        private static void TryExclusive(IAudioClient client, int rate, int bits,
                                         bool isFloat, List<string> depths)
        {
            IntPtr format = CreateFormat(rate, 2, bits, isFloat);
            IntPtr closest;
            int result = client.IsFormatSupported(ExclusiveMode, format, out closest);
            if (closest != IntPtr.Zero)
            {
                Native.CoTaskMemFree(closest);
            }
            Marshal.FreeHGlobal(format);
            if (result == 0)
            {
                depths.Add(isFloat ? "float32" : bits + "-bit");
            }
        }

        private static int Play(string[] args)
        {
            string file = null;
            string deviceFilter = null;
            bool wantExclusive = true;
            double gainDb = 0.0;

            for (int i = 1; i < args.Length; ++i)
            {
                switch (args[i])
                {
                    case "--file":
                        file = args[++i];
                        break;
                    case "--device":
                        deviceFilter = args[++i];
                        break;
                    case "--shared":
                        wantExclusive = false;
                        break;
                    case "--gain-db":
                        gainDb = double.Parse(args[++i], CultureInfo.InvariantCulture);
                        break;
                    default:
                        Usage();
                        return 2;
                }
            }

            if (file == null)
            {
                Usage();
                return 2;
            }

            WavData wav = ReadWav(file);
            Console.WriteLine("file: " + file + "  " + wav.SampleRate + " Hz, " +
                              wav.Channels + " ch, " + wav.Frames + " frames");

            IMMDeviceEnumerator enumerator =
                (IMMDeviceEnumerator)new MmDeviceEnumerator();
            IMMDeviceCollection collection;
            Check(enumerator.EnumAudioEndpoints(EDataFlow.Render, DeviceStateActive,
                                                out collection), "EnumAudioEndpoints");
            uint count;
            collection.GetCount(out count);

            IMMDevice selectedDevice = null;
            string selectedName = null;
            for (uint i = 0; i < count; ++i)
            {
                IMMDevice device;
                collection.Item(i, out device);
                string name = FriendlyName(device);
                if (deviceFilter == null ||
                    name.IndexOf(deviceFilter, StringComparison.OrdinalIgnoreCase) >= 0)
                {
                    selectedDevice = device;
                    selectedName = name;
                    break;
                }
                Marshal.ReleaseComObject(device);
            }
            Marshal.ReleaseComObject(collection);
            Marshal.ReleaseComObject(enumerator);

            if (selectedDevice == null)
            {
                Console.Error.WriteLine("no matching render device");
                return 1;
            }
            Console.WriteLine("device: " + selectedName);

            IAudioClient client = ActivateClient(selectedDevice);
            if (client == null)
            {
                Console.Error.WriteLine("Activate IAudioClient failed");
                return 1;
            }

            IntPtr chosenFormat = IntPtr.Zero;
            FormatInfo chosen = null;
            bool exclusive = wantExclusive;

            if (wantExclusive)
            {
                int[] bitsOrder = { 32, 24, 16 };
                bool[] floatOrder = { true, false };
                foreach (bool isFloat in floatOrder)
                {
                    foreach (int bits in bitsOrder)
                    {
                        if (isFloat && bits != 32)
                        {
                            continue;
                        }
                        IntPtr format = CreateFormat(wav.SampleRate, 2, bits, isFloat);
                        IntPtr closest;
                        int result = client.IsFormatSupported(ExclusiveMode, format,
                                                              out closest);
                        if (closest != IntPtr.Zero)
                        {
                            Native.CoTaskMemFree(closest);
                        }
                        if (result == 0)
                        {
                            chosenFormat = format;
                            chosen = new FormatInfo
                            {
                                SampleRate = wav.SampleRate,
                                Channels = 2,
                                Bits = bits,
                                IsFloat = isFloat,
                            };
                            break;
                        }
                        Marshal.FreeHGlobal(format);
                    }
                    if (chosenFormat != IntPtr.Zero)
                    {
                        break;
                    }
                }
            }

            if (chosenFormat == IntPtr.Zero)
            {
                exclusive = false;
                IntPtr mix;
                Check(client.GetMixFormat(out mix), "GetMixFormat");
                chosen = ParseFormat(mix);
                chosenFormat = mix;
                if (chosen.SampleRate != wav.SampleRate)
                {
                    Console.Error.WriteLine(
                        "exclusive mode unavailable and the shared mix rate (" +
                        chosen.SampleRate + " Hz) differs from the file rate (" +
                        wav.SampleRate + " Hz); use a file at the mix rate or a " +
                        "device that supports exclusive mode.");
                    return 1;
                }
                Console.WriteLine("exclusive mode unavailable, using shared mix format");
            }

            Console.WriteLine("output format: " + chosen.SampleRate + " Hz, " +
                              chosen.Channels + " ch, " + chosen.Bits + "-bit " +
                              (chosen.IsFloat ? "float" : "pcm") +
                              (exclusive ? " (exclusive)" : " (shared)"));

            int initResult = -1;
            long[] durations = { 2000000, 1000000, 500000, 4000000 };
            foreach (long duration in durations)
            {
                initResult = client.Initialize(exclusive ? ExclusiveMode : SharedMode,
                                               0, duration, 0, chosenFormat,
                                               IntPtr.Zero);
                if (initResult == 0)
                {
                    break;
                }
            }
            if (initResult != 0)
            {
                Console.Error.WriteLine("IAudioClient.Initialize failed: 0x" +
                                        initResult.ToString("X8"));
                return 1;
            }

            uint bufferFrames;
            Check(client.GetBufferSize(out bufferFrames), "GetBufferSize");
            Guid renderId = typeof(IAudioRenderClient).GUID;
            object renderObject;
            Check(client.GetService(ref renderId, out renderObject), "GetService");

            IAudioRenderClient render = (IAudioRenderClient)renderObject;
            object outputData = BuildOutput(wav, chosen, gainDb);
            int targetChannels = chosen.Channels;
            int bytesPerSample = chosen.Bits / 8;
            long totalFrames = wav.Frames;
            long position = 0;

            Check(client.Start(), "Start");
            while (position < totalFrames)
            {
                uint padding;
                Check(client.GetCurrentPadding(out padding), "GetCurrentPadding");
                uint available = bufferFrames - padding;
                if (available == 0)
                {
                    Thread.Sleep(2);
                    continue;
                }
                int frames = (int)Math.Min(available, totalFrames - position);
                IntPtr buffer;
                Check(render.GetBuffer((uint)frames, out buffer), "GetBuffer");
                CopyOutput(outputData, position, frames, targetChannels,
                           bytesPerSample, buffer);
                Check(render.ReleaseBuffer((uint)frames, 0), "ReleaseBuffer");
                position += frames;
                Thread.Sleep(1);
            }

            while (true)
            {
                uint padding;
                Check(client.GetCurrentPadding(out padding), "GetCurrentPadding");
                if (padding == 0)
                {
                    break;
                }
                Thread.Sleep(5);
            }
            client.Stop();
            Marshal.ReleaseComObject(render);
            Marshal.ReleaseComObject(client);
            Marshal.ReleaseComObject(selectedDevice);
            Console.WriteLine("done");
            return 0;
        }

        private static object BuildOutput(WavData wav, FormatInfo format, double gainDb)
        {
            double gain = Math.Pow(10.0, gainDb / 20.0);
            int total = checked((int)(wav.Frames * 2));  // Stereo output.
            if (format.IsFloat)
            {
                float[] data = new float[total];
                for (int i = 0; i < total; ++i)
                {
                    data[i] = (float)(wav.Samples[i] * gain);
                }
                return data;
            }
            if (format.Bits == 16)
            {
                short[] data = new short[total];
                for (int i = 0; i < total; ++i)
                {
                    double value = wav.Samples[i] * gain;
                    value = Math.Max(-1.0, Math.Min(1.0, value));
                    data[i] = (short)Math.Round(value * 32767.0);
                }
                return data;
            }
            if (format.Bits == 24)
            {
                byte[] data = new byte[total * 3];
                for (int i = 0; i < total; ++i)
                {
                    double value = wav.Samples[i] * gain;
                    value = Math.Max(-1.0, Math.Min(1.0, value));
                    int sample = (int)Math.Round(value * 8388607.0);
                    data[i * 3] = (byte)(sample & 0xFF);
                    data[i * 3 + 1] = (byte)((sample >> 8) & 0xFF);
                    data[i * 3 + 2] = (byte)((sample >> 16) & 0xFF);
                }
                return data;
            }
            if (format.Bits == 32)
            {
                int[] data = new int[total];
                for (int i = 0; i < total; ++i)
                {
                    double value = wav.Samples[i] * gain;
                    value = Math.Max(-1.0, Math.Min(1.0, value));
                    data[i] = (int)Math.Round(value * 2147483647.0);
                }
                return data;
            }
            throw new InvalidOperationException("unsupported output format");
        }

        private static void CopyOutput(object data, long frameOffset, int frames,
                                       int channels, int bytesPerSample, IntPtr target)
        {
            int sampleOffset = (int)(frameOffset * channels);
            int sampleCount = frames * channels;
            if (data is float[] floats)
            {
                Marshal.Copy(floats, sampleOffset, target, sampleCount);
            }
            else if (data is short[] shorts)
            {
                Marshal.Copy(shorts, sampleOffset, target, sampleCount);
            }
            else if (data is int[] ints)
            {
                Marshal.Copy(ints, sampleOffset, target, sampleCount);
            }
            else if (data is byte[] bytes)
            {
                Marshal.Copy(bytes, sampleOffset * bytesPerSample, target,
                             sampleCount * bytesPerSample);
            }
            else
            {
                throw new InvalidOperationException("unsupported sample buffer");
            }
        }

        private static WavData ReadWav(string path)
        {
            byte[] bytes = File.ReadAllBytes(path);
            if (bytes.Length < 12 ||
                Encoding.ASCII.GetString(bytes, 0, 4) != "RIFF" ||
                Encoding.ASCII.GetString(bytes, 8, 4) != "WAVE")
            {
                throw new InvalidDataException("not a RIFF/WAVE file");
            }

            int formatTag = 0;
            int channels = 0;
            int rate = 0;
            int bits = 0;
            Guid subFormat = Guid.Empty;
            int dataOffset = -1;
            int dataSize = 0;
            int position = 12;

            while (position + 8 <= bytes.Length)
            {
                string id = Encoding.ASCII.GetString(bytes, position, 4);
                int size = BitConverter.ToInt32(bytes, position + 4);
                int body = position + 8;
                if (id == "fmt ")
                {
                    formatTag = BitConverter.ToUInt16(bytes, body);
                    channels = BitConverter.ToUInt16(bytes, body + 2);
                    rate = BitConverter.ToInt32(bytes, body + 4);
                    bits = BitConverter.ToUInt16(bytes, body + 14);
                    if (formatTag == 0xFFFE && size >= 40)
                    {
                        subFormat = new Guid(SubArray(bytes, body + 24, 16));
                        formatTag = subFormat == SubtypeFloat ? 3 : 1;
                    }
                }
                else if (id == "data")
                {
                    dataOffset = body;
                    dataSize = size;
                    if (dataOffset + dataSize > bytes.Length)
                    {
                        dataSize = bytes.Length - dataOffset;
                    }
                    break;
                }
                position = body + size + (size & 1);
            }

            if (dataOffset < 0 || channels <= 0 || rate <= 0)
            {
                throw new InvalidDataException("WAV is missing fmt/data");
            }
            if (channels < 1 || channels > 2)
            {
                throw new InvalidDataException("only mono/stereo WAV is supported");
            }
            if (formatTag != 1 && formatTag != 3)
            {
                throw new InvalidDataException("only PCM or float WAV is supported");
            }

            int bytesPerSample = bits / 8;
            int frameBytes = channels * bytesPerSample;
            int frameCount = dataSize / frameBytes;
            float[] samples = new float[frameCount * 2];

            for (int frame = 0; frame < frameCount; ++frame)
            {
                float left = 0.0f;
                float right = 0.0f;
                for (int channel = 0; channel < channels; ++channel)
                {
                    int offset = dataOffset + frame * frameBytes +
                                 channel * bytesPerSample;
                    float value;
                    if (formatTag == 3)
                    {
                        value = bits == 64
                            ? (float)BitConverter.ToDouble(bytes, offset)
                            : BitConverter.ToSingle(bytes, offset);
                    }
                    else if (bits == 16)
                    {
                        value = BitConverter.ToInt16(bytes, offset) / 32768.0f;
                    }
                    else if (bits == 24)
                    {
                        int sample = bytes[offset] | (bytes[offset + 1] << 8) |
                                     (bytes[offset + 2] << 16);
                        if ((sample & 0x800000) != 0)
                        {
                            sample |= unchecked((int)0xFF000000);
                        }
                        value = sample / 8388608.0f;
                    }
                    else if (bits == 32)
                    {
                        value = BitConverter.ToInt32(bytes, offset) / 2147483648.0f;
                    }
                    else
                    {
                        throw new InvalidDataException("unsupported PCM depth");
                    }
                    if (channel == 0)
                    {
                        left = value;
                    }
                    else
                    {
                        right = value;
                    }
                }
                if (channels == 1)
                {
                    right = left;
                }
                samples[frame * 2] = left;
                samples[frame * 2 + 1] = right;
            }

            return new WavData
            {
                SampleRate = rate,
                Channels = 2,
                Samples = samples,
            };
        }

        private static byte[] SubArray(byte[] source, int offset, int length)
        {
            byte[] result = new byte[length];
            Array.Copy(source, offset, result, 0, length);
            return result;
        }

        private static FormatInfo ParseFormat(IntPtr format)
        {
            WaveFormatEx basic = Marshal.PtrToStructure<WaveFormatEx>(format);
            FormatInfo info = new FormatInfo
            {
                SampleRate = (int)basic.SampleRate,
                Channels = basic.Channels,
                Bits = basic.BitsPerSample,
                IsFloat = basic.FormatTag == 3,
            };
            if (basic.FormatTag == 0xFFFE)
            {
                WaveFormatExtensible extensible =
                    Marshal.PtrToStructure<WaveFormatExtensible>(format);
                info.IsFloat = extensible.SubFormat == SubtypeFloat;
                // The data buffer uses the container size (Format.BitsPerSample),
                // which can be 32 while ValidBits is 24.
                info.Bits = extensible.Format.BitsPerSample;
            }
            return info;
        }

        private static string DescribeFormat(IntPtr format)
        {
            FormatInfo info = ParseFormat(format);
            return info.SampleRate + " Hz, " + info.Channels + " ch, " +
                   info.Bits + "-bit " + (info.IsFloat ? "float" : "pcm");
        }

        private static IntPtr CreateFormat(int rate, int channels, int bits, bool isFloat)
        {
            WaveFormatExtensible format = new WaveFormatExtensible();
            format.Format.FormatTag = 0xFFFE;
            format.Format.Channels = (ushort)channels;
            format.Format.SampleRate = (uint)rate;
            format.Format.BitsPerSample = (ushort)bits;
            format.Format.BlockAlign = (ushort)(channels * bits / 8);
            format.Format.AvgBytesPerSec = (uint)(rate * channels * bits / 8);
            format.Format.ExtraSize = 22;
            format.ValidBits = (ushort)bits;
            format.ChannelMask = channels == 2 ? 3u : 0u;
            format.SubFormat = isFloat ? SubtypeFloat : SubtypePcm;
            IntPtr pointer = Marshal.AllocHGlobal(
                Marshal.SizeOf(typeof(WaveFormatExtensible)));
            Marshal.StructureToPtr(format, pointer, false);
            return pointer;
        }

        private static IAudioClient ActivateClient(IMMDevice device)
        {
            Guid iid = typeof(IAudioClient).GUID;
            object instance;
            int result = device.Activate(ref iid, ClsCtxAll, IntPtr.Zero, out instance);
            if (result != 0)
            {
                return null;
            }
            return (IAudioClient)instance;
        }

        private static string FriendlyName(IMMDevice device)
        {
            IPropertyStore store;
            if (device.OpenPropertyStore(StgmRead, out store) != 0)
            {
                return "(unknown)";
            }
            PropertyKey key = new PropertyKey();
            key.FormatId = new Guid("a45c254e-df1c-4efd-8020-67d146a850e0");
            key.Id = 14;
            PropVariant value;
            string result = "(unknown)";
            if (store.GetValue(ref key, out value) == 0)
            {
                result = Marshal.PtrToStringUni(value.Pointer) ?? "(unknown)";
                Native.PropVariantClear(ref value);
            }
            Marshal.ReleaseComObject(store);
            return result;
        }

        private static void Check(int result, string operation)
        {
            if (result != 0)
            {
                throw new COMException(operation + " failed: 0x" +
                                       result.ToString("X8"), result);
            }
        }
    }
}
