# `:android:llama` — Tier 2's native half

llama.cpp plus a small JNI shim, producing one `libdobby-llama.so`. This is the first NDK/CMake
build in the repo; everything else about Tier 2 is pure `:core` and is tested on a laptop.

## The pin

| | |
|---|---|
| Submodule | `llama.cpp` → [`ggml-org/llama.cpp`](https://github.com/ggml-org/llama.cpp) |
| Tag | `v0.4.1` |
| Commit | `b29c606e28a01b1bc8c1351026a0fa6e616bf6c4` |

**Fetch it before building anything:**

```
git submodule update --init --recursive
```

That is the whole fetch step, and it is why this is a submodule rather than a tarball: GitHub's
codeload tarballs are not contractually byte-stable, so a sha256 over one is a pin that can
break without anything changing. A submodule at a commit *is* the content pin. Bumping it is a
submodule commit and an edit to this table, and nothing else.

> `m6-plan.md` records this pin as "tag v0.4.1 … commit `391fac16…`". Those are two different
> commits: `391fac16` is tag `b10969`. The tag was taken as authoritative, since that is what
> the plan names first and what upstream calls a release.

### Why not a prebuilt

The `:android:sherpa` fetch-a-prebuilt pattern does not transfer. No upstream artifact exposes
grammar-constrained sampling plus KV control; we need our own shim compiled against `llama.h`
either way, so the NDK is required regardless; and upstream prebuilts are
`BUILD_SHARED_LIBS=ON`, which is four more `.so` files in an APK whose whole discipline is one
of each.

## What is built

One shared library, statically linked, exporting ten symbols:

```
$ llvm-readelf -d libdobby-llama.so | grep NEEDED
  liblog.so   libm.so   libdl.so   libc.so
$ llvm-readelf --dyn-syms libdobby-llama.so | grep -c Java_io_dobby_llama
  10
```

No `libc++_shared.so` — `ANDROID_STL=c++_static`, which is safe here because no C++ object
crosses the JNI boundary; everything is a `jlong` handle or a `jstring`. `--exclude-libs,ALL`
plus the version script keep llama's and ggml's symbols out of the dynamic table, so they
cannot collide with the two copies of ONNX Runtime the same process already carries.

`CMAKE_BUILD_TYPE=Release` for **both** variants. AGP hands the debug variant `-O0`, and the
debug APK is the one that gets sideloaded — a Q4_K decode at `-O0` is not slow, it is unusable.

## `-march`, and the check that makes it safe

Built for `armv8.2-a+fp16+dotprod` (`cpuArch` in `build.gradle.kts`).

The Snapdragon 750G is 2×A77 + 6×A55, and `+dotprod` is **optional** on A55 — the A55 cluster is
what binds, because ggml's threads synchronise at a barrier per op. Confirm before changing it:

```
adb shell grep -m1 Features /proc/cpuinfo      # look for asimddp
```

`+i8mm` is definitively out: it needs armv8.6-a and this part is armv8.2-a.

**The flag is not trusted at run time.** `Llama.open()` reads `/proc/cpuinfo` and compares it
against `Llama.REQUIRED_CPU_FEATURES` *before* `System.loadLibrary`. A device without the
feature disables Tier 2 with a reason instead of taking SIGILL — which on a `START_STICKY`
service would be a boot loop, not a crash. `CpuFeatureTest` (JVM) synthesises the unsupported
CPU, because a device test can only ever test the device it is running on.

## Checking the generated grammar

`tools/gbnf_check.cpp` feeds a `.gbnf` file to llama.cpp's own parser. It needs no phone —
`llama_grammar_parser` is pure C++ over a string:

```
NDK=$ANDROID_HOME/ndk/28.2.13676358
CLANG=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/clang++
cmake -S llama.cpp -B /tmp/gbnf -GNinja -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=x86_64 -DANDROID_PLATFORM=android-33 \
  -DBUILD_SHARED_LIBS=OFF -DLLAMA_BUILD_TOOLS=OFF -DLLAMA_BUILD_EXAMPLES=OFF \
  -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_SERVER=OFF -DLLAMA_BUILD_COMMON=OFF \
  -DLLAMA_CURL=OFF -DGGML_OPENMP=OFF -DGGML_NATIVE=OFF
cmake --build /tmp/gbnf --target llama -j
$CLANG --target=x86_64-linux-android33 -std=c++17 -static -O1 \
  -Illama.cpp/src -Illama.cpp/include -Illama.cpp/ggml/include \
  tools/gbnf_check.cpp /tmp/gbnf/src/libllama.a /tmp/gbnf/ggml/src/libggml*.a -ldl \
  -o /tmp/gbnf/check
/tmp/gbnf/check ../../core/src/test/resources/tier2/grammar.gbnf root
```

### What it answered

These are the questions `m6-plan.md` defers to the native spike, settled against the pinned
parser rather than against tutorials:

- **Rule names must use hyphens.** `is_word_char` accepts `[a-zA-Z0-9-]` and not `_`
  (`src/llama-grammar.cpp:98`). Verified both ways: the generated grammar parses (67 rules,
  root found), and the same grammar with `cmd_clock_set_timer` fails with
  `expecting newline or end at _clock_set_timer`.
- **Bounded repetition is honoured.** `char{1,40}` parses. `MAX_REPETITION_THRESHOLD` is 2000,
  well above 40 — above it, a max is silently replaced with "unbounded", which would have made
  the text cap a lie.
- **Literal escaping** is `\"` and `\\`, which is what `GrammarGenerator` emits.

## Numbers that are still estimates

Everything in this section is inherited from `dobby-plan.md`, **not** measured on a 750G. The
device suite in `src/androidTest` is what replaces them, and `m6-plan.md` step 11 is the task of
writing what it prints back into §5.4 and §9.

| | Current value | Where it is decided |
|---|---|---|
| `n_ctx` / `n_batch` | 2048 / 256 | `Llama.N_CTX`, `Llama.N_BATCH` |
| KV type | `F16` (~224 MiB) | `Q8_0` halves it if measurement demands |
| Threads | **2, a guess** | `Tier2DeviceTest.threadSweepAndWarmLatency` sweeps {2, 4, 8} |
| `mlock` | off | Turn it on if cold-after-idle latency dominates; the cost is a pinned gigabyte |
| `max_tokens` | 40 | Derived from the 5 s cap, asserted by `PromptBudgetTest` |

`Llama.THREADS = 2` is deliberately *not* `ParakeetRecognizer.THREADS = 4`: that number is right
for a workload sharing the CPU with the wake word, and Tier 2 never runs concurrently with STT.
Four threads spread over 2×A77 + 2×A55 can be slower than two on the big cores, because every
op waits for the slowest thread.

## Running the device suite

The GGUF is not downloaded by the tests. Push it, or they skip:

```
adb push Qwen3-1.7B-Q4_K_M.gguf /sdcard/Android/data/io.dobby.llama.test/files/
./gradlew :android:llama:connectedDebugAndroidTest
```

Manual checks worth running around it:

```
adb shell grep -m1 Features /proc/cpuinfo        # before choosing -march
llvm-readelf -d libdobby-llama.so                # DT_NEEDED, expect four
unzip -l app-debug.apk | grep '\.so$'            # expect three
adb shell dumpsys thermalservice                 # around the latency soak
```
