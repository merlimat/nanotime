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

| Platform      | Counter                | Scale                                          |
|---------------|------------------------|------------------------------------------------|
| Linux x86_64  | `rdtsc`                | calibrated against `System.nanoTime()` (20 ms) |
| Linux aarch64 | `mrs cntvct_el0`       | `cntfrq_el0`                                   |
| macOS         | `mach_absolute_time()` | `mach_timebase_info()`                         |
| Other, or Java 17 to 21 | `System.nanoTime()` | n/a                                 |

The jar is a multi-release jar. The base classes target Java 17, and the FFM counters
are under `META-INF/versions/22`, so Java 22+ picks them up automatically.

- **Linux:** the few instruction bytes are written into an `mmap`'d page, which is
  then flipped from writable to executable with `mprotect`. The page is then called
  as a function. x86_64 uses `rdtsc` only if `/proc/cpuinfo` reports
  `constant_tsc` and `nonstop_tsc`. Otherwise it falls back to `System.nanoTime()`.
- **macOS:** `mach_absolute_time()` is called directly from libSystem. This skips the
  global CAS that HotSpot's `System.nanoTime()` uses to enforce monotonicity. That
  CAS makes `nanoTime()` very slow when many threads call it at once.
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
- On x86_64 the tick rate comes from a 20 ms calibration, so expect a scale
  error of a few ppm.
- The first call initializes the class, which takes a few tens of ms. This covers
  FFM setup, plus calibration on x86_64. Call `NanoClock.nanoTime()` once at
  startup to keep that cost off the hot path.
- The Java 22 classes are only used when the library is loaded from its jar, not
  from an unpacked classes directory. If you shade it into a fat jar, keep the
  `Multi-Release: true` manifest attribute and the `META-INF/versions/22` entries.
- Resolution is the counter's: 41.67 ns on Apple Silicon (24 MHz, the same as
  `System.nanoTime()`), and `1 / cntfrq_el0` on Linux ARM, which can be as low as
  a few tens of MHz on some servers.

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

| Benchmark                     | ns/op |
|-------------------------------|------:|
| `System.nanoTime()`, 1 thread |  9.36 |
| `NanoClock.nanoTime()`, 1 thread |  5.71 |
| `System.nanoTime()`, 18 threads | 436.5 |
| `NanoClock.nanoTime()`, 18 threads |  7.77 |

Linux aarch64 (Docker VM on the same M5 Pro, Temurin 25, 10 cores, clocksource `arch_sys_counter`):

| Benchmark                     | ns/op |
|-------------------------------|------:|
| `System.nanoTime()`, 1 thread | 17.08 |
| `NanoClock.nanoTime()`, 1 thread |  1.56 |
| `System.nanoTime()`, 10 threads | 16.75 |
| `NanoClock.nanoTime()`, 10 threads |  1.79 |

The x86_64 path has been functionally tested under emulation, but not yet
benchmarked on real hardware.

## Build

```bash
./gradlew build
```

## License

Apache License, Version 2.0. See [LICENSE](LICENSE).
