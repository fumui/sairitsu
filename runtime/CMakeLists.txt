cmake_minimum_required(VERSION 3.22)
project(sairitsu_runtime LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

option(SR_ANDROID "Build the Android shared library" OFF)
# One logcat line per counted Play judgment (debug builds only; the PC tools read it).
option(SR_JUDGMENT_LOG "Log every counted Play judgment to logcat" OFF)

# Floating point policy: no FMA contraction, no fast-math.
# Set at directory level before any target is defined, so every target
# (including vendored libraries added later) inherits it.
if(MSVC)
    # cl and clang-cl: /fp:strict disables contraction and fast-math.
    add_compile_options(/fp:strict)
else()
    # -fno-math-errno lets sqrt compile to the hardware instruction instead of a libm call (results are identical).
    add_compile_options(-ffp-contract=off -fno-fast-math -fno-math-errno)
endif()

set(SR_SOURCES
    src/util/fp.cpp
    src/data/gzip.cpp
    src/data/zip.cpp
    src/data/json.cpp
    src/data/png.cpp
    src/data/engine_data.cpp
    src/data/configuration.cpp
    src/data/level_data.cpp
    src/data/skin.cpp
    src/data/particle_data.cpp
    src/data/effect_data.cpp
    src/data/background.cpp
    src/data/resources.cpp
    src/vm/fmath.cpp
    src/vm/fmath_sleef.cpp
    src/vm/convert.cpp
    src/vm/easing.cpp
    src/vm/blocks.cpp
    src/vm/memory.cpp
    src/vm/functions.cpp
    src/vm/compiler.cpp
    src/vm/interpreter.cpp
    src/time/time_tables.cpp
    src/time/frame_clock.cpp
    src/modes/session_core.cpp
    src/modes/watch_session.cpp
    src/modes/play_session.cpp
    src/modes/session_host.cpp
    src/modes/stats.cpp
    src/modes/judgment_log.cpp
    src/modes/replay.cpp
    src/modes/replay_writer.cpp
    src/modes/replay_file.cpp
    src/modes/replay_view.cpp
    src/modes/gameplay_result.cpp
    src/modes/streams.cpp
    src/modes/touch.cpp
    src/modes/worker_pool.cpp
    src/render/geometry.cpp
    src/render/background_layout.cpp
    src/render/background_blur.cpp
    src/render/ui.cpp
    src/particle/particle_system.cpp
    src/audio/bgm_duration.cpp
    src/audio/decode.cpp
    src/audio/resample.cpp
    src/audio/mixer.cpp
    src/audio/music_clock.cpp
)
# Platform audio decoding (M4A / AAC BGMs): MediaCodec wherever the NDK toolchain builds (the app's library and the
# device srun / sr_tests executables), a stub that declines on the host.
if(ANDROID)
    list(APPEND SR_SOURCES src/audio/platform_decode_android.cpp)
else()
    list(APPEND SR_SOURCES src/audio/platform_decode_none.cpp)
endif()

# Vendored libraries compile into sairitsu_runtime so they inherit the fp flags above.
set(SR_THIRD_PARTY_SOURCES
    third_party/yyjson/yyjson.c
    third_party/miniz/miniz.c
    third_party/miniz/miniz_tdef.c
    third_party/miniz/miniz_tinfl.c
    third_party/miniz/miniz_zip.c
    third_party/stb/stb_image_impl.c
    third_party/stb/stb_vorbis_impl.c
    third_party/stb/sr_vorbis_compat.c
    third_party/minimp3/minimp3_impl.c
    third_party/speexdsp/libspeexdsp/resample.c
    third_party/speexdsp/sr_compat.c
)

# Vendored musl float math (renamed sr_musl_*; see third_party/musl-math/README.md).
set(SR_MUSL_SOURCES
    __math_divzerof __math_invalidf __math_oflowf __math_uflowf __math_xflowf
    ceilf exp2f exp2f_data expf floorf fmodf log2f log2f_data roundf truncf
)
set(SR_MUSL_FILES "")
foreach(name ${SR_MUSL_SOURCES})
    list(APPEND SR_MUSL_FILES third_party/musl-math/${name}.c)
endforeach()

if(SR_ANDROID)
    # The GLES3 renderer and the live loop (Choreographer + EGL) exist on Android only.
    set(SR_ANDROID_SOURCES
        src/render/shaders.cpp
        src/render/gl_renderer.cpp
        src/android/aaudio_output.cpp
        src/android/gameplay_loop.cpp
        src/android/jni_bridge.cpp
        src/android/render_at_times.cpp
    )
    add_library(sairitsu_runtime SHARED ${SR_SOURCES} ${SR_THIRD_PARTY_SOURCES} ${SR_MUSL_FILES} ${SR_ANDROID_SOURCES})
    # nativewindow: the newer ANativeWindow_* calls live only there; as weak symbols they would resolve to null
    # if only libandroid were linked.
    target_link_libraries(sairitsu_runtime PRIVATE aaudio android nativewindow log EGL GLESv3)
    # minSdk 26: APIs from 28 (dataspace), 29 (postFrameCallback64) and 30 (setFrameRate) are weak symbols, called
    # behind __builtin_available.
    target_compile_definitions(sairitsu_runtime PRIVATE __ANDROID_UNAVAILABLE_SYMBOLS_ARE_WEAK__)
    target_compile_options(sairitsu_runtime PRIVATE -Werror=unguarded-availability)
    if(SR_JUDGMENT_LOG)
        target_compile_definitions(sairitsu_runtime PRIVATE SR_JUDGMENT_LOG=1)
    endif()
else()
    add_library(sairitsu_runtime STATIC ${SR_SOURCES} ${SR_THIRD_PARTY_SOURCES} ${SR_MUSL_FILES})
endif()
if(ANDROID)
    target_link_libraries(sairitsu_runtime PUBLIC mediandk)  # platform_decode_android.cpp
endif()
target_include_directories(sairitsu_runtime PUBLIC src)
target_include_directories(sairitsu_runtime PRIVATE
    third_party/yyjson third_party/miniz third_party/stb third_party/minimp3
    third_party/speexdsp/include/speex third_party/speexdsp/libspeexdsp)
set_source_files_properties(${SR_THIRD_PARTY_SOURCES} PROPERTIES COMPILE_OPTIONS "-w")
target_compile_definitions(sairitsu_runtime PRIVATE OUTSIDE_SPEEX RANDOM_PREFIX=sr)  # speexdsp symbol prefix (sr_speex_resampler_*)
# speexdsp: the float resampler on its own; sin() and floor() are routed away from the platform libm.
set_source_files_properties(third_party/speexdsp/libspeexdsp/resample.c PROPERTIES COMPILE_OPTIONS
    "-w;-DFLOATING_POINT;-include;${CMAKE_CURRENT_SOURCE_DIR}/third_party/speexdsp/sr_compat.h")
# stb_vorbis: its table setup's sin, cos, exp, log, pow, floor and ldexp are routed away from the platform libm.
set_source_files_properties(third_party/stb/stb_vorbis_impl.c PROPERTIES COMPILE_OPTIONS
    "-w;-include;${CMAKE_CURRENT_SOURCE_DIR}/third_party/stb/sr_vorbis_compat.h")
# musl: force-include the rename header; no builtin idiom recognition (floorf must not become a floorf call)
# and no errno-setting sqrt, so nothing resolves to the platform libm.
set_source_files_properties(${SR_MUSL_FILES} PROPERTIES COMPILE_OPTIONS
    "-w;-fno-builtin;-fno-math-errno;-I${CMAKE_CURRENT_SOURCE_DIR}/third_party/musl-math;-include;${CMAKE_CURRENT_SOURCE_DIR}/third_party/musl-math/sr_musl_rename.h")

if(NOT SR_ANDROID)
    # srun_core (tools/srun): the headless Watch and Play drivers, dump format and sha256 the tests use.
    add_library(srun_core STATIC
        tools/srun/sha256.cpp
        tools/srun/dump.cpp
        tools/srun/watch.cpp
        tools/srun/play.cpp
    )
    target_include_directories(srun_core PUBLIC tools PRIVATE third_party/yyjson)
    target_link_libraries(srun_core PUBLIC sairitsu_runtime)
endif()

if(NOT SR_ANDROID)
    # sr_tests builds for the host and, with the NDK toolchain (preset android-arm64), as a plain executable that
    # tools/sairitsu_tools/tests_device.py pushes to /data/local/tmp/sr_tests and runs from the adb shell.
    if(ANDROID)
        set(SR_TEST_DATA_DIR "/data/local/tmp/sr_tests")
        set(SR_TEST_FIXTURES_PATH "${SR_TEST_DATA_DIR}/fixtures")
    else()
        enable_testing()
        set(SR_TEST_FIXTURES_PATH "${CMAKE_CURRENT_SOURCE_DIR}/tests/fixtures")
    endif()

    set(SR_TEST_SOURCES
        tests/main.cpp
        tests/util/fp_test.cpp
        tests/data/gzip_test.cpp
        tests/data/engine_data_test.cpp
        tests/data/level_data_test.cpp
        tests/data/assets_test.cpp
        tests/vm/fmath_test.cpp
        tests/vm/convert_test.cpp
        tests/vm/easing_test.cpp
        tests/vm/blocks_test.cpp
        tests/vm/compiler_test.cpp
        tests/vm/interpreter_test.cpp
        tests/vm/golden_test.cpp
        tests/vm/edge_cases_test.cpp
        tests/time/time_tables_test.cpp
        tests/time/frame_clock_test.cpp
        tests/audio/bgm_duration_test.cpp
        tests/audio/decode_test.cpp
        tests/audio/mixer_test.cpp
        tests/audio/music_clock_test.cpp
        tests/modes/watch_prepare_test.cpp
        tests/modes/watch_update_test.cpp
        tests/modes/play_session_test.cpp
        tests/modes/stats_test.cpp
        tests/modes/judgment_log_test.cpp
        tests/modes/touch_test.cpp
        tests/modes/replay_test.cpp
        tests/modes/replay_writer_test.cpp
        tests/modes/replay_file_test.cpp
        tests/modes/replay_view_test.cpp
        tests/modes/gameplay_result_test.cpp
        tests/modes/replay_feed_test.cpp
        tests/render/geometry_test.cpp
        tests/render/background_layout_test.cpp
        tests/render/background_blur_test.cpp
        tests/render/ui_test.cpp
        tests/particle/particle_system_test.cpp
        tests/tools/srun_dump_test.cpp
    )
    add_executable(sr_tests ${SR_TEST_SOURCES})
    target_include_directories(sr_tests PRIVATE third_party/doctest third_party/miniz third_party/yyjson tests tools)
    target_compile_definitions(sr_tests PRIVATE SR_FIXTURE_DIR="${SR_TEST_FIXTURES_PATH}"
        SR_TEST_FIXTURES="${SR_TEST_FIXTURES_PATH}")
    target_link_libraries(sr_tests PRIVATE sairitsu_runtime srun_core)

    if(NOT ANDROID)
        add_test(NAME sr_tests COMMAND sr_tests)

        # No float math may resolve to the platform libm (see src/vm/fmath.h).
        find_program(SR_LLVM_NM NAMES llvm-nm HINTS "C:/Program Files/LLVM/bin")
        add_test(NAME no_platform_libm
            COMMAND ${CMAKE_COMMAND} "-DNM=${SR_LLVM_NM}" "-DLIB=$<TARGET_FILE:sairitsu_runtime>"
                    -P ${CMAKE_CURRENT_SOURCE_DIR}/tests/no_platform_libm.cmake)
    endif()
endif()
