cmake_minimum_required(VERSION 3.22.1)
project(dobby-llama CXX)

# llama.cpp is a git submodule pinned at a commit, not a tarball and not a prebuilt.
#
# No upstream artifact exposes grammar-constrained sampling together with KV control, and we
# need our own JNI shim compiled against llama.h either way — so the NDK is required whatever
# we do, and a prebuilt would only add four more .so files to an APK whose whole discipline is
# one of each. GitHub's codeload tarballs are not contractually byte-stable, so a sha256 over
# one is a pin that can break without anything changing; a submodule at a commit IS the pin.
#
# See README.md in this directory for the tag, the commit and how to bump them.
if(NOT EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/CMakeLists.txt")
    message(FATAL_ERROR
        "android/llama/llama.cpp is empty. Run:\n"
        "    git submodule update --init --recursive\n"
        "That is the whole fetch step.")
endif()

# One static library, and nothing else built at all. Every option here is either "make the
# output smaller" or "do not compile a tool we will never ship".
set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE            OFF CACHE BOOL "" FORCE)  # cross-compiling; -march comes from us
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)  # no libomp in the NDK sysroot
set(GGML_BACKEND_DL        OFF CACHE BOOL "" FORCE)  # one .so, loaded once, no plugin search
set(GGML_CPU_ALL_VARIANTS  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON     OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL             OFF CACHE BOOL "" FORCE)

# Release for BOTH variants. AGP hands the debug variant -O0, and the debug APK is the one that
# gets sideloaded — a Q4_K decode at -O0 is not slow, it is unusable.
if(NOT CMAKE_BUILD_TYPE STREQUAL "Release")
    message(STATUS "dobby-llama: forcing Release (was '${CMAKE_BUILD_TYPE}')")
    set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
endif()
set(CMAKE_CXX_FLAGS_DEBUG "-O3 -DNDEBUG" CACHE STRING "" FORCE)
set(CMAKE_C_FLAGS_DEBUG   "-O3 -DNDEBUG" CACHE STRING "" FORCE)

# DOBBY_CPU_ARCH is passed in from build.gradle.kts, which is also where it is documented.
# It is not trusted at run time: Llama.kt reads /proc/cpuinfo and compares it against
# DOBBY_REQUIRED_CPU_FEATURES *before* System.loadLibrary, so a device without the feature
# disables Tier 2 rather than taking SIGILL.
if(DEFINED DOBBY_CPU_ARCH AND NOT DOBBY_CPU_ARCH STREQUAL "")
    set(GGML_CPU_ARM_ARCH "${DOBBY_CPU_ARCH}" CACHE STRING "" FORCE)
    message(STATUS "dobby-llama: GGML_CPU_ARM_ARCH=${DOBBY_CPU_ARCH}")
endif()

add_subdirectory(llama.cpp EXCLUDE_FROM_ALL)

add_library(dobby-llama SHARED src/main/cpp/dobby_llama.cpp)

target_link_libraries(dobby-llama PRIVATE llama ggml android log)

target_compile_options(dobby-llama PRIVATE
    -fvisibility=hidden -ffunction-sections -fdata-sections -fno-exceptions -fno-rtti)

# The same ELF shape as libsherpa-onnx-jni.so: DT_NEEDED is libandroid/liblog/libm/libdl/libc
# and nothing else, and the only exported symbols are the JNI entry points. --exclude-libs
# keeps llama's and ggml's symbols out of the dynamic table even though they are linked in
# statically, which is what stops them colliding with anything ONNX Runtime brings.
target_link_options(dobby-llama PRIVATE
    "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp/dobby_llama.map"
    "-Wl,--exclude-libs,ALL"
    "-Wl,--gc-sections"
    "-Wl,--as-needed")
