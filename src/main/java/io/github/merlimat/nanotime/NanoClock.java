/*
 * Copyright 2026 Matteo Merli
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.merlimat.nanotime;

import java.lang.invoke.MethodHandle;
import java.time.Instant;

/**
 * Faster alternatives to {@link System#nanoTime()} and {@link System#currentTimeMillis()}, which
 * read the CPU counter directly.
 *
 * <pre>{@code
 * long start = NanoClock.nanoTime();
 * doWork();
 * long elapsedNanos = NanoClock.nanoTime() - start;
 *
 * long timestampMillis = NanoClock.currentTimeMillis();
 * }</pre>
 *
 * <h2>Counters</h2>
 *
 * <p>{@link #source()} returns the counter in use:
 *
 * <ul>
 *   <li>Linux and Windows x86_64: {@code rdtsc}, only if the TSC is invariant ({@code constant_tsc}
 *       and {@code nonstop_tsc} on Linux, the CPUID invariant TSC bit on Windows). The tick rate
 *       is calibrated against {@code System.nanoTime()} for about 100 ms when the class is
 *       initialized.
 *   <li>Linux and macOS aarch64: {@code cntvct_el0}, scaled by {@code cntfrq_el0}. On macOS it
 *       keeps counting while the system sleeps, unlike {@code System.nanoTime()}.
 *   <li>macOS x86_64: {@code mach_absolute_time()}, scaled by {@code mach_timebase_info()}. Also
 *       used on macOS aarch64 when the JVM runs with the hardened runtime but without the
 *       {@code com.apple.security.cs.allow-unsigned-executable-memory} entitlement.
 *   <li>Anything else, including any platform on Java 17 to 21: {@code System.nanoTime()}.
 * </ul>
 *
 * <p>On Linux, Windows and macOS aarch64 the counter instructions are copied into an executable
 * page at startup, so there is no native library to ship. The counter is read through an FFM
 * critical downcall, which skips the Java-to-native thread state transition. On macOS, both
 * counters also skip the global CAS that HotSpot does in {@code System.nanoTime()} to enforce
 * monotonicity.
 *
 * <h2>Comparing with System.nanoTime()</h2>
 *
 * <p>When the class is initialized, the counter is offset to match {@code System.nanoTime()}, so
 * values from the two can be compared. They drift apart afterwards: on x86_64 by the error of the
 * calibrated tick rate, on Linux by the NTP frequency corrections that {@code System.nanoTime()}
 * follows and the counter does not, and on macOS aarch64 by the time the system spends asleep.
 *
 * <h2>Setup</h2>
 *
 * <p>Runs on Java 17+, but the counters need the FFM API from Java 22. The jar is a multi-release
 * jar, so Java 22+ picks up the counter classes automatically. On Java 22+, run with
 * {@code --enable-native-access=ALL-UNNAMED} to avoid the JVM's restricted method warning.
 *
 * <p>The first call initializes the class, which takes a few tens of ms, plus about 100 ms on
 * x86_64 for the calibration. Call {@link #nanoTime()} once at startup to keep that cost off the
 * hot path. If setup fails, a warning is logged through {@link System.Logger} and both methods
 * fall back to {@code System.nanoTime()} and {@code System.currentTimeMillis()}.
 */
public final class NanoClock {

    // Nanoseconds per tick, in fixed point with 32 fractional bits
    private static final int SHIFT = 32;

    private static final String SOURCE;
    // Null when falling back to System.nanoTime()
    private static final MethodHandle TICKS;
    private static final long MULT;
    // Added to the counter so that nanoTime() starts out at System.nanoTime()
    private static final long OFFSET;
    // True when currentTimeMillis() just calls System.currentTimeMillis(): without a counter, and
    // on Windows. There, System.currentTimeMillis() is a cheap read of a time the OS updates at each
    // timer tick, so extrapolating from the counter would be slower and would run behind it.
    private static final boolean SYSTEM_MILLIS;

    // How long currentTimeMillis() extrapolates from the counter before it reads the wall clock
    private static final long RESYNC_NANOS = 1_000_000;
    // Longest counter interval around a wall clock read that is accepted as an anchor
    private static final long MAX_ANCHOR_NANOS = 10_000;

    /** A wall clock time, in nanoseconds since the epoch, and the counter value at that time. */
    private record Anchor(long epochNanos, long nanoTime) {}

    private static volatile Anchor anchor;

    static {
        Counter counter;
        try {
            counter = Counters.detect();
        } catch (Throwable t) {
            System.getLogger(NanoClock.class.getName())
                    .log(System.Logger.Level.WARNING, "Falling back to System.nanoTime()", t);
            counter = null;
        }
        if (counter != null) {
            SOURCE = counter.source();
            TICKS = counter.ticks();
            MULT = Math.round(counter.nanosPerTick() * (1L << SHIFT));
            OFFSET = systemNanoTimeOffset();
        } else {
            SOURCE = "System.nanoTime";
            TICKS = null;
            MULT = 0;
            OFFSET = 0;
        }
        SYSTEM_MILLIS = TICKS == null || System.getProperty("os.name").startsWith("Windows");
        // Already stale, so the first currentTimeMillis() reads the wall clock
        anchor = new Anchor(0, nanoTime() - RESYNC_NANOS);
    }

    private NanoClock() {}

    /**
     * Returns the current value of the counter, in nanoseconds. As with {@link System#nanoTime()},
     * only the difference between two values is meaningful.
     *
     * <p>The counter read is not serializing, so the CPU may execute it a few cycles before or
     * after the surrounding code.
     *
     * @return the counter, in nanoseconds, offset to start out at {@code System.nanoTime()}
     */
    public static long nanoTime() {
        if (TICKS == null) {
            return System.nanoTime();
        }
        return counterNanos() + OFFSET;
    }

    /** Returns the counter in nanoseconds, without the offset. */
    private static long counterNanos() {
        long ticks;
        try {
            ticks = (long) TICKS.invokeExact();
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
        // 128-bit (ticks * MULT) >> SHIFT
        return Math.multiplyHigh(ticks, MULT) << (64 - SHIFT) | (ticks * MULT) >>> SHIFT;
    }

    /**
     * Returns System.nanoTime() minus the counter, from the attempt where the two System.nanoTime()
     * reads around the counter were closest together, so a preemption between them cannot skew it.
     */
    private static long systemNanoTimeOffset() {
        long offset = 0;
        long bestGap = Long.MAX_VALUE;
        for (int i = 0; i < 1000; i++) {
            long before = System.nanoTime();
            long counter = counterNanos();
            long after = System.nanoTime();
            if (after - before < bestGap) {
                bestGap = after - before;
                offset = before + bestGap / 2 - counter;
            }
        }
        return offset;
    }

    /**
     * Returns the current time in milliseconds since the epoch, like {@link
     * System#currentTimeMillis()}.
     *
     * <p>The time is extrapolated from {@link #nanoTime()}, and the wall clock is read again once
     * the counter has moved 1 ms past the last read. So a change to the system clock is seen up to
     * 1 ms late, and so is time spent in system sleep if the counter stops during it. Right after
     * the wall clock is read again, the result can be 1 ms lower than the call before. Where
     * {@link #nanoTime()} falls back to {@code System.nanoTime()}, and on Windows, where it is
     * already cheaper than the counter, this calls {@code System.currentTimeMillis()}.
     *
     * @return the current time, in milliseconds since 1970-01-01T00:00:00Z
     */
    public static long currentTimeMillis() {
        if (SYSTEM_MILLIS) {
            return System.currentTimeMillis();
        }
        Anchor a = anchor;
        long elapsed = nanoTime() - a.nanoTime();
        // The counter on another core can be slightly behind the anchor, so only a large gap either
        // way means that the anchor is stale
        if (Math.abs(elapsed) >= RESYNC_NANOS) {
            return resync();
        }
        return (a.epochNanos() + elapsed) / 1_000_000;
    }

    /** Reads the wall clock, and makes it the new anchor if the read was not interrupted. */
    private static long resync() {
        long start = nanoTime();
        Instant now = Instant.now();
        long end = nanoTime();
        long epochNanos = now.getEpochSecond() * 1_000_000_000 + now.getNano();
        // A thread preempted or stopped at a safepoint between the reads would make every thread
        // extrapolate from a wrong anchor until the next resync
        if (end - start <= MAX_ANCHOR_NANOS) {
            anchor = new Anchor(epochNanos, start + (end - start) / 2);
        }
        return epochNanos / 1_000_000;
    }

    /**
     * Returns the name of the counter behind {@link #nanoTime()}.
     *
     * @return {@code "rdtsc"}, {@code "cntvct_el0"}, {@code "mach_absolute_time"} or
     *     {@code "System.nanoTime"}
     */
    public static String source() {
        return SOURCE;
    }

}
