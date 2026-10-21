<!--
  Copyright 2026 Matteo Merli

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
-->

# nanotime

A tiny, dependency-free Java library with a faster alternative to `System.nanoTime()`
for measuring latencies. It reads the CPU counter directly through an FFM
critical downcall. No native library is shipped: the jar is pure Java, ~12 KB.

```java
import io.github.merlimat.nanotime.NanoClock;

long start = NanoClock.nanoTime();
doWork();
long elapsedNanos = NanoClock.nanoTime() - start;

NanoClock.source(); // "rdtsc", "cntvct_el0", "mach_absolute_time" or "System.nanoTime"
```

Runs on Java 17+. The fast path needs Java 22+, because it uses the FFM API. On
Java 17 to 21, `NanoClock.nanoTime()` simply calls `System.nanoTime()`. On Java 22+,
run with `--enable-native-access=ALL-UNNAMED` to avoid the JVM's restricted-method
warning.

## How it works

| Platform                  | Counter                | Scale                                           |
|---------------------------|------------------------|-------------------------------------------------|
| Linux x86_64              | `rdtsc`                | calibrated against `System.nanoTime()` (100 ms) |
| Linux and macOS aarch64   | `mrs cntvct_el0`       | `cntfrq_el0`                                    |
| macOS x86_64              | `mach_absolute_time()` | `mach_timebase_info()`                          |
| Other, or Java 17 to 21   | `System.nanoTime()`    | n/a                                             |

The jar is a multi-release jar. The base classes target Java 17, and the FFM counters
are under `META-INF/versions/22`, so Java 22+ picks them up automatically.

- **Linux and macOS aarch64:** the few instruction bytes are written into an
  `mmap`'d page, which is then flipped from writable to executable with `mprotect`.
  The page is then called as a function. Linux x86_64 uses `rdtsc` only if
  `/proc/cpuinfo` reports `constant_tsc` and `nonstop_tsc`. Otherwise it falls back
  to `System.nanoTime()`.
- **macOS:** both counters skip the global CAS that HotSpot's `System.nanoTime()`
  uses to enforce monotonicity. That CAS makes `nanoTime()` very slow when many
  threads call it at once. On x86_64, `mach_absolute_time()` is called directly from
  libSystem.
- **macOS code signing:** the page only runs if the JVM is allowed to run unsigned
  code. Under the hardened runtime that takes the
  `com.apple.security.cs.allow-unsigned-executable-memory` entitlement, which
  Homebrew's OpenJDK signs `java` with. Without it, `mprotect` still succeeds but the
  kernel kills the process when the page runs. So `NanoClock` first checks the
  process's code-signing flags with `csops`, and uses `mach_absolute_time()` if
  unsigned code is not allowed.
- Ticks are converted to nanoseconds with 128-bit fixed-point math
  (`Math.multiplyHigh`), with no division or floating point.
- If setup fails for any reason, it logs a warning through `System.Logger` and falls
  back to `System.nanoTime()`.

## Caveats

- As with `System.nanoTime()`, only differences between values mean anything. Never
  mix `NanoClock` values with `System.nanoTime()` values.
- Counter reads are not serializing (no `lfence`/`isb`), so the CPU may run them a
  few cycles early or late relative to the surrounding code. That is fine for
  latency histograms, but not for bracketing a handful of instructions.
- On x86_64 the tick rate comes from a 100 ms calibration against
  `System.nanoTime()`. On a 4 vCPU x86_64 Kubernetes node, the scale was within
  ±1 ppm across 5 runs, about 1 ns of error per millisecond measured. A 20 ms
  window was skewed by +3 to +12 ppm across 10 runs, which is why it is 100 ms.
- The first call initializes the class. That takes a few tens of ms for FFM
  setup, and on x86_64 about 100 ms more for the calibration. Call
  `NanoClock.nanoTime()` once at startup to keep that cost off the hot path.
- The Java 22 classes are only used when the library is loaded from its jar, not
  from an unpacked classes directory. If you shade it into a fat jar, keep the
  `Multi-Release: true` manifest attribute and the `META-INF/versions/22` entries.
- On macOS aarch64, `cntvct_el0` keeps counting while the Mac sleeps.
  `System.nanoTime()` and `mach_absolute_time()` do not, so an interval that spans a
  system sleep includes the time asleep.
- Resolution is the counter's: 41.67 ns on Apple Silicon, the same as
  `System.nanoTime()`. The M5 reports `cntfrq_el0` as 1 GHz, but the counter moves
  in steps of 41 or 42 ticks, so it is still 24 MHz underneath. On Linux ARM it is
  `1 / cntfrq_el0`, which can be as low as a few tens of MHz on some servers.

## Benchmarks

`./gradlew jmh` runs [NanoClockBenchmark](src/test/java/io/github/merlimat/nanotime/NanoClockBenchmark.java)
(JMH, average time per call; "all cores" runs one thread per CPU).

To run the benchmarks on another machine, build a self-contained jar, copy it
there, and run it with `java -jar` (Java 22+ for the fast path). The output
prints `NanoClock source: ...`, so you can see which counter is in use.

```bash
./gradlew jmhJar
```

```bash
java -jar build/libs/nanotime-0.1.0-SNAPSHOT-jmh.jar
```

Apple M5 Pro, macOS 26.6, JDK 26.0.1, 18 cores:

| Bench (ns/op) | System | NanoClock |
|---------------|-------:|----------:|
| 1 thread      |   8.06 |      1.10 |
| 18 threads    |  414.7 |      1.57 |

Linux aarch64 (Docker VM on the same M5 Pro, Temurin 25, 10 cores, clocksource `arch_sys_counter`):

| Bench (ns/op) | System | NanoClock |
|---------------|-------:|----------:|
| 1 thread      |  17.08 |      1.56 |
| 10 threads    |  16.75 |      1.79 |

Linux x86_64 (Kubernetes pod, 4 vCPUs, no CPU limit, Temurin 26.0.2.1, clocksource `tsc`):

| Bench (ns/op) | System | NanoClock |
|---------------|-------:|----------:|
| 1 thread      |  26.03 |     10.22 |
| 4 threads     |  28.69 |     12.63 |

With clocksource `tsc`, `System.nanoTime()` already runs in the vDSO without a
syscall, so this is its best case on Linux. With `kvm-clock`, or `xen` on older
AWS instances, the gap should be larger.

## Build

```bash
./gradlew build
```

## License

Apache License, Version 2.0. See [LICENSE](LICENSE).
