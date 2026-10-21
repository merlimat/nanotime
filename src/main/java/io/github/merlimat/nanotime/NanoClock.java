package io.github.merlimat.nanotime;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

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
 *   <li>Anything else: {@code System.nanoTime()}.
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
 * <p>Run with {@code --enable-native-access=ALL-UNNAMED} to avoid the JVM's restricted method
 * warning.
 */
public final class NanoClock {

    private static final int PROT_READ = 1;
    private static final int PROT_WRITE = 2;
    private static final int PROT_EXEC = 4;
    private static final int MAP_PRIVATE = 0x02;
    private static final int MAP_ANONYMOUS = 0x20;

    // rdtsc; shl rdx, 32; or rax, rdx; ret
    private static final byte[] X86_64_CODE = {
        0x0f, 0x31, 0x48, (byte) 0xc1, (byte) 0xe2, 0x20, 0x48, 0x09, (byte) 0xd0, (byte) 0xc3
    };

    // At offset 0: mrs x0, cntvct_el0; ret
    // At offset 8: mrs x0, cntfrq_el0; ret
    private static final int[] AARCH64_CODE = {0xd53be040, 0xd65f03c0, 0xd53be000, 0xd65f03c0};

    private static final long CALIBRATION_NANOS = 20_000_000;

    // Nanoseconds per tick, in fixed point with 32 fractional bits
    private static final int SHIFT = 32;

    private static final String SOURCE;
    // Null when falling back to System.nanoTime()
    private static final MethodHandle TICKS;
    private static final long MULT;

    private record Counter(String source, MethodHandle ticks, double nanosPerTick) {}

    static {
        Counter counter;
        try {
            counter = detect();
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

    private static Counter detect() throws Throwable {
        String os = System.getProperty("os.name");
        String arch = System.getProperty("os.arch");
        Linker linker = Linker.nativeLinker();

        if (os.startsWith("Mac")) {
            SymbolLookup libc = linker.defaultLookup();
            MethodHandle ticks = linker.downcallHandle(
                    libc.find("mach_absolute_time").orElseThrow(),
                    FunctionDescriptor.of(JAVA_LONG),
                    Linker.Option.critical(false));
            MethodHandle timebaseInfo = linker.downcallHandle(
                    libc.find("mach_timebase_info").orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS));
            try (Arena arena = Arena.ofConfined()) {
                // struct mach_timebase_info { uint32_t numer; uint32_t denom; }
                MemorySegment info = arena.allocate(8);
                if ((int) timebaseInfo.invokeExact(info) != 0) {
                    throw new IllegalStateException("mach_timebase_info failed");
                }
                double nanosPerTick = (double) info.get(JAVA_INT, 0) / info.get(JAVA_INT, 4);
                return new Counter("mach_absolute_time", ticks, nanosPerTick);
            }
        }

        if (os.equals("Linux") && arch.equals("aarch64")) {
            MemorySegment code = mapExecutable(MemorySegment.ofArray(AARCH64_CODE));
            MethodHandle frequency = linker.downcallHandle(code.asSlice(8), FunctionDescriptor.of(JAVA_LONG));
            MethodHandle ticks = linker.downcallHandle(code, FunctionDescriptor.of(JAVA_LONG), Linker.Option.critical(false));
            return new Counter("cntvct_el0", ticks, 1e9 / (long) frequency.invokeExact());
        }

        if (os.equals("Linux") && arch.equals("amd64") && invariantTsc()) {
            MemorySegment code = mapExecutable(MemorySegment.ofArray(X86_64_CODE));
            MethodHandle ticks = linker.downcallHandle(code, FunctionDescriptor.of(JAVA_LONG), Linker.Option.critical(false));
            return new Counter("rdtsc", ticks, calibrate(ticks));
        }

        return null;
    }

    /** Copies machine code into a new page, then flips the page from writable to executable. */
    private static MemorySegment mapExecutable(MemorySegment code) throws Throwable {
        Linker linker = Linker.nativeLinker();
        SymbolLookup libc = linker.defaultLookup();
        MethodHandle mmap = linker.downcallHandle(
                libc.find("mmap").orElseThrow(),
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG));
        MethodHandle mprotect = linker.downcallHandle(
                libc.find("mprotect").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT));

        long size = code.byteSize();
        MemorySegment page = (MemorySegment) mmap.invokeExact(
                MemorySegment.NULL, size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0L);
        if (page.address() == -1L) {
            throw new IllegalStateException("mmap failed");
        }
        page = page.reinterpret(size);
        page.copyFrom(code);
        if ((int) mprotect.invokeExact(page, size, PROT_READ | PROT_EXEC) != 0) {
            throw new IllegalStateException("mprotect failed");
        }
        return page;
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
