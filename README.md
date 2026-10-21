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
for measuring latencies, and to `System.currentTimeMillis()` for timestamps. It reads
the CPU counter directly through an FFM critical downcall. No native library is
shipped: the jar is pure Java, ~15 KB.

```java
import io.github.merlimat.nanotime.NanoClock;

long start = NanoClock.nanoTime();
doWork();
long elapsedNanos = NanoClock.nanoTime() - start;

long timestampMillis = NanoClock.currentTimeMillis();

NanoClock.source(); // "rdtsc", "cntvct_el0", "mach_absolute_time" or "System.nanoTime"
```

Runs on Java 17+. The fast path needs Java 22+, because it uses the FFM API. On
Java 17 to 21, `NanoClock.nanoTime()` and `NanoClock.currentTimeMillis()` simply call
`System.nanoTime()` and `System.currentTimeMillis()`. On Java 22+,
run with `--enable-native-access=ALL-UNNAMED` to avoid the JVM's restricted-method
warning.

## How it works

| Platform                  | Counter                | Scale                                           |
|---------------------------|------------------------|-------------------------------------------------|
| Linux and Windows x86_64  | `rdtsc`                | calibrated against `System.nanoTime()` (100 ms) |
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
- **Windows x86_64:** the same, with kernel32's `VirtualAlloc` and `VirtualProtect`
  instead of `mmap` and `mprotect`. There is no `/proc/cpuinfo`, so a small `cpuid`
  stub reads the invariant TSC bit (leaf `0x80000007`, EDX bit 8) to decide whether
  to use `rdtsc`.
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
- **`currentTimeMillis()`:** the wall clock is not a counter, because the OS adjusts
  it through NTP and when the clock is set. So `currentTimeMillis()` keeps an anchor:
  a wall clock time from `Instant.now()`, and the counter value when it was read. It
  extrapolates from the anchor with the counter. Once the counter has moved 1 ms past
  the anchor, the next call reads the wall clock again, so there is no background
  thread. On macOS this also skips `gettimeofday()`, which ends up in the kernel and
  slows down when many threads call it at once.
- If setup fails for any reason, it logs a warning through `System.Logger` and falls
  back to `System.nanoTime()` and `System.currentTimeMillis()`.

## Caveats

- As with `System.nanoTime()`, only differences between `NanoClock.nanoTime()` values
  mean anything. Never mix them with `System.nanoTime()` values.
- `NanoClock.currentTimeMillis()` sees a change to the system clock up to 1 ms late.
  With `mach_absolute_time()`, which stops while the Mac sleeps, the same goes for
  waking up. Right after it reads the wall clock again, it can return 1 ms less than
  the call before.
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

The GitHub runner results come from the Benchmark workflow, which you can start
from the Actions tab.

### Machines

| Machine            | CPU                   | Environment                                    | Cores | Clocksource        | JDK      | `NanoClock` counter |
|--------------------|-----------------------|------------------------------------------------|------:|--------------------|----------|---------------------|
| M5 Pro             | Apple M5 Pro          | macOS 26.6                                     |    18 |                    | 26.0.1   | `cntvct_el0`        |
| M1 runner          | Apple M1 (virtual)    | macOS, GitHub `macos-latest`                   |     3 |                    | 26.0.2.1 | `cntvct_el0`        |
| M5 Pro VM          | Apple M5 Pro          | Linux aarch64, Docker VM on the M5 Pro         |    10 | `arch_sys_counter` | 25       | `cntvct_el0`        |
| Neoverse-N2 runner | Neoverse-N2           | Linux aarch64, GitHub `ubuntu-24.04-arm`       |     4 | `arch_sys_counter` | 26.0.2.1 | `cntvct_el0`        |
| EPYC 7763 runner   | AMD EPYC 7763 (Zen 3) | Linux x86_64, GitHub `ubuntu-latest`           |     4 | `tsc`              | 26.0.2.1 | `rdtsc`             |
| EPYC 7R13 pod      | AMD EPYC 7R13 (Zen 3) | Linux x86_64, AWS Kubernetes pod, no CPU limit |     4 | `tsc`              | 26.0.2.1 | `rdtsc`             |

### `nanoTime()`, ns per call

| Machine            | `System`, 1 thread | `NanoClock`, 1 thread | `System`, all cores | `NanoClock`, all cores |
|--------------------|-------------------:|----------------------:|--------------------:|-----------------------:|
| M5 Pro             |                8.1 |                   1.1 |               414.7 |                    1.6 |
| M1 runner          |               29.2 |                   3.1 |               131.2 |                    3.0 |
| M5 Pro VM          |               17.1 |                   1.6 |                16.8 |                    1.8 |
| Neoverse-N2 runner |               31.2 |                  13.4 |                31.2 |                   13.5 |
| EPYC 7763 runner   |               28.8 |                  11.2 |                30.7 |                   14.0 |
| EPYC 7R13 pod      |               26.0 |                  10.2 |                28.7 |                   12.6 |

### `currentTimeMillis()`, ns per call

| Machine            | `System`, 1 thread | `NanoClock`, 1 thread | `System`, all cores | `NanoClock`, all cores |
|--------------------|-------------------:|----------------------:|--------------------:|-----------------------:|
| M5 Pro             |               12.3 |                   1.6 |               345.3 |                    2.2 |
| M1 runner          |               27.1 |                   3.6 |                29.7 |                    3.3 |
| M5 Pro VM          |               14.6 |                   1.9 |                15.8 |                    2.3 |
| Neoverse-N2 runner |               31.2 |                  13.4 |                31.3 |                   13.5 |
| EPYC 7763 runner   |               28.7 |                  11.4 |                31.2 |                   16.2 |

`currentTimeMillis()` was not measured on the EPYC 7R13 pod.

- **macOS:** `System` slows down when many threads call it, because of HotSpot's
  global CAS. `NanoClock` doesn't.
- **Linux aarch64:** the cost of reading the counter depends on the core: about
  1.5 ns on Apple's, about 13 ns on Neoverse-N2.
- **Linux x86_64:** both machines use clocksource `tsc`, so `System.nanoTime()`
  already runs in the vDSO without a syscall. That is its best case on Linux.
  With `kvm-clock`, or `xen` on older AWS instances, the gap should be larger.
- **Windows** isn't listed: there is no counter for it yet, so `NanoClock` just
  calls `System.nanoTime()` there.
- GitHub runners are shared VMs, so expect some noise between runs. The macOS
  runner had the widest error bars, up to ±15 ns.

## Build

```bash
./gradlew build
```

## License

Apache License, Version 2.0. See [LICENSE](LICENSE).
