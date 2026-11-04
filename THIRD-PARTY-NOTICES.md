# Third-Party Notices (MoVo)

This file lists the licenses of third-party components distributed with MoVo.
MoVo itself is licensed under the GNU General Public License v3.0 (see `LICENSE`).

## 1. mpv-android app code and JNI bridge (MIT)

`mpv-player/app/src/main/java/is/xyz/mpv/MPVLib.kt` is derived from
[mpv-android at ad98fc97ff1d25e217389e7238a1abda8c13a6c4](https://github.com/mpv-android/mpv-android/tree/ad98fc97ff1d25e217389e7238a1abda8c13a6c4)
and modified for MoVo; it is not a verbatim copy.
The modified MIT native bridge sources are in `mpv-player/native/bridge/`.
MoVo 1.0.35 rebuilds `libplayer.so` for `arm64-v8a`, `armeabi-v7a`, and
`x86_64` with NDK 29.0.14206865 and Android API 26. The bridge retains structured
START events with a 64-bit playlist ID and END events with reason, error,
and the same ID so that only the current item's normal EOF advances playback.
It now also probes media through the retained FFmpeg libraries for full duration,
coded video dimensions, and embedded subtitle streams (including ASS).
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
[`MoVo-corresponding-source.tar.gz`](https://github.com/hyunex/MoVo/releases/download/v1.0.35/MoVo-corresponding-source.tar.gz)
with the [MoVo v1.0.35 release](https://github.com/hyunex/MoVo/releases/tag/v1.0.35).
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

## 3. Native components inside the engine libraries

The engine libraries in `app/src/main/jniLibs/` and `libc++_shared.so` are
built from the archives pinned in `mpv-player/native/sources.lock.json`.
Each row names one lock entry, its pinned revision, its SPDX license
expression, and the upstream copyright line with the file it was taken from.
Rows marked "not in the APK" are build-time tools or demos. The complete
license texts are in the named files inside each archive; all archives ship
in `source-archives/` of `MoVo-corresponding-source.tar.gz`.
`package-native-sources.py bundle` refuses to build that archive while a lock
entry has no row here.

| Lock entry | Revision | SPDX license | Copyright (license file) |
| --- | --- | --- | --- |
| `mpv` | `f4d13e1c2c91f3a56e589aef9cb44cbc02e26e47` | GPL-2.0-or-later | mpv, mplayer2 and MPlayer contributors (`Copyright`, `LICENSE.GPL`) |
| `ffmpeg` | `n9.0` | GPL-3.0-or-later (built with `--enable-gpl --enable-version3`) | the FFmpeg developers (`LICENSE.md`, `COPYING.GPLv3`) |
| `jni` | `ad98fc97ff1d25e217389e7238a1abda8c13a6c4` | MIT | Copyright (c) 2016 Ilya Zhuravlev; Copyright (c) 2016 sfan5 (`LICENSE`, quoted in section 1) |
| `libplacebo` | `4d82c6898551068d4ae6a6b5538efcddc2c7cf64` | LGPL-2.1-or-later | libplacebo contributors; the file has no central copyright line (`LICENSE`, source headers) |
| `placebo-vulkan-headers` | `74d8a6cb930c68ef617b202c3ff3c59d919e086b` | Apache-2.0 OR MIT | Copyright 2015-2023 The Khronos Group Inc. (`LICENSE.md`) |
| `placebo-fast-float` | `97b54ca9e75f5303507699d27c6b4f4efe4641a1` | Apache-2.0 OR BSL-1.0 OR MIT | Copyright (c) 2021 The fast_float authors (`LICENSE-MIT`, `LICENSE-APACHE`, `LICENSE-BOOST`) |
| `placebo-glad` | `73db193f853e2ee079bf3ca8a64aa2eaf6459043` | MIT AND Apache-2.0 | Copyright (c) 2013-2022 David Herberth; Copyright (c) 2013-2020 The Khronos Group Inc. (`LICENSE`) |
| `placebo-jinja` | `15206881c006c79667fe5154fe80c01c65410679` | BSD-3-Clause | Copyright 2007 Pallets (`LICENSE.txt`); build-time code generator only, not in the APK |
| `placebo-markupsafe` | `297fc8e356e6836a62087949245d09a28e9f1b13` | BSD-3-Clause | Copyright 2010 Pallets (`LICENSE.txt`); build-time code generator only, not in the APK |
| `placebo-nuklear` | `242f35efa067a46c595645eeda7b1771ea1f83b1` | MIT OR Unlicense | Copyright (c) 2017 Micha Mettke (`LICENSE`); libplacebo demos only, not in the APK |
| `libass` | `3087d2b2ffda76602a17f9b09d25cb8addc8d313` | ISC | Copyright (C) 2006-2016 libass contributors (`COPYING`) |
| `dav1d` | `54706fc6bc0cdecab7e9593974a4039cc038fca7` | BSD-2-Clause | Copyright © 2018-2025, VideoLAN and dav1d authors (`COPYING`) |
| `mbedtls` | `3.6.7` | Apache-2.0 OR GPL-2.0-or-later | Copyright The Mbed TLS Contributors (source headers; `LICENSE`) |
| `freetype` | `0a0221a1347e2f1e07c395263540026e9a0aa7c7` | FTL OR GPL-2.0-or-later | Copyright (C) 1996-2026 by David Turner, Robert Wilhelm, and Werner Lemberg (`include/freetype/freetype.h`; `LICENSE.TXT`, `docs/FTL.TXT`) |
| `freetype-dlg` | `395ccad2c1e0daae535c4d20bb0a3f2424648e17` | BSL-1.0 | Copyright (c) 2019 nyorain (`include/dlg/dlg.h`; `LICENSE`); compiled only with `FT_DEBUG_LOGGING` |
| `fribidi` | `1.0.16` | LGPL-2.1-or-later | Copyright (C) 2004 Sharif FarsiWeb, Inc; Copyright (C) 2001,2002 Behdad Esfahbod; Copyright (C) 1999,2000 Dov Grobgeld (`COPYING`, `lib/fribidi.c`) |
| `harfbuzz` | `14.2.1` | MIT-Modern-Variant | Copyright © 2010-2022 Google, Inc. and the other holders listed in `COPYING` |
| `libunibreak` | `7.0` | Zlib | Copyright (C) Wu Yongwei, Tom Hacohen, Petr Filipsky, Andreas Röver (`LICENCE`) |
| `libxml2` | `2.15.3` | MIT | Copyright (C) 1998-2012 Daniel Veillard; Copyright (C) The Libxml2 Contributors (`Copyright`) |
| `fontconfig` | `2.18.2` | HPND-sell-variant, plus the per-file notices in `COPYING` | Copyright © 2000-2007 Keith Packard and the other holders listed in `COPYING` |
| `lua` | `5.2.4` | MIT | Copyright (C) 1994-2015 Lua.org, PUC-Rio (`src/lua.h`) |
| `curl` | `8.21.0` | curl | Copyright (c) 1996 - 2026, Daniel Stenberg, <daniel@haxx.se>, and many contributors (`COPYING`) |
| `libc++` | `NDK 29.0.14206865` | Apache-2.0 WITH LLVM-exception | LLVM Project contributors; legacy code Copyright (c) 2003-2019 University of Illinois at Urbana-Champaign (NDK `toolchains/llvm/prebuilt/*/NOTICE`); shipped as `libc++_shared.so` |

Portions of this software are copyright © 2026 The FreeType
Project (https://freetype.org).  All rights reserved.

## 4. AndroidX / Jetpack / Material / Kotlin (Apache License 2.0)

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

