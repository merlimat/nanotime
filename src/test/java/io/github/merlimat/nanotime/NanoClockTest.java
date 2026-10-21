package io.github.merlimat.nanotime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class NanoClockTest {

    @Test
    void usesHardwareCounterWhereAvailable() throws Exception {
        String os = System.getProperty("os.name");
        String arch = System.getProperty("os.arch");
        String expected = "System.nanoTime";
        if (os.startsWith("Mac")) {
            expected = "mach_absolute_time";
        } else if (os.equals("Linux") && arch.equals("aarch64")) {
            expected = "cntvct_el0";
        } else if (os.equals("Linux") && arch.equals("amd64")) {
            String cpuinfo = Files.readString(Path.of("/proc/cpuinfo"));
            if (cpuinfo.contains(" constant_tsc ") && cpuinfo.contains(" nonstop_tsc ")) {
                expected = "rdtsc";
            }
        }
        assertEquals(expected, NanoClock.source());
    }

    @Test
    void tracksSystemNanoTime() throws Exception {
        // Initialize the class outside the measured interval
        NanoClock.nanoTime();
        long systemStart = System.nanoTime();
        long clockStart = NanoClock.nanoTime();
        Thread.sleep(200);
        long systemElapsed = System.nanoTime() - systemStart;
        long clockElapsed = NanoClock.nanoTime() - clockStart;
        assertEquals(1.0, (double) clockElapsed / systemElapsed, 1e-3);
    }

    @Test
    void neverGoesBackwardsOnOneThread() {
        long previous = NanoClock.nanoTime();
        for (int i = 0; i < 10_000_000; i++) {
            long now = NanoClock.nanoTime();
            assertTrue(now >= previous, () -> "went from " + previous + " to " + now);
        }
    }
}
