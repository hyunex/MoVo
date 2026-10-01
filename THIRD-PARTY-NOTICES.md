# Third-Party Notices (MoVo)

This file lists the licenses of third-party components distributed with MoVo.
MoVo itself is licensed under the GNU General Public License v3.0 (see `LICENSE`).

## 1. mpv-android app code and JNI bridge (MIT)

`mpv-player/app/src/main/java/is/xyz/mpv/MPVLib.kt` is derived from
[mpv-android at ad98fc97ff1d25e217389e7238a1abda8c13a6c4](https://github.com/mpv-android/mpv-android/tree/ad98fc97ff1d25e217389e7238a1abda8c13a6c4)
and modified for MoVo; it is not a verbatim copy.
The modified MIT native bridge sources are in `mpv-player/native/bridge/`.
MoVo 1.0.29 rebuilds `libplayer.so` for `arm64-v8a`, `armeabi-v7a`, and
`x86_64` with NDK 29.0.14206865 and Android API 26. The bridge sends structured
START events with a 64-bit playlist ID and END events with reason, error,
and the same ID so that only the current item's normal EOF advances playback.
It links the retained packaged engine libraries, including `libc++_shared.so`;
this bridge build is not a rebuild of the complete native engine.
The upstream MIT copyright and license follow unchanged.

```
Copyright (c) 2016 Ilya Zhuravlev
Copyright (c) 2016 sfan5 <sfan5@live.de>

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
```

## 2. libmpv + FFmpeg native binaries (GPLv3) — `app/src/main/jniLibs/`

### Binary origin and evidence

The original 30 native libraries came from the mpv-android **2026-08-11**
universal APK. Its SHA256 is
`5b59f3b1fd43d536edf1d78d55f1f173970c3783dc1493dfab7c7a9f3780bc3d`.
The exact asset URL and original per-library hashes are recorded in
`mpv-player/native/sources.lock.json`; this lock, not a floating branch,
defines the source and binary inputs.

For MoVo 1.0.29, strict `package-native-sources.py record-binaries` verification
confirmed that **27 engine/runtime libraries retain their original bytes** and
the three `libplayer.so` libraries have been replaced by the modified bridge
builds. Observed embedded version/revision strings and upstream commit
resolution identify:

| Component | Retained engine revision |
| --- | --- |
| mpv | `0.41.0-922-gf4d13e1c2`, commit `f4d13e1c2c91f3a56e589aef9cb44cbc02e26e47` |
| FFmpeg | `n9.0` |
| libplacebo | `4d82c6898551068d4ae6a6b5538efcddc2c7cf64` |
| libass | `3087d2b2ffda76602a17f9b09d25cb8addc8d313` |
| dav1d | `54706fc6bc0cdecab7e9593974a4039cc038fca7` |

Other static dependency revisions are derived from the pinned upstream build
recipe, **not independently observed in each binary**. Each source lock entry
states its evidence type. Providing pinned corresponding sources and build
inputs does not claim a byte-identical recompilation of the complete engine.

### Licenses and corresponding source

- mpv as a whole is **GPLv2 or later**; the packaged engine is a GPL build.
  See the [Copyright file at the pinned mpv revision](https://github.com/mpv-player/mpv/blob/f4d13e1c2c91f3a56e589aef9cb44cbc02e26e47/Copyright).
- FFmpeg `n9.0` is configured with `--enable-gpl --enable-version3` and reports
  **"libavcodec license: GPL version 3 or later"**.
- The distributed APK is a combined work subject to **GPLv3**. MoVo's own code
  remains licensed under GPL-3.0 (`LICENSE`); replacing native components does
  not by itself change that license.

Corresponding source for this version is provided as
[`MoVo-corresponding-source.tar.gz`](https://github.com/hyunex/MoVo/releases/download/v1.0.29/MoVo-corresponding-source.tar.gz)
with the [MoVo v1.0.29 release](https://github.com/hyunex/MoVo/releases/tag/v1.0.29).
The archive includes the application, modified JNI sources, build scripts,
license files, the central source lock, the public native binary manifest,
and **all 22 actual pinned upstream source archives**. It excludes precompiled
`.so` files, private signing keys/passwords, build outputs, logs, and temporary
verification fixtures. Upstream component license and copyright files are
included in their source archives.

`package-native-sources.py prepare` checks each archive's size/SHA256 before
extracting the original pinned mpv-android build scripts and dependencies.
It stages all nested libplacebo and FreeType submodules; the official mbedTLS
release archive includes its framework. Prepared existing dependency trees
allow the original `download-deps.sh` to skip downloads/clones, rather than
resolve floating revisions. The lock includes the exact URLs, sizes,
checksums, revisions, and nested preparation paths.

Source preparation, binary recording, and bundling are implemented by
[`package-native-sources.py`](package-native-sources.py); bridge-building by
[`mpv-player/app/buildNative.py`](mpv-player/app/buildNative.py). A full
engine rebuild uses the original scripts in the prepared `buildscripts/`
tree and their required toolchain, followed by the modified bridge build and
Gradle APK build. `mpv-player/app/buildNative.py` checks pinned source archives,
generates FFmpeg headers through its configure step, and compiles only the
three JNI bridge targets. No complete-engine recompilation or device/runtime
verification is asserted by this notice.

## 3. AndroidX / Jetpack / Material / Kotlin (Apache License 2.0)

The following Gradle runtime dependencies are Apache-2.0 licensed. Their
upstream license/copyright notices must be preserved in binary and source
distribution alongside this summary:

- `androidx.core:core-ktx`, `androidx.activity:activity-compose`,
  `androidx.appcompat:appcompat`, `androidx.lifecycle:lifecycle-runtime-compose`,
  `androidx.documentfile:documentfile`, `androidx.datastore:datastore-preferences`,
  `androidx.room:room-*`, `androidx.compose:compose-bom` managed artifacts
  (`ui`, `material3`, `material-icons-extended`)
- `com.google.android.material:material`
- `org.jetbrains.kotlinx:kotlinx-coroutines-android` + Kotlin stdlib

JUnit 4 is Eclipse Public License 1.0 licensed, test scope only, and is not
distributed in the APK.

