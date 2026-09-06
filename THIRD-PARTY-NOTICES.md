# Third-Party Notices (MoVo)

This file lists the licenses of third-party components distributed with MoVo.
MoVo itself is licensed under the GNU General Public License v3.0 (see `LICENSE`).

## 1. mpv-android app code (MIT) — vendored as `MPVLib.kt`

`mpv-player/app/src/main/java/is/xyz/mpv/MPVLib.kt` is taken verbatim from
[mpv-android](https://github.com/mpv-android/mpv-android) and is used under
the MIT license reproduced below. The JNI bridge binary `libplayer.so`
(`app/src/main/jniLibs/<abi>/libplayer.so`) is the compiled counterpart of
that project's native bridge.

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

`libmpv.so` and the `libav*.so` / `libsw*.so` libraries were produced by the
[mpv-android buildscripts](https://github.com/mpv-android/mpv-android/tree/master/buildscripts)
(forensic marker: build path `/var/tmp/mpv-android/buildscripts/` and the
`List of enabled features` string embedded in `libmpv.so`, which contains
the `gpl` feature flag).

- mpv as a whole is **GPLv2 or later**; this binary is a GPL build
  (see [mpv Copyright](https://github.com/mpv-player/mpv/blob/master/Copyright)).
- The bundled FFmpeg (n9.0) is configured with `--enable-gpl --enable-version3`
  and reports **"libavcodec license: GPL version 3 or later"**.

Consequences for this project:

- The distributed APK is a combined work subject to **GPLv3**. That is why
  MoVo itself is licensed under GPL-3.0 (see `LICENSE`).
- Corresponding source for the app (including build scripts and this notice)
  is this repository. Corresponding source for the vendored `.so` binaries is
  the mpv-android project at the release they were taken from, built with its
  published buildscripts; its full source is available at
  <https://github.com/mpv-android/mpv-android>.
- If you replace the vendored binaries with an LGPL FFmpeg/mpv build, the
  app's own-code licensing obligations relax accordingly — but as long as
  the GPL binaries ship in `jniLibs/`, GPLv3 applies to distribution.

## 3. AndroidX / Jetpack / Material / Kotlin (Apache License 2.0)

The following Gradle dependencies are Apache-2.0 licensed. Their copyright
notices travel inside their AARs; this notice satisfies the attribution
requirement for binary distribution:

- `androidx.core:core-ktx`, `androidx.activity:activity-compose`,
  `androidx.appcompat:appcompat`, `androidx.lifecycle:lifecycle-runtime-compose`,
  `androidx.documentfile:documentfile`, `androidx.datastore:datastore-preferences`,
  `androidx.room:room-*`, `androidx.compose:compose-bom` managed artifacts
  (`ui`, `material3`, `material-icons-extended`)
- `com.google.android.material:material`
- `org.jetbrains.kotlinx:kotlinx-coroutines-android` + Kotlin stdlib
- JUnit 4 (Eclipse Public License 1.0, test scope only — not distributed)

