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

import static java.lang.foreign.ValueLayout.JAVA_INT;
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
 * Linux and Windows x86_64: {@code rdtsc}, only if the TSC is invariant. On Linux that means
 * {@code constant_tsc} and {@code nonstop_tsc} in {@code /proc/cpuinfo}; on Windows, the invariant
 * TSC bit from CPUID. The tick rate is calibrated against {@code System.nanoTime()} for about
 * 100 ms. With 20 ms the result was skewed by +3 to +12 ppm; with 100 ms it is within 1 ppm.
 */
final class Amd64Counter {

    // rdtsc; shl rdx, 32; or rax, rdx; ret
    private static final byte[] CODE = {
        0x0f, 0x31, 0x48, (byte) 0xc1, (byte) 0xe2, 0x20, 0x48, 0x09, (byte) 0xd0, (byte) 0xc3
    };

    // Takes the leaf in ecx (Windows x64 calling convention), returns edx:eax.
    // push rbx; mov eax, ecx; xor ecx, ecx; cpuid; shl rdx, 32; or rax, rdx; pop rbx; ret
    private static final byte[] WINDOWS_CPUID_CODE = {
        0x53, (byte) 0x89, (byte) 0xc8, 0x31, (byte) 0xc9, 0x0f, (byte) 0xa2, 0x48,
        (byte) 0xc1, (byte) 0xe2, 0x20, 0x48, 0x09, (byte) 0xd0, 0x5b, (byte) 0xc3
    };

    private static final long CALIBRATION_NANOS = 100_000_000;

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

    static boolean invariantTsc() throws Throwable {
        if (System.getProperty("os.name").startsWith("Windows")) {
            return cpuidInvariantTsc();
        }
        return cpuinfoInvariantTsc();
    }

    /** Windows has no /proc/cpuinfo, so this asks the CPU directly: leaf 0x80000007, EDX bit 8. */
    private static boolean cpuidInvariantTsc() throws Throwable {
        MethodHandle cpuid = Linker.nativeLinker().downcallHandle(
                ExecutableMemory.map(MemorySegment.ofArray(WINDOWS_CPUID_CODE)),
                FunctionDescriptor.of(JAVA_LONG, JAVA_INT));
        // EAX of leaf 0x80000000 is the highest extended leaf the CPU supports
        long maxLeaf = (long) cpuid.invokeExact(0x80000000);
        if (Integer.compareUnsigned((int) maxLeaf, 0x80000007) < 0) {
            return false;
        }
        long powerManagement = (long) cpuid.invokeExact(0x80000007);
        int edx = (int) (powerManagement >>> 32);
        return (edx & (1 << 8)) != 0;
    }

    private static boolean cpuinfoInvariantTsc() throws IOException {
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
        // Throwaway sample, so that both samples below run warmed-up code. Otherwise the cold first
        // sample skews the result by a few ppm.
        sample(ticks);
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
