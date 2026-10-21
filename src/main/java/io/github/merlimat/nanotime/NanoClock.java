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

/**
 * A faster alternative to {@link System#nanoTime()} that reads the CPU counter directly.
 *
 * <ul>
 *   <li>Linux x86_64: {@code rdtsc}, only if the TSC is invariant ({@code constant_tsc} and
 *       {@code nonstop_tsc}). The tick rate is calibrated against {@code System.nanoTime()} for
 *       about 20 ms when the class is initialized.
 *   <li>Linux aarch64: {@code cntvct_el0}, scaled by {@code cntfrq_el0}.
 *   <li>macOS: {@code mach_absolute_time()}, scaled by {@code mach_timebase_info()}. This skips the
 *       global CAS that HotSpot does in {@code System.nanoTime()} to enforce monotonicity.
 *   <li>Anything else, including any platform on Java 17 to 21: {@code System.nanoTime()}.
 * </ul>
 *
 * <p>On Linux the counter instructions are copied into an executable page at startup, so there is
 * no native library to ship. The counter is read through an FFM critical downcall, which skips the
 * Java-to-native thread state transition.
 *
 * <p>As with {@code System.nanoTime()}, only differences between values are meaningful. Values
 * cannot be compared with values from {@code System.nanoTime()}. The counter reads are not
 * serializing, so the CPU may execute them a few cycles before or after the surrounding code.
 *
 * <p>Runs on Java 17+, but the counters need the FFM API from Java 22. The jar is a multi-release
 * jar, so Java 22+ picks up the counter classes automatically. On Java 22+, run with
 * {@code --enable-native-access=ALL-UNNAMED} to avoid the JVM's restricted method warning.
 */
public final class NanoClock {

    // Nanoseconds per tick, in fixed point with 32 fractional bits
    private static final int SHIFT = 32;

    private static final String SOURCE;
    // Null when falling back to System.nanoTime()
    private static final MethodHandle TICKS;
    private static final long MULT;

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
        } else {
            SOURCE = "System.nanoTime";
            TICKS = null;
            MULT = 0;
        }
    }

    private NanoClock() {}

    /** Returns the current value of the counter, in nanoseconds. */
    public static long nanoTime() {
        if (TICKS == null) {
            return System.nanoTime();
        }
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
     * Returns the counter behind {@link #nanoTime()}: {@code rdtsc}, {@code cntvct_el0},
     * {@code mach_absolute_time} or {@code System.nanoTime}.
     */
    public static String source() {
        return SOURCE;
    }

}
