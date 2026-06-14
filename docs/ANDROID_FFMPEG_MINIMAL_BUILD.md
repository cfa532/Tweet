# Minimal FFmpegKit Android Build Memo

Last updated: June 2026

The Play flavor needs local FFmpeg for the same narrow video path as iOS:

- run commands through `FFmpegKit`
- remux already-normalized H.264/AAC input to HLS when possible
- encode AAC audio
- scale video when needed
- produce HLS VOD output with MPEG-TS segments and `master.m3u8`

## Current Checked-In State

The app now uses a smaller full/play AAR:

- `app/libs/ffmpeg-kit-16kb-mediacodec-arm64.aar`
- compressed size: about 6.8 MB
- ABI: `arm64-v8a` only
- source: FFmpegKit v6.0 Android main build with Android MediaCodec enabled
- 16 KB ELF LOAD segment alignment: verified as `2**14`
- configured without `--enable-gpl` or `--enable-libx264`
- verified encoder strings include `h264_mediacodec`

## Active Command Contract

The Android code currently requires the active FFmpegKit AAR to support:

| Code path | Required FFmpeg pieces |
| --- | --- |
| `LocalHLSConverter` copy/remux path | `-c:v copy`, FFmpeg AAC encoder, file protocol, `h264_mp4toannexb` bitstream filter, HLS muxer, MPEG-TS segments |
| `LocalHLSConverter` re-encode path | `h264_mediacodec`, FFmpeg AAC encoder, `scale` filter, `yuv420p`, `h264_mp4toannexb` bitstream filter, HLS muxer, MPEG-TS segments |
| `VideoNormalizer` | `h264_mediacodec`, FFmpeg AAC encoder, `scale`, MP4 muxer, `+faststart` |
| `LocalVideoProcessingService` legacy normalization | `h264_mediacodec`, FFmpeg AAC encoder, `scale`, MP4 muxer, `+faststart` |

The checked-in full/play AAR meets this contract without GPL/x264 by using Android's platform H.264 encoder through `h264_mediacodec`. Revalidate on real devices because MediaCodec behavior depends on the device encoder.

## Target Rebuild

Use the archived upstream FFmpegKit source outside this repo:

```bash
git clone https://github.com/arthenica/ffmpeg-kit.git
cd ffmpeg-kit
git checkout v6.0
```

Build Android main release, arm64 only, no GPL, no `--full`, with Android platform codecs:

```bash
export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
export ANDROID_NDK_ROOT="/path/to/android-ndk-r25b-or-compatible"

./android.sh \
  --enable-android-media-codec \
  --disable-arm-v7a \
  --disable-arm-v7a-neon \
  --disable-x86 \
  --disable-x86-64
```

Do not pass:

- `--full`
- `--enable-gpl`
- `--enable-lib-x264`
- `--enable-lib-x265`
- `--enable-lib-vpx`
- TLS/font/image/subtitle libraries unless a new app feature needs them

After the rebuild, replace:

```text
app/libs/ffmpeg-kit-16kb-mediacodec-arm64.aar
```

Android video commands must use `h264_mediacodec` where re-encoding is required. Keep the copy/remux fast path. HLS outputs must pass H.264 through `h264_mp4toannexb`; MP4 normalization outputs must not.

## 16 KB Alignment Checks

Google's current Android guidance requires apps submitted to Play and targeting Android 15+ devices to support 16 KB page sizes on 64-bit devices. For native libraries that means both ELF LOAD segment alignment and APK/AAB packaging alignment must be correct.

Check ELF LOAD alignment:

```bash
unzip -q app/libs/ffmpeg-kit-16kb-mediacodec-arm64.aar -d /tmp/ffmpeg-kit-aar
llvm-objdump -p /tmp/ffmpeg-kit-aar/jni/arm64-v8a/libavcodec.so | grep LOAD
```

Every LOAD line should show at least:

```text
align 2**14
```

Check APK zip alignment after building:

```bash
./gradlew :app:assemblePlayRelease
$ANDROID_SDK_ROOT/build-tools/37.0.0/zipalign -v -c -P 16 4 \
  app/build/outputs/apk/play/release/app-play-release.apk
```

For an AAB, confirm bundle alignment metadata:

```bash
bundletool dump config --bundle app/build/outputs/bundle/playRelease/app-play-release.aab | grep alignment
```

Expected output includes:

```text
PAGE_ALIGNMENT_16K
```
