# Third-party notices

Sairitsu's native runtime vendors the libraries below under `runtime/third_party/`. Each folder holds the
library's full licence text; the folder's `README.md` lists the version and any local changes.

| Library | Used for | Licence | Licence file |
| --- | --- | --- | --- |
| [doctest](https://github.com/doctest/doctest) | C++ unit tests (not shipped in the app) | MIT | `runtime/third_party/doctest/LICENSE.txt` |
| [minimp3](https://github.com/lieff/minimp3) | MP3 decoding | CC0 1.0 | `runtime/third_party/minimp3/LICENSE` |
| [miniz](https://github.com/richgel999/miniz) | gzip / deflate | MIT | `runtime/third_party/miniz/LICENSE` |
| [musl](https://musl.libc.org) (selected `libm` files) | portable float math | MIT | `runtime/third_party/musl-math/COPYRIGHT` |
| [SLEEF](https://sleef.org) (derived code in `runtime/src/vm/fmath_sleef.cpp`) | transcendental functions | Boost Software License 1.0 | `runtime/third_party/sleef/LICENSE.txt` |
| [SpeexDSP](https://github.com/xiph/speexdsp) (resampler) | audio resampling | BSD 3-Clause | `runtime/third_party/speexdsp/LICENSE` |
| [stb_image, stb_vorbis](https://github.com/nothings/stb) | PNG and Ogg Vorbis decoding | MIT or public domain | `runtime/third_party/stb/LICENSE` |
| [yyjson](https://github.com/ibireme/yyjson) | JSON parsing | MIT | `runtime/third_party/yyjson/LICENSE` |

The MIT, BSD 3-Clause and Boost licences require their notices to accompany binary distributions. If you ship an
APK, include this file and the licence files above (for example in an in-app "Open source licences" screen).

The Android app's Gradle dependencies (AndroidX, Jetpack Compose, Kotlin, kotlinx.serialization, OkHttp, ...)
are fetched from Maven and carry their own licences, mostly Apache 2.0.
