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

import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.io.IOException;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Linux x86_64: {@code rdtsc}, only if the TSC is invariant ({@code constant_tsc} and
 * {@code nonstop_tsc}). The tick rate is calibrated against {@code System.nanoTime()} for about
 * 20 ms.
 */
final class Amd64Counter {

    // rdtsc; shl rdx, 32; or rax, rdx; ret
    private static final byte[] CODE = {
        0x0f, 0x31, 0x48, (byte) 0xc1, (byte) 0xe2, 0x20, 0x48, 0x09, (byte) 0xd0, (byte) 0xc3
    };

    private static final long CALIBRATION_NANOS = 20_000_000;

    private Amd64Counter() {}

    /** Returns null if the TSC is not invariant. */
    static Counter create() throws Throwable {
        if (!invariantTsc()) {
            return null;
        }
        MemorySegment code = ExecutableMemory.map(MemorySegment.ofArray(CODE));
        MethodHandle ticks = Linker.nativeLinker()
                .downcallHandle(code, FunctionDescriptor.of(JAVA_LONG), Linker.Option.critical(false));
        return new Counter("rdtsc", ticks, calibrate(ticks));
    }

    private static boolean invariantTsc() throws IOException {
        try (Stream<String> lines = Files.lines(Path.of("/proc/cpuinfo"))) {
            return lines.filter(line -> line.startsWith("flags"))
                    .findFirst()
                    .map(line -> List.of(line.split("\\s+")))
                    .map(flags -> flags.contains("constant_tsc") && flags.contains("nonstop_tsc"))
                    .orElse(false);
        }
    }

    /** Measures nanoseconds per tick against System.nanoTime(). */
    private static double calibrate(MethodHandle ticks) throws Throwable {
        long[] start = sample(ticks);
        while (System.nanoTime() - start[0] < CALIBRATION_NANOS) {
            Thread.onSpinWait();
        }
        long[] end = sample(ticks);
        return (double) (end[0] - start[0]) / (end[1] - start[1]);
    }

    /**
     * Returns {nanos, ticks} from the attempt where the two reads were closest together, so a
     * preemption between them cannot skew the calibration.
     */
    private static long[] sample(MethodHandle ticks) throws Throwable {
        long[] best = null;
        long bestGap = Long.MAX_VALUE;
        for (int i = 0; i < 1000; i++) {
            long before = System.nanoTime();
            long t = (long) ticks.invokeExact();
            long after = System.nanoTime();
            if (after - before < bestGap) {
                bestGap = after - before;
                best = new long[] {before + bestGap / 2, t};
            }
        }
        return best;
    }
}
